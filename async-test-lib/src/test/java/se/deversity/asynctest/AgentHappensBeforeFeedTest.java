package se.deversity.asynctest;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import se.deversity.asynctest.diagnostics.RaceConditionDetector;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The agent's hooks feed the happens-before model, so correct hand-offs in woven code stop
 * reading as races without a single declaration in the test.
 *
 * <p>The hooks are called directly, which is exactly what the woven call sites do: the weaver
 * replaces {@code queue.put(x)} with {@code AgentConcurrencyUtilHooks.put(queue, x)}. Each case
 * has a twin that makes the same hand-off through the plain, unwoven call, which the model cannot
 * see and which must therefore keep its finding: an edge the hooks did not report is not one the
 * detector may assume.
 */
class AgentHappensBeforeFeedTest {

    static final class Box {
        int value;
    }

    /** Two threads, one hand-off between them, recorded the way a test records by hand. */
    private static boolean handOffReported(boolean woven) throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        Box box = new Box();
        Thread producer = new Thread(() -> {
            box.value = 1;
            detector.recordFieldWrite(box, "value");
            try {
                if (woven) {
                    AgentConcurrencyUtilHooks.put(queue, box);
                } else {
                    queue.put(box);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread consumer = new Thread(() -> {
            try {
                Box taken = (Box) (woven ? AgentConcurrencyUtilHooks.take(queue) : queue.take());
                detector.recordFieldRead(taken, "value");
                taken.value++;
                detector.recordFieldWrite(taken, "value");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();
        producer.start();
        producer.join();
        consumer.join();
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a woven BlockingQueue put and take order the hand-off")
    void wovenQueueHandOff() throws InterruptedException {
        assertFalse(handOffReported(true), "the take received what the put published");
        assertTrue(handOffReported(false), "the unwoven twin: nothing told the model");
    }

    private static boolean startJoinReported(boolean woven) throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box box = new Box();
        box.value = 1;
        detector.recordFieldWrite(box, "value");
        Thread child = new Thread(() -> {
            box.value++;
            detector.recordFieldWrite(box, "value");
        });
        if (woven) {
            AgentThreadHooks.threadStart(child);
            AgentThreadHooks.threadJoin(child);
        } else {
            child.start();
            child.join();
        }
        detector.recordFieldRead(box, "value");
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a woven Thread.start and join order the child between the parent's accesses")
    void wovenStartAndJoin() throws InterruptedException {
        assertFalse(startJoinReported(true), "start and join are the lifecycle's two edges");
        assertTrue(startJoinReported(false), "the unwoven twin keeps its finding");
    }

    private static boolean mapPublicationReported(Map<Object, Object> map)
            throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        CountDownLatch published = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            Box fresh = new Box();
            fresh.value = 7;
            detector.recordFieldWrite(fresh, "value");
            AgentCollectionHooks.mapPut(map, "config", fresh);
            published.countDown();
        });
        Thread reader = new Thread(() -> {
            try {
                published.await(); // unwoven, so it orders nothing as far as the model knows
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Box seen = (Box) AgentCollectionHooks.mapGet(map, "config");
            detector.recordFieldRead(seen, "value");
        });
        writer.start();
        reader.start();
        writer.join();
        reader.join();
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a ConcurrentHashMap put and get publish the value; a HashMap does not")
    void concurrentMapPublication() throws InterruptedException {
        assertFalse(mapPublicationReported(new ConcurrentHashMap<>()),
                "ConcurrentMap's memory consistency effect orders the put before the get");
        assertTrue(mapPublicationReported(new HashMap<>()),
                "a HashMap promises nothing, so the same calls order nothing");
    }

    private static boolean latchReported(boolean woven) throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        CountDownLatch ready = new CountDownLatch(1);
        Box box = new Box();
        Thread writer = new Thread(() -> {
            box.value = 3;
            detector.recordFieldWrite(box, "value");
            if (woven) {
                AgentConcurrencyUtilHooks.countDown(ready);
            } else {
                ready.countDown();
            }
        });
        Thread reader = new Thread(() -> {
            try {
                if (woven) {
                    AgentConcurrencyUtilHooks.await(ready);
                } else {
                    ready.await();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            detector.recordFieldRead(box, "value");
        });
        writer.start();
        reader.start();
        writer.join();
        reader.join();
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a woven countDown and await order the writer before the waiter")
    void wovenLatch() throws InterruptedException {
        assertFalse(latchReported(true));
        assertTrue(latchReported(false));
    }

    private static boolean volatileFlagReported(boolean afterVolatileRead)
            throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box box = new Box();
        CountDownLatch flagged = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            box.value = 5;
            detector.recordFieldWrite(box, "value");
            // What the weaver emits before a volatile write of Box.ready.
            TelemetryRegistry.recordAccess(box, null, null, Thread.currentThread().threadId(),
                    "Box.ready", true, true, Integer.MIN_VALUE, false, false);
            flagged.countDown();
        });
        Thread reader = new Thread(() -> {
            try {
                flagged.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            // A plain read the weaver marks as following a volatile read of the same object.
            TelemetryRegistry.recordAccess(box, null, null, Thread.currentThread().threadId(),
                    "Box.value", false, false, Integer.MIN_VALUE, afterVolatileRead, false);
            detector.recordFieldRead(box, "value");
        });
        writer.start();
        reader.start();
        writer.join();
        reader.join();
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a woven volatile write publishes to a later access that follows a volatile read")
    void wovenVolatilePublication() throws InterruptedException {
        assertFalse(volatileFlagReported(true));
        assertTrue(volatileFlagReported(false),
                "an access the weaver did not mark as following a volatile read acquires nothing");
    }
}
