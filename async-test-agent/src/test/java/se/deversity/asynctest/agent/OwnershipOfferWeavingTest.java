package se.deversity.asynctest.agent;

import com.example.agentfixture.OfferedChunkBean;
import com.example.agentfixture.OfferedChunkBean.Chunk;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import se.deversity.asynctest.diagnostics.AtomicityValidator;
import se.deversity.asynctest.telemetry.TelemetryBridge;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins that the offers and takes #664 is about reach the detector with the container they went
 * through, so a take-first generation can name its owner, and that a drained or taken offer cannot
 * name the wrong one.
 *
 * <p>Two layers. The event cases run one offer and one take on a worker and read back what the
 * validator was told: the offered and taken identities must match, and both must carry the
 * container's identity (the atomic, the receiver whose field is the slot, the array, the queue).
 * The end-to-end cases replay the stream {@code DetectorAccuracyEvalTest} pins, through woven
 * code: an alias writing a chunk in the first generation of a slot hand-off must fire, while the
 * offerer's own late write stays silent; and a chunk drained or taken out of a queue and put back
 * by another thread must not let the first offer turn that thread's late write into an alias.
 *
 * <p>JCTools is not on any test classpath (invariant 12), so the JCTools cases run against
 * {@code com.example.agentfixture.jctools.queues.MessagePassingQueue}, a stand-in with the same
 * name tail and method shapes the weaver matches on. A corpus row through the real netty or
 * JCTools queue is still missing.
 *
 * <p>Separate class because {@code selfAttach} is at-most-once per JVM and this class needs
 * {@code fields=true,collections=true}; {@code reuseForks=false} gives it its own fork.
 */
@Tag("e2e")
class OwnershipOfferWeavingTest {

