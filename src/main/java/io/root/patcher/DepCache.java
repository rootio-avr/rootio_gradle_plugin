package io.root.patcher;

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

import groovy.json.JsonOutput;
import groovy.json.JsonSlurper;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** SHA-1-keyed JSON file cache for Root.io API responses, stored under {@code .gradle/rootio-cache/}. */
public class DepCache {

    private static final Logger logger = Logging.getLogger(ApiKeyResolver.class);
    private static final String CACHE_SUBDIR = ".gradle/rootio-cache";
    private static final String PATCHED_COORDS_KEY = "patched";
    private static final String HASHING_ALGORITHM = "SHA-1";

    /**
     * Look up the patched coordinates for {@code coords} from the local cache.
     * On a cache miss (file absent or older than {@code ttlHours}), calls {@code onMiss},
     * writes the result to the cache, and returns it.
     *
     * @param coords    Maven GAV string — "group:artifact:version"
     * @param rootDir   project root directory (always use {@code project.getRootDir()})
     * @param ttlHours  cache TTL in hours
     * @param onMiss    called when the cache is cold or stale; may return null (no patch)
     * @return patched GAV string, or null if no patch exists
     */
    public static String lookup(String coords, List<String> ignoreEntries, File rootDir, long ttlHours, Supplier<String> onMiss) {
        File cacheFile = cacheFile(coords, ignoreEntries, rootDir);

        if (cacheFile.exists() && isWithinTtl(cacheFile, ttlHours)) {
            logger.debug("Using cached patch for {} from {}", coords, cacheFile);
            Map<String, String> cached = readCache(cacheFile);
            if (cached != null) {
                return cached.get(PATCHED_COORDS_KEY);
            }
        }

        String result = onMiss.get();
        writeCache(cacheFile, result);
        return result;
    }

    private static boolean isWithinTtl(File file, long ttlHours) {
        long ageMs = System.currentTimeMillis() - file.lastModified();
        return ageMs < ttlHours * 3_600_000L;
    }

    private static File cacheFile(String coords, List<String> ignoreEntries, File rootDir) {
        File dir = new File(rootDir, CACHE_SUBDIR);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String cacheKey = coords;
        if (ignoreEntries != null && !ignoreEntries.isEmpty()) {
            List<String> sorted = new ArrayList<>(ignoreEntries);
            Collections.sort(sorted);
            cacheKey = coords + "|" + String.join(",", sorted);
        }
        return new File(dir, sha1(cacheKey) + ".json");
    }

    private static Map<String, String> readCache(File file) {
        byte[] content;

        try {
            content = Files.readAllBytes(file.toPath());
        } catch (IOException exception) {
            logger.debug("Failed to read cache file {}", file, exception);
            return null;
        }

        try {
            //noinspection unchecked
            return (Map<String, String>) new JsonSlurper().parse(content);
        } catch (ClassCastException exception) {
            logger.debug("Failed to parse cache file {}", file, exception);
            return null;
        }
    }

    private static void writeCache(File file, String patchedCoords) {
        Map<String, Object> map = new HashMap<>() {{
            put(PATCHED_COORDS_KEY, patchedCoords);
        }};

        try {
            Files.writeString(file.toPath(), JsonOutput.toJson(map));
        } catch (IOException exception) {
            // swallow — cache write failure is non-fatal; the build continues
            logger.debug("Failed to write cache file {}", file, exception);
        }
    }

    private static String sha1(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance(HASHING_ALGORITHM);
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new RuntimeException(HASHING_ALGORITHM + " not available", exception);
        }
    }
}
