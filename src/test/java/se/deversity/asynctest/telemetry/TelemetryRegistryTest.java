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

        // The background drainer flushes every 1 ms.
        assertTrue(latch.await(500, TimeUnit.MILLISECONDS), "Telemetry event was not drained/delivered");
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

        TelemetryRegistry.recordAccess(Thread.currentThread().threadId(), "SecondService", "getUser");

        assertTrue(latch.await(500, TimeUnit.MILLISECONDS), "Updated telemetry callback was not invoked");
        assertEquals(List.of("SecondService#getUser"), events);
    }
}