    @BeforeAll
    static void attachWithFieldAndCollectionWeaving() {
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            supported = false;
        }
        assumeTrue(supported,
                "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");
        AsyncTestAgent.selfAttach("includes=com.example.agentfixture,fields=true,collections=true");
    }

    @AfterEach
    void stopRegistry() {
        TelemetryRegistry.stop();
    }

    /** One ownership event as the validator received it. */
    private record Event(String kind, int identity, int container, long threadId) { }

    /** A validator that also keeps the ownership events it is given, in order. */
    private static final class RecordingValidator extends AtomicityValidator {
        final List<Event> events = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public void recordOwnershipOffered(int identity, int container, long threadId) {
            events.add(new Event("offered", identity, container, threadId));
            super.recordOwnershipOffered(identity, container, threadId);
        }

        @Override
        public void recordOwnershipTaken(int identity, int container, long threadId) {
            events.add(new Event("taken", identity, container, threadId));
            super.recordOwnershipTaken(identity, container, threadId);
        }

        @Override
        public void recordContainerDrained(int container, long threadId) {
            events.add(new Event("drained", 0, container, threadId));
            super.recordContainerDrained(container, threadId);
        }

        List<Event> ofKind(String kind) {
            synchronized (events) {
                return events.stream().filter(e -> e.kind().equals(kind)).toList();
            }
        }
    }

    /**
     * A slot shape: how a chunk is offered, how it is taken, and which object is the container.
     * The container is {@code null} for a slot inside an object or an array, which is keyed by its
     * field or index as well as its holder (#692): there both ends must agree, and
     * {@link #siblingSlotsAreDifferentContainers()} pins that siblings do not.
     */
    private record Shape(String name, BiFunction<OfferedChunkBean, Chunk, Object> offer,
                         Function<OfferedChunkBean, Chunk> take,
                         Function<OfferedChunkBean, Object> container) { }

    private static List<Shape> shapes() {
        return List.of(
                new Shape("AtomicReference.set / getAndSet",
                        (b, c) -> { b.offerBySet(c); return null; }, OfferedChunkBean::takeFromSlot,
                        OfferedChunkBean::slot),
                new Shape("AtomicReference.lazySet / getAndSet",
                        (b, c) -> { b.offerByLazySet(c); return null; }, OfferedChunkBean::takeFromSlot,
                        OfferedChunkBean::slot),
                new Shape("AtomicReference.compareAndSet / getAndSet",
                        OfferedChunkBean::offerByCompareAndSet, OfferedChunkBean::takeFromSlot,
                        OfferedChunkBean::slot),
                new Shape("AtomicReferenceFieldUpdater.set / getAndSet",
                        (b, c) -> { b.offerThroughUpdater(c); return null; },
                        OfferedChunkBean::takeThroughUpdater, b -> null),
                new Shape("AtomicReferenceFieldUpdater.compareAndSet / getAndSet",
                        OfferedChunkBean::offerThroughUpdaterCompareAndSet,
                        OfferedChunkBean::takeThroughUpdater, b -> null),
                new Shape("AtomicReferenceArray.set / getAndSet",
                        (b, c) -> { b.offerToArray(c); return null; }, OfferedChunkBean::takeFromArray,
                        b -> null),
                new Shape("VarHandle.setRelease / getAndSet",
                        (b, c) -> { b.offerThroughHandle(c); return null; },
                        OfferedChunkBean::takeThroughHandle, b -> null),
                new Shape("VarHandle.compareAndSet / getAndSet",
                        OfferedChunkBean::offerThroughHandleCompareAndSet,
                        OfferedChunkBean::takeThroughHandle, b -> null),
                new Shape("MessagePassingQueue.relaxedOffer / relaxedPoll",
                        OfferedChunkBean::relaxedOfferToQueue, OfferedChunkBean::relaxedPollQueue,
                        OfferedChunkBean::queue),
                new Shape("MessagePassingQueue.offer / poll",
                        OfferedChunkBean::offerToQueue, OfferedChunkBean::pollQueue,
                        OfferedChunkBean::queue),
                // The entry and removal forms #664 left unwoven (#692).
                new Shape("Deque.offerFirst / pollFirst",
                        OfferedChunkBean::offerFirstToDeque, OfferedChunkBean::pollFirstFromDeque,
                        OfferedChunkBean::deque),
                new Shape("Deque.offerLast / pollLast",
                        OfferedChunkBean::offerLastToDeque, OfferedChunkBean::pollLastFromDeque,
                        OfferedChunkBean::deque),
                new Shape("Deque.addFirst / removeFirst",
                        (b, c) -> { b.addFirstToDeque(c); return null; },
                        OfferedChunkBean::removeFirstFromDeque, OfferedChunkBean::deque),
                new Shape("Deque.addLast / removeLast",
                        (b, c) -> { b.addLastToDeque(c); return null; },
                        OfferedChunkBean::removeLastFromDeque, OfferedChunkBean::deque),
                new Shape("Deque.push / pop",
                        (b, c) -> { b.pushToDeque(c); return null; },
                        OfferedChunkBean::popFromDeque, OfferedChunkBean::deque),
                new Shape("Collection.addAll / Queue.remove()",
                        OfferedChunkBean::addAllToPlain, OfferedChunkBean::removeHeadOfPlain,
                        OfferedChunkBean::plain),
                new Shape("Queue.offer / Collection.remove(Object)",
                        OfferedChunkBean::offerToPlain, OfferedChunkBean::removeFromPlainByName,
                        OfferedChunkBean::plain));
    }

    // ---- The events -----------------------------------------------------------------------------

    @TestFactory
    @DisplayName("an offer and a take through each slot shape carry the chunk and the same container (#664)")
    Stream<DynamicTest> offerAndTakeCarryTheContainer() {
        return shapes().stream().map(shape -> DynamicTest.dynamicTest(shape.name(), () -> {
            OfferedChunkBean bean = new OfferedChunkBean();
            RecordingValidator validator = new RecordingValidator();
            Chunk chunk = OfferedChunkBean.newChunk();
            try (Actors actors = new Actors(); TelemetryBridge bridge =
                    TelemetryBridge.activateWithFilter(validator, actors.ids::contains)) {
                actors.run(1, () -> shape.offer().apply(bean, chunk));
                Chunk taken = actors.run(2, () -> shape.take().apply(bean));
                assertSame(chunk, taken, "the fixture must hand over the offered chunk");
                TelemetryRegistry.flush();
            }
            int chunkId = System.identityHashCode(chunk);
            Object container = shape.container().apply(bean);
            List<Event> offered = validator.ofKind("offered").stream()
                    .filter(e -> e.identity() == chunkId).toList();
            List<Event> taken = validator.ofKind("taken").stream()
                    .filter(e -> e.identity() == chunkId).toList();
            assertEquals(1, offered.size(), shape.name() + ": the offer must be published once; "
                    + validator.events);
            if (container != null) {
                assertEquals(System.identityHashCode(container), offered.get(0).container(),
                        shape.name() + ": the offer must name the container the chunk went into");
            }
            assertTrue(offered.get(0).container() != 0,
                    shape.name() + ": the offer must name a container");
            assertEquals(1, taken.size(), shape.name() + ": the take must be published once; "
                    + validator.events);
            assertEquals(offered.get(0).container(), taken.get(0).container(),
                    shape.name() + ": the take must name the container the chunk came out of");
        }));
    }

    @Test
    @DisplayName("two fields of one object, and two elements of one array, are different containers (#692)")
    void siblingSlotsAreDifferentContainers() throws Exception {
        record Siblings(String name, java.util.function.BiConsumer<OfferedChunkBean, Chunk> offer,
                        Function<OfferedChunkBean, Chunk> take,
                        java.util.function.BiConsumer<OfferedChunkBean, Chunk> siblingOffer,
                        Function<OfferedChunkBean, Chunk> siblingTake) { }
        List<Siblings> kinds = List.of(
                new Siblings("AtomicReferenceFieldUpdater", OfferedChunkBean::offerThroughUpdater,
                        OfferedChunkBean::takeThroughUpdater,
                        OfferedChunkBean::offerThroughSiblingUpdater,
                        OfferedChunkBean::takeThroughSiblingUpdater),
                new Siblings("VarHandle", OfferedChunkBean::offerThroughHandle,
                        OfferedChunkBean::takeThroughHandle,
                        OfferedChunkBean::offerThroughSiblingHandle,
                        OfferedChunkBean::takeThroughSiblingHandle),
                new Siblings("AtomicReferenceArray", OfferedChunkBean::offerToArray,
                        OfferedChunkBean::takeFromArray,
                        OfferedChunkBean::offerToSiblingArrayElement,
                        OfferedChunkBean::takeFromSiblingArrayElement));
        for (Siblings kind : kinds) {
            OfferedChunkBean bean = new OfferedChunkBean();
            RecordingValidator validator = new RecordingValidator();
            Chunk here = OfferedChunkBean.newChunk();
            Chunk there = OfferedChunkBean.newChunk();
            try (Actors actors = new Actors(); TelemetryBridge bridge =
                    TelemetryBridge.activateWithFilter(validator, actors.ids::contains)) {
                actors.run(1, () -> { kind.offer().accept(bean, here); return null; });
                actors.run(1, () -> { kind.siblingOffer().accept(bean, there); return null; });
                assertSame(here, actors.run(2, () -> kind.take().apply(bean)));
                assertSame(there, actors.run(2, () -> kind.siblingTake().apply(bean)));
                TelemetryRegistry.flush();
            }
            List<Integer> hereContainers = containersOf(validator, here);
            List<Integer> thereContainers = containersOf(validator, there);
            assertEquals(2, hereContainers.size(), kind.name() + ": " + validator.events);
            assertEquals(hereContainers.get(0), hereContainers.get(1),
                    kind.name() + ": an offer and a take through one slot must agree");
            assertEquals(thereContainers.get(0), thereContainers.get(1),
                    kind.name() + ": an offer and a take through the sibling slot must agree");
            assertTrue(!hereContainers.get(0).equals(thereContainers.get(0)),
                    kind.name() + ": an offer into one slot must not match a take out of its "
                            + "sibling, or the wrong thread is named the owner; " + validator.events);
        }
    }

    private static List<Integer> containersOf(RecordingValidator validator, Chunk chunk) {
        int identity = System.identityHashCode(chunk);
        synchronized (validator.events) {
            return validator.events.stream().filter(e -> e.identity() == identity)
                    .map(Event::container).toList();
        }
    }

    @Test
    @DisplayName("BlockingQueue.take is a take out of that queue, and drainTo drops the queue's offers (#664)")
    void blockingTakeAndDrainArePublished() throws Exception {
        OfferedChunkBean bean = new OfferedChunkBean();
        RecordingValidator validator = new RecordingValidator();
        Chunk first = OfferedChunkBean.newChunk();
        Chunk second = OfferedChunkBean.newChunk();
        try (Actors actors = new Actors(); TelemetryBridge bridge =
                TelemetryBridge.activateWithFilter(validator, actors.ids::contains)) {
            actors.run(1, () -> bean.offerToBlocking(first));
            assertSame(first, actors.run(2, bean::takeFromBlocking));
            actors.run(1, () -> bean.offerToBlocking(second));
            assertEquals(List.of(second), actors.run(2, bean::drainBlocking));
            actors.run(1, () -> bean.offerToBlocking(second));
            assertEquals(List.of(second), actors.run(2, () -> bean.drainBlockingAtMost(1)));
            TelemetryRegistry.flush();
        }
        int queue = System.identityHashCode(bean.blocking());
        assertTrue(validator.ofKind("taken").stream().anyMatch(e ->
                        e.identity() == System.identityHashCode(first) && e.container() == queue),
                "take() hands the element to one thread, like poll(); " + validator.events);
        assertEquals(2, validator.ofKind("drained").stream().filter(e -> e.container() == queue).count(),
                "both drainTo forms must publish the drained queue; " + validator.events);
    }

    @Test
    @DisplayName("removeIf on a queue names no element, so it drops the queue's offers like drainTo (#692)")
    void removeIfOnAQueueIsPublishedAsADrain() throws Exception {
        OfferedChunkBean bean = new OfferedChunkBean();
        RecordingValidator validator = new RecordingValidator();
        try (Actors actors = new Actors(); TelemetryBridge bridge =
                TelemetryBridge.activateWithFilter(validator, actors.ids::contains)) {
            actors.run(1, () -> bean.offerToPlain(OfferedChunkBean.newChunk()));
            assertTrue(actors.run(2, bean::removeEveryChunkFromPlain));
            TelemetryRegistry.flush();
        }
        int queue = System.identityHashCode(bean.plain());
        assertEquals(1, validator.ofKind("drained").stream().filter(e -> e.container() == queue).count(),
                "removeIf must publish the queue it emptied; " + validator.events);
    }

    // ---- End to end: who owns a take-first generation ------------------------------------------

    @TestFactory
    @DisplayName("an alias writing a chunk handed over through a slot fires (#664)")
    Stream<DynamicTest> aliasInASlotHandOffFires() {
        return shapes().stream().map(shape -> DynamicTest.dynamicTest(shape.name(), () ->
                assertTrue(handOffWithLateWriter(shape, 3).hasIssues(),
                        shape.name() + ": actor 1 offered the chunk and actor 2 took it; actor 3, "
                                + "which did neither, wrote it under a lock actor 2 never held. "
                                + "Without the offer's container the take-first generation "
                                + "excuses every thread (#557)")));
    }

    @TestFactory
    @DisplayName("the offerer's own late write to a chunk handed over through a slot stays silent (#557, #664)")
    Stream<DynamicTest> offerersLateWriteInASlotHandOffStaysSilent() {
        return shapes().stream().map(shape -> DynamicTest.dynamicTest(shape.name(), () -> {
            AtomicityValidator.AtomicityReport report = handOffWithLateWriter(shape, 1);
            assertFalse(report.hasIssues(), shape.name() + ": the late write is the offerer's, "
                    + "a hand-off. Findings: " + report.unsafeFieldAccesses + report.totcouRaces);
        }));
    }

    /**
     * Actor 1 offers a fresh chunk, actor 2 takes it and writes it unlocked, {@code lateWriter}
     * writes it under a lock, then actors 4 and 5 each take it and write it, 5 in a later round.
     */
    private static AtomicityValidator.AtomicityReport handOffWithLateWriter(Shape shape,
                                                                            int lateWriter)
            throws Exception {
        OfferedChunkBean bean = new OfferedChunkBean();
        AtomicityValidator validator = new AtomicityValidator();
        try (Actors actors = new Actors(); TelemetryBridge bridge =
                TelemetryBridge.activateWithFilter(validator, actors.ids::contains)) {
            validator.markInvocationStart();
            Chunk chunk = OfferedChunkBean.newChunk();
            actors.run(1, () -> shape.offer().apply(bean, chunk));
            Chunk taken = actors.run(2, () -> shape.take().apply(bean));
            assertSame(chunk, taken);
            actors.run(2, () -> { OfferedChunkBean.write(taken); return null; });
            actors.run(lateWriter, () -> { bean.writeLocked(taken); return null; });
            takeAndWrite(actors, 4, shape, bean, taken);
            TelemetryRegistry.flush();
            validator.markInvocationStart();
            takeAndWrite(actors, 5, shape, bean, taken);
            TelemetryRegistry.flush();
        }
        return validator.analyzeAtomicity();
    }

    private static void takeAndWrite(Actors actors, int actor, Shape shape, OfferedChunkBean bean,
                                     Chunk chunk) throws Exception {
        actors.run(actor, () -> {
            shape.offer().apply(bean, chunk);
            Chunk again = shape.take().apply(bean);
            assertSame(chunk, again);
            OfferedChunkBean.write(again);
            return null;
        });
    }

    // ---- End to end: a stale offer after an unobserved put-back --------------------------------

    @Test
    @DisplayName("a chunk taken out of a BlockingQueue and put back by that thread keeps its late write silent (#664)")
    void staleOfferAfterATakeAndPutBackStaysSilent() throws Exception {
        AtomicityValidator.AtomicityReport report = staleOfferThenLateWrite(bean -> {
            try {
                return List.of(bean.takeFromBlocking());
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }, false);
        assertFalse(report.hasIssues(), "actor 3 took the chunk and put it back through an unwoven "
                + "add; its late write is a hand-off, and actor 1's offer must not outlive the take. "
                + "Findings: " + report.unsafeFieldAccesses + report.totcouRaces);
    }

    @Test
    @DisplayName("a chunk drained out of a BlockingQueue and put back by that thread keeps its late write silent (#664)")
    void staleOfferAfterADrainAndPutBackStaysSilent() throws Exception {
        AtomicityValidator.AtomicityReport report =
                staleOfferThenLateWrite(OfferedChunkBean::drainBlocking, false);
        assertFalse(report.hasIssues(), "actor 3 drained the chunk and put it back through an "
                + "unwoven add; the drain made actor 1's offer stale. Findings: "
                + report.unsafeFieldAccesses + report.totcouRaces);
    }

    @Test
    @DisplayName("an offer made after the drain still names the owner, so an alias still fires (#664)")
    void offerAfterTheDrainStillNamesTheOwner() throws Exception {
        assertTrue(staleOfferThenLateWrite(OfferedChunkBean::drainBlocking, true).hasIssues(),
                "actor 1 offered the chunk again after actor 3's drain, so actor 1 owns generation "
                        + "0 and actor 3's write, under a lock actor 2 never held, is an alias");
    }

    /**
     * Actor 1 offers a fresh chunk to the blocking queue; actor 3 removes it with {@code remove}
     * and puts it back with an unwoven {@code add} (or, when {@code reOffer}, actor 1 offers it
     * again through the woven offer); actor 2 polls and writes it; actor 3 writes it under a lock;
     * actors 4 and 5 poll and write it.
     */
    private static AtomicityValidator.AtomicityReport staleOfferThenLateWrite(
            Function<OfferedChunkBean, List<Chunk>> remove, boolean reOffer) throws Exception {
        OfferedChunkBean bean = new OfferedChunkBean();
        AtomicityValidator validator = new AtomicityValidator();
        try (Actors actors = new Actors(); TelemetryBridge bridge =
                TelemetryBridge.activateWithFilter(validator, actors.ids::contains)) {
            validator.markInvocationStart();
            Chunk chunk = OfferedChunkBean.newChunk();
            actors.run(1, () -> bean.offerToBlocking(chunk));
            actors.run(3, () -> {
                assertEquals(List.of(chunk), remove.apply(bean));
                if (!reOffer) {
                    bean.blocking().add(chunk); // this test class is not woven: an unobserved add
                }
                return null;
            });
            if (reOffer) {
                actors.run(1, () -> bean.offerToBlocking(chunk));
            }
            Chunk polled = actors.run(2, bean::pollBlocking);
            assertSame(chunk, polled);
            actors.run(2, () -> { OfferedChunkBean.write(polled); return null; });
            actors.run(3, () -> { bean.writeLocked(polled); return null; });
            pollAndWrite(actors, 4, bean, chunk);
            TelemetryRegistry.flush();
            validator.markInvocationStart();
            pollAndWrite(actors, 5, bean, chunk);
            TelemetryRegistry.flush();
        }
        return validator.analyzeAtomicity();
    }

    private static void pollAndWrite(Actors actors, int actor, OfferedChunkBean bean, Chunk chunk)
            throws Exception {
        actors.run(actor, () -> {
            bean.blocking().add(chunk);
            Chunk again = bean.pollBlocking();
            assertSame(chunk, again);
            OfferedChunkBean.write(again);
            return null;
        });
    }

    /**
     * Numbered actors, each one thread for the life of a case, run one step at a time.
     *
     * <p>A step completes before the next starts, so the order the steps publish in is the order
     * the drain sees. Every actor thread registers as a worker before its first step runs.
     */
    private static final class Actors implements AutoCloseable {
        final Set<Long> ids = ConcurrentHashMap.newKeySet();
        private final java.util.Map<Integer, ExecutorService> threads = new java.util.HashMap<>();

        <T> T run(int actor, Callable<T> step) throws Exception {
            ExecutorService thread = threads.computeIfAbsent(actor,
                    ignored -> Executors.newSingleThreadExecutor(r -> new Thread(r, "actor-" + actor)));
            return thread.submit(() -> {
                ids.add(Thread.currentThread().threadId());
                return step.call();
            }).get(10, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            threads.values().forEach(ExecutorService::shutdownNow);
        }
    }
}
