package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.diagnostics.WeakReferenceRaceDetector;
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
 * WeakReferenceRaceDetector reports two things. One is a reference that returned
 * non-null on one thread and null on another, which needs the collector to
 * actually collect. The other is a get() result used without a null check, which
 * needs nothing at all: it is a property of the code.
 *
 * This example used to aim at the first, and the test held a strong reference to
 * the payload in a field, so the referent could never be collected and the report
 * was empty three runs out of three. See issue #346. It now aims at the second,
 * recorded from inside process() at the point where the unchecked use happens,
 * which is deterministic.
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

    /**
     * The bug, made deterministic: the reference is cleared between the two get() calls, which
     * is exactly what the collector is entitled to do, and process() throws.
     */
    @Test
    void testProcess_referentClearedBetweenTheTwoGets_throwsNullPointerException() {
        AtomicInteger gets = new AtomicInteger();
        entry.observeReference(result -> {
            if (gets.incrementAndGet() == 1) {
                entry.getRef().clear();      // stand in for a GC cycle landing right here
            }
        }, () -> { });

        assertThrows(NullPointerException.class, () -> entry.process(),
                "the second get() returned null and nothing checked it");
    }

    /**
     * And the fixed version under the same treatment: one get(), one null check, a strong
     * local reference for the rest of the method. Clearing the WeakReference afterwards
     * changes nothing.
     */
    @Test
    void testProcessFixed_referentClearedAfterTheGet_completes() {
        entry.observeReference(result -> entry.getRef().clear(), () -> { });

        assertDoesNotThrow(() -> entry.processFixed());
        assertEquals(1, payload.count.get(), "the work still happened");
    }

    /**
     * The detector's positive direction: a get() result used without a null check.
     */
    @Test
    void testWeakReferenceRaceDetector_uncheckedUse_reports() {
        WeakReferenceRaceDetector detector = new WeakReferenceRaceDetector();
        wire(detector);

        entry.process();

        assertTrue(detector.analyze().hasIssues(),
                "using a get() result without checking it is the finding");
    }

    /**
     * And the other direction: one get(), checked, used. Nothing to report, and a detector
     * that fired here would fire on every correct use of a WeakReference.
     */
    @Test
    void testWeakReferenceRaceDetector_checkedUse_isSilent() {
        WeakReferenceRaceDetector detector = new WeakReferenceRaceDetector();
        wire(detector);

        entry.processFixed();

        assertFalse(detector.analyze().hasIssues(),
                "get() into a local, null-checked, then used is the correct idiom");
    }

    private void wire(WeakReferenceRaceDetector detector) {
        entry.observeReference(
                result -> detector.recordGet(entry.getRef(), entry.getName(), result,
                        Thread.currentThread()),
                () -> detector.recordNullDereference(entry.getRef(), entry.getName(),
                        Thread.currentThread()));
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
    // invocations is 1 to keep the report readable. The detector joins every recording thread's
    // name into one line with no deduplication, so 400 executions produce a single line listing
    // 400 thread names. That is issue #351; put the number back once it is fixed.
    @AsyncTest(threads = 8, invocations = 1, detectAll = false,
            detectWeakReferenceRace = true, failOn = FailOn.LOW)
    void test_concurrent_detectsWeakReferenceRace() {
        // The detector's two findings are a get() result used without a null check, and a
        // reference that returned non-null on one thread and null on another. This
        // demonstration used to aim at the second, which needs the collector to actually
        // collect - and the test held a strong reference to the payload in a field, so it never
        // could. Empty report, three runs out of three. See issue #346.
        //
        // The first finding needs no collector at all: it is a property of the code, and
        // process() has it. Recording it from inside process(), where the unchecked use is,
        // makes the demonstration deterministic.
        WeakReferenceRaceDetector detector = AsyncTestContext.weakReferenceRaceDetector();
        entry.observeReference(
                result -> detector.recordGet(entry.getRef(), entry.getName(), result,
                        Thread.currentThread()),
                () -> detector.recordNullDereference(entry.getRef(), entry.getName(),
                        Thread.currentThread()));

        entry.process();   // BUG: two gets, the second used unchecked
    }
}
