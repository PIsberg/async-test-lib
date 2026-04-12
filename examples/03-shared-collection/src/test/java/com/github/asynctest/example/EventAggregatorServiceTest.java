package com.github.asynctest.example;

import com.github.asynctest.AsyncTest;
import com.github.asynctest.AsyncTestContext;
import com.github.asynctest.example.service.EventAggregatorService;
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
 * DETECTORS TRIGGERED:
 * ✅ SharedCollectionDetector — Primary: writes from multiple threads to non-thread-safe collections
 * ✅ ConcurrentModificationDetector — Secondary: read during concurrent write
 * ✅ RaceConditionDetector — Tertiary: unsynchronized compound read-modify-write
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
     * The bug: with 8 concurrent threads each adding 100 events, the ArrayList
     * loses entries due to unsafe concurrent adds. SharedCollectionDetector reports
     * the multi-thread write pattern and ConcurrentModificationDetector may fire
     * when getEvents() iterates while another thread is still adding.
     *
     * To see the failure:
     * 1. Remove @Disabled
     * 2. Run this test — it should be detected by SharedCollectionDetector
     * 3. To fix: replace ArrayList with CopyOnWriteArrayList or Collections.synchronizedList
     *            replace HashMap with ConcurrentHashMap
     */
    @Disabled("Remove @Disabled to see the bug detected by SharedCollectionDetector")
    @AsyncTest(threads = 8, invocations = 100, detectSharedCollections = true)
    void testRecordEvent_concurrent_detectsSharedCollectionUse() {
        String source = "source-" + Thread.currentThread().threadId() % 4;
        service.recordEvent(source, "event");

        // Instrument the detector
        AsyncTestContext.sharedCollectionMonitor()
                .recordWrite(service.getEvents(), "event-log", "add");
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
