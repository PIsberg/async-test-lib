package se.deversity.asynctest.telemetry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import se.deversity.asynctest.diagnostics.AtomicityValidator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryBridgeTest {

    // Synthetic worker-thread ids. Using ids that are not real live threads makes the
    // filtering assertions deterministic regardless of the current thread's id.
    private static final long WORKER_A = 900_001L;
    private static final long WORKER_B = 900_002L;
    private static final long WORKER_C = 900_003L;
    private static final long WORKER_D = 900_004L;
    private static final long NON_WORKER = 700_007L;

    @BeforeEach
    @AfterEach
    void cleanup() {
        // Never leave the registry running between tests (mirrors TelemetryRegistryTest).
        TelemetryRegistry.stop();
    }

    @Test
    void droppedNonWorkerEventsAreCountedSoTheGapCanBeAnnounced() {
        AtomicityValidator av = new AtomicityValidator();
        try (TelemetryBridge bridge =
                     TelemetryBridge.activate(av, Set.of(WORKER_A, WORKER_B))) {
            assertEquals(0L, bridge.droppedNonWorkerEvents(), "nothing dropped yet");

            bridge.onEvent(WORKER_A, "com.example.Account.balance", false);
            assertEquals(0L, bridge.droppedNonWorkerEvents(), "a worker's access is forwarded");

            // The shape the count exists for: a body that started its own thread.
            bridge.onEvent(NON_WORKER, "com.example.Account.balance", false);
            bridge.onEvent(NON_WORKER, "com.example.Account.balance", true);

            assertEquals(2L, bridge.droppedNonWorkerEvents(),
                    "dropping is right - the bridge cannot attribute these to a round - but the "
                        + "count is what lets the runner say so, instead of a clean atomicity "
                        + "report silently meaning 'not observed'");
        }
    }

    // ---- Threads the body hands work to (#745) ----------------------------------------------

    private static final long CHILD = 800_001L;
    private static final long GRANDCHILD = 800_002L;
    private static final long POOL = 800_003L;

    private static void started(TelemetryBridge bridge, long parent, long child) {
        bridge.onEvent(parent, TelemetryRegistry.THREAD_STARTING, false, child);
    }

    private static void submitted(TelemetryBridge bridge, long submitter, long token) {
        bridge.onEvent(submitter, TelemetryRegistry.TASK_SUBMITTED, false, token);
    }

    private static void taskStarted(TelemetryBridge bridge, long thread, long token) {
        bridge.onEvent(thread, TelemetryRegistry.TASK_STARTED, false, token);
    }

    private static void taskEnded(TelemetryBridge bridge, long thread) {
        bridge.onEvent(thread, TelemetryRegistry.TASK_ENDED, false, 0L);
    }

    /** A task start that says how many handed tasks the thread was already running (#834). */
    private static void taskStarted(TelemetryBridge bridge, long thread, long token, int depth) {
        bridge.onEvent(thread, TelemetryRegistry.TASK_STARTED, false, token, false, depth);
    }

    /** A task end that says how many handed tasks the thread is still running (#834). */
    private static void taskEnded(TelemetryBridge bridge, long thread, int depthAfter) {
        bridge.onEvent(thread, TelemetryRegistry.TASK_ENDED, false, 0L, false, depthAfter);
    }

    /** {@return whether {@code thread}'s access reached the bridge's validator} */
    private static boolean forwarded(TelemetryBridge bridge, long thread) {
        long before = bridge.droppedNonWorkerEvents();
        bridge.onEvent(thread, "com.example.Probe.value", false);
        return bridge.droppedNonWorkerEvents() == before;
    }

    @Test
    void aThreadAWorkerStartsIsPartOfTheRunAndSoAreItsOwn() {
        AtomicityValidator av = new AtomicityValidator();
        try (TelemetryBridge bridge = TelemetryBridge.activate(av, Set.of(WORKER_A))) {
            started(bridge, WORKER_A, CHILD);
            bridge.onEvent(CHILD, "com.example.Account.balance", true);
            bridge.onEvent(WORKER_A, "com.example.Account.balance", false);
            started(bridge, CHILD, GRANDCHILD);

            assertTrue(forwarded(bridge, GRANDCHILD), "a thread the child started works for the run too");
            assertEquals(0L, bridge.droppedNonWorkerEvents(),
                    "the start events are not accesses, and every access came from the run");
        }
        assertTrue(av.analyzeAtomicity().unsafeFieldAccesses.stream()
                        .anyMatch(s -> s.contains("com.example.Account.balance")),
                "the child's write and the worker's read, nothing ordering them, are a race between "
                        + "the body and the thread it spawned");
    }

    @Test
    void aThreadStartedOutsideTheRunStaysOutside() {
        try (TelemetryBridge bridge = TelemetryBridge.activate(new AtomicityValidator(), Set.of(WORKER_A))) {
            started(bridge, NON_WORKER, CHILD);

            assertFalse(forwarded(bridge, CHILD),
                    "a thread started by a thread the run does not own is not the run's");
        }
    }

    @Test
    void aThreadStartedInOneRunIsNotPartOfTheNext() {
        try (TelemetryBridge first = TelemetryBridge.activate(new AtomicityValidator(), Set.of(WORKER_A))) {
            started(first, WORKER_A, CHILD);
            assertTrue(forwarded(first, CHILD), "precondition: the first run owns its child");
        }
        try (TelemetryBridge next = TelemetryBridge.activate(new AtomicityValidator(), Set.of(WORKER_B))) {
            assertFalse(forwarded(next, CHILD),
                    "a child that outlives the run that started it must contribute nothing to the "
                            + "next run: that run never saw it start");
        }
    }

    @Test
    void aPoolThreadIsPartOfTheRunOnlyWhileItRunsTheRunsTask() {
        try (TelemetryBridge bridge = TelemetryBridge.activate(new AtomicityValidator(), Set.of(WORKER_A))) {
            assertFalse(forwarded(bridge, POOL), "precondition: a pool thread is nobody's");

            submitted(bridge, WORKER_A, 7L);
            taskStarted(bridge, POOL, 7L);
            assertTrue(forwarded(bridge, POOL), "running a task a worker submitted");
            taskEnded(bridge, POOL);
            assertFalse(forwarded(bridge, POOL), "the same thread after that task ended");

            submitted(bridge, NON_WORKER, 8L);
            taskStarted(bridge, POOL, 8L);
            assertFalse(forwarded(bridge, POOL), "running a task submitted from outside the run");
            taskEnded(bridge, POOL);

            taskStarted(bridge, POOL, 7L);
            assertFalse(forwarded(bridge, POOL), "a token is taken up once");
            taskEnded(bridge, POOL);
        }
    }

    @Test
    void aTaskInsideATaskIsJudgedOnItsOwn() {
        try (TelemetryBridge bridge = TelemetryBridge.activate(new AtomicityValidator(), Set.of(WORKER_A))) {
            submitted(bridge, WORKER_A, 1L);
            taskStarted(bridge, POOL, 1L);
            taskStarted(bridge, POOL, 2L);
            assertFalse(forwarded(bridge, POOL),
                    "a fork-join worker helping with somebody else's task inside the run's");
            submitted(bridge, POOL, 3L);
            taskEnded(bridge, POOL);
            assertTrue(forwarded(bridge, POOL), "back in the run's task");
            submitted(bridge, POOL, 4L);
            taskEnded(bridge, POOL);
            assertFalse(forwarded(bridge, POOL), "after both");

            taskStarted(bridge, CHILD, 3L);
            assertFalse(forwarded(bridge, CHILD),
                    "submitted from the task that was not the run's, while it ran");
            taskEnded(bridge, CHILD);
            taskStarted(bridge, CHILD, 4L);
            assertTrue(forwarded(bridge, CHILD),
                    "submitted from the run's task while it ran, begun after that task ended");
            taskEnded(bridge, CHILD);
        }
    }

    @Test
    void aDroppedTaskEndIsRepairedByTheThreadsNextTaskStart() {
        try (TelemetryBridge bridge = TelemetryBridge.activate(new AtomicityValidator(), Set.of(WORKER_A))) {
            submitted(bridge, WORKER_A, 7L);
            taskStarted(bridge, POOL, 7L, 0);
            assertTrue(forwarded(bridge, POOL), "precondition: running the run's task");
            // The end of task 7 never drains: the ring gave up on it (#834).
            submitted(bridge, NON_WORKER, 8L);
            taskStarted(bridge, POOL, 8L, 0);
            assertFalse(forwarded(bridge, POOL), "running a task from outside the run");
            taskEnded(bridge, POOL, 0);
            assertFalse(forwarded(bridge, POOL),
                    "task 8 began with nothing running under it, so task 7 had ended: a lost end "
                            + "must not leave the pool thread working for the run between tasks");
        }
    }

    @Test
    void aTaskEndSaysHowManyTasksTheThreadIsStillRunning() {
        try (TelemetryBridge bridge = TelemetryBridge.activate(new AtomicityValidator(), Set.of(WORKER_A))) {
            submitted(bridge, NON_WORKER, 1L);
            taskStarted(bridge, POOL, 1L, 0);
            submitted(bridge, WORKER_A, 2L);
            taskStarted(bridge, POOL, 2L, 1);
            assertTrue(forwarded(bridge, POOL), "precondition: the run's task inside another");
            taskEnded(bridge, POOL, 1);
            assertFalse(forwarded(bridge, POOL), "back in the outer task, which is not the run's");
            submitted(bridge, WORKER_A, 3L);
            taskStarted(bridge, POOL, 3L, 1);
            submitted(bridge, NON_WORKER, 4L);
            taskStarted(bridge, POOL, 4L, 2);
            // The end of task 4 is lost; task 3's end says the thread is one deep again.
            taskEnded(bridge, POOL, 1);
            assertFalse(forwarded(bridge, POOL),
                    "task 3 ended with only task 1 still running, so the lost end of task 4 must not "
                            + "leave task 3 looking current");
            taskEnded(bridge, POOL, 0);
            assertFalse(forwarded(bridge, POOL), "after every task");
        }
    }

    @Test
    void forwardsWorkerEventsAndDropsNonWorkerEvents() {
        AtomicityValidator av = new AtomicityValidator();
        try (TelemetryBridge bridge =
                     TelemetryBridge.activate(av, Set.of(WORKER_A, WORKER_B))) {

            // A mixed read/write on the SAME identifier across two worker threads is the
            // signal AtomicityValidator surfaces as a cross-thread hazard.
            bridge.onEvent(WORKER_A, "com.example.Account.balance", false); // read
            bridge.onEvent(WORKER_B, "com.example.Account.balance", true);  // write

            AtomicityValidator.AtomicityReport report = av.analyzeAtomicity();
            assertTrue(report.hasIssues(),
                    "Worker-thread events must be forwarded to the detector");
            assertTrue(report.unsafeFieldAccesses.stream()
                            .anyMatch(s -> s.contains("com.example.Account.balance")),
                    "The forwarded field must appear in the cross-thread report");
        }

        // A second detector fed only by non-worker events must see nothing.
        AtomicityValidator filtered = new AtomicityValidator();
        try (TelemetryBridge bridge =
                     TelemetryBridge.activate(filtered, Set.of(WORKER_A, WORKER_B))) {
            bridge.onEvent(NON_WORKER, "com.example.Account.balance", false);
            bridge.onEvent(NON_WORKER, "com.example.Account.balance", true);
        }
        assertFalse(filtered.analyzeAtomicity().hasIssues(),
                "Events from non-worker threads must be dropped");
    }

    @Test
    void endToEndThroughRegistry() throws InterruptedException {
        AtomicityValidator av = new AtomicityValidator();
        try (TelemetryBridge ignored =
                     TelemetryBridge.activate(av, Set.of(WORKER_A, WORKER_B))) {

            // Publish agent-style events; the registry drain thread (1 ms) forwards them
            // through the bridge into the detector.
            TelemetryRegistry.recordAccess(WORKER_A, "com.example.Order.total", false);
            TelemetryRegistry.recordAccess(WORKER_B, "com.example.Order.total", true);

            assertTrue(awaitIssues(av, 2, TimeUnit.SECONDS),
                    "Detector should observe the drained accesses within the drain window");
            assertTrue(av.analyzeAtomicity().totcouRaces.stream()
                            .anyMatch(s -> s.contains("com.example.Order.total")),
                    "The drained field must be attributed as a cross-thread race");
        }
    }

    /** The weaver's view of a plain field published by a volatile flag. */
    static final class Pub {
        int data;
        volatile boolean ready;
    }

    /**
     * Replays the woven volatile-flag idiom through the real ring on two real threads, each side
     * emitting what the weaver emits: the access record, and the volatile hook with the value.
     *
     * @param readyRead what the reader's volatile read of {@code ready} returned, 1 for the value
     *                  the writer stored
     */
    private static boolean volatileFlagThroughTheRing(int readyRead)
            throws InterruptedException {
        AtomicityValidator av = new AtomicityValidator();
        Pub pub = new Pub();
        try (TelemetryBridge ignored = TelemetryBridge.activateWithFilter(av, id -> true)) {
            Thread writer = new Thread(() -> {
                long me = Thread.currentThread().threadId();
                TelemetryRegistry.recordAccess(pub, null, null, me, "Pub.data", true, false,
                        Integer.MIN_VALUE, false, false);
                TelemetryRegistry.recordAccess(pub, null, null, me, "Pub.ready", true, true, 1,
                        false, false);
                TelemetryRegistry.volatileStore(pub, 1, "Pub.ready");
            });
            writer.start();
            writer.join();
            Thread reader = new Thread(() -> {
                long me = Thread.currentThread().threadId();
                TelemetryRegistry.recordAccess(pub, null, null, me, "Pub.ready", false, true,
                        Integer.MIN_VALUE, false, false);
                TelemetryRegistry.volatileLoad(pub, readyRead, "Pub.ready");
                TelemetryRegistry.recordAccess(pub, null, null, me, "Pub.data", false, false,
                        Integer.MIN_VALUE, true, false);
            });
            reader.start();
            reader.join();
            TelemetryRegistry.flush();
            return av.analyzeAtomicity().unsafeFieldAccesses.stream()
                    .anyMatch(line -> line.startsWith("Pub.data"));
        }
    }

    @Test
    void aVolatileFlagPublishedThroughTheRingIsOrdered() throws InterruptedException {
        assertFalse(volatileFlagThroughTheRing(1),
                "the stamps travel through the ring, so the drain sees the writer's release and "
                        + "the reader's acquire and the lock-free single writer is not reported");
        assertTrue(volatileFlagThroughTheRing(0),
                "a read that returned the value from before the write acquires nothing");
    }

    /** The weaver's view of the idiom lane's order: a mutable object handed off through a queue. */
    static final class Order {
        int quantity;
    }

    /** Runs {@code body} holding {@code lock} as a woven {@code synchronized} block does, or bare. */
    private static void under(@org.jspecify.annotations.Nullable Object lock, Runnable body) {
        if (lock == null) {
            body.run();
            return;
        }
        synchronized (lock) {
            try (var held = se.deversity.asynctest.diagnostics.HeldLocks.holding(lock)) {
                body.run();
            }
        }
    }

    /** Runs {@code body} holding {@code monitor} as a synchronized method does: unseen by HeldLocks. */
    private static void inMethod(@org.jspecify.annotations.Nullable Object monitor, Runnable body) {
        if (monitor == null) {
            body.run();
            return;
        }
        synchronized (monitor) {
            body.run();
        }
    }

    /**
     * Replays the idiom lane's queue hand-off (#751) through the real hooks and the real ring: one
     * worker builds an order and offers it, a second polls it and updates it outside any lock, one
     * after the other so the stream is the same on every run.
     *
     * @param queue     the queue the order goes through
     * @param offerLock the monitor held around the offer, {@code null} for none
     * @param pollLock  the monitor held around the poll, {@code null} for none
     * @return whether AtomicityValidator reported the order's field
     */
    private static boolean orderHandedOffThrough(java.util.Queue<Object> queue,
                                                 @org.jspecify.annotations.Nullable Object offerLock,
                                                 @org.jspecify.annotations.Nullable Object pollLock)
            throws InterruptedException {
        return orderHandedOffThrough(queue, offerLock, null, pollLock, null);
    }

    /**
     * {@link #orderHandedOffThrough(java.util.Queue, Object, Object)} with a {@code synchronized}
     * method around either side (#796): its monitor is held, never recorded in the lockset, and
     * handed to the hook the way the weaver hands it.
     *
     * @param offerMethod the monitor of a synchronized method around the offer, {@code null} for none
     * @param pollMethod  the monitor of a synchronized method around the poll, {@code null} for none
     */
    private static boolean orderHandedOffThrough(java.util.Queue<Object> queue,
                                                 @org.jspecify.annotations.Nullable Object offerLock,
                                                 @org.jspecify.annotations.Nullable Object offerMethod,
                                                 @org.jspecify.annotations.Nullable Object pollLock,
                                                 @org.jspecify.annotations.Nullable Object pollMethod)
            throws InterruptedException {
        AtomicityValidator av = new AtomicityValidator();
        Order order = new Order();
        try (TelemetryBridge ignored = TelemetryBridge.activateWithFilter(av, id -> true)) {
            Thread producer = new Thread(() -> {
                long me = Thread.currentThread().threadId();
                TelemetryRegistry.recordAccess(order, null, null, me, "Order.quantity", true,
                        false, Integer.MIN_VALUE, false, false);
                under(offerLock, () -> inMethod(offerMethod,
                        () -> se.deversity.asynctest.AgentCollectionHooks.queueOffer(queue, order, offerMethod)));
            });
            producer.start();
            producer.join();
            Thread consumer = new Thread(() -> {
                long me = Thread.currentThread().threadId();
                Object[] taken = new Object[1];
                under(pollLock, () -> inMethod(pollMethod,
                        () -> taken[0] = se.deversity.asynctest.AgentCollectionHooks.queuePoll(queue, pollMethod)));
                TelemetryRegistry.recordAccess(taken[0], null, null, me, "Order.quantity", false,
                        false, Integer.MIN_VALUE, false, false);
                TelemetryRegistry.recordAccess(taken[0], null, null, me, "Order.quantity", true,
                        false, Integer.MIN_VALUE, false, false);
            });
            consumer.start();
            consumer.join();
            TelemetryRegistry.flush();
            return av.analyzeAtomicity().unsafeFieldAccesses.stream()
                    .anyMatch(line -> line.startsWith("Order.quantity"));
        }
    }

    @Test
    void aPollFromAPlainDequeUnderADifferentLockThanTheOfferIsNotAnOwnershipHandOff()
            throws InterruptedException {
        assertTrue(orderHandedOffThrough(new java.util.ArrayDeque<>(), new Object(), new Object()),
                "the offer and the poll held two different monitors, which exclude nothing: an "
                        + "ArrayDeque orders nothing itself and can hand one order to two pollers, "
                        + "and a take recorded from it would open an ownership generation that "
                        + "excuses the race on the order (#751)");
    }

    @Test
    void aPlainDequeHandOffWithNoLockOnASideIsNotAnOwnershipHandOff() throws InterruptedException {
        Object pool = new Object();
        String why = "a side that holds no lock shares none with the other, and with a synchronized "
                + "method's monitor passed to the hooks (#796) an empty lockset is an unguarded "
                + "side, not an invisible one. An ArrayDeque can then hand one order to two "
                + "pollers, so the take is no hand-off (#751). Case: ";
        assertTrue(orderHandedOffThrough(new java.util.ArrayDeque<>(), null, null),
                why + "no lock on either side");
        assertTrue(orderHandedOffThrough(new java.util.ArrayDeque<>(), pool, null),
                why + "a lock on the offer side only");
        assertTrue(orderHandedOffThrough(new java.util.ArrayDeque<>(), null, pool),
                why + "a lock on the poll side only");
    }

    @Test
    void aPollFromAPlainDequeUnderTheOffersLockIsAnOwnershipHandOff() throws InterruptedException {
        Object pool = new Object();
        assertFalse(orderHandedOffThrough(new java.util.ArrayDeque<>(), pool, pool),
                "offer and poll both under the pool's monitor is the synchronized object pool: "
                        + "the lock serialises the deque, so the order leaves it to one thread only, "
                        + "and the consumer's unlocked use of what it took is its own (#751)");
    }

    @Test
    void aSynchronizedMethodsMonitorIsALockTheHandOffIsJudgedBy() throws InterruptedException {
        Object pool = new Object();
        assertTrue(orderHandedOffThrough(new java.util.ArrayDeque<>(), null, pool, new Object(), null),
                "the offer ran inside a synchronized method on the pool and the poll under a lock the "
                        + "offers never take. The method's monitor reaches the hook from the weaver, "
                        + "so both sides show a lock and they share none (#796)");
        assertFalse(orderHandedOffThrough(new java.util.ArrayDeque<>(), null, pool, pool, null),
                "a synchronized-method offer and a synchronized-block poll on the same pool share its "
                        + "monitor, whichever way each side took it (#796)");
        assertFalse(orderHandedOffThrough(new java.util.ArrayDeque<>(), null, pool, null, pool),
                "both sides in synchronized methods on the pool is the synchronized object pool (#796)");
    }

    /** The StampedLock javadoc's point, reduced to the one coordinate the cases need (#740). */
    static final class Point {
        int x;
    }

    /** How the reader in {@link #optimisticReadReported} treats its optimistic stamp. */
    enum Reader {
        /** Validates, and nothing wrote in between, so the validation holds. */
        VALIDATES,
        /** Validates after a writer ran in between, so the validation fails and it re-reads. */
        VALIDATES_AND_FALLS_BACK,
        /** Validates after a writer ran in between, and retries optimistically until one holds. */
        RETRIES_UNTIL_VALID,
        /** Validates after a writer ran in between, and gives up without reading again. */
        VALIDATES_AND_GIVES_UP,
        /** Uses what it read and never validates. */
        NEVER_VALIDATES
    }

    /**
     * One writer moves the point under the write lock and one reader reads it optimistically,
     * through the real lock hooks, ring and bridge, in an order fixed by the test (#740).
     *
     * @return whether AtomicityValidator reported the point's field
     */
    private static boolean optimisticReadReported(Reader reader) throws Exception {
        AtomicityValidator av = new AtomicityValidator();
        java.util.concurrent.locks.StampedLock lock = new java.util.concurrent.locks.StampedLock();
        Point point = new Point();
        java.util.concurrent.ExecutorService readerThread =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        Runnable write = () -> {
            long me = Thread.currentThread().threadId();
            long stamp = se.deversity.asynctest.AgentLockHooks.writeLock(lock);
            try {
                TelemetryRegistry.recordAccess(point, null, null, me, "Point.x", true, false,
                        Integer.MIN_VALUE, false, false);
                point.x++;
            } finally {
                se.deversity.asynctest.AgentLockHooks.unlockWrite(lock, stamp);
            }
        };
        Runnable read = () -> TelemetryRegistry.recordAccess(point, null, null,
                Thread.currentThread().threadId(), "Point.x", false, false, Integer.MIN_VALUE,
                false, false);
        try (TelemetryBridge ignored = TelemetryBridge.activateWithFilter(av, id -> true)) {
            Thread writer = new Thread(write);
            writer.start();
            writer.join();
            long[] stamp = new long[1];
            readerThread.submit(() -> {
                stamp[0] = se.deversity.asynctest.AgentLockHooks.tryOptimisticRead(lock);
                read.run();
            }).get(10, TimeUnit.SECONDS);
            if (reader == Reader.VALIDATES_AND_FALLS_BACK || reader == Reader.RETRIES_UNTIL_VALID
                    || reader == Reader.VALIDATES_AND_GIVES_UP) {
                Thread second = new Thread(write);
                second.start();
                second.join();
            }
            if (reader == Reader.RETRIES_UNTIL_VALID) {
                readerThread.submit(() -> {
                    long current = stamp[0];
                    while (!se.deversity.asynctest.AgentLockHooks.validate(lock, current)) {
                        current = se.deversity.asynctest.AgentLockHooks.tryOptimisticRead(lock);
                        read.run();
                    }
                }).get(10, TimeUnit.SECONDS);
            } else if (reader == Reader.VALIDATES_AND_GIVES_UP) {
                readerThread.submit(() -> se.deversity.asynctest.AgentLockHooks.validate(lock, stamp[0]))
                        .get(10, TimeUnit.SECONDS);
            } else if (reader != Reader.NEVER_VALIDATES) {
                readerThread.submit(() -> {
                    if (!se.deversity.asynctest.AgentLockHooks.validate(lock, stamp[0])) {
                        long readStamp = se.deversity.asynctest.AgentLockHooks.readLock(lock);
                        try {
                            read.run();
                        } finally {
                            se.deversity.asynctest.AgentLockHooks.unlockRead(lock, readStamp);
                        }
                    }
                }).get(10, TimeUnit.SECONDS);
            }
            TelemetryRegistry.flush();
            return av.analyzeAtomicity().unsafeFieldAccesses.stream()
                    .anyMatch(line -> line.startsWith("Point.x"));
        } finally {
            readerThread.shutdownNow();
        }
    }

    @Test
    void aValidatedOptimisticReadIsAReadUnderTheLock() throws Exception {
        assertFalse(optimisticReadReported(Reader.VALIDATES),
                "the read ran between tryOptimisticRead and a validate that held, so no write lock "
                        + "was taken in between and it saw what a reader holding the lock in shared "
                        + "mode would have seen. It shares the writer's lock (#740)");
    }

    @Test
    void aFailedValidationDropsItsReadsAndTheFallbackIsGuarded() throws Exception {
        assertFalse(optimisticReadReported(Reader.VALIDATES_AND_FALLS_BACK),
                "a writer ran between the stamp and the validate, so the validate failed and the "
                        + "reader threw its speculative read away and read again under the read "
                        + "lock. The discarded read is no access, and the re-read is guarded (#740)");
    }

    @Test
    void aFailedValidationsReadsAreNoAccessesAtAll() throws Exception {
        assertFalse(optimisticReadReported(Reader.VALIDATES_AND_GIVES_UP),
                "the speculative read overlapped a writer, validate said so, and the reader "
                        + "discarded what it read and used nothing. A read nobody uses is no access; "
                        + "delivered as a plain read it would be a finding with nothing behind it, "
                        + "because no re-read under the lock confirms it (#740)");
    }

    @Test
    void aRetryLoopKeepsOnlyTheReadsOfTheValidationThatHeld() throws Exception {
        assertFalse(optimisticReadReported(Reader.RETRIES_UNTIL_VALID),
                "the first speculative read overlapped a writer and its validate failed, so the "
                        + "reader discarded it and read again optimistically until a validate held. "
                        + "Only the read that validation covered was used, and it counts as a read "
                        + "under the lock; the discarded one is no access at all (#740)");
    }

    @Test
    void anOptimisticReadNeverValidatedIsAnUnguardedRead() throws Exception {
        assertTrue(optimisticReadReported(Reader.NEVER_VALIDATES),
                "the reader used what it read under an optimistic stamp and never asked validate, "
                        + "so nothing says the read was consistent: it is a plain read racing the "
                        + "writer, and a speculation nobody closes must still reach the detector by "
                        + "the end of the run (#740)");
    }

    /**
     * The point's writer as in {@link #optimisticReadReported}, and a reader that opens a second
     * speculation on an unrelated lock while the first is still open, then validates each (#823).
     *
     * @param outerHolds whether the point's lock validates; a writer runs in between when not
     * @return whether AtomicityValidator reported the point's field
     */
    private static boolean nestedOptimisticReadReported(boolean outerHolds) throws Exception {
        AtomicityValidator av = new AtomicityValidator();
        java.util.concurrent.locks.StampedLock pointLock = new java.util.concurrent.locks.StampedLock();
        java.util.concurrent.locks.StampedLock other = new java.util.concurrent.locks.StampedLock();
        Point point = new Point();
        java.util.concurrent.ExecutorService readerThread =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        Runnable write = () -> {
            long me = Thread.currentThread().threadId();
            long stamp = se.deversity.asynctest.AgentLockHooks.writeLock(pointLock);
            try {
                TelemetryRegistry.recordAccess(point, null, null, me, "Point.x", true, false,
                        Integer.MIN_VALUE, false, false);
                point.x++;
            } finally {
                se.deversity.asynctest.AgentLockHooks.unlockWrite(pointLock, stamp);
            }
        };
        try (TelemetryBridge ignored = TelemetryBridge.activateWithFilter(av, id -> true)) {
            Thread writer = new Thread(write);
            writer.start();
            writer.join();
            long[] stamps = new long[2];
            readerThread.submit(() -> {
                stamps[0] = se.deversity.asynctest.AgentLockHooks.tryOptimisticRead(pointLock);
                TelemetryRegistry.recordAccess(point, null, null, Thread.currentThread().threadId(),
                        "Point.x", false, false, Integer.MIN_VALUE, false, false);
                stamps[1] = se.deversity.asynctest.AgentLockHooks.tryOptimisticRead(other);
                se.deversity.asynctest.AgentLockHooks.validate(other, stamps[1]);
            }).get(10, TimeUnit.SECONDS);
            if (!outerHolds) {
                Thread second = new Thread(write);
                second.start();
                second.join();
            }
            boolean[] used = new boolean[1];
            readerThread.submit(() -> {
                used[0] = se.deversity.asynctest.AgentLockHooks.validate(pointLock, stamps[0]);
            }).get(10, TimeUnit.SECONDS);
            assertEquals(outerHolds, used[0], "the premise: the point's validate holds only with no writer");
            TelemetryRegistry.flush();
            return av.analyzeAtomicity().unsafeFieldAccesses.stream()
                    .anyMatch(line -> line.startsWith("Point.x"));
        } finally {
            readerThread.shutdownNow();
        }
    }

    @Test
    void aSecondLocksSpeculationNestsInsteadOfClosingTheFirst() throws Exception {
        assertFalse(nestedOptimisticReadReported(true),
                "the point was read under its lock's optimistic stamp, a speculation on another "
                        + "lock opened and closed inside it, and the point's validate then held. The "
                        + "read is one a shared-mode reader would have made; closing the outer "
                        + "speculation when the inner one opened delivered it as a plain read (#823)");
    }

    @Test
    void aNestedSpeculationsFailedOuterValidationStillDropsItsReads() throws Exception {
        assertFalse(nestedOptimisticReadReported(false),
                "the point's validate failed, so the reader discards what it read under that "
                        + "stamp, nested speculation or not; a read nobody uses is no access (#740)");
    }

    @Test
    void aPollFromAConcurrentQueueIsAnOwnershipHandOffWithOrWithoutALock()
            throws InterruptedException {
        assertFalse(orderHandedOffThrough(new java.util.concurrent.ConcurrentLinkedQueue<>(),
                        null, null),
                "a concurrent queue orders the hand-off by its contract, and the consumer owns "
                        + "what it took");
        assertFalse(orderHandedOffThrough(new java.util.concurrent.ConcurrentLinkedQueue<>(),
                        new Object(), new Object()),
                "and locks the caller happens to hold do not change that");
    }

    /**
     * Two accesses from two workers, the first still in the ring when the next round starts, which
     * is what a {@code TelemetryRegistry.flush()} that gave up after its one-second wait leaves.
     */
    private static boolean lateDrainReported(boolean nextRoundStartsBeforeTheDrain) {
        AtomicityValidator av = new AtomicityValidator();
        TelemetryBridge bridge = TelemetryBridge.detached(av, id -> true);
        TelemetryEventBuffer ring = new TelemetryEventBuffer(16);
        Object owner = new Object();
        int identity = System.identityHashCode(owner);
        av.markInvocationStart();
        ring.publish(WORKER_A, "com.example.Slot.value", true, 0L, false, Integer.MIN_VALUE,
                identity, false, 0, 0, 0, owner, null, se.deversity.asynctest.diagnostics.HappensBefore.round());
        if (nextRoundStartsBeforeTheDrain) {
            av.markInvocationStart();
        }
        ring.publish(WORKER_B, "com.example.Slot.value", false, 0L, false, Integer.MIN_VALUE,
                identity, false, 0, 0, 0, owner, null, se.deversity.asynctest.diagnostics.HappensBefore.round());
        ring.drain(bridge);
        return av.analyzeAtomicity().hasIssues();
    }

    @Test
    void anEventDrainedAfterTheNextRoundStartedStaysInTheRoundThatProducedIt() {
        assertFalse(lateDrainReported(true),
                "the write was published in round 1 and the read in round 2; attributing both to "
                        + "the round current at drain time paired accesses the harness ordered");
        assertTrue(lateDrainReported(false), "the same two accesses in one round still race");
    }

    @Test
    void anOfferEventNamesTheOwnerBeforeATakeFirstGeneration() {
        // What the queue hooks publish (#630): the offer and the take carry the queue's identity
        // in the stored-identity slot. Only the offerer's late write is a hand-off; a write by a
        // third worker is an alias, and the bridge has to route both events for that to show.
        assertTrue(offerTakeAndWriteThroughTheBridge(WORKER_C).hasIssues(),
                "A worker that neither offered nor took the chunk wrote to it under its own lock");
        assertFalse(offerTakeAndWriteThroughTheBridge(WORKER_A).hasIssues(),
                "The late write came from the worker that offered the chunk, a hand-off (#557)");
    }

    @Test
    void aDrainedEventDropsTheOfferBeforeIt() {
        // What the BlockingQueue.drainTo hooks publish (#664): the drained queue's identity in the
        // stored-identity slot. Worker C drained the queue and put the chunk back unobserved, so
        // its late write is a hand-off; the stale offer from worker A must not name A as owner.
        AtomicityValidator av = new AtomicityValidator();
        String field = "com.example.Chunk.allocated";
        int chunk = 90;
        int queue = 7;
        try (TelemetryBridge bridge =
                     TelemetryBridge.activate(av, Set.of(WORKER_A, WORKER_B, WORKER_C, WORKER_D))) {
            av.markInvocationStart();
            bridge.onEvent(WORKER_A, TelemetryRegistry.OWNERSHIP_OFFERED, false, 0L, false,
                    Integer.MIN_VALUE, chunk, false, 0, 0, queue);
            bridge.onEvent(WORKER_C, TelemetryRegistry.OWNERSHIP_DRAINED, false, 0L, false,
                    Integer.MIN_VALUE, queue, false, 0, 0, queue);
            bridge.onEvent(WORKER_B, TelemetryRegistry.OWNERSHIP_TAKEN, false, 0L, false,
                    Integer.MIN_VALUE, chunk, false, 0, 0, queue);
            bridge.onEvent(WORKER_B, field, true, 0L, false, Integer.MIN_VALUE, chunk, false,
                    0, 0, 0);
            bridge.onEvent(WORKER_C, field, true, 0x1111L, false, Integer.MIN_VALUE, chunk,
                    false, 0, 0, 0);
            bridge.onEvent(WORKER_D, TelemetryRegistry.OWNERSHIP_TAKEN, false, 0L, false,
                    Integer.MIN_VALUE, chunk, false, 0, 0, queue);
            bridge.onEvent(WORKER_D, field, true, 0L, false, Integer.MIN_VALUE, chunk, false,
                    0, 0, 0);
            av.markInvocationStart();
            bridge.onEvent(WORKER_B, TelemetryRegistry.OWNERSHIP_TAKEN, false, 0L, false,
                    Integer.MIN_VALUE, chunk, false, 0, 0, queue);
            bridge.onEvent(WORKER_B, field, true, 0L, false, Integer.MIN_VALUE, chunk, false,
                    0, 0, 0);
        }
        assertFalse(av.analyzeAtomicity().hasIssues(),
                "The drain dropped worker A's offer, so the take-first generation keeps the #557 "
                        + "excuse and worker C's write is not an alias. Without the drained event "
                        + "this is the stream anOfferEventNamesTheOwnerBeforeATakeFirstGeneration "
                        + "reports");
    }

    private static AtomicityValidator.AtomicityReport offerTakeAndWriteThroughTheBridge(
            long lateWriter) {
        AtomicityValidator av = new AtomicityValidator();
        String field = "com.example.Chunk.allocated";
        int chunk = 90;
        int queue = 7;
        try (TelemetryBridge bridge =
                     TelemetryBridge.activate(av, Set.of(WORKER_A, WORKER_B, WORKER_C, WORKER_D))) {
            av.markInvocationStart();
            bridge.onEvent(WORKER_A, TelemetryRegistry.OWNERSHIP_OFFERED, false, 0L, false,
                    Integer.MIN_VALUE, chunk, false, 0, 0, queue);
            bridge.onEvent(WORKER_B, TelemetryRegistry.OWNERSHIP_TAKEN, false, 0L, false,
                    Integer.MIN_VALUE, chunk, false, 0, 0, queue);
            bridge.onEvent(WORKER_B, field, true, 0L, false, Integer.MIN_VALUE, chunk, false,
                    0, 0, 0);
            bridge.onEvent(lateWriter, field, true, 0x1111L, false, Integer.MIN_VALUE, chunk,
                    false, 0, 0, 0);
            bridge.onEvent(WORKER_D, TelemetryRegistry.OWNERSHIP_TAKEN, false, 0L, false,
                    Integer.MIN_VALUE, chunk, false, 0, 0, queue);
            bridge.onEvent(WORKER_D, field, true, 0L, false, Integer.MIN_VALUE, chunk, false,
                    0, 0, 0);
            av.markInvocationStart();
            bridge.onEvent(WORKER_B, TelemetryRegistry.OWNERSHIP_TAKEN, false, 0L, false,
                    Integer.MIN_VALUE, chunk, false, 0, 0, queue);
            bridge.onEvent(WORKER_B, field, true, 0L, false, Integer.MIN_VALUE, chunk, false,
                    0, 0, 0);
        }
        return av.analyzeAtomicity();
    }

    @Test
    void closeIsIdempotentAndStopsForwarding() {
        AtomicityValidator av = new AtomicityValidator();
        TelemetryBridge bridge =
                TelemetryBridge.activate(av, Set.of(WORKER_A, WORKER_B));

        bridge.close();
        // Second/extra close() and the deactivate() alias must not throw.
        bridge.close();
        bridge.deactivate();

        // After close, further events (even from worker threads) are not forwarded.
        bridge.onEvent(WORKER_A, "com.example.Cart.items", false);
        bridge.onEvent(WORKER_B, "com.example.Cart.items", true);

        assertFalse(av.analyzeAtomicity().hasIssues(),
                "A closed bridge must not forward events to the detector");
    }

    @Test
    void attributesEventsToOriginatingThreadNotDrainThread() {
        // The callback runs on the drain thread; if attribution used Thread.currentThread()
        // both events would collapse to one thread id and no cross-thread hazard would be
        // reported. Distinct worker ids proving up as "2 threads" confirms the explicit
        // thread-id overload is used.
        AtomicityValidator av = new AtomicityValidator();
        try (TelemetryBridge bridge =
                     TelemetryBridge.activate(av, Set.of(WORKER_A, WORKER_B))) {
            bridge.onEvent(WORKER_A, "com.example.Ledger.entry", true);
            bridge.onEvent(WORKER_B, "com.example.Ledger.entry", true);

            assertTrue(av.analyzeAtomicity().totcouRaces.stream()
                            .anyMatch(s -> s.contains("2 threads")),
                    "Events must be attributed to their two originating worker threads");
        }
    }

    @Test
    void activateRejectsNullDetectorAndNullWorkerSet() {
        assertThrows(NullPointerException.class,
                () -> TelemetryBridge.activate(null, Set.of(WORKER_A)));
        assertThrows(NullPointerException.class,
                () -> TelemetryBridge.activate(new AtomicityValidator(), null));
    }

    /**
     * Pins that a bridge closing does not silence the bridge that currently holds the slot.
     *
     * <p>The registry holds one callback, so two {@code @AsyncTest} runs in one JVM take it from
     * each other. That trade-off is documented and accepted — the loser under-reports. What was
     * not acceptable was the teardown: closing cleared the callback unconditionally, so when the
     * loser finished first it wiped the <em>winner's</em> registration. The winner then received
     * no further events for the rest of its run, its detectors saw nothing, and its test passed
     * green with nothing logged, because the drain thread was still running and so the
     * agent-absent hint could not fire either.
     *
     * <p>The assertion is deliberately about the second bridge still being live after the first
     * one closes, because that is the user-visible property: a test that was observing keeps
     * observing.
     */
    @Test
    void closingASupersededBridgeLeavesTheCurrentHoldersCallbackInPlace() {
        AtomicityValidator first = new AtomicityValidator();
        AtomicityValidator second = new AtomicityValidator();

        TelemetryBridge superseded = TelemetryBridge.activate(first, Set.of(WORKER_A));
        TelemetryBridge current = TelemetryBridge.activate(second, Set.of(WORKER_A));

        // The second activation took the slot; closing the first must not touch it.
        superseded.close();

        assertFalse(TelemetryRegistry.clearCallbackIf(superseded),
                "The superseded bridge must not have been holding the callback after the second "
                        + "activation replaced it.");
        assertTrue(TelemetryRegistry.clearCallbackIf(current),
                "The current holder's callback must survive an earlier bridge closing. It did "
                        + "not, which means close() cleared the slot unconditionally and the run "
                        + "that legitimately owned it has gone blind while still passing green.");
    }

    private static boolean awaitIssues(AtomicityValidator av, long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (av.analyzeAtomicity().hasIssues()) {
                return true;
            }
            Thread.sleep(10);
        }
        return av.analyzeAtomicity().hasIssues();
    }
}
