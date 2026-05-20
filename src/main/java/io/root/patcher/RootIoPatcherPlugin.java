package io.root.patcher;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.DependencyResolveDetails;
import org.gradle.api.artifacts.ModuleVersionSelector;
import org.gradle.api.artifacts.repositories.PasswordCredentials;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Provider;
import org.gradle.authentication.http.BasicAuthentication;

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

        project.getConfigurations().all(config -> {
            // Only hook resolvable configurations — non-resolvable ones (e.g. `api`, `implementation`)
            // are for declaring dependencies and do not support eachDependency. Their dependencies
            // are still patched because resolvable configurations (e.g. `compileClasspath`,
            // `runtimeClasspath`) inherit from them and are processed below.
            if (!config.isCanBeResolved()) {
                return;
            }

            config.getResolutionStrategy().eachDependency(details ->
                    handleDependency(project, details, extension));

            // Capability conflict resolution — picks the highest-version candidate when both
            // the patched and unpatched siblings of an artifact end up in the graph claiming
            // the same capability. Capability versions are compared (not coord versions), so
            // the patched coord's injected (originalGroup, artifact, originalVersion)
            // competes against the upstream sibling's implicit default capability. Users
            // who require a different winner in the rare same-version case can override via
            // standard Gradle dependencySubstitution.
            config.getResolutionStrategy().getCapabilitiesResolution().all(details ->
                details.selectHighestVersion());
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

    private static void handleDependency(Project project, DependencyResolveDetails details, RootIoExtension extension) {
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

        resolvePatchedDependency(project, details, coords, extension);
    }

    private static void resolvePatchedDependency(
            Project project,
            DependencyResolveDetails details,
            String coords,
            RootIoExtension ext
    ) {
        Provider<String> patchedProvider = project.getProviders().of(RootIoValueSource.class, spec -> {
            spec.getParameters().getCoords().set(coords);
            spec.getParameters().getApiUrl().set(ext.getApiUrl());
            spec.getParameters().getApiKey().set(ext.getApiKey());
            spec.getParameters().getRootDirPath().set(project.getRootDir().getAbsolutePath());
            spec.getParameters().getTtlHours().set(ext.getTtlHours());
            spec.getParameters().getMaxRetries().set(ext.getMaxRetries());
            spec.getParameters().getRetryBaseDelayMs().set(ext.getRetryBaseDelayMs());
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
