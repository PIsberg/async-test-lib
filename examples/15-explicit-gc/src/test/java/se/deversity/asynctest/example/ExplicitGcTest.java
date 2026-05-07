package se.deversity.asynctest.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates the ExplicitGcDetector (Phase 12).
 *
 * ============================================================
 * NOTE: ExplicitGcDetector ships in async-test-lib 0.10.0.
 * This example targets 0.9.0 so it compiles from Maven Central.
 * ============================================================
 *
 * THE BUG: A cache manager calls System.gc() to hint memory reclamation
 * after eviction. In a concurrent stress test this triggers a full
 * stop-the-world pause, inflating operation latency unpredictably and
 * potentially causing artificial timeouts that mask the real behaviour.
 *
 * WHY @Test PASSES: Single-threaded execution and no latency assertions
 * mean the GC hint has no visible effect on the test result.
 *
 * WHY @AsyncTest DETECTS THE BUG (0.10.0): The detector records any
 * System.gc() call and reports it as a hygiene violation.
 */
class ExplicitGcTest {

    static class CacheManager {
        private final java.util.Map<String, Object> cache = new java.util.concurrent.ConcurrentHashMap<>();

        void evictAll() {
            cache.clear();
            System.gc(); // BUG: hints a full GC — never appropriate in concurrent code
        }

        void evictAllFixed() {
            cache.clear();
            // Fixed: just clear; let the JVM decide when to GC
        }

        void put(String key, Object value) { cache.put(key, value); }
        Object get(String key) { return cache.get(key); }
    }

    // =========================================================================
    // Part 1: @Test — passes, gives false confidence
    // =========================================================================

    @Test
    void part1_evictCache_singleThread() {
        CacheManager cache = new CacheManager();
        cache.put("user:1", "Alice");
        cache.evictAll(); // calls System.gc() but @Test doesn't care
        assertNull(cache.get("user:1"));
    }

    // =========================================================================
    // Part 2: Upgrade to @AsyncTest (0.10.0) to detect the bug
    //
    // @AsyncTest(threads = 4, invocations = 3, detectExplicitGc = true, timeoutMs = 5000)
    // =========================================================================

    @Test
    void part2_detectExplicitGc_placeholder() {
        // After upgrading to 0.10.0, replace with:
        //
        //   var d = AsyncTestContext.explicitGcDetector();
        //   d.recordGcInvocation(Thread.currentThread(), "CacheManager.evictAll");
        //   System.gc(); // triggers detection
        //
        // The detector will report "Explicit GC requested by thread '...' at
        // [CacheManager.evictAll] — System.gc() causes unpredictable STW pauses."
        assertTrue(true, "Placeholder — see comments above");
    }

    // =========================================================================
    // Part 3: Fixed — no explicit GC
    // =========================================================================

    @Test
    void part3_fixed_noExplicitGc() {
        CacheManager cache = new CacheManager();
        cache.put("user:1", "Alice");
        cache.evictAllFixed(); // no System.gc()
        assertNull(cache.get("user:1"));
    }
}
