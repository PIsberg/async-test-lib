package se.deversity.asynctest.telemetry;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryEventBufferTest {

    @Test
    void testBasicPublishAndDrain() {
        TelemetryEventBuffer buffer = new TelemetryEventBuffer(4);
        buffer.publish(10L, "ClassA#field1", true);
        buffer.publish(20L, "ClassB#field2", false);

        List<String> results = new ArrayList<>();
        int count = buffer.drain((tid, field, write) -> {
            results.add(tid + ":" + field + ":" + write);
        });

        assertEquals(2, count);
        assertEquals(List.of("10:ClassA#field1:true", "20:ClassB#field2:false"), results);
        assertEquals(2L, buffer.publishedCount());
    }

    @Test
    void testMpscConcurrency() throws Exception {
        int capacity = 1024;
        TelemetryEventBuffer buffer = new TelemetryEventBuffer(capacity);
        int producerThreadsCount = 8;
        int eventsPerProducer = 500;
        ExecutorService executor = Executors.newFixedThreadPool(producerThreadsCount);

        List<String> drainedEvents = Collections.synchronizedList(new ArrayList<>());
        ScheduledExecutorService drainExecutor = Executors.newSingleThreadScheduledExecutor();

        // Run consumer in background to drain buffer constantly.
        drainExecutor.scheduleAtFixedRate(() -> {
            buffer.drain((tid, field, write) -> {
                drainedEvents.add(tid + ":" + field);
            });
        }, 0, 10, TimeUnit.MILLISECONDS);

        CountDownLatch latch = new CountDownLatch(producerThreadsCount);
        for (int p = 0; p < producerThreadsCount; p++) {
            final long threadId = p + 100;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < eventsPerProducer; i++) {
                        buffer.publish(threadId, "TargetClass#field" + i, true);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Producers did not finish publishing in time");

        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.SECONDS);

        // Final flush.
        Thread.sleep(50);
        buffer.drain((tid, field, write) -> {
            drainedEvents.add(tid + ":" + field);
        });
        drainExecutor.shutdown();

        assertEquals(producerThreadsCount * eventsPerProducer, drainedEvents.size(), "Drained event count mismatch");
    }

    @Test
    void testBufferBlockOnFull() throws Exception {
        int capacity = 4;
        TelemetryEventBuffer buffer = new TelemetryEventBuffer(capacity);

        // Publish capacity events: should fill the buffer perfectly.
        buffer.publish(1L, "Field#1", true);
        buffer.publish(2L, "Field#2", true);
        buffer.publish(3L, "Field#3", true);
        buffer.publish(4L, "Field#4", true);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger step = new AtomicInteger(0);

        // This 5th publish should block/spin-wait because the buffer is full (seq = 4, consumerCursor = -1, seq - consumerCursor = 5 > 4).
        Future<?> future = executor.submit(() -> {
            started.countDown();
            buffer.publish(5L, "Field#5", true);
            step.set(1);
        });

        assertTrue(started.await(500, TimeUnit.MILLISECONDS));
        Thread.sleep(100); // Allow thread to block
        assertEquals(0, step.get(), "Producer did not block on full buffer");

        // Drain 1 event.
        List<String> drained = new ArrayList<>();
        // The first drain event will be seq 0, consumerCursor becomes 0.
        // For the 5th publish: seq = 4, consumerCursor = 0, seq - consumerCursor = 4 which is not > 4, so it should unblock!
        int count = buffer.drain((tid, field, isWrite) -> {
            drained.add(field);
        });
        // We only drain as much as available. Drains seq 0, 1, 2, 3 (4 events). consumerCursor becomes 3.
        assertEquals(4, count);
        assertEquals(List.of("Field#1", "Field#2", "Field#3", "Field#4"), drained);

        // Wait for the blocked producer to finish.
        future.get(1, TimeUnit.SECONDS);
        assertEquals(1, step.get(), "Blocked producer did not unblock after drain");

        // Drain the newly published event.
        List<String> drained2 = new ArrayList<>();
        int count2 = buffer.drain((tid, field, isWrite) -> {
            drained2.add(field);
        });
        assertEquals(1, count2);
        assertEquals(List.of("Field#5"), drained2);

        executor.shutdown();
    }
}
