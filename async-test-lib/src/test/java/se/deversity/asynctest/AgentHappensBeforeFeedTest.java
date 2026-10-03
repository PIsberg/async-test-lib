package se.deversity.asynctest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

    /**
     * The start and join of {@link #startJoinReported}, with the child started the way
     * {@code how} names and waited for by a spin on {@code isAlive} (#834): 0 through a platform
     * {@code Thread.Builder}, 1 through a virtual one, 2 through {@code startVirtualThread}.
     */
    private static boolean builtStartPolledEndReported(int how, boolean woven) {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box box = new Box();
        box.value = 1;
        detector.recordFieldWrite(box, "value");
        Runnable task = () -> {
            box.value++;
            detector.recordFieldWrite(box, "value");
        };
        Thread.Builder builder = how == 0 ? Thread.ofPlatform() : Thread.ofVirtual();
        Thread child;
        if (woven) {
            child = how == 2 ? AgentThreadHooks.threadStartVirtual(task)
                    : AgentThreadHooks.threadBuilderStart(builder, task);
            while (AgentThreadHooks.threadIsAlive(child)) {
                Thread.onSpinWait();
            }
        } else {
            child = how == 2 ? Thread.startVirtualThread(task) : builder.start(task);
            while (child.isAlive()) {
                Thread.onSpinWait();
            }
        }
        detector.recordFieldRead(box, "value");
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a woven Thread.Builder start or startVirtualThread, and an isAlive that said false, order the child")
    void wovenBuiltStartAndPolledEnd() {
        for (int how = 0; how < 3; how++) {
            assertFalse(builtStartPolledEndReported(how, true),
                    "start " + how + ": the builder start is a fork and a false isAlive a join");
            assertTrue(builtStartPolledEndReported(how, false),
                    "start " + how + ": the unwoven twin keeps its finding");
        }
    }

    @Test
    @DisplayName("an isAlive that says true orders nothing, and one about an unstarted thread nothing either")
    void anIsAliveThatSaysTrueOrdersNothing() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box box = new Box();
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread child = new Thread(() -> {
            box.value = 1;
            detector.recordFieldWrite(box, "value");
            written.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertFalse(AgentThreadHooks.threadIsAlive(child), "a thread not started yet is not alive");
        child.start();
        written.await();
        assertTrue(AgentThreadHooks.threadIsAlive(child), "the hook answers what isAlive answers");
        detector.recordFieldRead(box, "value");
        release.countDown();
        child.join();
        assertTrue(detector.analyzeRaceConditions().hasIssues(),
                "the read came after a latch the model does not see and an isAlive that said true");
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

    /**
     * A writer publishes {@code Box.value} with a volatile write of {@code Box.ready}, storing 1;
     * the reader reads the volatile field {@code readField} of the same box, which returns
     * {@code valueRead}, then {@code Box.value}. Each side emits exactly what the weaver emits.
     */
    private static boolean volatileFlagReported(String readField, int valueRead)
            throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box box = new Box();
        CountDownLatch flagged = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            box.value = 5;
            detector.recordFieldWrite(box, "value");
            // What the weaver emits before a volatile write of Box.ready that stores 1.
            TelemetryRegistry.recordAccess(box, null, null, Thread.currentThread().threadId(),
                    "Box.ready", true, true, Integer.MIN_VALUE, false, false);
            TelemetryRegistry.volatileStore(box, 1, "Box.ready");
            flagged.countDown();
        });
        Thread reader = new Thread(() -> {
            try {
                flagged.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            // What the weaver emits around the volatile read of readField: before, then after.
            TelemetryRegistry.recordAccess(box, null, null, Thread.currentThread().threadId(),
                    readField, false, true, Integer.MIN_VALUE, false, false);
            TelemetryRegistry.volatileLoad(box, valueRead, readField);
            // A plain read the weaver marks as following a volatile read of the same object.
            TelemetryRegistry.recordAccess(box, null, null, Thread.currentThread().threadId(),
                    "Box.value", false, false, Integer.MIN_VALUE, true, false);
            detector.recordFieldRead(box, "value");
        });
        writer.start();
        reader.start();
        writer.join();
        reader.join();
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a woven volatile write publishes to a read that returned the value it stored")
    void wovenVolatilePublication() throws InterruptedException {
        assertFalse(volatileFlagReported("Box.ready", 1));
    }

    @Test
    @DisplayName("a volatile read that returned the older value receives nothing (#742)")
    void aReadOfTheOlderValueReceivesNothing() throws InterruptedException {
        assertTrue(volatileFlagReported("Box.ready", 0),
                "the reader read 0, not the 1 the writer stored: it synchronizes with a write "
                        + "before the writer's, and Box.value reached it through nothing");
    }

    @Test
    @DisplayName("a volatile read of one field does not receive what a write of another published (#742)")
    void aVolatileEdgeIsPerField() throws InterruptedException {
        assertTrue(volatileFlagReported("Box.other", 1),
                "the reader read Box.other, which nobody wrote: Box.value reached it through "
                        + "nothing, and the write of Box.ready must not order it");
        assertFalse(volatileFlagReported("Box.ready", 1),
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

    /**
     * A producer writes two boxes and adds both to a bounded queue with room for one, through the
     * woven {@code addAll}; the queue takes the first and refuses the second. The consumer then
     * reads one of them: the accepted box taken out of the queue, or the refused box through a
     * concurrent map the main thread published it through before either thread ran, which orders
     * nothing between the two of them (#806).
     */
    private static boolean batchElementReported(boolean readTheAcceptedOne)
            throws InterruptedException {
        return batchElementReported(readTheAcceptedOne, new ArrayBlockingQueue<>(1));
    }

    private static boolean batchElementReported(boolean readTheAcceptedOne, BlockingQueue<Object> queue)
            throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box accepted = new Box();
        Box refused = new Box();
        Map<Object, Object> registry = new ConcurrentHashMap<>();
        AgentCollectionHooks.mapPut(registry, "refused", refused);
        CountDownLatch handed = new CountDownLatch(1);
        AtomicReference<String> setUpWrong = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            accepted.value = 1;
            detector.recordFieldWrite(accepted, "value");
            refused.value = 1;
            detector.recordFieldWrite(refused, "value");
            try {
                AgentCollectionHooks.collectionAddAll(queue, List.of(accepted, refused));
                setUpWrong.set("the queue took the whole batch");
            } catch (IllegalStateException full) {
                // The second element did not fit, which is the point.
            }
            handed.countDown(); // unwoven, so it orders nothing as far as the model knows
        });
        Thread consumer = new Thread(() -> {
            Object received;
            try {
                handed.await();
                received = readTheAcceptedOne ? AgentConcurrencyUtilHooks.take(queue)
                        : AgentCollectionHooks.mapGet(registry, "refused");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Box box = readTheAcceptedOne ? accepted : refused;
            if (received != box) {
                setUpWrong.set("the consumer did not receive the box it reads");
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

    @Test
    @DisplayName("an addAll that the queue takes only part of publishes only that part (#806)")
    void aPartlyRefusedAddAllPublishesOnlyWhatWentIn() throws InterruptedException {
        assertTrue(batchElementReported(false),
                "the queue refused the second box, so the producer's write of it reached the "
                        + "consumer through nothing: the addAll must not have released it");
        assertFalse(batchElementReported(true),
                "the queue took the first box and the consumer took it out: the hand-off orders them");
    }

    @Test
    @DisplayName("a queue with its own addAll that takes only part of the batch publishes only that part (#806)")
    void aPartlyRefusedOwnAddAllPublishesOnlyWhatWentIn() throws InterruptedException {
        // LinkedBlockingDeque links the whole batch at once when it fits, and when it does not,
        // falls back to adding one at a time and throws at the first that does not fit. The hook
        // keeps that call, so before #806's rest it released every element up front and withdrew
        // nothing when the call threw.
        assertTrue(batchElementReported(false, new java.util.concurrent.LinkedBlockingDeque<>(1)),
                "the deque refused the second box, so the producer's write of it reached the "
                        + "consumer through nothing: the addAll must not have released it");
        assertFalse(batchElementReported(true, new java.util.concurrent.LinkedBlockingDeque<>(1)),
                "the deque took the first box and the consumer took it out: the hand-off orders them");
    }

    /** How the reader below reaches the box a writer completed a future with. */
    private enum Completion {
        /** The writer completes the future; the reader joins it. */
        JOINED,
        /** The same, with the reader calling get instead of join. */
        GOT,
        /** The writer completes the future; the reader skips it and reads the box directly. */
        SKIPPED,
        /** The main thread completed the future first, so the writer's complete is refused. */
        REFUSED
    }

    /**
     * A writer writes a box and completes a future with it; a reader then reads and writes the
     * box. The two threads are otherwise coordinated only through an unwoven latch, so the only
     * edge the model can know is the one the future makes (#741).
     */
    private static boolean completionReported(Completion shape) throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        CompletableFuture<Object> future = new CompletableFuture<>();
        Box box = new Box();
        if (shape == Completion.REFUSED) {
            future.complete(box); // unwoven: publishes nothing
        }
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> setUpWrong = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            box.value = 1;
            detector.recordFieldWrite(box, "value");
            if (AgentConcurrencyUtilHooks.complete(future, box) == (shape == Completion.REFUSED)) {
                setUpWrong.set("the complete call did not answer what the case needs");
            }
            completed.countDown(); // unwoven, so it orders nothing as far as the model knows
        });
        Thread reader = new Thread(() -> {
            try {
                completed.await();
                Object received = switch (shape) {
                    case GOT -> AgentConcurrencyUtilHooks.get(future);
                    case SKIPPED -> box;
                    default -> AgentConcurrencyUtilHooks.join(future);
                };
                if (received != box) {
                    setUpWrong.set("the reader did not receive the box");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.util.concurrent.ExecutionException e) {
                setUpWrong.set("the future failed: " + e);
                return;
            }
            detector.recordFieldRead(box, "value");
            box.value++;
            detector.recordFieldWrite(box, "value");
        });
        writer.start();
        reader.start();
        writer.join();
        reader.join();
        assertNull(setUpWrong.get());
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a woven complete orders the writer before a woven join or get of the future (#741)")
    void completionPublishes() throws InterruptedException {
        assertFalse(completionReported(Completion.JOINED),
                "the writer completed the future after writing the box, and the reader's join "
                        + "returned it: CompletableFuture orders the two");
        assertFalse(completionReported(Completion.GOT), "the same through Future.get");
        assertTrue(completionReported(Completion.SKIPPED),
                "the reader never joined, so nothing orders the writer's write before its read");
        assertTrue(completionReported(Completion.REFUSED),
                "the future was already complete, so the writer's complete published nothing and "
                        + "the join observed a completion the writer did not make");
    }

    /** Where the registrar below breaks the order a dependent stage makes, if it does. */
    private enum Stage {
        /** Input written before thenApply, output read after joining the stage it returned. */
        ORDERED,
        /** The input is written after thenApply, where the function may already be reading it. */
        INPUT_AFTER_REGISTERING,
        /** The output is read before the join. */
        OUTPUT_BEFORE_JOIN,
        /** The source is already complete, so the function runs inside thenApply. */
        ALREADY_COMPLETE,
        /** Both calls made on the future directly, which the model cannot see. */
        UNWOVEN,
        /** As ORDERED, through a call site typed against CompletionStage. */
        TYPED_AS_STAGE
    }

    /**
     * The registering thread writes an input and registers a function on a future another thread
     * completes with a box it wrote; the function reads the input and the box and writes an
     * output, and the registering thread joins the stage and reads the output (#741). The function
     * runs on the completing thread, or inside thenApply when the source was already complete.
     */
    private static boolean stageReported(Stage shape) throws Exception {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box input = new Box();
        Box completed = new Box();
        Box output = new Box();
        CompletableFuture<Object> source = new CompletableFuture<>();
        Runnable complete = () -> {
            completed.value = 1;
            detector.recordFieldWrite(completed, "value");
            AgentConcurrencyUtilHooks.complete(source, completed);
        };
        if (shape == Stage.ALREADY_COMPLETE) {
            Thread completer = new Thread(complete);
            completer.start();
            completer.join();
        }
        if (shape != Stage.INPUT_AFTER_REGISTERING) {
            input.value = 1;
            detector.recordFieldWrite(input, "value");
        }
        java.util.function.Function<Object, Object> function = value -> {
            detector.recordFieldRead(input, "value");
            detector.recordFieldRead(value, "value");
            output.value = input.value + ((Box) value).value;
            detector.recordFieldWrite(output, "value");
            return output;
        };
        CompletableFuture<Object> stage = switch (shape) {
            case UNWOVEN -> source.thenApply(function);
            case TYPED_AS_STAGE -> (CompletableFuture<Object>) AgentConcurrencyUtilHooks.thenApply(
                    (java.util.concurrent.CompletionStage<Object>) source, function);
            default -> AgentConcurrencyUtilHooks.thenApply(source, function);
        };
        if (shape == Stage.INPUT_AFTER_REGISTERING) {
            input.value = 1;
            detector.recordFieldWrite(input, "value");
        }
        if (shape != Stage.ALREADY_COMPLETE) {
            Thread completer = new Thread(complete);
            completer.start();
            completer.join(); // unwoven: orders nothing as far as the model knows
        }
        if (shape == Stage.OUTPUT_BEFORE_JOIN) {
            detector.recordFieldRead(output, "value");
        }
        Object joined = shape == Stage.UNWOVEN ? stage.join() : AgentConcurrencyUtilHooks.join(stage);
        if (joined != output) {
            throw new AssertionError("the stage did not return the output");
        }
        if (shape != Stage.OUTPUT_BEFORE_JOIN) {
            detector.recordFieldRead(output, "value");
        }
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a woven thenApply orders the registrar and the completer before the function, and the function before a woven join (#741)")
    void dependentStagesPublish() throws Exception {
        assertFalse(stageReported(Stage.ORDERED),
                "the input was written before thenApply, the box before the completion the function "
                        + "followed, and the output read after joining the stage: all three ordered");
        assertFalse(stageReported(Stage.TYPED_AS_STAGE),
                "the same through a call typed against CompletionStage, which a library returning "
                        + "one makes");
        assertFalse(stageReported(Stage.ALREADY_COMPLETE),
                "the same with the source completed first, so the function ran inside thenApply");
        assertTrue(stageReported(Stage.INPUT_AFTER_REGISTERING),
                "the input was written after thenApply, which orders nothing before the function");
        assertTrue(stageReported(Stage.OUTPUT_BEFORE_JOIN),
                "the output was read before the join, which orders nothing after the function");
        assertTrue(stageReported(Stage.UNWOVEN),
                "the unwoven twin: nothing told the model");
    }

    @Test
    @DisplayName("a stage that is not a CompletableFuture gets the caller's own function (#741)")
    void anotherStageImplementationSeesNoWrapper() {
        java.util.concurrent.atomic.AtomicReference<Object> handed = new java.util.concurrent.atomic.AtomicReference<>();
        @SuppressWarnings("unchecked")
        java.util.concurrent.CompletionStage<Object> stage = (java.util.concurrent.CompletionStage<Object>)
                java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[] {java.util.concurrent.CompletionStage.class}, (proxy, method, args) -> {
                            if (method.getName().equals("thenApply")) {
                                handed.set(args[0]);
                                return proxy;
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
        java.util.function.Function<Object, Object> function = value -> value;

        AgentConcurrencyUtilHooks.thenApply(stage, function);

        assertTrue(handed.get() == function,
                "a stage the JDK did not write may hand its functions back, so it must get the "
                        + "caller's own, unwrapped: " + handed.get());
    }

    /** Where the submitter below breaks the order an executor makes, if it does. */
    private enum Submission {
        /** Input written before submit, output read after get. */
        ORDERED,
        /** The input is written after the submit, where the task may already be reading it. */
        INPUT_AFTER_SUBMIT,
        /** The output is read before the get. */
        OUTPUT_BEFORE_GET,
        /** Both calls made on the executor directly, which the model cannot see. */
        UNWOVEN
    }

    /**
     * The calling thread writes an input, submits a task that reads it and writes an output, gets
     * the task's future and reads the output: the idiom the {@code java.util.concurrent} package
     * javadoc orders at both ends (#741). The task's accesses are recorded on the pool thread.
     */
    private static boolean submissionReported(Submission shape) throws Exception {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box input = new Box();
        Box output = new Box();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            if (shape != Submission.INPUT_AFTER_SUBMIT) {
                input.value = 1;
                detector.recordFieldWrite(input, "value");
            }
            CountDownLatch inputWritten = new CountDownLatch(1);
            Runnable task = () -> {
                try {
                    inputWritten.await(); // unwoven: only holds the task until the input is written
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                detector.recordFieldRead(input, "value");
                output.value = input.value * 2;
                detector.recordFieldWrite(output, "value");
            };
            Future<?> result = shape == Submission.UNWOVEN ? executor.submit(task)
                    : AgentConcurrencyUtilHooks.submit(executor, task);
            if (shape == Submission.INPUT_AFTER_SUBMIT) {
                input.value = 1;
                detector.recordFieldWrite(input, "value");
            }
            inputWritten.countDown();
            if (shape == Submission.OUTPUT_BEFORE_GET) {
                detector.recordFieldRead(output, "value");
            }
            @SuppressWarnings("unchecked")
            Future<Object> future = (Future<Object>) result;
            Object ignored = shape == Submission.UNWOVEN ? future.get()
                    : AgentConcurrencyUtilHooks.get(future);
            if (shape != Submission.OUTPUT_BEFORE_GET) {
                detector.recordFieldRead(output, "value");
            }
        } finally {
            executor.shutdownNow();
        }
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a woven submit orders the submitter before the task, and a woven get the task before the getter (#741)")
    void submissionPublishes() throws Exception {
        assertFalse(submissionReported(Submission.ORDERED),
                "the input was written before the submit and the output read after the get: "
                        + "the executor orders both");
        assertTrue(submissionReported(Submission.INPUT_AFTER_SUBMIT),
                "the input was written after the submit, which orders nothing before the task");
        assertTrue(submissionReported(Submission.OUTPUT_BEFORE_GET),
                "the output was read before the get, which orders nothing after the task");
        assertTrue(submissionReported(Submission.UNWOVEN),
                "the unwoven twin: nothing told the model");
    }

    /**
     * Two threads each write a box of their own, exchange it and read the one they got. With
     * {@code lateWrite} each also writes its own box again after the exchange, where the partner
     * is already reading it (#741).
     */
    private static boolean exchangeReported(boolean woven, boolean lateWrite)
            throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Exchanger<Object> exchanger = new Exchanger<>();
        AtomicReference<String> setUpWrong = new AtomicReference<>();
        Runnable side = () -> {
            Box mine = new Box();
            mine.value = 1;
            detector.recordFieldWrite(mine, "value");
            Object theirs;
            try {
                theirs = woven ? AgentConcurrencyUtilHooks.exchange(exchanger, mine)
                        : exchanger.exchange(mine);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!(theirs instanceof Box) || theirs == mine) {
                setUpWrong.set("the exchange did not hand over the partner's box");
                return;
            }
            if (lateWrite) {
                mine.value++;
                detector.recordFieldWrite(mine, "value");
            }
            detector.recordFieldRead(theirs, "value");
        };
        Thread left = new Thread(side);
        Thread right = new Thread(side);
        left.start();
        right.start();
        left.join();
        right.join();
        assertNull(setUpWrong.get());
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a woven exchange orders each side's writes before its partner's reads (#741)")
    void exchangePublishes() throws InterruptedException {
        assertFalse(exchangeReported(true, false),
                "each thread wrote its box before exchange and read the partner's after it: the "
                        + "Exchanger orders them");
        assertTrue(exchangeReported(true, true),
                "each thread wrote its box again after the exchange, which orders nothing before "
                        + "the partner's read");
        assertTrue(exchangeReported(false, false), "the unwoven twin: nothing told the model");
    }

    /** What the reader below reads out of an atomic slot, and how the writer filled it. */
    private enum SlotShape {
        /** The writer writes the box, then sets it; the reader gets it. */
        SET_THEN_GET,
        /** The writer sets the box and writes it after. */
        WRITTEN_AFTER_THE_SET,
        /**
         * The writer writes the box and sets a shared token into one slot; the reader gets the
         * same token out of another slot the main thread filled, and reaches the box through a map.
         */
        SAME_VALUE_OTHER_SLOT
    }

    /**
     * {@code AtomicReference.set} is a volatile write and {@code get} a volatile read, so a get
     * that returned what a set stored is ordered after it (#741). The edge is the slot's, found by
     * the value the get returned, as for a volatile field: the same object read out of another
     * slot orders nothing.
     */
    private static boolean slotReported(SlotShape shape) throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        AtomicReference<Object> slot = new AtomicReference<>();
        AtomicReference<Object> otherSlot = new AtomicReference<>();
        Object token = new Object();
        Box box = new Box();
        Map<Object, Object> registry = new ConcurrentHashMap<>();
        AgentCollectionHooks.mapPut(registry, "box", box);
        TelemetryRegistry.setAtomicReference(otherSlot, token);
        CountDownLatch set = new CountDownLatch(1);
        AtomicReference<String> setUpWrong = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            if (shape != SlotShape.WRITTEN_AFTER_THE_SET) {
                box.value = 1;
                detector.recordFieldWrite(box, "value");
            }
            TelemetryRegistry.setAtomicReference(slot,
                    shape == SlotShape.SAME_VALUE_OTHER_SLOT ? token : box);
            if (shape == SlotShape.WRITTEN_AFTER_THE_SET) {
                box.value = 1;
                detector.recordFieldWrite(box, "value");
            }
            set.countDown(); // unwoven, so it orders nothing as far as the model knows
        });
        Thread reader = new Thread(() -> {
            try {
                set.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Object got = TelemetryRegistry.getAtomicReference(
                    shape == SlotShape.SAME_VALUE_OTHER_SLOT ? otherSlot : slot);
            Object expected = shape == SlotShape.SAME_VALUE_OTHER_SLOT ? token : box;
            if (got != expected) {
                setUpWrong.set("the reader's get did not return what the case needs");
            }
            // Only the case that reads another slot needs the map; a get that returned the box
            // must be the only thing that hands it over.
            Box read = (Box) (shape == SlotShape.SAME_VALUE_OTHER_SLOT
                    ? AgentCollectionHooks.mapGet(registry, "box") : got);
            detector.recordFieldRead(read, "value");
            read.value++;
            detector.recordFieldWrite(read, "value");
        });
        writer.start();
        reader.start();
        writer.join();
        reader.join();
        assertNull(setUpWrong.get());
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a woven AtomicReference get receives what the set that stored its value published (#741)")
    void atomicReferencePublishes() throws InterruptedException {
        assertFalse(slotReported(SlotShape.SET_THEN_GET),
                "the writer wrote the box and set it; the reader's get returned it: the volatile "
                        + "semantics of set and get order the write before the read");
        assertTrue(slotReported(SlotShape.WRITTEN_AFTER_THE_SET),
                "the writer wrote the box after the set, which orders nothing before the read");
        assertTrue(slotReported(SlotShape.SAME_VALUE_OTHER_SLOT),
                "the reader read the token out of a slot the writer never set, so it received "
                        + "nothing the writer published through its own slot");
    }

    /**
     * A stopped telemetry registry stops the event ring, not the happens-before model.
     *
     * <p>{@code TelemetryRegistry.stop()} runs at JVM shutdown, and in tests that stand in for
     * the agent. It left a flag that every woven hand-off checked before telling the model, so in
     * any JVM where it had run once, every later hand-off went unrecorded and read as a race. That
     * is what kept the weekly mutation gate red from 2026-09-27: pitest runs every test class in
     * one JVM, and a test that stopped the registry ran before this class, whose woven cases then
     * all failed. Surefire gives each class its own JVM and never saw it.
     */
    @Test
    @DisplayName("a stopped telemetry registry does not switch off the happens-before feed")
    void aStoppedRegistryStillOrdersWovenHandOffs() throws InterruptedException {
        TelemetryRegistry.start(null);
        TelemetryRegistry.stop();
        assertFalse(handOffReported(true), "a woven queue hand-off, after the registry stopped");
        assertFalse(slotReported(SlotShape.SET_THEN_GET),
                "an AtomicReference set and get, after the registry stopped");
        assertFalse(volatileFlagReported("Box.ready", 1), "a volatile publication, after the registry stopped");
    }
}
