package io.root.patcher;

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Set of dependency coordinates to skip patching, loaded from a {@code .rootioignore} file
 * and/or supplied entries. Each entry is {@code group:artifact@version} (exact, case-sensitive).
 */
public final class IgnoreList {
    private static final Logger logger = Logging.getLogger(IgnoreList.class);
    private static final String IGNORE_FILE_NAME = ".rootioignore";

    private final Set<String> keys;

    private IgnoreList(Set<String> keys) {
        this.keys = keys;
    }

    /**
     * Builds an ignore set from {@code <rootDir>/.rootioignore} (silently skipped if absent)
     * merged with {@code extraEntries}. Blank lines and lines starting with {@code #} are
     * ignored; malformed entries are logged at warn and skipped.
     *
     * @param rootDir      directory to look for {@code .rootioignore} in
     * @param extraEntries additional {@code group:artifact@version} entries (e.g. from config)
     * @return a populated {@link IgnoreList}
     */
    public static IgnoreList load(File rootDir, List<String> extraEntries) {
        Set<String> keys = new HashSet<>();

        File file = new File(rootDir, IGNORE_FILE_NAME);
        if (file.isFile()) {
            try {
                for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                    addEntry(keys, line);
                }
            } catch (IOException e) {
                logger.warn("Could not read {}: {}", file, e.getMessage());
            }
        }

        if (extraEntries != null) {
            for (String entry : extraEntries) {
                addEntry(keys, entry);
            }
        }

        return new IgnoreList(keys);
    }

    // Validates and adds a single "group:artifact@version" entry; skips blanks, comments,
    // and malformed entries (warn-logged).
    private static void addEntry(Set<String> keys, String raw) {
        if (raw == null) {
            return;
        }
        String line = raw.trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }
        if (!isWellFormed(line)) {
            logger.warn("Skipping malformed .rootioignore entry (expected group:artifact@version): {}", line);
            return;
        }
        keys.add(line);
    }

    // A well-formed entry is "group:artifact@version": exactly one '@', and the part before it
    // is "group:artifact" with exactly one ':' and non-empty group, artifact, and version.
    private static boolean isWellFormed(String entry) {
        int at = entry.indexOf('@');
        if (at <= 0 || at == entry.length() - 1) {
            return false;
        }
        String version = entry.substring(at + 1);
        String groupArtifact = entry.substring(0, at);
        if (version.isEmpty() || version.indexOf('@') >= 0) {
            return false;
        }
        int colon = groupArtifact.indexOf(':');
        if (colon <= 0 || colon == groupArtifact.length() - 1) {
            return false;
        }
        // exactly one colon
        return groupArtifact.indexOf(':', colon + 1) < 0;
    }

    /**
     * Returns all ignore entries as a list of {@code "group:artifact@version"} strings,
     * suitable for passing to the Root.io API as the {@code ignore} field.
     */
    public List<String> toApiEntries() {
        return new java.util.ArrayList<>(keys);
    }
}
