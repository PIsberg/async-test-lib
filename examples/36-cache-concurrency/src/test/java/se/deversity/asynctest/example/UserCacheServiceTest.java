package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.UserCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for UserCacheService.
 *
 * ========================================================================
 * DETECTOR: CacheConcurrencyDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - A sequential @Test PASSES (but gives false confidence)
 * - The same test with @AsyncTest FAILS (exposing the real concurrent bug)
 *
 * THE BUG:
 * UserCacheService wraps a plain HashMap. HashMap is not thread-safe.
 * Concurrent put() calls during an internal resize can corrupt the backing
 * array and cause lost updates or ConcurrentModificationException.
 * Concurrent get() during a structural modification may return null even
 * for keys that were successfully written by another thread.
 *
 * WHY @Test PASSES:
 * All put/get operations are sequential. HashMap behaves correctly when
 * accessed by a single thread at a time. No structural modifications overlap.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * 8 threads concurrently put() and get() distinct keys. CacheConcurrencyDetector
 * tracks every unsynchronized concurrent read-write pair on the same map
 * instance and reports the violation.
 *
 * DETECTORS TRIGGERED:
 *   CacheConcurrencyDetector — primary: detects concurrent HashMap access
 *
 * FIX: replace HashMap with ConcurrentHashMap.
 */
class UserCacheServiceTest {

    private UserCacheService service;

    @BeforeEach
    void setUp() {
        service = new UserCacheService();
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, passes cleanly
    // -----------------------------------------------------------------------

    @Test
    void testPutAndGet_singleThread_works() {
        service.put("user-1", "Alice");
        assertEquals("Alice", service.get("user-1"));
    }

    @Test
    void testMissingKey_returnsNull() {
        assertNull(service.get("does-not-exist"));
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes unsynchronized HashMap access
    // -----------------------------------------------------------------------

    @Disabled("Remove @Disabled to see HashMap cache race detected by CacheConcurrencyDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectCacheConcurrency = true)
    void testCache_concurrent_detectsRace() {
        var cache = service.getCache();
        String key = "user-" + Thread.currentThread().getId();
        String value = "UserValue-" + key;

        // Register the cache once; detector deduplicates by map identity
        AsyncTestContext.get().cacheConcurrencyDetector()
                .registerCache(cache, "user-cache");

        // Write to the cache
        AsyncTestContext.get().cacheConcurrencyDetector()
                .recordPut(cache, "user-cache", key, value);
        service.put(key, value);

        // Read from the cache — may race with another thread's structural modification
        AsyncTestContext.get().cacheConcurrencyDetector()
                .recordGet(cache, "user-cache", key);
        String result = service.get(key);

        // Under sequential execution this assertion always holds;
        // under concurrency the map may be corrupted and get() returns null
        assertNotNull(result, "Cache should return the value we just stored");
    }
}
