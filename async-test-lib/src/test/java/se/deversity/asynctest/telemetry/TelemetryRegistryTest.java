package se.deversity.asynctest.telemetry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryRegistryTest {

    @BeforeEach
    @AfterEach
    void cleanup() {
        TelemetryRegistry.stop();
    }

    @Test
    void testStartAndStopLifecycle() throws Exception {
        TelemetryRegistry.start();
        
        // Use reflection to check if the RUNNING flag is true.
        Field runningField = TelemetryRegistry.class.getDeclaredField("RUNNING");
        runningField.setAccessible(true);
        AtomicBoolean running = (AtomicBoolean) runningField.get(null);
        assertTrue(running.get());

        // Verify shutdownHook is registered.
        Field hookField = TelemetryRegistry.class.getDeclaredField("shutdownHook");
        hookField.setAccessible(true);
        Thread hook = (Thread) hookField.get(null);
        assertNotNull(hook);

        TelemetryRegistry.stop();
        assertFalse(running.get());
        assertNull(hookField.get(null));
    }

    @Test
    void testCallbackExecution() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        List<String> events = Collections.synchronizedList(new ArrayList<>());

        TelemetryRegistry.start((tid, targetField, isWrite) -> {
            events.add(targetField + ":" + isWrite);
            latch.countDown();
        });

        TelemetryRegistry.recordAccess(Thread.currentThread().threadId(), "MyService", "setCount");

        // The background drainer flushes every 1 ms. The timeout is generous so the test
        // tolerates the daemon drain thread being starved on a loaded CI runner (the suite
        // runs many heavily-threaded forks in parallel).
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Telemetry event was not drained/delivered");
        assertEquals(List.of("MyService#setCount:true"), events);
    }

    @Test
    void testUpdatableCallback() throws Exception {
        // First start with no-op default callback.
        TelemetryRegistry.start();

        CountDownLatch latch = new CountDownLatch(1);
        List<String> events = Collections.synchronizedList(new ArrayList<>());

        // Second start: update callback to our real mock.
        TelemetryRegistry.start((tid, targetField, isWrite) -> {
            events.add(targetField);
            latch.countDown();
        });

        // A drain cycle that started before the callback swap can consume the first
        // recorded event with the previous (no-op default) callback, so a single record
        // can be silently discarded. Re-record until the new callback observes an event;
        // once the swap is fully visible every drain uses the new callback. This makes the
        // test deterministic without depending on the swap/drain interleaving or CI timing.
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean delivered = false;
        while (System.nanoTime() < deadlineNanos) {
            TelemetryRegistry.recordAccess(Thread.currentThread().threadId(), "SecondService", "getUser");
            if (latch.await(100, TimeUnit.MILLISECONDS)) {
                delivered = true;
                break;
            }
        }

        assertTrue(delivered, "Updated telemetry callback was not invoked");
        // May be delivered more than once if an early record survived the swap; the
        // contract under test is that the new callback receives the recorded access.
        assertTrue(events.contains("SecondService#getUser"),
                "updated callback should receive the recorded access; got " + events);
    }
}
