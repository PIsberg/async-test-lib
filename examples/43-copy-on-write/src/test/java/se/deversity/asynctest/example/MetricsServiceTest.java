package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.MetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for MetricsService.
 *
 * ========================================================================
 * DETECTOR: CopyOnWriteCollectionDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - A sequential @Test PASSES (but gives false confidence)
 * - The same test with @AsyncTest FAILS (exposing the real concurrent bug)
 *
 * THE BUG:
 * MetricsService uses CopyOnWriteArrayList as a write-heavy event store.
 * Every add() allocates a new backing array and copies all existing elements
 * into it — O(n) per write. CopyOnWriteArrayList is designed for read-heavy
 * workloads where writes are rare. Using it on the hot write path causes
 * CPU spikes, excessive allocation, and GC pauses under concurrent load.
 *
 * WHY @Test PASSES:
 * Sequential writes keep the list small. A handful of copies of a tiny array
 * is not measurably slow, and correctness is unaffected.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * 8 threads each call recordEvent() 50 times. The list grows to hundreds of
 * entries; each write copies hundreds of longs. CopyOnWriteCollectionDetector
 * tracks read and write counts on registered COW collections and reports when
 * the write-to-read ratio indicates write-heavy misuse.
 *
 * DETECTORS TRIGGERED:
 *   CopyOnWriteCollectionDetector — primary: write-heavy COW collection
 *
 * FIX: replace CopyOnWriteArrayList with ConcurrentLinkedQueue or LongAdder.
 */
class MetricsServiceTest {

    private MetricsService service;

    @BeforeEach
    void setUp() {
        service = new MetricsService();
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, passes cleanly
    // -----------------------------------------------------------------------

    @Test
    void testRecordEvent_singleThread_incrementsCount() {
        service.recordEvent();
        service.recordEvent();
        assertEquals(2, service.getEventCount());
    }

    @Test
    void testEventCount_startsAtZero() {
        assertEquals(0, service.getEventCount());
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes write-heavy COW misuse
    // -----------------------------------------------------------------------

    @Disabled("Remove @Disabled to see write-heavy CopyOnWriteArrayList detected by CopyOnWriteCollectionDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectCopyOnWriteCollectionIssues = true, failOn = FailOn.LOW)
    void testRecordEvent_concurrent_detectsWriteHeavy() {
        var timestamps = service.getTimestamps();

        // Register the COW collection with the detector
        AsyncTestContext.get().copyOnWriteMonitor()
                .registerCollection(timestamps, "metrics-timestamps");

        // Record a write (the hot path — every thread writes every invocation)
        AsyncTestContext.get().copyOnWriteMonitor()
                .recordWrite(timestamps, "metrics-timestamps");
        service.recordEvent();

        // Occasional read to give the detector a write-to-read ratio
        if (Thread.currentThread().getId() % 8 == 0) {
            AsyncTestContext.get().copyOnWriteMonitor()
                    .recordRead(timestamps, "metrics-timestamps");
            assertTrue(service.getEventCount() > 0,
                    "At least one event should have been recorded");
        }
    }
}
