package io.root.patcher;

import org.gradle.api.artifacts.CacheableRule;
import org.gradle.api.artifacts.ComponentMetadataContext;
import org.gradle.api.artifacts.ComponentMetadataRule;
import org.gradle.api.artifacts.ModuleVersionIdentifier;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * For every {@code io.root.<G>:<A>:<V>-root.io.N} component Gradle resolves metadata
 * for, declare a secondary capability matching the upstream coordinate the patched
 * artifact substitutes for: {@code (<G>, <A>, <V>)}.
 *
 * <p>When the unpatched sibling {@code <G>:<A>:<Vmax>} also lands in the resolution
 * graph, both variants claim capability {@code <G>:<A>}; Gradle's capability conflict
 * detector fires and the plugin's per-configuration {@code capabilitiesResolution}
 * rule calls {@code selectHighestVersion()} so the upstream sibling wins.
 *
 * <p>{@link CacheableRule}-annotated so Gradle's metadata cache amortises the
 * mutation across builds.
 */
@CacheableRule
public abstract class RootIoCapabilityRule implements ComponentMetadataRule {

    static final String ROOT_IO_GROUP_PREFIX = "io.root.";

    static final Pattern ROOT_IO_SUFFIX = Pattern.compile("-root\\.io\\.\\d+$");

    @Override
    public void execute(ComponentMetadataContext ctx) {
        ModuleVersionIdentifier id = ctx.getDetails().getId();
        String group = id.getGroup();
        if (!group.startsWith(ROOT_IO_GROUP_PREFIX)) {
            return;
        }
        Matcher m = ROOT_IO_SUFFIX.matcher(id.getVersion());
        if (!m.find()) {
            // io.root.* group but missing the patcher suffix — not a Root.io minted coord.
            return;
        }
        String originalGroup = group.substring(ROOT_IO_GROUP_PREFIX.length());
        String originalVersion = id.getVersion().substring(0, m.start());
        ctx.getDetails().allVariants(variant ->
            variant.withCapabilities(caps ->
                caps.addCapability(originalGroup, id.getName(), originalVersion)));
    }
}
