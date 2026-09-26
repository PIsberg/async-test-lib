package se.deversity.asynctest;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import se.deversity.asynctest.diagnostics.RaceConditionDetector;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        return volatileFlagReported("Box.ready", afterVolatileRead);
    }

    /**
     * A writer publishes {@code Box.value} with a volatile write of {@code Box.ready}; the reader
     * reads the volatile field {@code readField} of the same box, then {@code Box.value} in an
     * access the weaver marks as following that read.
     */
    private static boolean volatileFlagReported(String readField, boolean afterVolatileRead)
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
            // What the weaver emits before the volatile read of readField.
            TelemetryRegistry.recordAccess(box, null, null, Thread.currentThread().threadId(),
                    readField, false, true, Integer.MIN_VALUE, false, false);
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

    @Test
    @DisplayName("a volatile read of one field does not receive what a write of another published (#742)")
    void aVolatileEdgeIsPerField() throws InterruptedException {
        assertTrue(volatileFlagReported("Box.other", true),
                "the reader read Box.other, which nobody wrote: Box.value reached it through "
                        + "nothing, and the write of Box.ready must not order it");
        assertFalse(volatileFlagReported("Box.ready", true),
                "the twin reads the flag the writer set, which orders the plain write before it");
    }

    /** The two ways the producer below hands the token over, each of which can be refused. */
    private interface Hand {

        /** {@return whether the container took {@code token}} */
        boolean offer(Object token);

        /** {@return the token, taken back out of the container} */
        Object take() throws InterruptedException;
    }

    /**
     * A producer writes, then hands a token over through {@code hand}; a consumer then reads what
     * the producer wrote. With the hand-off accepted the consumer takes the token out of the same
     * container. With it refused the consumer can only get the token from a concurrent map the
     * main thread published it through before either thread ran, which orders nothing between the
     * two of them: the producer's refused offer published nothing to anybody.
     */
    private static boolean handOffAfterOfferReported(Hand hand, boolean accepted)
            throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box box = new Box();
        Object token = new Object();
        Map<Object, Object> registry = new ConcurrentHashMap<>();
        AgentCollectionHooks.mapPut(registry, "token", token);
        CountDownLatch handed = new CountDownLatch(1);
        AtomicReference<String> setUpWrong = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            box.value = 1;
            detector.recordFieldWrite(box, "value");
            if (hand.offer(token) != accepted) {
                setUpWrong.set("the container did not " + (accepted ? "take" : "refuse") + " the token");
            }
            handed.countDown(); // unwoven, so it orders nothing as far as the model knows
        });
        Thread consumer = new Thread(() -> {
            Object received;
            try {
                handed.await();
                received = accepted ? hand.take() : AgentCollectionHooks.mapGet(registry, "token");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (received != token) {
                setUpWrong.set("the consumer did not receive the token");
            }
            detector.recordFieldRead(box, "value");
            box.value++;
            detector.recordFieldWrite(box, "value");
        });
        producer.start();
        consumer.start();
        producer.join();
        consumer.join();
        assertNull(setUpWrong.get());
        return detector.analyzeRaceConditions().hasIssues();
    }

    /** A bounded queue, full when the offer must be refused. */
    private static Hand queue(boolean accepts) {
        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(1);
        if (!accepts) {
            queue.add(new Object());
        }
        return new Hand() {
            @Override
            public boolean offer(Object token) {
                return AgentConcurrencyUtilHooks.offer(queue, token);
            }

            @Override
            public Object take() throws InterruptedException {
                return AgentConcurrencyUtilHooks.take(queue);
            }
        };
    }

    /** An atomic slot, whose compareAndSet fails when the expected value is not the one it holds. */
    private static Hand slot(boolean accepts) {
        AtomicReference<Object> slot = new AtomicReference<>();
        Object expected = accepts ? null : new Object();
        return new Hand() {
            @Override
            public boolean offer(Object token) {
                return TelemetryRegistry.compareAndSetAtomicReference(slot, expected, token);
            }

            @Override
            public Object take() {
                return TelemetryRegistry.getAndSetAtomicReference(slot, null);
            }
        };
    }

    @Test
    @DisplayName("a refused offer publishes nothing; an accepted one still orders the take (#742)")
    void aRefusedOfferIsNotARelease() throws InterruptedException {
        assertTrue(handOffAfterOfferReported(queue(false), false),
                "the queue refused the token, so the producer's write reached the consumer through "
                        + "nothing: the offer must not have released it");
        assertFalse(handOffAfterOfferReported(queue(true), true),
                "the queue took the token and the consumer took it out: the hand-off orders them");
    }

    @Test
    @DisplayName("a compareAndSet retry that fails and then succeeds orders the take (#742)")
    void aRetryThatSucceedsStillPublishes() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box box = new Box();
        Object token = new Object();
        Object stale = new Object();
        AtomicReference<Object> slot = new AtomicReference<>(stale);
        CountDownLatch handed = new CountDownLatch(1);
        AtomicReference<String> setUpWrong = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            box.value = 1;
            detector.recordFieldWrite(box, "value");
            Object expected = new Object(); // the first attempt reads the slot wrong and fails
            while (!TelemetryRegistry.compareAndSetAtomicReference(slot, expected, token)) {
                expected = slot.get();
            }
            handed.countDown(); // unwoven, so it orders nothing as far as the model knows
        });
        Thread consumer = new Thread(() -> {
            try {
                handed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (TelemetryRegistry.getAndSetAtomicReference(slot, null) != token) {
                setUpWrong.set("the consumer did not take the token");
            }
            detector.recordFieldRead(box, "value");
            box.value++;
            detector.recordFieldWrite(box, "value");
        });
        producer.start();
        consumer.start();
        producer.join();
        consumer.join();
        assertNull(setUpWrong.get());
        assertFalse(detector.analyzeRaceConditions().hasIssues(),
                "the withdrawn release of the failed attempt must not take the successful one with it");
    }

    @Test
    @DisplayName("a failed compareAndSet publishes nothing; a successful one still orders the take (#742)")
    void aFailedCompareAndSetIsNotARelease() throws InterruptedException {
        assertTrue(handOffAfterOfferReported(slot(false), false),
                "the swap failed, so the producer's write reached the consumer through nothing: "
                        + "the compareAndSet must not have released it");
        assertFalse(handOffAfterOfferReported(slot(true), true),
                "the swap stored the token and the consumer's getAndSet took it: that orders them");
    }
}
