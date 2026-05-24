package io.root.patcher;

import java.util.List;
import java.util.regex.Matcher;

/**
 * Pure decision logic for resolving capability conflicts between a Root.io
 * patched coord and upstream sibling(s) claiming the same capability.
 *
 * <p>Extracted from {@link RootIoPatcherPlugin} for unit-testability without
 * mocking Gradle's {@code CapabilityResolutionDetails}, which is fragile
 * across Gradle versions. The plugin adapts Gradle's real candidates to the
 * simple {@link Candidate} pairs used here, then applies the returned
 * {@link Decision} via either {@code details.select(...)} or
 * {@code details.selectHighestVersion()}.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Find the single Root.io-patched candidate (group starts with
 *       {@code io.root.}, version matches {@code -root.io.N} suffix).</li>
 *   <li>If none — defer.</li>
 *   <li>If more than one — defensively defer; Gradle's intra-module conflict
 *       resolution should have collapsed multiples before capability
 *       resolution fires.</li>
 *   <li>Strip the suffix to obtain the patched coord's base upstream
 *       version. This string is, by patcher-service invariant, byte-identical
 *       to the upstream version string the patch was minted from, so plain
 *       {@code String.equals} on it is the correct tie comparator.</li>
 *   <li>If every other candidate is a module candidate AND its version
 *       equals the patched base version — return select(patched). This is
 *       the same-version tie that {@code selectHighestVersion()} cannot
 *       break.</li>
 *   <li>Else — defer. Gradle's own version comparator inside
 *       {@code selectHighestVersion()} handles cross-version cases including
 *       qualifier-bearing pre-releases and trailing-zero normalisation.</li>
 * </ol>
 */
public final class RootIoCapabilityResolver {

    private RootIoCapabilityResolver() {}

    /** Simple module-candidate / non-module-candidate distinction. */
    public static final class Candidate {
        final boolean isModule;
        final String group;
        final String version;

        private Candidate(boolean isModule, String group, String version) {
            this.isModule = isModule;
            this.group = group;
            this.version = version;
        }

        public static Candidate module(String group, String version) {
            return new Candidate(true, group, version);
        }

        public static Candidate nonModule() {
            return new Candidate(false, null, null);
        }
    }

    /** Either select a specific candidate index, or defer to selectHighestVersion. */
    public static final class Decision {
        public static final Decision DEFER = new Decision(-1);
        public final int selectIndex;

        private Decision(int selectIndex) {
            this.selectIndex = selectIndex;
        }

        public static Decision select(int index) {
            return new Decision(index);
        }

        public boolean isSelect() {
            return selectIndex >= 0;
        }
    }

    public static Decision decide(List<Candidate> candidates) {
        int patchedIdx = -1;
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            if (!c.isModule) continue;
            if (!c.group.startsWith(RootIoCapabilityRule.ROOT_IO_GROUP_PREFIX)) continue;
            if (!RootIoCapabilityRule.ROOT_IO_SUFFIX.matcher(c.version).find()) continue;
            if (patchedIdx != -1) {
                return Decision.DEFER;
            }
            patchedIdx = i;
        }
        if (patchedIdx == -1) {
            return Decision.DEFER;
        }

        Candidate patched = candidates.get(patchedIdx);
        Matcher m = RootIoCapabilityRule.ROOT_IO_SUFFIX.matcher(patched.version);
        m.find();
        String patchedBaseVersion = patched.version.substring(0, m.start());

        boolean sawOther = false;
        for (int i = 0; i < candidates.size(); i++) {
            if (i == patchedIdx) continue;
            sawOther = true;
            Candidate c = candidates.get(i);
            if (!c.isModule) return Decision.DEFER;
            if (!c.version.equals(patchedBaseVersion)) return Decision.DEFER;
        }
        if (!sawOther) return Decision.DEFER;
        return Decision.select(patchedIdx);
    }
}
