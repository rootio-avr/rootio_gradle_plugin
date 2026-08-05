package io.root.patcher;

import com.sun.net.httpserver.HttpServer;
import groovy.json.JsonOutput;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Functional tests for the capability-based deduplication of patched coords.
 *
 * <p>Each scenario plants a fake local Maven repo, starts a mock {@code /v3/analyze/v2/maven}
 * HTTP server, writes a per-test {@code build.gradle.kts}, runs Gradle via TestKit,
 * and asserts on the resolved compileClasspath jar list.
 */
class RootIoPatcherPluginCapabilityTest {

    @TempDir
    File projectDir;

    private HttpServer server;
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    static Stream<String> gradleVersions() {
        String prop = System.getProperty("test.gradleVersions");
        if (prop != null && !prop.isBlank()) {
            return Arrays.stream(prop.split(",")).map(String::trim).filter(s -> !s.isEmpty());
        }
        return Stream.of("7.6.4", "8.10", "9.4.1");
    }

    private Map<String, String> baseEnv() {
        Map<String, String> env = new HashMap<>(System.getenv());
        // Every scenario here is about the aliased coord splitting one module into two, which is
        // the only case where capabilities come into play — so opt in explicitly.
        env.put("ROOTIO_USE_ALIAS", "true");
        String javaHome = System.getProperty("test.javaHome");
        if (javaHome != null && !javaHome.isBlank()) {
            env.put("JAVA_HOME", javaHome);
        }
        return env;
    }

    // ===== F2: BOM sibling wins capability conflict (canonical bug-fix scenario) =====
    @ParameterizedTest(name = "Gradle {0}")
    @MethodSource("gradleVersions")
    void f2_preferNewest_bomSiblingWins(String gradleVersion) throws IOException {
        bugReproRepo();
        setupPatchOnly113();
        writeBuildScript(gradleVersion,
            "implementation(\"org.example:host-lib:1.0\")\n" +
            "    implementation(\"ch.qos.logback:logback-core:1.5.8\")");

        BuildResult result = runListClasspath(gradleVersion);
        List<String> logbackJars = jarsByPrefix(result, "logback-core-");

        assertEquals(1, logbackJars.size(),
            "Expected exactly one logback-core jar under PREFER_NEWEST, got " + logbackJars + "\n" + result.getOutput());
        assertEquals("logback-core-1.5.8.jar", logbackJars.get(0),
            "Expected BOM-resolved 1.5.8 to win under PREFER_NEWEST");
    }

    // ===== F4: happy path — single patched, no conflict =====
    @ParameterizedTest(name = "Gradle {0}")
    @MethodSource("gradleVersions")
    void f4_happyPath_singlePatchedNoConflict(String gradleVersion) throws IOException {
        File repoDir = new File(projectDir, "local-repo");
        createFakeArtifact(repoDir, "org.example", "vulnerable-lib", "1.0");
        createFakeArtifact(repoDir, "io.root.org.example", "vulnerable-lib", "1.0-root.io.1");
        setupVersionAwareApiServer(req ->
            req.contains("\"version\":\"1.0\"") && req.contains("vulnerable-lib")
                ? patchJson("org.example:vulnerable-lib", "1.0",
                    "io.root.org.example:vulnerable-lib", "1.0-root.io.1")
                : emptyJson());
        writeBuildScript(gradleVersion,
            "implementation(\"org.example:vulnerable-lib:1.0\")");

        BuildResult result = runListClasspath(gradleVersion);
        List<String> jars = jarsByPrefix(result, "vulnerable-lib-");
        assertEquals(1, jars.size(),
            "Expected exactly one vulnerable-lib jar, got " + jars + "\n" + result.getOutput());
        assertEquals("vulnerable-lib-1.0-root.io.1.jar", jars.get(0),
            "Expected substituted patched jar");
    }

