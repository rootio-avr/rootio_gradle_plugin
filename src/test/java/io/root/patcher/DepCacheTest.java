package io.root.patcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DepCacheTest {

    @TempDir
    File tempDir;

    @Test
    void cacheMissCallsOnMissAndWritesResult() {
        AtomicInteger callCount = new AtomicInteger(0);

        String result = DepCache.lookup("org.example:foo:1.0", List.of(), tempDir, 24, () -> {
            callCount.incrementAndGet();
            return "org.example:foo:1.0-root.io.1";
        });

        assertEquals("org.example:foo:1.0-root.io.1", result);
        assertEquals(1, callCount.get());
    }

    @Test
    void cacheHitSkipsOnMiss() {
        // Warm the cache
        DepCache.lookup("org.example:bar:2.0", List.of(), tempDir, 24, () -> "org.example:bar:2.0-root.io.1");

        AtomicInteger callCount = new AtomicInteger(0);
        String result = DepCache.lookup("org.example:bar:2.0", List.of(), tempDir, 24, () -> {
            callCount.incrementAndGet();
            return "should-not-be-called";
        });

        assertEquals("org.example:bar:2.0-root.io.1", result);
        assertEquals(0, callCount.get());
    }

    @Test
    void expiredTtlCallsOnMissAgain() {
        // Warm the cache
        DepCache.lookup("org.example:baz:3.0", List.of(), tempDir, 24, () -> "org.example:baz:3.0-root.io.1");

        // Backdate the cache file past the TTL
        File cacheDir = new File(tempDir, ".gradle/rootio-cache");
        File[] files = cacheDir.listFiles();
        assertNotNull(files);
        assertEquals(1, files.length);
        assertTrue(files[0].setLastModified(System.currentTimeMillis() - 25 * 3_600_000L));

        AtomicInteger callCount = new AtomicInteger(0);
        DepCache.lookup("org.example:baz:3.0", List.of(), tempDir, 24, () -> {
            callCount.incrementAndGet();
            return "refreshed";
        });

        assertEquals(1, callCount.get());
    }

    @Test
    void nullResultIsCachedAndNotRefetched() {
        // Cache a null (no patch for this dep)
        DepCache.lookup("org.example:qux:4.0", List.of(), tempDir, 24, () -> null);

        AtomicInteger callCount = new AtomicInteger(0);
        String result = DepCache.lookup("org.example:qux:4.0", List.of(), tempDir, 24, () -> {
            callCount.incrementAndGet();
            return "should-not-be-called";
        });

        assertNull(result);
        assertEquals(0, callCount.get());
    }

    @Test
    void differentIgnoreListProducesDifferentCacheEntry(@TempDir File rootDir) {
        AtomicInteger callCount = new AtomicInteger(0);

        String result1 = DepCache.lookup(
            "org.example:foo:1.0", List.of(), rootDir, 24,
            () -> { callCount.incrementAndGet(); return "patched-no-ignore"; });

        String result2 = DepCache.lookup(
            "org.example:foo:1.0", List.of("org.example:foo@1.0-root.io.5"), rootDir, 24,
            () -> { callCount.incrementAndGet(); return "patched-with-ignore"; });

        assertEquals("patched-no-ignore", result1);
        assertEquals("patched-with-ignore", result2);
        assertEquals(2, callCount.get(), "Expected two cache misses for different ignore lists");
    }

    @Test
    void sameIgnoreListHitsCacheOnSecondCall(@TempDir File rootDir) {
        AtomicInteger callCount = new AtomicInteger(0);
        List<String> ignoreEntries = List.of("org.example:foo@1.0-root.io.5");

        DepCache.lookup("org.example:foo:1.0", ignoreEntries, rootDir, 24,
            () -> { callCount.incrementAndGet(); return "patched"; });
        DepCache.lookup("org.example:foo:1.0", ignoreEntries, rootDir, 24,
            () -> { callCount.incrementAndGet(); return "patched"; });

        assertEquals(1, callCount.get(), "Expected cache hit on second call with same ignore list");
    }
}
