package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.WeakCacheEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for WeakCacheEntry.
 *
 * ========================================================================
 * DETECTOR: WeakReferenceRaceDetector
 * ========================================================================
 *
 * THE BUG:
 * WeakCacheEntry.process() calls ref.get() twice without assigning the result
 * to a local variable. Between the null-check (first call) and the use (second
 * call), the GC can collect the weakly-reachable object. The second get() returns
 * null, causing a NullPointerException that manifests non-deterministically under
 * GC pressure.
 *
 * WHY @Test PASSES:
 * In single-threaded tests the GC rarely runs between two consecutive statements,
 * and the referent is strongly reachable from local scope anyway. The race window
 * effectively does not exist.
 *
 * WHY @AsyncTest DETECTS:
 * Under concurrency WeakReferenceRaceDetector tracks both get() calls per thread.
 * When some threads see null (referent collected) while others saw non-null on the
 * same reference, the detector flags the unsafe double-get pattern.
 *
 * FIX:
 * Assign ref.get() to a local variable once: T val = ref.get(); if (val != null) { val.doWork(); }
 */
class WeakCacheEntryTest {

    private static class Counter implements WeakCacheEntry.Workable {
        final AtomicInteger count = new AtomicInteger();
        @Override public void doWork() { count.incrementAndGet(); }
    }

    private Counter payload;
    private WeakCacheEntry<Counter> entry;

    @BeforeEach
    void setUp() {
        payload = new Counter();
        entry = new WeakCacheEntry<>(payload, "counter-cache");
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes but gives false confidence
    // -------------------------------------------------------------------------

    @Test
    void testProcess_withLiveRef_invokesWork() {
        entry.process();
        assertEquals(1, payload.count.get());
    }

    @Test
    void testGetRef_returnsNonNull() {
        assertNotNull(entry.getRef());
    }

    @Test
    void testGetName_returnsLabel() {
        assertEquals("counter-cache", entry.getName());
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the weak reference race
    // -------------------------------------------------------------------------

    /**
     * Multiple threads call process() concurrently. WeakReferenceRaceDetector
     * records each get() call and the result. If some threads see null (collected)
     * while others see non-null, the detector reports the double-get TOCTOU race.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test
     * 3. To fix: assign ref.get() to a local variable inside process()
     */
    @Disabled("Remove @Disabled to see the bug detected by WeakReferenceRaceDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectWeakReferenceRace = true)
    void test_concurrent_detectsWeakReferenceRace() {
        WeakReference<Counter> ref = entry.getRef();
        Object result = ref.get();

        // Instrument: record the get() result (may be null if GC collected)
        AsyncTestContext.weakReferenceRaceDetector()
                .recordGet(ref, "counter-cache", result, Thread.currentThread());

        if (result != null) {
            // Call process() — internally does the unsafe double-get
            entry.process();
        } else {
            // Referent was collected — record the null dereference risk
            AsyncTestContext.weakReferenceRaceDetector()
                    .recordNullDereference(ref, "counter-cache", Thread.currentThread());
        }
    }
}