    // ===== F5: two patched coords, intra-module conflict resolves to highest =====
    @ParameterizedTest(name = "Gradle {0}")
    @MethodSource("gradleVersions")
    void f5_twoPatchedSameArtifact_highestWins(String gradleVersion) throws IOException {
        File repoDir = new File(projectDir, "local-repo");
        createFakeArtifact(repoDir, "io.root.ch.qos.logback", "logback-core", "1.1.3-root.io.1");
        createFakeArtifact(repoDir, "io.root.ch.qos.logback", "logback-core", "1.5.8-root.io.1");
        setupServerResponse(emptyJson()); // no patches needed; user declares io.root.* directly
        writeBuildScript(gradleVersion,
            "implementation(\"io.root.ch.qos.logback:logback-core:1.1.3-root.io.1\")\n" +
            "    implementation(\"io.root.ch.qos.logback:logback-core:1.5.8-root.io.1\")");

        BuildResult result = runListClasspath(gradleVersion);
        List<String> jars = jarsByPrefix(result, "logback-core-");
        assertEquals(1, jars.size(),
            "Expected exactly one logback-core jar after intra-module resolution, got " + jars);
        assertEquals("logback-core-1.5.8-root.io.1.jar", jars.get(0),
            "Expected higher patched version to win intra-module conflict");
    }

    // ===== F6: multi-project — per-subproject isolation =====
    @ParameterizedTest(name = "Gradle {0}")
    @MethodSource("gradleVersions")
    void f6_multiProject_perSubprojectIsolation(String gradleVersion) throws IOException {
        File repoDir = new File(projectDir, "local-repo");
        // :subA needs the bug-shape artifacts
        createFakeArtifactWithDeps(repoDir, "org.example", "host-lib", "1.0",
            List.of("ch.qos.logback:logback-core:1.1.3"));
        createFakeArtifact(repoDir, "ch.qos.logback", "logback-core", "1.1.3");
        createFakeArtifact(repoDir, "ch.qos.logback", "logback-core", "1.5.8");
        createFakeArtifact(repoDir, "io.root.ch.qos.logback", "logback-core", "1.1.3-root.io.1");
        // :subB just an inert lib
        createFakeArtifact(repoDir, "org.example", "inert-lib", "1.0");
        setupPatchOnly113();

        Files.writeString(new File(projectDir, "settings.gradle.kts").toPath(),
            "rootProject.name = \"root\"\ninclude(\":subA\", \":subB\")\n");
        Files.writeString(new File(projectDir, "build.gradle.kts").toPath(),
            "plugins { id(\"io.root.patcher\") apply false }\n" +
            "subprojects {\n" +
            "    apply(plugin = \"java\")\n" +
            "    apply(plugin = \"io.root.patcher\")\n" +
            "    repositories { maven { url = uri(\"" + repoDir.toURI() + "\") } }\n" +
            "    extensions.configure<io.root.patcher.RootIoExtension> {\n" +
            "        apiKey.set(\"k\")\n" +
            "        apiUrl.set(\"http://localhost:" + port + "\")\n" +
            "    }\n" +
            "    tasks.register(\"listClasspath\") {\n" +
            "        doLast {\n" +
            "            configurations[\"compileClasspath\"].resolve().forEach { println(\"CP: \" + it.name) }\n" +
            "        }\n" +
            "    }\n" +
            "}\n");
        new File(projectDir, "subA/src/main/java").mkdirs();
        new File(projectDir, "subB/src/main/java").mkdirs();
        Files.writeString(new File(projectDir, "subA/build.gradle.kts").toPath(),
            "dependencies {\n" +
            "    implementation(\"org.example:host-lib:1.0\")\n" +
            "    implementation(\"ch.qos.logback:logback-core:1.5.8\")\n" +
            "}\n");
        Files.writeString(new File(projectDir, "subB/build.gradle.kts").toPath(),
            "dependencies {\n" +
            "    implementation(\"org.example:inert-lib:1.0\")\n" +
            "}\n");

        BuildResult resA = GradleRunner.create()
            .withProjectDir(projectDir).withPluginClasspath().withGradleVersion(gradleVersion)
            .withEnvironment(baseEnv())
            .withArguments(":subA:listClasspath").build();
        BuildResult resB = GradleRunner.create()
            .withProjectDir(projectDir).withPluginClasspath().withGradleVersion(gradleVersion)
            .withEnvironment(baseEnv())
            .withArguments(":subB:listClasspath").build();

        assertEquals(1, jarsByPrefix(resA, "logback-core-").size(),
            "subA: expected exactly one logback-core jar, got " + jarsByPrefix(resA, "logback-core-")
                + "\n" + resA.getOutput());
        assertEquals(0, jarsByPrefix(resB, "logback-core-").size(),
            "subB: expected NO logback-core jar (isolated from subA), got " + jarsByPrefix(resB, "logback-core-")
                + "\n" + resB.getOutput());
    }

