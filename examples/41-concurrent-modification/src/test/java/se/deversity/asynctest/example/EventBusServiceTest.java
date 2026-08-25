package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.EventBusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for EventBusService.
 *
 * ========================================================================
 * DETECTOR: ConcurrentModificationDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - A sequential @Test PASSES (but gives false confidence)
 * - The same test with @AsyncTest FAILS (exposing the real concurrent bug)
 *
 * THE BUG:
 * EventBusService stores listeners in a plain ArrayList. fireEvent() iterates
 * the list while register() adds to it. The ArrayList iterator is fail-fast:
 * it checks modCount on every next() call and throws
 * ConcurrentModificationException when another thread has modified the list
 * since the iterator was created.
 *
 * WHY @Test PASSES:
 * All register() calls complete before fireEvent() is called. The ArrayList
 * is never modified while an iterator is alive. No exception is thrown.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * 8 threads concurrently call register() and fireEvent(). Some threads iterate
 * the list while other threads add to it. ConcurrentModificationDetector
 * tracks iteration start/end and modifications and reports when a modification
 * overlaps with an active iteration on the same collection.
 *
 * DETECTORS TRIGGERED:
 *   ConcurrentModificationDetector — primary: modification during iteration
 *
 * FIX: use CopyOnWriteArrayList, or synchronize both register() and fireEvent().
 */
class EventBusServiceTest {

    private EventBusService service;

    @BeforeEach
    void setUp() {
        service = new EventBusService();
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, passes cleanly
    // -----------------------------------------------------------------------

    @Test
    void testFireEvent_allListenersCalled() {
        AtomicInteger counter = new AtomicInteger(0);
        service.register(counter::incrementAndGet);
        service.register(counter::incrementAndGet);
        service.fireEvent();
        assertEquals(2, counter.get());
    }

    @Test
    void testRegister_incrementsCount() {
        service.register(() -> {});
        assertEquals(1, service.listenerCount());
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes concurrent modification
    // -----------------------------------------------------------------------

    @Disabled("Remove @Disabled to see concurrent-modification detected by ConcurrentModificationDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectConcurrentModifications = true, failOn = FailOn.LOW)
    void testEventBus_concurrent_detectsModification() {
        var listeners = service.getListeners();

        // Half the threads register new listeners; half fire events
        if (Thread.currentThread().getId() % 2 == 0) {
            // Producer: record modification and add a listener
            AsyncTestContext.get().concurrentModificationMonitor()
                    .recordModification(listeners, "event-listeners", "add");
            service.register(() -> {/* listener */});
        } else {
            // Consumer: record iteration start, fire event, record iteration end
            AsyncTestContext.get().concurrentModificationMonitor()
                    .recordIterationStarted(listeners, "event-listeners");
            try {
                service.fireEvent();
            } finally {
                AsyncTestContext.get().concurrentModificationMonitor()
                        .recordIterationEnded(listeners, "event-listeners");
            }
        }
    }
}
