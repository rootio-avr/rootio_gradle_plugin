package io.root.patcher;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.CapabilityResolutionDetails;
import org.gradle.api.artifacts.ComponentVariantIdentifier;
import org.gradle.api.artifacts.DependencyResolveDetails;
import org.gradle.api.artifacts.ModuleVersionSelector;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.repositories.PasswordCredentials;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Provider;
import org.gradle.authentication.http.BasicAuthentication;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;

/** Gradle plugin that intercepts dependency resolution and substitutes vulnerable artifacts with Root.io patches. */
public class RootIoPatcherPlugin implements Plugin<Project> {
    private static final Logger logger = Logging.getLogger(RootIoPatcherPlugin.class);
    private static final String MAVEN_REPO_NAME = "Root.io patches";
    private static final ApiKeyResolver apiKeyResolver = new ApiKeyResolver();

    @Override
    public void apply(Project project) {
        RootIoExtension extension = project.getExtensions().create("rootio", RootIoExtension.class);
        extension.getApiUrl().convention(envOrDefault("ROOTIO_API_URL", "https://api.root.io"));
        extension.getPkgUrl().convention(envOrDefault("ROOTIO_PKG_URL", "https://pkg.root.io/maven"));
        extension.getTtlHours().convention(24L);
        extension.getMaxRetries().convention(3);
        extension.getRetryBaseDelayMs().convention(1000L);
        extension.getAllowInsecurePkgRepo().convention(false);
        // apiKey resolved automatically from .env, systemProp, or env var
        // it will throw an exception if not set later on in afterEvaluate
        apiKeyResolver.resolve(project.getRootDir()).ifPresent(key -> extension.getApiKey().convention(key));

        project.afterEvaluate(p -> registerRootMavenRepo(p, extension));

        // Capability injection — when Gradle resolves metadata for a Root.io-patched coord
        // (io.root.<G>:<A>:<V>-root.io.N), declare the secondary capability (G, A, V) so
        // unpatched siblings in the same graph trigger Gradle's capability conflict
        // detector instead of co-existing on the classpath. See `RootIoCapabilityRule`.
        project.getDependencies().getComponents().all(RootIoCapabilityRule.class);

        // IgnoreList must be built after the build script's rootio { } block is evaluated.
        // We use a single-element array so the lambda can capture the reference set in afterEvaluate.
        @SuppressWarnings("unchecked")
        List<String>[] ignoreEntriesHolder = new List[]{List.of()};
        project.afterEvaluate(p ->
            ignoreEntriesHolder[0] = IgnoreList.load(p.getRootDir(), resolveIgnoreEntries(p, extension)).toApiEntries());

        project.getConfigurations().all(config -> {
            // Only hook resolvable configurations — non-resolvable ones (e.g. `api`, `implementation`)
            // are for declaring dependencies and do not support eachDependency. Their dependencies
            // are still patched because resolvable configurations (e.g. `compileClasspath`,
            // `runtimeClasspath`) inherit from them and are processed below.
            if (!config.isCanBeResolved()) {
                return;
            }

            config.getResolutionStrategy().eachDependency(details ->
                    handleDependency(project, details, extension, ignoreEntriesHolder[0]));

            // Capability conflict resolution. The common case — patched and upstream
            // sibling at different versions — is delegated to selectHighestVersion(), which
            // uses Gradle's own version comparator (handles qualifiers, trailing-zero
            // normalisation, etc.). The special case — patched coord and upstream sibling
            // tied at the same base version, which selectHighestVersion() cannot break and
            // hard-fails on — is detected by RootIoCapabilityResolver and resolved by
            // picking the patched candidate (same upstream version means identical API
            // surface plus security fix).
            config.getResolutionStrategy().getCapabilitiesResolution().all(RootIoPatcherPlugin::resolveCapabilityConflict);
        });
    }

    // Auto-register the Root.io patches Maven repository so patched artifacts resolve
    // without users needing to add it manually. Done in afterEvaluate so apiKey/pkgUrl
    // are fully configured by the time we read them.
    private static void registerRootMavenRepo(Project p, RootIoExtension extension) {
        String pkgUrl = extension.getPkgUrl().get().replaceAll("/$", "");

        Action<? super PasswordCredentials> credsAction =
            PkgRepoCredentialResolver.resolve(extension, apiKeyResolver, p.getRootDir());

        logger.info("Registering repo: {} (credentials: {})", pkgUrl, credsAction != null ? "yes" : "none");
        p.getRepositories().maven(repo -> {
            repo.setName(MAVEN_REPO_NAME);
            repo.setUrl(pkgUrl);

            if (credsAction != null) {
                repo.credentials(credsAction);
                repo.authentication(auth -> auth.create("basic", BasicAuthentication.class));
            }
            if (extension.getAllowInsecurePkgRepo().get()) {
                repo.setAllowInsecureProtocol(true);
            }
        });
    }