    // ===== F7: dependencyLocking — lockfile has no io.root.* ghost =====
    @ParameterizedTest(name = "Gradle {0}")
    @MethodSource("gradleVersions")
    void f7_dependencyLocking_noIoRootGhost(String gradleVersion) throws IOException {
        bugReproRepo();
        setupPatchOnly113();
        Files.writeString(new File(projectDir, "settings.gradle.kts").toPath(),
            "rootProject.name = \"test-project\"\n");
        Files.writeString(new File(projectDir, "build.gradle.kts").toPath(),
            "plugins {\n    java\n    id(\"io.root.patcher\")\n}\n" +
            "dependencyLocking { lockAllConfigurations() }\n" +
            "repositories { maven { url = uri(\"" + new File(projectDir, "local-repo").toURI() + "\") } }\n" +
            "dependencies {\n" +
            "    implementation(\"org.example:host-lib:1.0\")\n" +
            "    implementation(\"ch.qos.logback:logback-core:1.5.8\")\n" +
            "}\n" +
            "rootio {\n" +
            "    apiKey.set(\"k\")\n" +
            "    apiUrl.set(\"http://localhost:" + port + "\")\n" +
            "}\n");

        BuildResult lockRun = GradleRunner.create()
            .withProjectDir(projectDir).withPluginClasspath().withGradleVersion(gradleVersion)
            .withEnvironment(baseEnv())
            .withArguments("dependencies", "--write-locks", "--configuration", "compileClasspath")
            .build();

        File lockfile = new File(projectDir, "gradle.lockfile");
        assertTrue(lockfile.exists(), "Expected gradle.lockfile to be written.\n" + lockRun.getOutput());
        List<String> lockLines = Files.readAllLines(lockfile.toPath());

        long logbackCoreLines = lockLines.stream()
            .filter(l -> l.startsWith("ch.qos.logback:logback-core:")
                      || l.startsWith("io.root.ch.qos.logback:logback-core:"))
            .count();
        assertEquals(1L, logbackCoreLines,
            "Expected exactly one logback-core entry in lockfile, got:\n" + String.join("\n", lockLines));
        assertTrue(lockLines.stream().anyMatch(l ->
                l.startsWith("ch.qos.logback:logback-core:1.5.8")),
            "Expected BOM-supplied 1.5.8 to be the surviving lockfile entry under PREFER_NEWEST:\n"
                + String.join("\n", lockLines));
        assertFalse(lockLines.stream().anyMatch(l ->
                l.startsWith("io.root.ch.qos.logback:logback-core:")),
            "Expected NO io.root.* ghost entries under PREFER_NEWEST:\n"
                + String.join("\n", lockLines));
    }

    // ===== F8: configuration cache =====
    @ParameterizedTest(name = "Gradle {0}")
    @MethodSource("gradleVersions")
    void f8_configurationCache_reusedOnSecondBuild(String gradleVersion) throws IOException {
        bugReproRepo();
        setupPatchOnly113();
        // Use the built-in `dependencies` task instead of the custom listClasspath here —
        // `dependencies` is configuration-cache safe by design; our custom task uses
        // Configuration.resolve() at execution time which is CC-hostile in 8.x+.
        writeBuildScript(gradleVersion,
            "implementation(\"org.example:host-lib:1.0\")\n" +
            "    implementation(\"ch.qos.logback:logback-core:1.5.8\")");

        BuildResult first = GradleRunner.create()
            .withProjectDir(projectDir).withPluginClasspath().withGradleVersion(gradleVersion)
            .withEnvironment(baseEnv())
            .withArguments("--configuration-cache", "dependencies", "--configuration", "compileClasspath")
            .build();
        BuildResult second = GradleRunner.create()
            .withProjectDir(projectDir).withPluginClasspath().withGradleVersion(gradleVersion)
            .withEnvironment(baseEnv())
            .withArguments("--configuration-cache", "dependencies", "--configuration", "compileClasspath")
            .build();

        assertTrue(
            second.getOutput().contains("Configuration cache entry reused")
                || second.getOutput().contains("Reusing configuration cache"),
            "Expected configuration-cache entry reuse on second build:\n" + second.getOutput());
        // Both builds must show the BOM-supplied coord winning the capability conflict.
        assertTrue(first.getOutput().contains("ch.qos.logback:logback-core:1.5.8"),
            "First build must show BOM-supplied 1.5.8 in `dependencies` output:\n" + first.getOutput());
        assertTrue(second.getOutput().contains("ch.qos.logback:logback-core:1.5.8"),
            "Second (cached) build must show BOM-supplied 1.5.8 in `dependencies` output:\n" + second.getOutput());
    }

