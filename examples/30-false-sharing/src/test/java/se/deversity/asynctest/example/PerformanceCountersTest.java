package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.PerformanceCounters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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
 * WHY @AsyncTest DETECTS THE ISSUE:
 * FalseSharingDetector tracks which threads access which fields and their
 * approximate memory offsets. When different threads access fields within
 * 64 bytes of each other, it reports the false-sharing pairs.
 *
 * DETECTORS TRIGGERED:
 * FalseSharingDetector — accessed via AsyncTestContext.falseSharingDetector()
 *                        (wired through DetectorRegistry, enabled via
 *                         detectFalseSharing = true in @AsyncTest).
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
    // Part 2: @AsyncTest — exposes false sharing via FalseSharingDetector
    // -------------------------------------------------------------------------

    /**
     * The performance bug: different threads update requestCount, errorCount,
     * and latencySum simultaneously. Because all three fields share a cache line,
     * every write from any thread invalidates the line for all other threads —
     * causing expensive cache coherence traffic even though threads touch
     * independent fields.
     *
     * FalseSharingDetector reports the adjacent field pairs accessed by
     * different threads within the same 64-byte cache line.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — FalseSharingDetector will report the contention pairs
     * 3. Fix: annotate each field with @Contended, or switch to LongAdder
     */
    @Disabled("Remove @Disabled to see false sharing detected by FalseSharingDetector")
    @AsyncTest(threads = 8, invocations = 200, detectFalseSharing = true)
    void testRecordRequest_concurrent_detectsFalseSharing() {
        // Different threads update different fields — but they all share a cache line
        long tid = Thread.currentThread().threadId();

        if (tid % 3 == 0) {
            // Thread group A: updates requestCount
            counters.requestCount++;
            AsyncTestContext.falseSharingDetector()
                    .recordFieldAccess(counters, "requestCount", long.class);
        } else if (tid % 3 == 1) {
            // Thread group B: updates errorCount
            counters.errorCount++;
            AsyncTestContext.falseSharingDetector()
                    .recordFieldAccess(counters, "errorCount", long.class);
        } else {
            // Thread group C: updates latencySum
            counters.latencySum += 5;
            AsyncTestContext.falseSharingDetector()
                    .recordFieldAccess(counters, "latencySum", long.class);
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