    private static void handleDependency(Project project, DependencyResolveDetails details, RootIoExtension extension, List<String> ignoreEntries) {
        ModuleVersionSelector req = details.getRequested();
        String version = req.getVersion();

        // Skip deps with no version — these are BOM/platform-managed or Kotlin-plugin-managed
        // deps whose version is resolved separately. Sending an empty version to the API
        // produces a 400.
        if (version == null || version.isEmpty()) {
            logger.info("Skipping {}:{} (no version)", req.getGroup(), req.getName());
            return;
        }

        String coords = req.getGroup() + ":" + req.getName() + ":" + version;

        resolvePatchedDependency(project, details, coords, extension, ignoreEntries);
    }

    private static void resolvePatchedDependency(
            Project project,
            DependencyResolveDetails details,
            String coords,
            RootIoExtension ext,
            List<String> ignoreEntries
    ) {
        Provider<String> patchedProvider = project.getProviders().of(RootIoValueSource.class, spec -> {
            spec.getParameters().getCoords().set(coords);
            spec.getParameters().getApiUrl().set(ext.getApiUrl());
            spec.getParameters().getApiKey().set(ext.getApiKey());
            spec.getParameters().getRootDirPath().set(project.getRootDir().getAbsolutePath());
            spec.getParameters().getTtlHours().set(ext.getTtlHours());
            spec.getParameters().getMaxRetries().set(ext.getMaxRetries());
            spec.getParameters().getRetryBaseDelayMs().set(ext.getRetryBaseDelayMs());
            spec.getParameters().getIgnore().set(ignoreEntries);
        });
        // gets from cache if available or resolves from Root.io if not
        String patched = patchedProvider.getOrNull();

        if (patched != null) {
            RootDependency dep = new RootDependency(patched);
            details.useTarget(dep.toBuildTarget());
            details.because("Root.io security patch");
            logger.info("Patching {} -> {}", coords, patched);
        } else {
            logger.info("No patch for {}", coords);
        }
    }

    // Break the same-version capability tie selectHighestVersion() can't decide; defer to it for every other case.
    static void resolveCapabilityConflict(CapabilityResolutionDetails details) {
        List<? extends ComponentVariantIdentifier> candidates = details.getCandidates();

        ComponentVariantIdentifier patched = null;
        for (ComponentVariantIdentifier c : candidates) {
            ComponentIdentifier cid = c.getId();
            if (!(cid instanceof ModuleComponentIdentifier)) continue;
            ModuleComponentIdentifier mid = (ModuleComponentIdentifier) cid;
            if (!mid.getGroup().startsWith(RootIoCapabilityRule.ROOT_IO_GROUP_PREFIX)) continue;
            if (!RootIoCapabilityRule.ROOT_IO_SUFFIX.matcher(mid.getVersion()).find()) continue;
            if (patched != null) {
                // Shouldn't reach here: Gradle's intra-module conflict resolution collapses io.root.* duplicates first.
                details.selectHighestVersion();
                return;
            }
            patched = c;
        }
        if (patched == null) {
            details.selectHighestVersion();
            return;
        }

        // String equality is correct: the patcher mints the patched version from the upstream string verbatim.
        ModuleComponentIdentifier patchedId = (ModuleComponentIdentifier) patched.getId();
        Matcher m = RootIoCapabilityRule.ROOT_IO_SUFFIX.matcher(patchedId.getVersion());
        m.find();
        String patchedBaseVersion = patchedId.getVersion().substring(0, m.start());

        boolean sawOther = false;
        for (ComponentVariantIdentifier c : candidates) {
            if (c == patched) continue;
            sawOther = true;
            ComponentIdentifier cid = c.getId();
            if (!(cid instanceof ModuleComponentIdentifier)
                    || !((ModuleComponentIdentifier) cid).getVersion().equals(patchedBaseVersion)) {
                details.selectHighestVersion();
                return;
            }
        }
        if (!sawOther) {
            details.selectHighestVersion();
            return;
        }
        details.select(patched).because("Root.io security patch (same upstream version)");
    }

    // Merges the extension `ignore` list with the comma-separated `-Prootio.ignore` property.
    private static List<String> resolveIgnoreEntries(Project project, RootIoExtension extension) {
        List<String> entries = new ArrayList<>(extension.getIgnore().getOrElse(List.of()));
        Object prop = project.findProperty("rootio.ignore");
        if (prop != null) {
            for (String part : Arrays.asList(prop.toString().split(","))) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    entries.add(trimmed);
                }
            }
        }
        return entries;
    }

    private static String envOrDefault(String name, String defaultValue) {
        String val = System.getenv(name);
        return (val != null && !val.isEmpty()) ? val : defaultValue;
    }

    private static String maskApiKey(String apiKey) {
        return apiKey != null && apiKey.length() > 8 ?
                apiKey.substring(0, 4) + "..." + apiKey.substring(apiKey.length() - 4) :
                "(too short)";
    }
}