    // ===== F9: user-declared patched coord directly =====
    @ParameterizedTest(name = "Gradle {0}")
    @MethodSource("gradleVersions")
    void f9_userPinnedPatchedCoord_directly_capabilityFires(String gradleVersion) throws IOException {
        File repoDir = new File(projectDir, "local-repo");
        createFakeArtifact(repoDir, "ch.qos.logback", "logback-core", "1.5.8");
        createFakeArtifact(repoDir, "io.root.ch.qos.logback", "logback-core", "1.1.3-root.io.1");
        setupServerResponse(emptyJson()); // no API substitution involved
        writeBuildScript(gradleVersion,
            "implementation(\"io.root.ch.qos.logback:logback-core:1.1.3-root.io.1\")\n" +
            "    implementation(\"ch.qos.logback:logback-core:1.5.8\")");

        BuildResult result = runListClasspath(gradleVersion);
        List<String> jars = jarsByPrefix(result, "logback-core-");
        assertEquals(1, jars.size(),
            "Expected exactly one logback-core jar when user pins patched coord directly, got " + jars
                + "\n" + result.getOutput());
        // Capability conflict fires (user-pinned io.root.* vs implicit upstream sibling).
        // selectHighestVersion compares capability versions — the upstream 1.5.8 beats the
        // patched alias's injected 1.1.3 capability. To force the user-pinned patched coord
        // to win in the rare same-version case, override via standard Gradle
        // dependencySubstitution rather than via a plugin knob.
        assertEquals("logback-core-1.5.8.jar", jars.get(0),
            "Upstream BOM coord wins capability conflict even when user pins the patched coord directly.");
    }

    // ===== F10: adversarial — io.root.* group, no -root.io.N suffix → no injection =====
    @ParameterizedTest(name = "Gradle {0}")
    @MethodSource("gradleVersions")
    void f10_adversarial_ioRootGroupNoSuffix_noInjection(String gradleVersion) throws IOException {
        File repoDir = new File(projectDir, "local-repo");
        createFakeArtifact(repoDir, "io.root.org.example", "custom-lib", "2.0.0");
        setupServerResponse(emptyJson());
        writeBuildScript(gradleVersion,
            "implementation(\"io.root.org.example:custom-lib:2.0.0\")");

        BuildResult result = runListClasspath(gradleVersion);
        List<String> jars = jarsByPrefix(result, "custom-lib-");
        assertEquals(1, jars.size(),
            "Adversarial coord still resolves; got " + jars);
        // Also confirm via dependencyInsight that no upstream capability got injected.
        BuildResult insight = GradleRunner.create()
            .withProjectDir(projectDir).withPluginClasspath().withGradleVersion(gradleVersion)
            .withEnvironment(baseEnv())
            .withArguments("dependencyInsight", "--dependency", "custom-lib",
                "--configuration", "compileClasspath")
            .build();
        // The default capability is `io.root.org.example:custom-lib`. A spurious injection
        // would surface as `org.example:custom-lib` in the insight output. Assert it's absent.
        assertFalse(insight.getOutput().contains("org.example:custom-lib (default capability)")
                || insight.getOutput().contains("Capability group: org.example, name: custom-lib"),
            "Expected NO upstream capability injected on adversarial coord; insight:\n"
                + insight.getOutput());
    }

