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
    void recordAccessAfterStopIsDiscardedInsteadOfBuffered() {
        TelemetryRegistry.start();
        TelemetryRegistry.stop();

        // After stop() there is no drain thread left, ever. Buffering here just fills the
        // ring until it is full, at which point every instrumented thread in the JVM used
        // to spin forever inside publish() — including application threads running woven
        // getters during JVM shutdown, hanging the shutdown itself.
        long publishedBefore = TelemetryRegistry.buffer().publishedCount();
        TelemetryRegistry.recordAccess(Thread.currentThread().threadId(), "StoppedService", "setValue");
        assertEquals(publishedBefore, TelemetryRegistry.buffer().publishedCount(),
                "recordAccess after stop() must discard the event, not buffer it for a consumer "
                        + "that no longer exists");
    }

    @Test
    void periodicDrainSurvivesCallbackStackOverflowError() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicBoolean thrown = new AtomicBoolean(false);
        TelemetryRegistry.start((tid, targetField, isWrite) -> {
            if (thrown.compareAndSet(false, true)) {
                throw new StackOverflowError("detector callback recursed");
            }
            delivered.countDown();
        });

        TelemetryRegistry.recordAccess(Thread.currentThread().threadId(), "ErrService", "setA");

        // A task that throws out of scheduleAtFixedRate cancels every future execution:
        // before the fix a single callback Error killed the periodic drain for the rest of
        // the JVM, and no event was ever delivered again. The drain must swallow the Error
        // (same containment rule as DetectorRegistry.ifIssue) and redeliver on a later cycle.
        assertTrue(delivered.await(5, TimeUnit.SECONDS),
                "a callback Error must not kill the periodic drain — the event should be "
                        + "redelivered on the next 1ms cycle");
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

        // One record is enough: a drain that began with the previous (no-op default) callback
        // still hands an event recorded after the swap to the new one.
        TelemetryRegistry.recordAccess(Thread.currentThread().threadId(), "SecondService", "getUser");

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Updated telemetry callback was not invoked");
        assertEquals(List.of("SecondService#getUser"), events,
                "updated callback should receive the recorded access once");
    }

    @Test
    void anEventRecordedAfterTheSwapReachesTheNewCallbackThoughADrainWasUnderway() throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> previous = Collections.synchronizedList(new ArrayList<>());
        List<String> current = Collections.synchronizedList(new ArrayList<>());
        TelemetryRegistry.start((tid, targetField, isWrite) -> {
            previous.add(targetField);
            if ("Earlier#run".equals(targetField)) {
                holding.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        long self = Thread.currentThread().threadId();
        TelemetryRegistry.recordAccess(self, "Earlier", "run");
        assertTrue(holding.await(5, TimeUnit.SECONDS), "the drain never delivered the first event");

        // The drain thread is inside a drain that began with the previous callback, as one
        // preempted on a loaded runner is when the next run's bridge takes the slot.
        TelemetryRegistry.setCallback((tid, targetField, isWrite) -> current.add(targetField));
        TelemetryRegistry.recordAccess(self, "Next", "run");
        release.countDown();
        TelemetryRegistry.flush();

        assertEquals(List.of("Next#run"), current,
                "an event recorded after the swap went to the callback it replaced, so the run that "
                        + "took the slot never saw it (VolatilePublicationWeavingTest, 2026-10-08); "
                        + "the previous callback got " + previous);
    }
}
