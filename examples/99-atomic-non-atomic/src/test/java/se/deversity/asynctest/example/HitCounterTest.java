package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.HitCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for HitCounter.
 *
 * ========================================================================
 * DETECTOR: AtomicNonAtomicUpdateDetector
 * ========================================================================
 *
 * THE BUG:
 * HitCounter.increment() uses counter.set(counter.get() + 1). Even though get()
 * and set() are individually atomic, the compound sequence is not. Under concurrency,
 * two threads can both read the same value and both write value+1, silently losing
 * one of the increments.
 *
 * WHY @Test PASSES:
 * In single-threaded tests, no other thread can interleave between get() and set(),
 * so the final count is always correct. The race window simply does not exist.
 *
 * WHY @AsyncTest DETECTS:
 * With 8 threads all calling increment() concurrently, AtomicNonAtomicUpdateDetector
 * records each get() and set() call per thread. When the same thread did a get()
 * followed by a set() without an intervening CAS, the detector flags it as a
 * non-atomic compound update.
 *
 * FIX:
 * Replace counter.set(counter.get() + 1) with counter.incrementAndGet().
 */
class HitCounterTest {

    private HitCounter counter;

    @BeforeEach
    void setUp() {
        counter = new HitCounter();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes but gives false confidence
    // -------------------------------------------------------------------------

    @Test
    void testIncrement_singleThread_correctCount() {
        counter.increment();
        counter.increment();
        counter.increment();
        assertEquals(3, counter.getCount());
    }

    @Test
    void testReset_setsCountToZero() {
        counter.increment();
        counter.reset();
        assertEquals(0, counter.getCount());
    }

    @Test
    void testGetCount_initial_isZero() {
        assertEquals(0, counter.getCount());
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the non-atomic compound update
    // -------------------------------------------------------------------------

    /**
     * Eight threads all call increment() concurrently. The detector tracks
     * each get()/set() pair per thread and flags the non-atomic compound
     * read-modify-write sequence.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test
     * 3. To fix: replace counter.set(counter.get() + 1) with counter.incrementAndGet()
     */
    @Disabled("Remove @Disabled to see the bug detected by AtomicNonAtomicUpdateDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectAtomicNonAtomicUpdates = true)
    void test_concurrent_detectsNonAtomicUpdate() {
        var mon = AsyncTestContext.atomicNonAtomicUpdateMonitor();
        var raw = counter.getCounter();
        Thread thread = Thread.currentThread();

        // Instrument: record the get() that starts the compound operation
        mon.recordGet(raw, "hit-counter", thread);

        // Perform the buggy increment
        counter.increment();

        // Instrument: record the set() that ends the compound operation
        mon.recordSet(raw, "hit-counter", thread);
    }
}