    // ===== F11: same-version capability tie — patched coord wins =====
    //
    // The customer-reported failure: a patched coord and an unpatched upstream
    // sibling co-exist in the graph at the same base version, both claiming
    // the same capability at version 2.18.2. selectHighestVersion() cannot
    // break the tie and the build hard-fails. The resolver detects the tie
    // and picks the patched candidate.
    //
    // In the customer's project the unpatched coord enters the graph because
    // an io.spring.dependency-management-supplied BOM constraint pins a
    // version-less direct decl, which bypasses eachDependency. Reproducing
    // that exact mechanism inside TestKit + a file-backed repo turns out to
    // be brittle (constraints behave subtly differently with TestKit's
    // pluginClasspath); instead this test declares both coords directly and
    // configures the mock backend to return no patch for the upstream — so
    // neither dep is substituted — but {@code RootIoCapabilityRule} still
    // fires on the patched coord, creating the same tied conflict.
    @ParameterizedTest(name = "Gradle {0}")
    @MethodSource("gradleVersions")
    void f11_sameVersionTie_picksPatched(String gradleVersion) throws IOException {
        File repoDir = new File(projectDir, "local-repo");
        createFakeArtifact(repoDir, "com.fasterxml.jackson.core", "jackson-core", "2.18.2");
        createFakeArtifact(repoDir, "io.root.com.fasterxml.jackson.core", "jackson-core", "2.18.2-root.io.1");

        // Mock backend returns NO patch for jackson-core — neither dep gets
        // substituted by eachDependency, so both coords land in the graph.
        setupVersionAwareApiServer(req -> emptyJson());

        writeBuildScript(gradleVersion,
            "implementation(\"com.fasterxml.jackson.core:jackson-core:2.18.2\")\n" +
            "    implementation(\"io.root.com.fasterxml.jackson.core:jackson-core:2.18.2-root.io.1\")");

        BuildResult result = runListClasspath(gradleVersion);
        List<String> jars = jarsByPrefix(result, "jackson-core-");
        assertEquals(1, jars.size(),
            "Expected exactly one jackson-core jar after same-version tie resolution, got " + jars + "\n"
                + result.getOutput());
        assertEquals("jackson-core-2.18.2-root.io.1.jar", jars.get(0),
            "On a same-version capability tie, the io.root.* patched coord must win — got " + jars.get(0));
    }

    // ===== Shared fixtures =====

    /** Plants the canonical bug-repro repo: host-lib transitively → logback-core 1.1.3 + sibling 1.5.8 + patched. */
    private void bugReproRepo() throws IOException {
        File repoDir = new File(projectDir, "local-repo");
        createFakeArtifactWithDeps(repoDir, "org.example", "host-lib", "1.0",
            List.of("ch.qos.logback:logback-core:1.1.3"));
        createFakeArtifact(repoDir, "ch.qos.logback", "logback-core", "1.1.3");
        createFakeArtifact(repoDir, "ch.qos.logback", "logback-core", "1.5.8");
        createFakeArtifact(repoDir, "io.root.ch.qos.logback", "logback-core", "1.1.3-root.io.1");
    }

    /** Mock backend returns the patch for logback-core:1.1.3, empty otherwise. */
    private void setupPatchOnly113() {
        setupVersionAwareApiServer(req ->
            req.contains("\"version\":\"1.1.3\"") && req.contains("logback-core")
                ? patchJson("ch.qos.logback:logback-core", "1.1.3",
                    "io.root.ch.qos.logback:logback-core", "1.1.3-root.io.1")
                : emptyJson());
    }

    private void writeBuildScript(String gradleVersion, String depsBlock) throws IOException {
        Files.writeString(new File(projectDir, "settings.gradle.kts").toPath(),
            "rootProject.name = \"test-project\"\n");
        Files.writeString(new File(projectDir, "build.gradle.kts").toPath(),
            "plugins {\n    java\n    id(\"io.root.patcher\")\n}\n" +
            "repositories { maven { url = uri(\"" + new File(projectDir, "local-repo").toURI() + "\") } }\n" +
            "dependencies {\n    " + depsBlock + "\n}\n" +
            "rootio {\n" +
            "    apiKey.set(\"k\")\n" +
            "    apiUrl.set(\"http://localhost:" + port + "\")\n" +
            "}\n" +
            "tasks.register(\"listClasspath\") {\n" +
            "    doLast {\n" +
            "        configurations[\"compileClasspath\"].resolve().forEach { println(\"CP: \" + it.name) }\n" +
            "    }\n" +
            "}\n");
    }

    private BuildResult runListClasspath(String gradleVersion) {
        return GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withGradleVersion(gradleVersion)
            .withEnvironment(baseEnv())
            .withArguments("listClasspath")
            .build();
    }

    private static List<String> jarsByPrefix(BuildResult result, String prefix) {
        return result.getOutput().lines()
            .filter(l -> l.startsWith("CP: " + prefix))
            .map(l -> l.substring(4))
            .toList();
    }

