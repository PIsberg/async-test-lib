package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.WeakCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for WeakCacheService.
 *
 * ========================================================================
 * DETECTOR: WeakHashMapSharedDetector
 * ========================================================================
 *
 * THE BUG:
 * WeakCacheService stores data in a WeakHashMap that is shared across all threads
 * with no synchronization. WeakHashMap is not thread-safe: concurrent puts can
 * corrupt the internal bucket array, and GC-driven entry expiry runs at any time
 * alongside active mutation, causing data loss or ConcurrentModificationException.
 *
 * WHY @Test PASSES:
 * Single-threaded tests always complete their put/get sequences before any other
 * thread can interfere. WeakHashMap works fine in isolation.
 *
 * WHY @AsyncTest DETECTS:
 * With 8 threads sharing the same WeakCacheService instance, WeakHashMapSharedDetector
 * tracks which threads access the map and reports the multi-thread access pattern.
 *
 * FIX:
 * Use Collections.synchronizedMap(new WeakHashMap<>()) for simple cases, or replace
 * the WeakHashMap with a proper concurrent cache implementation.
 */
class WeakCacheServiceTest {

    private WeakCacheService service;

    @BeforeEach
    void setUp() {
        service = new WeakCacheService();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes but gives false confidence
    // -------------------------------------------------------------------------

    @Test
    void testPutAndGet_singleThread_works() {
        Object key = new Object();
        service.put(key, "value-1");
        assertEquals("value-1", service.get(key));
    }

    @Test
    void testSize_afterPut_incrementsCount() {
        Object key = new Object();
        service.put(key, "data");
        assertEquals(1, service.size());
    }

    @Test
    void testGet_missingKey_returnsNull() {
        assertNull(service.get(new Object()));
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the shared WeakHashMap bug
    // -------------------------------------------------------------------------

    /**
     * Eight threads concurrently put and get from the same WeakCacheService.
     * WeakHashMapSharedDetector records all accesses and reports that the
     * same WeakHashMap instance is used from multiple threads unsafely.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test
     * 3. To fix: wrap cache in Collections.synchronizedMap()
     */
    @Disabled("Remove @Disabled to see the bug detected by WeakHashMapSharedDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectWeakHashMapShared = true)
    void test_concurrent_detectsSharedWeakHashMap() {
        Object key = new Object();
        Thread thread = Thread.currentThread();

        // Instrument: record that this thread accesses the shared WeakHashMap
        AsyncTestContext.weakHashMapSharedDetector()
                .recordAccess(service.getCache(), "weak-cache", thread);

        service.put(key, "value-" + thread.getName());
        service.get(key);
    }
}
