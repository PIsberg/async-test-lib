package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.EventAggregatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for EventAggregatorService.
 *
 * ========================================================================
 * DETECTOR: SharedCollectionDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - A sequential @Test PASSES (but gives false confidence)
 * - The same test with @AsyncTest FAILS (exposing the real concurrent bug)
 *
 * THE BUG:
 * EventAggregatorService uses ArrayList and HashMap to collect events from multiple
 * threads. Neither collection is thread-safe. Under concurrent load:
 *   - ArrayList can lose entries or throw ArrayIndexOutOfBoundsException internally
 *   - HashMap can corrupt its internal table during concurrent resize operations
 *   - getTotalEventCount() can return a value inconsistent with the events stored
 *
 * WHY @Test PASSES:
 * A single thread calls recordEvent() sequentially. ArrayList and HashMap work
 * perfectly for single-threaded access. The test always sees the expected count.
 *
 * WHY @AsyncTest FAILS:
 * With 8 concurrent threads each calling recordEvent() 100 times, we expect 800
 * total events. Because ArrayList is not thread-safe, the actual count is less
 * (entries get dropped during concurrent array copies/resizes).
 * SharedCollectionDetector flags the unsafe concurrent writes to these collections.
 *
 * DETECTOR ENABLED HERE:
 * SharedCollectionDetector — writes from multiple threads to non-thread-safe collections.
 * It is the only one the demonstration switches on, so it is the only one that can report.
 */
class EventAggregatorServiceTest {

    private EventAggregatorService service;

    @BeforeEach
    void setUp() {
        service = new EventAggregatorService();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes, but gives false confidence
    // -------------------------------------------------------------------------

    @Test
    void testRecordEvent_singleThread_allEventsRecorded() {
        // Single-threaded: works fine with ArrayList and HashMap
        for (int i = 0; i < 10; i++) {
            service.recordEvent("source-A", "event-" + i);
        }

        assertEquals(10, service.getTotalEventCount(),
                "Single-thread: all 10 events should be recorded");
        assertEquals(10, service.getEventCounts().get("source-A"),
                "Event count for source-A should be 10");
    }

    @Test
    void testGetEvents_singleThread_containsAllEntries() {
        service.recordEvent("source-A", "click");
        service.recordEvent("source-B", "view");
        service.recordEvent("source-A", "purchase");

        List<String> events = service.getEvents();
        assertEquals(3, events.size());
        assertTrue(events.stream().anyMatch(e -> e.contains("source-A: click")));
        assertTrue(events.stream().anyMatch(e -> e.contains("source-B: view")));
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the concurrency bug
    // -------------------------------------------------------------------------

    /**
     * The bug: 8 concurrent threads each add 100 events to the same ArrayList and merge into
     * the same HashMap. SharedCollectionDetector reports both, naming the number of threads that
     * wrote each one in a single round.
     *
     * To see the failure:
     * 1. Remove @Disabled
     * 2. Run this test — it fails with a SharedCollectionDetector finding for event-log and
     *    event-counts, each written by 8 threads
     * 3. To fix: replace ArrayList with CopyOnWriteArrayList or Collections.synchronizedList
     *            replace HashMap with ConcurrentHashMap
     */
    @Disabled("Remove @Disabled to see the bug detected by SharedCollectionDetector")
    @AsyncTest(threads = 8, invocations = 100, detectSharedCollections = true, failOn = FailOn.LOW)

    void testRecordEvent_concurrent_detectsSharedCollectionUse() {
        // The recording has to name the collection the threads actually mutate. Recording
        // service.getEvents() instead handed the detector a fresh defensive copy per call, so it
        // saw 800 collections with one writer each rather than one collection with eight, and it
        // reported nothing however long the test ran. See issue #346.
        service.observeCollectionWrites((collection, operation) ->
                AsyncTestContext.sharedCollectionMonitor()
                        .recordWrite(collection, collection instanceof java.util.Map
                                ? "event-counts" : "event-log", operation));

        String source = "source-" + Thread.currentThread().threadId() % 4;

        // An unsynchronized HashMap.merge can lose its race with itself and throw
        // ConcurrentModificationException out of its own modCount check, which happened in one
        // reactor run of three. That is the bug, not a test failure, and letting it escape the
        // body fails the run before the failOn gate reports SharedCollectionDetector's finding.
        // Absorbed here; the writes have already been recorded by the seam above. See #363.
        try {
            service.recordEvent(source, "event");
        } catch (RuntimeException corrupted) {
            // ArrayList and HashMap tearing under concurrent structural modification.
        }
    }

    /**
     * Fixed version using thread-safe alternatives.
     * Demonstrates the correct implementation that passes @AsyncTest.
     */
    @Test
    void testRecordEvent_fixedWithConcurrentCollections_singleThread() {
        // For demonstration: the fix uses ConcurrentHashMap and CopyOnWriteArrayList
        // (or synchronizedList) — tested here in single-threaded mode only
        java.util.concurrent.ConcurrentHashMap<String, Integer> safeMap = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.CopyOnWriteArrayList<String> safeList = new java.util.concurrent.CopyOnWriteArrayList<>();

        safeList.add("source-A: event-1");
        safeList.add("source-A: event-2");
        safeMap.merge("source-A", 1, Integer::sum);
        safeMap.merge("source-A", 1, Integer::sum);

        assertEquals(2, safeList.size());
        assertEquals(2, safeMap.get("source-A"));
    }
}
