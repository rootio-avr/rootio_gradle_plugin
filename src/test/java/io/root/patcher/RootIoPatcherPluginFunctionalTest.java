package io.root.patcher;

import com.sun.net.httpserver.HttpServer;
import groovy.json.JsonOutput;
import groovy.text.SimpleTemplateEngine;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class RootIoPatcherPluginFunctionalTest {

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

    /**
     * Gradle versions to test against. Override with -Dtest.gradleVersions=7.6.4,8.10
     * when running the JDK 11 CI job (Gradle 9.x requires Java 17+).
     */
    static Stream<String> gradleVersions() {
        String prop = System.getProperty("test.gradleVersions");
        if (prop != null && !prop.isBlank()) {
            return Arrays.stream(prop.split(",")).map(String::trim).filter(s -> !s.isEmpty());
        }
        return Stream.of("7.6.4", "8.10", "9.4.1");
    }

    /**
     * Environment for all GradleRunner calls. When test.javaHome is set, it overrides
     * JAVA_HOME so TestKit daemons use a different JDK than the one running the tests
     * (e.g. JDK 11 daemons driven by a JDK 17 Gradle wrapper in CI).
     */
    private Map<String, String> env() {
        Map<String, String> env = new HashMap<>(System.getenv());
        String javaHome = System.getProperty("test.javaHome");
        if (javaHome != null && !javaHome.isBlank()) {
            env.put("JAVA_HOME", javaHome);
        }
        return env;
    }

    @ParameterizedTest(name = "Gradle {0}")
    @MethodSource("gradleVersions")
    void substitutesDepWhenPatchAvailable(String gradleVersion) throws IOException {
        setupServerResponse(200, patchResponseJson("io.test:my-lib", "1.0.0", "io.test:my-lib", "1.0.0-root.io.4"));
        writeProjectFiles();

        BuildResult result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withGradleVersion(gradleVersion)
            .withEnvironment(env())
            .withArguments("dependencies", "--configuration", "compileClasspath")
            .build();

        assertTrue(result.getOutput().contains("io.test:my-lib:1.0.0 -> 1.0.0-root.io.4"),
            "Expected patched coordinates in output:\n" + result.getOutput());
    }

    @ParameterizedTest(name = "Gradle {0}")
    @MethodSource("gradleVersions")
    void doesNotSubstituteWhenNoPatchAvailable(String gradleVersion) throws IOException {
        setupServerResponse(200, emptyPatchResponseJson());
        writeProjectFiles();

        BuildResult result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withGradleVersion(gradleVersion)
            .withEnvironment(env())
            .withArguments("dependencies", "--configuration", "compileClasspath")
            .build();

        assertTrue(result.getOutput().contains("io.test:my-lib:1.0.0"),
            "Expected original coordinates in output:\n" + result.getOutput());
        assertFalse(result.getOutput().contains("root.io"),
            "Expected no substitution in output:\n" + result.getOutput());
    }

    @ParameterizedTest(name = "Gradle {0}")
    @MethodSource("gradleVersions")
    void reasonStringAppearsInDependencyInsight(String gradleVersion) throws IOException {
        setupServerResponse(200, patchResponseJson("io.test:my-lib", "1.0.0", "io.test:my-lib", "1.0.0-root.io.4"));
        writeProjectFiles();

        BuildResult result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withGradleVersion(gradleVersion)
            .withEnvironment(env())
            .withArguments("dependencyInsight", "--dependency", "io.test:my-lib",
                "--configuration", "compileClasspath")
            .build();

        assertTrue(result.getOutput().contains("Root.io security patch"),
            "Expected 'Root.io security patch' reason in dependencyInsight output:\n" + result.getOutput());
    }

    @ParameterizedTest(name = "Gradle {0}")
    @MethodSource("gradleVersions")
    void failsBuildWhenApiReturns500(String gradleVersion) throws IOException {
        setupServerResponse(500, "");
        writeProjectFiles();

        BuildResult result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withGradleVersion(gradleVersion)
            .withEnvironment(env())
            .withArguments("forceResolve")
            .buildAndFail();

        assertTrue(result.getOutput().contains("500") || result.getOutput().contains("Root.io"),
            "Expected error message about HTTP 500 in output:\n" + result.getOutput());
    }

    @ParameterizedTest(name = "Gradle {0}")
    @MethodSource("gradleVersions")
    void resolvesFromAutoRegisteredPkgRepo(String gradleVersion) throws IOException {
        // The plugin must auto-register {pkgUrl}/maven so patched artifacts resolve
        // without the user needing to add the repository manually.
        setupServerResponse(200, patchResponseJson("io.test:my-lib", "1.0.0", "io.test:my-lib", "1.0.0-root.io.4"));

        File repoDir = new File(projectDir, "local-repo");
        File pkgRepoDir = new File(projectDir, "pkg-repo");
        createFakeArtifact(repoDir, "io.test", "my-lib", "1.0.0");
        createFakeArtifact(pkgRepoDir, "io.test", "my-lib", "1.0.0-root.io.4");

        // Both repos served over HTTP so we can assert exactly which one served each artifact.
        try (FileServingRepo originalRepo = new FileServingRepo(repoDir);
             FileServingRepo pkgRepo = new FileServingRepo(pkgRepoDir)) {

            Files.writeString(new File(projectDir, "settings.gradle.kts").toPath(),
                "rootProject.name = \"test-project\"\n");
            writeBuildGradleKts(originalRepo.url(), "http://localhost:" + port, "test-key",
                pkgRepo.url(), true);

            // Use a per-test Gradle user home so the module cache is fresh and Gradle
            // must contact our HTTP servers rather than using a cross-test cached artifact.
            Map<String, String> env = env();
            env.put("GRADLE_USER_HOME", new File(projectDir, ".gradle-home").getAbsolutePath());

            BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withGradleVersion(gradleVersion)
                .withEnvironment(env)
                .withArguments("forceResolve")
                .build();

            assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"),
                "Expected patched artifact to resolve from auto-registered pkg repo:\n" + result.getOutput());
            assertTrue(pkgRepo.served("1.0.0-root.io.4"),
                "Expected pkg repo to serve the patched artifact");
            assertFalse(originalRepo.served("1.0.0-root.io.4"),
                "Expected original repo NOT to serve the patched artifact");
        }
    }

    @ParameterizedTest(name = "Gradle {0}")
    @MethodSource("gradleVersions")
    void worksWithConfigurationCache(String gradleVersion) throws IOException {
        setupServerResponse(200, patchResponseJson("io.test:my-lib", "1.0.0", "io.test:my-lib", "1.0.0-root.io.4"));
        writeProjectFiles();

        // First build: stores the configuration cache
        BuildResult first = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withGradleVersion(gradleVersion)
            .withEnvironment(env())
            .withArguments("--configuration-cache", "dependencies", "--configuration", "compileClasspath")
            .build();

        assertTrue(first.getOutput().contains("io.test:my-lib:1.0.0 -> 1.0.0-root.io.4"),
            "Expected patched coordinates in first build output:\n" + first.getOutput());

        // Second build: must reuse the stored configuration cache
        BuildResult second = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withGradleVersion(gradleVersion)
            .withEnvironment(env())
            .withArguments("--configuration-cache", "dependencies", "--configuration", "compileClasspath")
            .build();

        assertTrue(
            second.getOutput().contains("Configuration cache entry reused") ||
            second.getOutput().contains("Reusing configuration cache"),
            "Expected second build to reuse configuration cache:\n" + second.getOutput());
        assertTrue(second.getOutput().contains("io.test:my-lib:1.0.0 -> 1.0.0-root.io.4"),
            "Expected patched coordinates in second build output:\n" + second.getOutput());
    }

    @ParameterizedTest(name = "Gradle {0}")
    @MethodSource("gradleVersions")
    void resolvesApiKeyFromDotEnvFile(String gradleVersion) throws IOException {
        setupServerResponse(200, emptyPatchResponseJson());

        File repoDir = new File(projectDir, "local-repo");
        createFakeArtifact(repoDir, "io.test", "my-lib", "1.0.0");

        // Write a .env file with the API key — no apiKey.set() in the build script
        Files.writeString(new File(projectDir, ".env").toPath(), "ROOTIO_API_KEY=test-key\n");

        Files.writeString(new File(projectDir, "settings.gradle.kts").toPath(),
            "rootProject.name = \"test-project\"\n");
        // No apiKey.set() — key must come from .env file
        writeBuildGradleKts(repoDir.toURI().toString(), "http://localhost:" + port, null, null, false);

        BuildResult result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withGradleVersion(gradleVersion)
            .withEnvironment(env())
            .withArguments("dependencies", "--configuration", "compileClasspath")
            .build();

        assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"),
            "Expected build to succeed when API key is resolved from .env file:\n" + result.getOutput());
    }

    @ParameterizedTest(name = "Gradle {0}")
    @MethodSource("gradleVersions")
    void returnsAlternativePatchWhenPreferredPatchIsIgnored(String gradleVersion) throws IOException {
        // The API receives the ignore list and returns an alternative patch
        AtomicReference<String> capturedRequestBody = new AtomicReference<>();
        server.createContext("/v3/analyze/v2/maven", exchange -> {
            capturedRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = patchResponseJson("io.test:my-lib", "1.0.0",
                "io.test:my-lib", "1.0.0-root.io.4").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });

        File repoDir = new File(projectDir, "local-repo");
        createFakeArtifact(repoDir, "io.test", "my-lib", "1.0.0");
        createFakeArtifact(repoDir, "io.test", "my-lib", "1.0.0-root.io.4");
        Files.writeString(new File(projectDir, "settings.gradle.kts").toPath(),
            "rootProject.name = \"test-project\"\n");
        writeBuildGradleKtsWithIgnore(repoDir.toURI().toString(), "http://localhost:" + port,
            "test-key", "io.test:my-lib@1.0.0-root.io.5");

        BuildResult result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withGradleVersion(gradleVersion)
            .withEnvironment(env())
            .withArguments("dependencies", "--configuration", "compileClasspath")
            .build();

        // The API received the ignore list
        assertNotNull(capturedRequestBody.get(), "Expected API to be called");
        assertTrue(capturedRequestBody.get().contains("root.io.5"),
            "Expected ignore entry in API request body:\n" + capturedRequestBody.get());

        // The alternative patch (not the ignored one) was substituted
        assertTrue(result.getOutput().contains("io.test:my-lib:1.0.0 -> 1.0.0-root.io.4"),
            "Expected alternative patch in output:\n" + result.getOutput());
        assertFalse(result.getOutput().contains("1.0.0-root.io.5"),
            "Expected ignored patch version NOT in output:\n" + result.getOutput());
    }

    // --- Helpers ---

    private static final String BUILD_SCRIPT_TEMPLATE =
        "plugins {\n" +
        "    java\n" +
        "    id(\"io.root.patcher\")\n" +
        "}\n" +
        "repositories {\n" +
        "    maven {\n" +
        "        url = uri(\"${repoUri}\")\n" +
        "${allowInsecureRepoLine}" +
        "    }\n" +
        "}\n" +
        "dependencies {\n" +
        "    implementation(\"io.test:my-lib:1.0.0\")\n" +
        "}\n" +
        "rootio {\n" +
        "${rootioConfig}" +
        "}\n" +
        "${extraTasks}";

    // Writes build.gradle.kts using SimpleTemplateEngine to bind runtime values into the template.
    // apiKey and pkgUrl are optional (pass null to omit).
    // forceResolve uses strict (non-lenient) resolution — fails the build if any dep throws
    // during eachDependency. The `dependencies` task uses lenient resolution and only marks
    // deps FAILED without failing the overall build.
    private void writeBuildGradleKts(String repoUri, String apiUrl, String apiKey, String pkgUrl,
            boolean includeForceResolve) throws IOException {
        StringBuilder rootioConfig = new StringBuilder();
        if (apiKey != null) rootioConfig.append("    apiKey.set(\"").append(apiKey).append("\")\n");
        rootioConfig.append("    apiUrl.set(\"").append(apiUrl).append("\")\n");
        if (pkgUrl != null) {
            rootioConfig.append("    pkgUrl.set(\"").append(pkgUrl).append("\")\n");
            if (pkgUrl.startsWith("http://")) {
                rootioConfig.append("    allowInsecurePkgRepo.set(true)\n");
            }
        }

        String forceResolveTask = includeForceResolve
            ? "tasks.register(\"forceResolve\") {\n" +
              "    doLast {\n" +
              "        configurations[\"compileClasspath\"].files\n" +
              "    }\n" +
              "}\n"
            : "";

        Map<String, Object> bindings = new HashMap<>();
        bindings.put("repoUri", repoUri);
        bindings.put("allowInsecureRepoLine", repoUri.startsWith("http://") ? "        isAllowInsecureProtocol = true\n" : "");
        bindings.put("rootioConfig", rootioConfig.toString());
        bindings.put("extraTasks", forceResolveTask);

        try {
            String content = new SimpleTemplateEngine()
                .createTemplate(BUILD_SCRIPT_TEMPLATE)
                .make(bindings)
                .toString();
            Files.writeString(new File(projectDir, "build.gradle.kts").toPath(), content);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    // Like writeBuildGradleKts but adds an `ignore.set(listOf("..."))` line to the rootio block.
    private void writeBuildGradleKtsWithIgnore(String repoUri, String apiUrl, String apiKey,
            String ignoreEntry) throws IOException {
        StringBuilder rootioConfig = new StringBuilder();
        rootioConfig.append("    apiKey.set(\"").append(apiKey).append("\")\n");
        rootioConfig.append("    apiUrl.set(\"").append(apiUrl).append("\")\n");
        rootioConfig.append("    ignore.set(listOf(\"").append(ignoreEntry).append("\"))\n");

        Map<String, Object> bindings = new HashMap<>();
        bindings.put("repoUri", repoUri);
        bindings.put("allowInsecureRepoLine", repoUri.startsWith("http://") ? "        isAllowInsecureProtocol = true\n" : "");
        bindings.put("rootioConfig", rootioConfig.toString());
        bindings.put("extraTasks", "");

        try {
            String content = new SimpleTemplateEngine()
                .createTemplate(BUILD_SCRIPT_TEMPLATE)
                .make(bindings)
                .toString();
            Files.writeString(new File(projectDir, "build.gradle.kts").toPath(), content);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private static String patchResponseJson(String packageName, String version, String patchedName, String patchedVersion) {
        Map<String, Object> patch = new java.util.LinkedHashMap<>();
        patch.put("package_name", packageName);
        patch.put("version", version);
        patch.put("patch", Map.of("name", patchedName, "version", patchedVersion));
        patch.put("cve_ids", List.of());
        return JsonOutput.toJson(Map.of("patches", List.of(patch), "skipped", List.of()));
    }

    private static String emptyPatchResponseJson() {
        return JsonOutput.toJson(Map.of("patches", List.of(), "skipped", List.of()));
    }

    private void setupServerResponse(int status, String body) {
        server.createContext("/v3/analyze/v2/maven", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, status == 200 ? bytes.length : -1);
            if (status == 200) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } else {
                exchange.getResponseBody().close();
            }
        });
    }

    private void writeProjectFiles() throws IOException {
        File repoDir = new File(projectDir, "local-repo");
        createFakeArtifact(repoDir, "io.test", "my-lib", "1.0.0");
        createFakeArtifact(repoDir, "io.test", "my-lib", "1.0.0-root.io.4");

        Files.writeString(new File(projectDir, "settings.gradle.kts").toPath(),
            "rootProject.name = \"test-project\"\n");
        writeBuildGradleKts(repoDir.toURI().toString(), "http://localhost:" + port, "test-key", null, true);
    }

    /**
     * Creates a minimal Maven artifact (POM + empty valid JAR) in a local file repository.
     * Gradle needs a valid ZIP/JAR file to successfully resolve the artifact.
     */
    private void createFakeArtifact(File repoDir, String group, String artifact, String version)
            throws IOException {
        File dir = new File(repoDir, group.replace('.', '/') + "/" + artifact + "/" + version);
        dir.mkdirs();

        String pom = "<project><modelVersion>4.0.0</modelVersion>" +
            "<groupId>" + group + "</groupId>" +
            "<artifactId>" + artifact + "</artifactId>" +
            "<version>" + version + "</version>" +
            "<packaging>jar</packaging></project>";
        Files.writeString(new File(dir, artifact + "-" + version + ".pom").toPath(), pom);

        // Create a valid (empty) JAR — JARs are ZIP files; an empty ZIP is valid
        try (ZipOutputStream zos = new ZipOutputStream(
                new FileOutputStream(new File(dir, artifact + "-" + version + ".jar")))) {
            // intentionally empty
        }
    }

    /**
     * An HTTP server that serves files from a local directory and tracks which paths
     * were successfully served. Use {@link #served(String)} to assert routing behaviour.
     */
    private static class FileServingRepo implements AutoCloseable {
        private final HttpServer server;
        private final List<String> servedPaths = new CopyOnWriteArrayList<>();

        FileServingRepo(File rootDir) throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/", exchange -> {
                // Strip the leading "/" — new File(root, "/abs") ignores root entirely on Unix
                String relativePath = exchange.getRequestURI().getPath().replaceFirst("^/", "");
                File file = new File(rootDir, relativePath);
                if (file.exists() && file.isFile()) {
                    servedPaths.add(exchange.getRequestURI().getPath());
                    byte[] bytes = Files.readAllBytes(file.toPath());
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                } else {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.getResponseBody().close();
                }
            });
            server.start();
        }

        String url() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        /**
         * Returns true if any successfully served path contains {@code fragment}
         * (e.g. a version string to distinguish which repo served which artifact).
         */
        boolean served(String fragment) {
            return servedPaths.stream().anyMatch(p -> p.contains(fragment));
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
