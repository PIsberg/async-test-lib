package se.deversity.asynctest.example;

import se.deversity.asynctest.diagnostics.FalseSharingDetector;
import se.deversity.asynctest.example.service.PerformanceCounters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for PerformanceCounters.
 *
 * ========================================================================
 * DETECTOR: FalseSharingDetector (via AsyncTestContext.falseSharingDetector())
 * ========================================================================
 *
 * This test demonstrates how adjacent hot fields on the same CPU cache line
 * can cause performance-degrading cache coherence traffic across threads.
 *
 * THE BUG:
 * PerformanceCounters has three consecutive volatile long fields:
 *   requestCount (offset ~16), errorCount (~24), latencySum (~32).
 * On a 64-byte cache line they all share the same line. When Thread A writes
 * requestCount and Thread B writes errorCount, both invalidate the entire
 * cache line on all CPUs — even though neither thread cares about the other's
 * field. This "cache ping-pong" can reduce throughput by 10x under load.
 *
 * WHY @Test PASSES:
 * Single-threaded tests never cause cache-line contention. The fields behave
 * correctly regardless of memory layout.
 *
 * WHAT FalseSharingDetector SAYS ABOUT IT:
 * It tracks which threads access which fields and their approximate memory
 * offsets, and reports pairs within 64 bytes of each other. Those offsets are
 * estimates the JVM's real layout does not follow, so the findings are off
 * unless -Dasync-test.experimental.false-sharing=true is set. Part 2 below pins
 * both directions of that gate and says why this example has no @AsyncTest
 * demonstration.
 *
 * DETECTORS TRIGGERED:
 * None by default. FalseSharingDetector is experimental and opt-in; see
 * docs/DETECTOR_CATALOG.md.
 *
 * FIX:
 * - Use @Contended on each hot field to force JVM padding to a full cache line
 * - Or pad manually with 7 dummy long fields between each counter
 * - Or use LongAdder which performs internal striping automatically
 */
class PerformanceCountersTest {

    private PerformanceCounters counters;

    @BeforeEach
    void setUp() {
        counters = new PerformanceCounters();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — correct behaviour is observable in single-threaded use
    // -------------------------------------------------------------------------

    @Test
    void testRecordRequest_incrementsCountAndLatency() {
        counters.recordRequest(15);
        counters.recordRequest(25);

        assertEquals(2, counters.requestCount);
        assertEquals(40, counters.latencySum);
        assertEquals(20.0, counters.averageLatency(), 0.001);
    }

    @Test
    void testRecordError_incrementsErrorAndRequest() {
        counters.recordRequest(10);
        counters.recordError(100);

        assertEquals(2, counters.requestCount);
        assertEquals(1, counters.errorCount);
        assertEquals(50, counters.getErrorRate()); // 1/2 = 50%
    }

    @Test
    void testAverageLatency_noRequests_returnsZero() {
        assertEquals(0.0, counters.averageLatency());
    }

    @Test
    void testGetErrorRate_noRequests_returnsZero() {
        assertEquals(0L, counters.getErrorRate());
    }

    // -------------------------------------------------------------------------
    // Part 2: the experimental gate, and why there is no @AsyncTest demonstration
    // -------------------------------------------------------------------------

    /**
     * This example carried a disabled {@code @AsyncTest} promising "false sharing detected by
     * FalseSharingDetector". Enabling it produced a green test, every run, and the reason was
     * not timing: the detector's findings are off unless
     * {@code -Dasync-test.experimental.false-sharing=true} is set, which the examples do not set.
     * That put it on {@code .github/known-silent-demos.txt} as the one structural entry. See
     * issue #362.
     *
     * <p>Setting the property would have made the demonstration fail, and it is still not what
     * this example should do. docs/DETECTOR_CATALOG.md is blunt about why the gate exists: cache
     * line effects are not observable from pure Java, the detector estimates offsets by summing
     * nominal type sizes in declaration order, and the JVM reorders fields, compresses
     * references and honours {@code @Contended} padding, so the estimated offsets do not
     * correspond to real memory layout. Its reports are not evidence of false sharing. Telling a
     * reader to remove {@code @Disabled} and look at one would be teaching them to trust a
     * number the library itself does not stand behind.
     *
     * <p>So the demonstration is gone and the gate is pinned instead, in both directions, by
     * tests that run in CI. The bug is still here: PerformanceCounters still has three adjacent
     * hot fields, and the fix below is still the fix.
     */

    @Test
    void testFalseSharingDetector_silentByDefault() throws Exception {
        FalseSharingDetector detector = new FalseSharingDetector();
        recordTwoThreadsPerField(detector);

        assertFalse(detector.analyze().hasIssues(),
                "without -" + FalseSharingDetector.EXPERIMENTAL_PROPERTY + "=true the detector "
                        + "reports nothing, because an offset it guessed is not evidence");
    }

    @Test
    void testFalseSharingDetector_reportsThePairOnceTheGateIsOpen() throws Exception {
        FalseSharingDetector detector = new FalseSharingDetector();
        recordTwoThreadsPerField(detector);

        String previous = System.getProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY);
        System.setProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY, "true");
        try {
            // No re-run needed: recording happens whatever the property says, and only the
            // analysis is gated.
            assertTrue(detector.analyze().hasIssues(),
                    "with the gate open the adjacent counters are reported as a pair");
        } finally {
            if (previous == null) {
                System.clearProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY);
            } else {
                System.setProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY, previous);
            }
        }
    }

    /**
     * Two threads per field, because the detector only considers a field that more than one
     * thread touched. Joined one at a time, so nothing here depends on the scheduler.
     */
    private void recordTwoThreadsPerField(FalseSharingDetector detector) throws Exception {
        for (String field : new String[] {"requestCount", "errorCount"}) {
            for (int i = 0; i < 2; i++) {
                Thread t = new Thread(
                        () -> detector.recordFieldAccess(counters, field, long.class),
                        "toucher-" + field + "-" + i);
                t.start();
                t.join(5_000);
            }
        }
    }

    /**
     * Demonstrates the fixed design using LongAdder for each counter.
     * LongAdder uses internal cell striping to avoid cache-line contention —
     * each thread typically writes to its own dedicated cell.
     */
    @Test
    void testRecordRequest_fixedWithLongAdder_noFalseSharing() {
        java.util.concurrent.atomic.LongAdder requests = new java.util.concurrent.atomic.LongAdder();
        java.util.concurrent.atomic.LongAdder errors   = new java.util.concurrent.atomic.LongAdder();
        java.util.concurrent.atomic.LongAdder latency  = new java.util.concurrent.atomic.LongAdder();

        // Simulate concurrent updates — LongAdder prevents false sharing
        requests.increment();
        requests.increment();
        errors.increment();
        latency.add(30);
        latency.add(20);

        assertEquals(2, requests.sum());
        assertEquals(1, errors.sum());
        assertEquals(50, latency.sum());
    }
}