    private static String patchJson(String pkg, String version, String patchedName, String patchedVersion) {
        Map<String, Object> patchAlias = Map.of("name", patchedName, "version", patchedVersion);
        Map<String, Object> patch = new java.util.LinkedHashMap<>();
        patch.put("package_name", pkg);
        patch.put("version", version);
        patch.put("patch_alias", patchAlias);
        patch.put("cve_ids", List.of());
        return JsonOutput.toJson(Map.of("patches", List.of(patch), "skipped", List.of()));
    }

    private static String emptyJson() {
        return JsonOutput.toJson(Map.of("patches", List.of(), "skipped", List.of()));
    }

    private void setupServerResponse(String body) {
        server.createContext("/v3/analyze/v2/maven", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    /** Mock backend with per-request decision based on the request body. */
    private void setupVersionAwareApiServer(java.util.function.Function<String, String> router) {
        server.createContext("/v3/analyze/v2/maven", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String requestBody = new String(body, StandardCharsets.UTF_8);
            String response = router.apply(requestBody);
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    private void createFakeArtifact(File repoDir, String group, String artifact, String version)
            throws IOException {
        File dir = new File(repoDir, group.replace('.', '/') + "/" + artifact + "/" + version);
        dir.mkdirs();
        String pom = "<project><modelVersion>4.0.0</modelVersion>"
            + "<groupId>" + group + "</groupId>"
            + "<artifactId>" + artifact + "</artifactId>"
            + "<version>" + version + "</version>"
            + "<packaging>jar</packaging></project>";
        Files.writeString(new File(dir, artifact + "-" + version + ".pom").toPath(), pom);
        try (ZipOutputStream zos = new ZipOutputStream(
                new FileOutputStream(new File(dir, artifact + "-" + version + ".jar")))) {
            // empty zip is a valid jar
        }
    }

    /**
     * Writes a fake BOM/platform POM ({@code <packaging>pom</packaging>} with a
     * {@code <dependencyManagement>} block constraining the listed coords). The
     * POM has no JAR — platforms aren't artifacts. Consumed via
     * {@code implementation(platform("g:a:v"))} in the build script.
     */
    private void createFakeBomPlatform(File repoDir, String group, String artifact, String version,
            List<String> constraintCoords) throws IOException {
        File dir = new File(repoDir, group.replace('.', '/') + "/" + artifact + "/" + version);
        dir.mkdirs();
        StringBuilder constraints = new StringBuilder("<dependencyManagement><dependencies>");
        for (String coord : constraintCoords) {
            String[] parts = coord.split(":", 3);
            constraints.append("<dependency>")
                .append("<groupId>").append(parts[0]).append("</groupId>")
                .append("<artifactId>").append(parts[1]).append("</artifactId>")
                .append("<version>").append(parts[2]).append("</version>")
                .append("</dependency>");
        }
        constraints.append("</dependencies></dependencyManagement>");
        String pom = "<project><modelVersion>4.0.0</modelVersion>"
            + "<groupId>" + group + "</groupId>"
            + "<artifactId>" + artifact + "</artifactId>"
            + "<version>" + version + "</version>"
            + "<packaging>pom</packaging>"
            + constraints
            + "</project>";
        Files.writeString(new File(dir, artifact + "-" + version + ".pom").toPath(), pom);
    }

    private void createFakeArtifactWithDeps(File repoDir, String group, String artifact,
            String version, List<String> depCoords) throws IOException {
        File dir = new File(repoDir, group.replace('.', '/') + "/" + artifact + "/" + version);
        dir.mkdirs();
        StringBuilder deps = new StringBuilder("<dependencies>");
        for (String coord : depCoords) {
            String[] parts = coord.split(":", 3);
            deps.append("<dependency>")
                .append("<groupId>").append(parts[0]).append("</groupId>")
                .append("<artifactId>").append(parts[1]).append("</artifactId>");
            if (parts.length == 3) {
                deps.append("<version>").append(parts[2]).append("</version>");
            }
            deps.append("</dependency>");
        }
        deps.append("</dependencies>");
        String pom = "<project><modelVersion>4.0.0</modelVersion>"
            + "<groupId>" + group + "</groupId>"
            + "<artifactId>" + artifact + "</artifactId>"
            + "<version>" + version + "</version>"
            + "<packaging>jar</packaging>"
            + deps
            + "</project>";
        Files.writeString(new File(dir, artifact + "-" + version + ".pom").toPath(), pom);
        try (ZipOutputStream zos = new ZipOutputStream(
                new FileOutputStream(new File(dir, artifact + "-" + version + ".jar")))) {
            // empty
        }
    }
}
