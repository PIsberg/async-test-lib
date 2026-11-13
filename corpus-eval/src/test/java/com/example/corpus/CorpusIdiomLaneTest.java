package com.example.corpus;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Exchanger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Phaser;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * The idiom lane: correct user-code concurrency, with every detector switched on.
 *
 * <p><strong>Why it exists.</strong> The other lanes measure documented-safe library classes and
 * pairs written around one detector. Neither measures what a user's own test body looks like: an
 * object handed through a queue, a flag published through a volatile, a counter in an
 * {@code AtomicInteger}. Those are the bodies the false positives fixed for 1.12.3 were found in,
 * by a throwaway probe, and nothing would have noticed them coming back. This lane is that probe,
 * kept and gated.
 *
 * <p><strong>How a row works.</strong> Every body is written the way a user writes it, with
 * {@code detectAll = true} and the agent attached as
 * {@code fields=true,collections=true,arrays=true}, and
 * records nothing, except where the idiom is only observable through the manual API. Those rows
 * are named in {@link Corpus#idiomManualApiRows()} with the reason, and {@link IdiomRowPremise}
 * fails the lane if any other body touches {@code AsyncTestContext}. Each correct row has a broken
 * twin, the same code with the synchronization removed or put in the wrong place, which must fire
 * its natural detector. {@link CorpusGates#checkIdiomLane} holds a correct row to nothing at
 * {@code FACT} tier or above from any detector, and to nothing at any tier from the detector the
 * row names.
 *
 * <p><strong>Why most rows share a per-round slot.</strong> A round runs the body once on each of
 * {@link #THREADS} threads, and the ordering an idiom provides is between threads of one round:
 * rounds are separated by the harness's own barrier. A row that needs one fresh shared object per
 * round, and one thread to play the writer, takes both from {@link Rounds}: a ticket counter, the
 * first ticket of each round as the writer, and an object built for that round before the run.
 * That is harness, not idiom, and it is the same in both halves of every pair.
 */
@ExtendWith(SubjectTracking.class)
class CorpusIdiomLaneTest {

    static final int THREADS = 6;
    static final int INVOCATIONS = 40;

    /** How long a reader spins for a flag before giving up, so a broken twin cannot hang. */
    private static final int SPIN_LIMIT = 200_000;

    private static final byte[] PAYLOAD = "idiom".getBytes(StandardCharsets.UTF_8);

    @BeforeAll
    static void installRecorder() {
        CorpusRecorder.install();
    }

    @AfterAll
    static void reportAndGate() throws IOException {
        COUNTING_POOL.shutdownNow();
        CorpusRecorder.uninstall();
        CorpusLane lane = CorpusLane.current();
        Path report = CorpusReport.writeIdioms(CorpusRecorder.findings(), THREADS, INVOCATIONS, lane);
        System.out.println("Corpus idiom-lane report written to " + report.toAbsolutePath());
        CorpusGates.checkIdiomLane(CorpusRecorder.findings(), lane, CorpusIdiomLaneTest.class);
    }

    // --- 1. A mutable object handed off through a BlockingQueue ------------------------------

    private static final class Order {
        String item;
        int quantity;
    }

    /**
     * A queue per round, so an order never outlives the round that built it: one left over for a
     * later round would be read as a construction that later rounds corroborate, and excused.
     */
    private static final Rounds<BlockingQueue<Order>> ORDERS = new Rounds<>(LinkedBlockingQueue::new);
    private static final Rounds<Deque<Order>> UNSAFE_ORDERS = new Rounds<>(ArrayDeque::new);

    /**
     * Half the threads build an order and put it on the queue, the other half take one and
     * update it. The taker owns what it took: the put and the take are the hand-off.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_blockingQueue_handsOffAMutableObject() {
        correct(() -> {
            Turn<BlockingQueue<Order>> turn = ORDERS.next();
            BlockingQueue<Order> orders = turn.shared();
            if (turn.ticket() % 2 == 0) {
                Order order = new Order();
                order.item = "widget";
                order.quantity = turn.ticket();
                orders.put(order);
            } else {
                Order order = orders.take();
                order.quantity++;
                use(order.item.length() + order.quantity);
            }
        });
    }

    /** The same hand-off through an ArrayDeque, which orders nothing and is not thread-safe. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_blockingQueue_handsOffThroughAPlainDeque() {
        broken(() -> {
            Turn<Deque<Order>> turn = UNSAFE_ORDERS.next();
            Deque<Order> orders = turn.shared();
            if (turn.ticket() % 2 == 0) {
                Order order = new Order();
                order.item = "widget";
                order.quantity = turn.ticket();
                orders.offer(order);
            } else {
                Order order = null;
                for (int spins = 0; order == null && spins < SPIN_LIMIT; spins++) {
                    order = orders.poll();
                }
                if (order != null) {
                    order.quantity++;
                    use(order.item.length() + order.quantity);
                }
            }
        });
    }

    // --- 2. Plain data published by a volatile flag -------------------------------------------

    private static final class Mailbox {
        int data;
        volatile boolean ready;
    }

    private static final class PlainMailbox {
        int data;
        boolean ready;
    }

    private static final Rounds<Mailbox> MAILBOXES = new Rounds<>(Mailbox::new);
    private static final Rounds<PlainMailbox> PLAIN_MAILBOXES = new Rounds<>(PlainMailbox::new);

    /**
     * The round's first thread writes plain {@code data} and then the volatile {@code ready};
     * every other thread waits for {@code ready} and then reads {@code data}. The volatile write
     * and the read that sees it are the whole ordering.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_volatileFlag_publishesPlainData() {
        correct(() -> {
            Turn<Mailbox> turn = MAILBOXES.next();
            Mailbox box = turn.shared();
            if (turn.opensTheRound()) {
                box.data = 42 + turn.ticket();
                box.ready = true;
            } else {
                for (int spins = 0; !box.ready && spins < SPIN_LIMIT; spins++) {
                    Thread.onSpinWait();
                }
                if (box.ready) {
                    use(box.data);
                }
            }
        });
    }

    /** The same code with {@code ready} declared without {@code volatile}. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_volatileFlag_plainFlagPublishesNothing() {
        broken(() -> {
            Turn<PlainMailbox> turn = PLAIN_MAILBOXES.next();
            PlainMailbox box = turn.shared();
            if (turn.opensTheRound()) {
                box.data = 42 + turn.ticket();
                box.ready = true;
            } else {
                for (int spins = 0; !box.ready && spins < SPIN_LIMIT; spins++) {
                    Thread.onSpinWait();
                }
                if (box.ready) {
                    use(box.data);
                }
            }
        });
    }

    // --- 3. A child thread's write, ordered by Thread.start and Thread.join -----------------

    private static final class Result {
        int input;
        int output;
    }

    /**
     * The parent writes the input, starts a child that computes, joins it, reads the output. The
     * child's accesses are the run's because a worker started it (#745).
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_threadStartJoin_ordersTheChildsWrite() {
        correct(() -> {
            Result result = new Result();
            result.input = (int) Thread.currentThread().threadId();
            Thread child = new Thread(() -> result.output = result.input * 2);
            child.start();
            child.join();
            use(result.output);
        });
    }

    /** The same child, with the output read before the join instead of after it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_threadStartJoin_readsBeforeTheJoin() {
        broken(() -> {
            Result result = new Result();
            result.input = (int) Thread.currentThread().threadId();
            Thread child = new Thread(() -> result.output = result.input * 2);
            child.start();
            use(result.output);
            child.join();
        });
    }
    // --- 4. An AtomicInteger counter shared by every thread ----------------------------------

    private static final class Hits {
        final AtomicInteger atomic = new AtomicInteger();
        int plain;
    }

    private static final Hits HITS = new Hits();

    /** Every thread counts into one AtomicInteger. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_atomicInteger_sharedCounter() {
        correct(() -> use(HITS.atomic.incrementAndGet()));
    }

    /** Every thread counts into one plain int, which loses updates. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_atomicInteger_plainCounterLosesUpdates() {
        broken(() -> {
            HITS.plain++;
            use(HITS.plain);
        });
    }

    // --- 5. A single lock-free writer publishing through a volatile --------------------------

    private static final class Gauge {
        volatile long latest;
    }

    private static final Rounds<Object> GAUGE_TURNS = new Rounds<>(Object::new);
    private static final Gauge GAUGE = new Gauge();
    private static final Gauge CONTENDED_GAUGE = new Gauge();

    /**
     * One writer per round bumps a volatile with a read-then-write; every other thread reads it.
     * With one writer the read-then-write cannot lose an update, and the volatile publishes it.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_singleWriter_publishesThroughAVolatile() {
        correct(() -> {
            if (GAUGE_TURNS.next().opensTheRound()) {
                GAUGE.latest = GAUGE.latest + 1;
            } else {
                use(GAUGE.latest);
            }
        });
    }

    /** The same read-then-write on the same kind of volatile, from every thread. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_singleWriter_everyThreadWrites() {
        broken(() -> {
            CONTENDED_GAUGE.latest = CONTENDED_GAUGE.latest + 1;
            use(CONTENDED_GAUGE.latest);
        });
    }

    // --- 6. A fresh object built with setters, published through a ConcurrentHashMap --------

    private static final class Profile {
        private String name;
        private int visits;

        String getName() {
            return name;
        }

        void setName(String name) {
            this.name = name;
        }

        int getVisits() {
            return visits;
        }

        void setVisits(int visits) {
            this.visits = visits;
        }
    }

    private static final Rounds<Object> PROFILE_TURNS = new Rounds<>(Object::new);
    private static final Rounds<Object> LATE_PROFILE_TURNS = new Rounds<>(Object::new);
    private static final ConcurrentMap<Integer, Profile> PROFILES = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Integer, Profile> LATE_PROFILES = new ConcurrentHashMap<>();

    /** Each thread builds a profile, puts it, and reads the one the previous ticket put. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_concurrentHashMap_publishesAFreshlyBuiltObject() {
        correct(() -> {
            int ticket = PROFILE_TURNS.next().ticket();
            Profile mine = new Profile();
            mine.setName("user-" + ticket);
            mine.setVisits(ticket);
            PROFILES.put(ticket, mine);
            Profile previous = PROFILES.get(ticket - 1);
            if (previous != null) {
                use(previous.getName().length() + previous.getVisits());
            }
        });
    }

    /** The same object, with the setters run after the put that published it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_concurrentHashMap_mutatedAfterThePut() {
        broken(() -> {
            int ticket = LATE_PROFILE_TURNS.next().ticket();
            Profile mine = new Profile();
            LATE_PROFILES.put(ticket, mine);
            mine.setName("user-" + ticket);
            mine.setVisits(ticket);
            Profile previous = LATE_PROFILES.get(ticket - 1);
            if (previous != null && previous.getName() != null) {
                use(previous.getName().length() + previous.getVisits());
            }
        });
    }

    // --- 7. Two thread-confined objects per thread --------------------------------------------

    private static final class Account {
        int balance;

        void transferTo(Account other, int amount) {
            balance -= amount;
            other.balance += amount;
        }
    }

    private static final Account SHARED_FROM = new Account();
    private static final Account SHARED_TO = new Account();

    /** Each thread opens two accounts of its own and moves money between them. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_threadConfined_twoObjectsPerThread() {
        correct(() -> {
            Account from = new Account();
            Account to = new Account();
            from.balance = 100;
            to.balance = 50;
            from.transferTo(to, 30);
            use(from.balance + to.balance);
        });
    }

    /** The same transfer between two accounts every thread shares, with no lock. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_threadConfined_twoObjectsSharedByEveryThread() {
        broken(() -> {
            SHARED_FROM.transferTo(SHARED_TO, 30);
            use(SHARED_FROM.balance + SHARED_TO.balance);
        });
    }

    // --- 8. A MessageDigest pool checked out through a BlockingQueue -------------------------

    private static final BlockingQueue<MessageDigest> DIGEST_POOL = digestPool();
    private static final BlockingQueue<MessageDigest> PEEKED_POOL = digestPool();

    /** Take a digest from the pool, use it, put it back: the pool hands each to one thread. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_messageDigestPool_checkedOutThroughAQueue() {
        correct(() -> {
            MessageDigest digest = DIGEST_POOL.take();
            try {
                digest.update(PAYLOAD);
                use(digest.digest().length);
            } finally {
                DIGEST_POOL.put(digest);
            }
        });
    }

    /** The same pool, with peek() instead of take(), so every thread uses the head digest. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_messageDigestPool_peekedByEveryThread() {
        broken(() -> {
            MessageDigest digest = PEEKED_POOL.peek();
            digest.update(PAYLOAD);
            use(digest.digest().length);
        });
    }

    // --- 9. synchronized (list) around an iterate-and-add ------------------------------------

    private static final List<Integer> SEEN = new ArrayList<>();
    private static final List<Integer> UNGUARDED_SEEN = new ArrayList<>();

    /** Walk the list and append to it, both inside the list's own monitor. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_synchronizedList_iterateAndAdd() {
        correct(() -> {
            synchronized (SEEN) {
                int sum = 0;
                for (Integer value : SEEN) {
                    sum += value;
                }
                SEEN.add(sum % 7);
                if (SEEN.size() > 32) {
                    SEEN.clear();
                }
            }
        });
    }

    /** The same walk and append with no monitor around them. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_synchronizedList_iterateAndAddUnguarded() {
        broken(() -> {
            int sum = 0;
            for (Integer value : UNGUARDED_SEEN) {
                sum += value;
            }
            UNGUARDED_SEEN.add(sum % 7);
            if (UNGUARDED_SEEN.size() > 32) {
                UNGUARDED_SEEN.clear();
            }
        });
    }

    // --- 10. A check-then-act on a ConcurrentHashMap, inside synchronized --------------------

    private static final Rounds<Object> CACHE_TURNS = new Rounds<>(Object::new);
    private static final Rounds<Object> RACED_CACHE_TURNS = new Rounds<>(Object::new);
    private static final ConcurrentMap<String, Integer> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Integer> RACED_CACHE = new ConcurrentHashMap<>();

    /**
     * Every thread of a round tries to fill the round's key once, under the map's own monitor.
     * Manual API: no agent-fed detector models a check-then-act, so the body says it made one.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_synchronizedCheckThenAct_onAConcurrentHashMap() {
        correct(() -> {
            Turn<Object> turn = CACHE_TURNS.next();
            String key = "round-" + turn.round();
            synchronized (CACHE) {
                AsyncTestContext.nonAtomicConcurrentMapUpdateDetector().recordCheckThenAct(
                        CACHE, key, "containsKey-then-put", Thread.currentThread());
                if (!CACHE.containsKey(key)) {
                    CACHE.put(key, turn.ticket());
                }
            }
        });
    }

    /** The same containsKey-then-put with no monitor, so two threads can both see it absent. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_synchronizedCheckThenAct_withoutTheMonitor() {
        broken(() -> {
            Turn<Object> turn = RACED_CACHE_TURNS.next();
            String key = "round-" + turn.round();
            AsyncTestContext.nonAtomicConcurrentMapUpdateDetector().recordCheckThenAct(
                    RACED_CACHE, key, "containsKey-then-put", Thread.currentThread());
            if (!RACED_CACHE.containsKey(key)) {
                RACED_CACHE.put(key, turn.ticket());
            }
        });
    }
    // --- 11. ThreadLocalRandom.current() on every thread -------------------------------------

    private static final class Captured {
        volatile ThreadLocalRandom random;
    }

    private static final Rounds<Captured> CAPTURES = new Rounds<>(Captured::new);

    /**
     * Every thread asks for its own generator. Manual API: ThreadLocalRandom is not woven, so the
     * body reports the obtain and the use itself.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_threadLocalRandom_currentOnEveryThread() {
        correct(() -> {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            AsyncTestContext.threadLocalRandomMisuseDetector()
                    .recordObtain(random, "per-thread", Thread.currentThread());
            AsyncTestContext.threadLocalRandomMisuseDetector()
                    .recordUse(random, Thread.currentThread());
            use(random.nextInt(100));
        });
    }

    /** The round's first thread captures current() and every other thread uses its capture. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_threadLocalRandom_capturedByOneThread() {
        broken(() -> {
            Turn<Captured> turn = CAPTURES.next();
            Captured captured = turn.shared();
            if (turn.opensTheRound()) {
                ThreadLocalRandom mine = ThreadLocalRandom.current();
                AsyncTestContext.threadLocalRandomMisuseDetector()
                        .recordObtain(mine, "captured", Thread.currentThread());
                captured.random = mine;
            }
            ThreadLocalRandom random = captured.random;
            for (int spins = 0; random == null && spins < SPIN_LIMIT; spins++) {
                Thread.onSpinWait();
                random = captured.random;
            }
            if (random != null) {
                AsyncTestContext.threadLocalRandomMisuseDetector()
                        .recordUse(random, Thread.currentThread());
                use(random.nextInt(100));
            }
        });
    }

    // --- 12. A guarded wait loop with a notifier ----------------------------------------------

    private static final class Gate {
        boolean open;
    }

    private static final Rounds<Gate> GATES = new Rounds<>(Gate::new);
    private static final Rounds<Gate> IF_GATES = new Rounds<>(Gate::new);

    /** The round's first thread opens the gate and notifies; the others wait in a loop for it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_guardedWait_loopsOnTheCondition() {
        correct(() -> {
            Turn<Gate> turn = GATES.next();
            Gate gate = turn.shared();
            synchronized (gate) {
                if (turn.opensTheRound()) {
                    gate.open = true;
                    gate.notifyAll();
                } else {
                    while (!gate.open) {
                        gate.wait();
                    }
                }
            }
        });
    }

    /**
     * The same monitor with the condition taken out: the notifier only notifies, and each waiter
     * waits once with nothing to re-test. A notify that lands before a waiter arrives is lost, and
     * that waiter waits out its timeout. The timeout is the only change beyond the missing
     * condition, and it is there so the bug cannot hang the run.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_guardedWait_waitsWithNoCondition() {
        broken(() -> {
            Turn<Gate> turn = IF_GATES.next();
            Gate gate = turn.shared();
            synchronized (gate) {
                if (turn.opensTheRound()) {
                    gate.notifyAll();
                } else {
                    gate.wait(20);
                }
            }
        });
    }

    // --- 13. A CountDownLatch publication -----------------------------------------------------

    private static final class Delivery {
        int data;
        final CountDownLatch delivered = new CountDownLatch(1);
    }

    private static final Rounds<Delivery> DELIVERIES = new Rounds<>(Delivery::new);
    private static final Rounds<Delivery> UNAWAITED_DELIVERIES = new Rounds<>(Delivery::new);

    /** The round's first thread writes and counts down; the others await and then read. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_countDownLatch_publishesBeforeTheCountDown() {
        correct(() -> {
            Turn<Delivery> turn = DELIVERIES.next();
            Delivery delivery = turn.shared();
            if (turn.opensTheRound()) {
                delivery.data = 42 + turn.ticket();
                delivery.delivered.countDown();
            } else {
                delivery.delivered.await();
                use(delivery.data);
            }
        });
    }

    /** The same write and count-down, with the readers not waiting for it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_countDownLatch_readersSkipTheAwait() {
        broken(() -> {
            Turn<Delivery> turn = UNAWAITED_DELIVERIES.next();
            Delivery delivery = turn.shared();
            if (turn.opensTheRound()) {
                delivery.data = 42 + turn.ticket();
                delivery.delivered.countDown();
            } else {
                use(delivery.data);
            }
        });
    }

    // --- 14. A shared java.util.Random ----------------------------------------------------------

    private static final Random SHARED_RANDOM = new Random(42);
    private static final SplittableRandom SHARED_SPLITTABLE = new SplittableRandom(42);

    /**
     * Every thread draws from one Random, which is thread-safe and contended. Manual API: Random
     * is not woven, so the body reports the draw itself.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_sharedRandom_drawnByEveryThread() {
        correct(() -> {
            AsyncTestContext.sharedRandomDetector()
                    .recordRandomAccess(SHARED_RANDOM, "shared-random", "nextInt");
            use(SHARED_RANDOM.nextInt(100));
        });
    }

    /** The same draw from one SplittableRandom, whose javadoc says it is not thread-safe. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_sharedRandom_splittableDrawnByEveryThread() {
        broken(() -> {
            AsyncTestContext.sharedSplittableRandomDetector()
                    .recordAccess(SHARED_SPLITTABLE, "shared-splittable", "nextInt");
            use(SHARED_SPLITTABLE.nextInt(100));
        });
    }
    // --- Hand-offs the happens-before model learned in #741: completion, submission, exchange --

    private static final class Promise {
        int data;
        final CompletableFuture<Promise> done = new CompletableFuture<>();
    }

    private static final Rounds<Promise> PROMISES = new Rounds<>(Promise::new);
    private static final Rounds<Promise> UNJOINED_PROMISES = new Rounds<>(Promise::new);

    /** The round's first thread writes and completes a future; the others join it and read. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_completableFuture_publishesThroughCompletion() {
        correct(() -> {
            Turn<Promise> turn = PROMISES.next();
            Promise promise = turn.shared();
            if (turn.opensTheRound()) {
                promise.data = 42 + turn.ticket();
                promise.done.complete(promise);
            } else {
                use(promise.done.join().data);
            }
        });
    }

    /** The same completion, with the readers not joining it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_completableFuture_readersSkipTheJoin() {
        broken(() -> {
            Turn<Promise> turn = UNJOINED_PROMISES.next();
            Promise promise = turn.shared();
            if (turn.opensTheRound()) {
                promise.data = 42 + turn.ticket();
                promise.done.complete(promise);
            } else {
                use(promise.data);
            }
        });
    }

    /**
     * Submit a task that reads the input and writes the output, get() it, read the output. The
     * pool thread's accesses are the run's while it runs a task a worker submitted (#745).
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_executorSubmit_futureGetOrdersTheTask() {
        correct(() -> {
            Result result = new Result();
            result.input = (int) Thread.currentThread().threadId();
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                Future<?> task = executor.submit(() -> {
                    result.output = result.input * 2;
                });
                getUnchecked(task);
                use(result.output);
            }
        });
    }

    /** The same task, with the output read before the get(). */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_executorSubmit_readsBeforeTheGet() {
        broken(() -> {
            Result result = new Result();
            result.input = (int) Thread.currentThread().threadId();
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                Future<?> task = executor.submit(() -> {
                    result.output = result.input * 2;
                });
                use(result.output);
                getUnchecked(task);
            }
        });
    }

    private static final class Parcel {
        int contents;
    }

    private static final class Swap {
        Parcel left;
    }

    private static final Exchanger<Parcel> EXCHANGER = new Exchanger<>();
    private static final Swap PLAIN_SWAP = new Swap();

    /** Each thread fills a parcel, exchanges it with a partner, and reads what it got. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_exchanger_swapsFilledParcels() {
        correct(() -> {
            Parcel mine = new Parcel();
            mine.contents = (int) Thread.currentThread().threadId();
            try {
                Parcel theirs = EXCHANGER.exchange(mine, 10, TimeUnit.SECONDS);
                use(theirs.contents);
            } catch (TimeoutException e) {
                throw new AssertionError("an exchange round lost its partner", e);
            }
        });
    }

    /** The same swap through a plain field, which orders nothing. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_exchanger_swapsThroughAPlainField() {
        broken(() -> {
            Parcel mine = new Parcel();
            mine.contents = (int) Thread.currentThread().threadId();
            Parcel theirs = PLAIN_SWAP.left;
            PLAIN_SWAP.left = mine;
            if (theirs != null) {
                use(theirs.contents);
            }
        });
    }

    private static final class Config {
        private int port;

        int getPort() {
            return port;
        }

        void setPort(int port) {
            this.port = port;
        }
    }

    private static final class PlainReference {
        Config value;
    }

    private static final Rounds<AtomicReference<Config>> CONFIGS = new Rounds<>(AtomicReference::new);
    private static final Rounds<PlainReference> PLAIN_CONFIGS = new Rounds<>(PlainReference::new);

    /** The round's first thread builds a config and sets it; the others get it and read it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_atomicReference_publishesAFreshlyBuiltObject() {
        correct(() -> {
            Turn<AtomicReference<Config>> turn = CONFIGS.next();
            AtomicReference<Config> reference = turn.shared();
            if (turn.opensTheRound()) {
                Config config = new Config();
                config.setPort(8080 + turn.ticket());
                reference.set(config);
            } else {
                Config config = reference.get();
                for (int spins = 0; config == null && spins < SPIN_LIMIT; spins++) {
                    Thread.onSpinWait();
                    config = reference.get();
                }
                if (config != null) {
                    use(config.getPort());
                }
            }
        });
    }

    /** The same publication through a plain field. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_atomicReference_plainFieldPublishesNothing() {
        broken(() -> {
            Turn<PlainReference> turn = PLAIN_CONFIGS.next();
            PlainReference reference = turn.shared();
            if (turn.opensTheRound()) {
                Config config = new Config();
                config.setPort(8080 + turn.ticket());
                reference.value = config;
            } else {
                Config config = reference.value;
                for (int spins = 0; config == null && spins < SPIN_LIMIT; spins++) {
                    Thread.onSpinWait();
                    config = reference.value;
                }
                if (config != null) {
                    use(config.getPort());
                }
            }
        });
    }

    // --- A MessageDigest pool that hands out holders (#747) -----------------------------------

    /** What the holder pool hands out: a wrapper around one digest, never the digest itself. */
    private static final class DigestHolder {
        final MessageDigest digest;

        DigestHolder(MessageDigest digest) {
            this.digest = digest;
        }
    }

    private static final Deque<DigestHolder> HOLDER_POOL = holderPool();
    private static final Deque<DigestHolder> PEEKED_HOLDERS = holderPool();
    private static final Deque<DigestHolder> DECLARED_HOLDER_POOL = holderPool();
    private static final Deque<DigestHolder> DECLARED_PEEKED_HOLDERS = holderPool();

    /**
     * Take the one holder from a pool guarded by its own monitor, use its digest, put it back. The
     * take names the holder, while the detector tracks the digest inside it, and the model takes
     * no edge from a monitor, so nothing it sees hands the digest over.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_digestHolderPool_checkedOutUnderALock() {
        correct(() -> {
            DigestHolder holder = checkOut(HOLDER_POOL);
            try {
                holder.digest.update(PAYLOAD);
                use(holder.digest.digest().length);
            } finally {
                checkIn(HOLDER_POOL, holder);
            }
        });
    }

    /** The same pool with peek() for poll(), so every thread uses the one holder's digest. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_digestHolderPool_peekedByEveryThread() {
        broken(() -> {
            DigestHolder holder;
            synchronized (PEEKED_HOLDERS) {
                holder = PEEKED_HOLDERS.peek();
            }
            holder.digest.update(PAYLOAD);
            use(holder.digest.digest().length);
        });
    }

    /** The checkout above, declared: the taker names the digest it now owns. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_digestHolderPool_checkoutDeclared() {
        correct(() -> {
            DigestHolder holder = checkOut(DECLARED_HOLDER_POOL);
            AsyncTestContext.ownershipTaken(holder.digest);
            try {
                holder.digest.update(PAYLOAD);
                use(holder.digest.digest().length);
            } finally {
                checkIn(DECLARED_HOLDER_POOL, holder);
            }
        });
    }

    /**
     * The same declaration over peek(): every thread declares the one digest its own and uses it
     * at once, so each declaration starts an owner while the last is still using it. The uses are
     * repeated so the threads overlap after their declarations.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_digestHolderPool_declaredButPeeked() {
        broken(() -> {
            DigestHolder holder;
            synchronized (DECLARED_PEEKED_HOLDERS) {
                holder = DECLARED_PEEKED_HOLDERS.peek();
            }
            AsyncTestContext.ownershipTaken(holder.digest);
            for (int i = 0; i < 50; i++) {
                holder.digest.update(PAYLOAD);
            }
            use(holder.digest.digest().length);
        });
    }

    // --- A StampedLock optimistic read, validated (#740) ---------------------------------------

    /** The {@code StampedLock} class javadoc's point: moved under the write lock, read optimistically. */
    private static final class Point {
        private final StampedLock lock = new StampedLock();
        private double x;
        private double y;

        void move(double deltaX, double deltaY) {
            long stamp = lock.writeLock();
            try {
                x += deltaX;
                y += deltaY;
            } finally {
                lock.unlockWrite(stamp);
            }
        }

        double distanceFromOrigin() {
            long stamp = lock.tryOptimisticRead();
            double currentX = x;
            double currentY = y;
            if (!lock.validate(stamp)) {
                stamp = lock.readLock();
                try {
                    currentX = x;
                    currentY = y;
                } finally {
                    lock.unlockRead(stamp);
                }
            }
            return Math.hypot(currentX, currentY);
        }

        double distanceWithoutValidating() {
            lock.tryOptimisticRead();
            double currentX = x;
            double currentY = y;
            return Math.hypot(currentX, currentY);
        }
    }

    private static final Rounds<Point> POINTS = new Rounds<>(Point::new);
    private static final Rounds<Point> UNVALIDATED_POINTS = new Rounds<>(Point::new);

    /**
     * The round's first thread moves the point under the write lock, every other thread reads it
     * optimistically, validates, and re-reads under the read lock when the validation failed.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_stampedLock_validatesItsOptimisticRead() {
        correct(() -> {
            Turn<Point> turn = POINTS.next();
            Point point = turn.shared();
            if (turn.opensTheRound()) {
                point.move(1, turn.ticket());
            } else {
                use((long) point.distanceFromOrigin());
            }
        });
    }

    /** The same point, read under an optimistic stamp that is never validated. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_stampedLock_usesAnOptimisticReadUnvalidated() {
        broken(() -> {
            Turn<Point> turn = UNVALIDATED_POINTS.next();
            Point point = turn.shared();
            if (turn.opensTheRound()) {
                point.move(1, turn.ticket());
            } else {
                use((long) point.distanceWithoutValidating());
            }
        });
    }

    // --- A check-then-act whose callers all put one instance (#827, #833) ----------------------

    private static final Rounds<Object> GENERATIONS = new Rounds<>(Object::new);
    private static final ConcurrentMap<String, Object> CURRENT = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Object> RACED_CURRENT = new ConcurrentHashMap<>();

    /**
     * Every thread of a round installs the round's one generation object under a key the whole run
     * shares, with get-then-put and no lock. The value is chosen without reading the map and every
     * caller of the round puts the same instance, so whichever put lands last the map holds what
     * {@code putIfAbsent} would have left. The generation changes every round, so the row also
     * pins that the excuse is judged per round. Manual API: the detector is told what was put.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_checkThenAct_everyCallerPutsTheRoundsOneInstance() {
        correct(() -> {
            Object generation = GENERATIONS.next().shared();
            AsyncTestContext.nonAtomicConcurrentMapUpdateDetector().recordCheckThenAct(
                    CURRENT, "generation", generation, "install-generation", Thread.currentThread());
            if (CURRENT.get("generation") != generation) {
                CURRENT.put("generation", generation);
            }
        });
    }

    /**
     * The same get-then-put with each caller building its own generation object, so a caller can
     * install its object and have another caller's put drop it.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_checkThenAct_everyCallerPutsItsOwnInstance() {
        broken(() -> {
            Object generation = new Object();
            AsyncTestContext.nonAtomicConcurrentMapUpdateDetector().recordCheckThenAct(
                    RACED_CURRENT, "generation", generation, "install-generation",
                    Thread.currentThread());
            if (RACED_CURRENT.get("generation") != generation) {
                RACED_CURRENT.put("generation", generation);
            }
        });
    }

    private static DigestHolder checkOut(Deque<DigestHolder> pool) throws InterruptedException {
        synchronized (pool) {
            while (pool.isEmpty()) {
                pool.wait();
            }
            return pool.poll();
        }
    }

    private static void checkIn(Deque<DigestHolder> pool, DigestHolder holder) {
        synchronized (pool) {
            pool.offer(holder);
            pool.notifyAll();
        }
    }

    private static Deque<DigestHolder> holderPool() {
        Deque<DigestHolder> pool = new ArrayDeque<>();
        try {
            pool.add(new DigestHolder(MessageDigest.getInstance("SHA-256")));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JRE", e);
        }
        return pool;
    }

    // --- Common mistakes, each after its fix ----------------------------------------------------

    private static final Rounds<Map<Integer, Integer>> CONCURRENT_MAPS =
            new Rounds<>(ConcurrentHashMap::new);
    private static final Rounds<Map<Integer, Integer>> PLAIN_MAPS = new Rounds<>(HashMap::new);

    /** Every thread puts its own key into one ConcurrentHashMap. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_concurrentHashMap_putByEveryThread() {
        correct(() -> {
            Turn<Map<Integer, Integer>> turn = CONCURRENT_MAPS.next();
            turn.shared().put(turn.ticket(), turn.ticket());
            use(turn.shared().size());
        });
    }

    /** The same puts into a HashMap. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_hashMap_putByEveryThread() {
        broken(() -> {
            Turn<Map<Integer, Integer>> turn = PLAIN_MAPS.next();
            turn.shared().put(turn.ticket(), turn.ticket());
            use(turn.shared().size());
        });
    }

    private static final Rounds<List<Integer>> SYNCHRONIZED_LISTS =
            new Rounds<>(() -> Collections.synchronizedList(new ArrayList<>()));
    private static final Rounds<List<Integer>> PLAIN_LISTS = new Rounds<>(ArrayList::new);

    /** Every thread adds to one synchronizedList. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_synchronizedList_addedToByEveryThread() {
        correct(() -> {
            Turn<List<Integer>> turn = SYNCHRONIZED_LISTS.next();
            turn.shared().add(turn.ticket());
        });
    }

    /** The same adds to an ArrayList. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_arrayList_addedToByEveryThread() {
        broken(() -> {
            Turn<List<Integer>> turn = PLAIN_LISTS.next();
            turn.shared().add(turn.ticket());
        });
    }

    private static final Rounds<List<Integer>> COPY_ON_WRITE_LISTS =
            new Rounds<>(CopyOnWriteArrayList::new);
    private static final Rounds<List<Integer>> ITERATED_LISTS = new Rounds<>(ArrayList::new);

    /** Half the threads add, half iterate, over a list whose iterator is a snapshot. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_copyOnWriteList_iteratedWhileAdding() {
        correct(() -> addOrIterate(COPY_ON_WRITE_LISTS.next()));
    }

    /** The same adds and iteration over an ArrayList. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_arrayList_iteratedWhileAdding() {
        broken(() -> addOrIterate(ITERATED_LISTS.next()));
    }

    private static void addOrIterate(Turn<List<Integer>> turn) {
        if (turn.ticket() % 2 == 0) {
            turn.shared().add(turn.ticket());
        } else {
            for (int value : turn.shared()) {
                use(value);
            }
        }
    }

    private static final Rounds<Map<String, Integer>> MERGED_COUNTS =
            new Rounds<>(ConcurrentHashMap::new);
    private static final Rounds<Map<String, Integer>> RACED_COUNTS =
            new Rounds<>(ConcurrentHashMap::new);

    /** Every thread counts one key with merge. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_concurrentMap_countsWithMerge() {
        correct(() -> MERGED_COUNTS.next().shared().merge("hits", 1, Integer::sum));
    }

    /** The same count as a get and then a put. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_concurrentMap_countsWithGetThenPut() {
        broken(() -> {
            Map<String, Integer> counts = RACED_COUNTS.next().shared();
            Integer seen = counts.get("hits");
            counts.put("hits", seen == null ? 1 : seen + 1);
        });
    }

    private static final class Settings {
        int size = 7;
    }

    private static final class LazySettings {
        private Settings settings;

        Settings unsynchronized() {
            if (settings == null) {
                settings = new Settings();
            }
            return settings;
        }

        synchronized Settings guarded() {
            if (settings == null) {
                settings = new Settings();
            }
            return settings;
        }
    }

    private static final Rounds<LazySettings> GUARDED_SETTINGS = new Rounds<>(LazySettings::new);
    private static final Rounds<LazySettings> RACED_SETTINGS = new Rounds<>(LazySettings::new);

    /** The first caller builds the object, inside a synchronized getter. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_lazyInit_synchronizedGetter() {
        correct(() -> use(GUARDED_SETTINGS.next().shared().guarded().size));
    }

    /** The same getter with no monitor. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_lazyInit_unsynchronizedGetter() {
        broken(() -> use(RACED_SETTINGS.next().shared().unsynchronized().size));
    }

    private static final class VolatileLazySettings {
        private volatile Settings settings;

        Settings get() {
            Settings local = settings;
            if (local == null) {
                synchronized (this) {
                    local = settings;
                    if (local == null) {
                        local = new Settings();
                        settings = local;
                    }
                }
            }
            return local;
        }
    }

    private static final class PlainLazySettings {
        private Settings settings;

        Settings get() {
            if (settings == null) {
                synchronized (this) {
                    if (settings == null) {
                        settings = new Settings();
                    }
                }
            }
            return settings;
        }
    }

    private static final Rounds<VolatileLazySettings> VOLATILE_SETTINGS =
            new Rounds<>(VolatileLazySettings::new);
    private static final Rounds<PlainLazySettings> PLAIN_SETTINGS =
            new Rounds<>(PlainLazySettings::new);

    /** Double-checked locking on a volatile field, the form that is correct since Java 5. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_doubleCheckedLocking_volatileField() {
        correct(() -> use(VOLATILE_SETTINGS.next().shared().get().size));
    }

    /** The same check, lock and recheck on a plain field (#1154). */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_doubleCheckedLocking_plainField() {
        broken(() -> use(PLAIN_SETTINGS.next().shared().get().size));
    }

    private static final class Ledger {
        final Object lock = new Object();
        final AtomicInteger tasksStarted = new AtomicInteger();
        int balance;

        /**
         * Holds a pool task until a second has started, so two pool threads each run one in this
         * round. One thread running them all has no race to report, and a row whose outcome rests on
         * that is a coin toss: this one went silent on CI.
         */
        void awaitASecondTask() {
            tasksStarted.incrementAndGet();
            for (int spins = 0; tasksStarted.get() < 2 && spins < SPIN_LIMIT; spins++) {
                Thread.onSpinWait();
            }
        }
    }

    private static final Rounds<Ledger> LOCKED_READS = new Rounds<>(Ledger::new);
    private static final Rounds<Ledger> PLAIN_READS = new Rounds<>(Ledger::new);

    /** Half the threads write, half read, all under the ledger's lock. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_guardedField_readAndWrittenUnderTheLock() {
        correct(() -> {
            Turn<Ledger> turn = LOCKED_READS.next();
            Ledger ledger = turn.shared();
            synchronized (ledger.lock) {
                if (turn.ticket() % 2 == 0) {
                    ledger.balance = turn.ticket() + 1;
                } else {
                    use(ledger.balance);
                }
            }
        });
    }

    /** The same writes under the lock, with the reads outside it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_guardedField_readWithoutTheLock() {
        broken(() -> {
            Turn<Ledger> turn = PLAIN_READS.next();
            Ledger ledger = turn.shared();
            if (turn.ticket() % 2 == 0) {
                synchronized (ledger.lock) {
                    ledger.balance = turn.ticket() + 1;
                }
            } else {
                use(ledger.balance);
            }
        });
    }

    private static final Rounds<Ledger> ONE_LOCK = new Rounds<>(Ledger::new);
    private static final Rounds<Ledger> LOCK_PER_CALLER = new Rounds<>(Ledger::new);

    /** Every thread increments under the ledger's one lock. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_oneLock_guardsTheCounter() {
        correct(() -> {
            Ledger ledger = ONE_LOCK.next().shared();
            synchronized (ledger.lock) {
                ledger.balance++;
            }
        });
    }

    /** The same increment, each caller synchronizing on a lock it just built for itself. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_lockPerCaller_guardsNothing() {
        broken(() -> {
            Ledger ledger = LOCK_PER_CALLER.next().shared();
            Object mine = new Object();
            synchronized (mine) {
                ledger.balance++;
            }
        });
    }

    private static final class BoxedCount {
        final Object lock = new Object();
        Integer count = 0;
    }

    private static final Rounds<BoxedCount> LOCKED_COUNTS = new Rounds<>(BoxedCount::new);
    private static final Rounds<BoxedCount> SELF_LOCKED_COUNTS = new Rounds<>(BoxedCount::new);

    /** A boxed counter updated under a separate final lock. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_finalLock_guardsABoxedCounter() {
        correct(() -> {
            BoxedCount boxed = LOCKED_COUNTS.next().shared();
            synchronized (boxed.lock) {
                boxed.count = boxed.count + 1;
            }
        });
    }

    /** The same update synchronized on the field it replaces. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_boxedCounter_synchronizesOnItself() {
        broken(() -> {
            BoxedCount boxed = SELF_LOCKED_COUNTS.next().shared();
            synchronized (boxed.count) {
                boxed.count = boxed.count + 1;
            }
        });
    }

    private static final class LatchedReader {
        final CountDownLatch written = new CountDownLatch(1);
        int value;
    }

    private static final class SleepingReader {
        int value;
    }

    private static final Rounds<LatchedReader> LATCHED_READERS = new Rounds<>(LatchedReader::new);
    private static final Rounds<SleepingReader> SLEEPING_READERS = new Rounds<>(SleepingReader::new);

    /** The round's first thread writes and counts down; the others await and read. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_latch_ordersAWrittenField() {
        correct(() -> {
            Turn<LatchedReader> turn = LATCHED_READERS.next();
            LatchedReader reader = turn.shared();
            if (turn.opensTheRound()) {
                reader.value = turn.ticket() + 1;
                reader.written.countDown();
            } else {
                reader.written.await();
                use(reader.value);
            }
        });
    }

    /** The same write, with the others sleeping instead of waiting for it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_sleep_ordersNothing() {
        broken(() -> {
            Turn<SleepingReader> turn = SLEEPING_READERS.next();
            SleepingReader reader = turn.shared();
            if (turn.opensTheRound()) {
                reader.value = turn.ticket() + 1;
            } else {
                Thread.sleep(2);
                use(reader.value);
            }
        });
    }

    /** Lives as long as the class: a pool the tasks of a whole round share is the point. */
    private static final ExecutorService COUNTING_POOL = Executors.newFixedThreadPool(2);

    private static final Rounds<Ledger> LOCKED_POOL_COUNTS = new Rounds<>(Ledger::new);
    private static final Rounds<Ledger> PLAIN_POOL_COUNTS = new Rounds<>(Ledger::new);

    /** Every thread submits a task that increments under the ledger's lock, and gets it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_poolTasks_counterUnderALock() {
        correct(() -> {
            Ledger ledger = LOCKED_POOL_COUNTS.next().shared();
            getUnchecked(COUNTING_POOL.submit(() -> {
                ledger.awaitASecondTask();
                synchronized (ledger.lock) {
                    ledger.balance++;
                }
            }));
        });
    }

    /** The same tasks with no lock. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_poolTasks_plainCounter() {
        broken(() -> {
            Ledger ledger = PLAIN_POOL_COUNTS.next().shared();
            getUnchecked(COUNTING_POOL.submit(() -> {
                ledger.awaitASecondTask();
                ledger.balance++;
            }));
        });
    }

    private static final Rounds<Ledger> LOCKED_EXECUTE_COUNTS = new Rounds<>(Ledger::new);
    private static final Rounds<Ledger> PLAIN_EXECUTE_COUNTS = new Rounds<>(Ledger::new);

    /** Every thread hands the pool a task through execute, which increments under a lock. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_poolExecute_counterUnderALock() {
        correct(() -> {
            Ledger ledger = LOCKED_EXECUTE_COUNTS.next().shared();
            CountDownLatch done = new CountDownLatch(1);
            COUNTING_POOL.execute(() -> {
                ledger.awaitASecondTask();
                synchronized (ledger.lock) {
                    ledger.balance++;
                }
                done.countDown();
            });
            done.await();
        });
    }

    /** The same tasks with no lock (#1155). */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_poolExecute_plainCounter() {
        broken(() -> {
            Ledger ledger = PLAIN_EXECUTE_COUNTS.next().shared();
            CountDownLatch done = new CountDownLatch(1);
            COUNTING_POOL.execute(() -> {
                ledger.awaitASecondTask();
                ledger.balance++;
                done.countDown();
            });
            done.await();
        });
    }

    /** How many elements a parallel row walks, enough that the common pool's workers help. */
    private static final int PARALLEL_ELEMENTS = 2_000;

    /**
     * One caller, so the only other threads are the common pool's: its workers increment under one
     * lock, and the caller reads the result after the terminal operation has joined them.
     */
    @AsyncTest(threads = 1, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_parallelForEach_countsUnderALock() {
        correct(() -> {
            Ledger ledger = new Ledger();
            IntStream.range(0, PARALLEL_ELEMENTS).parallel().forEach(i -> {
                synchronized (ledger.lock) {
                    ledger.balance++;
                }
            });
            use(ledger.balance);
        });
    }

    /** The same stream with no lock (#1156). */
    @AsyncTest(threads = 1, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_parallelForEach_plainCounter() {
        broken(() -> {
            Ledger ledger = new Ledger();
            IntStream.range(0, PARALLEL_ELEMENTS).parallel().forEach(i -> ledger.balance++);
            use(ledger.balance);
        });
    }

    /** Adding to a list from the same stream, through a lambda so that the add is a call site. */
    @AsyncTest(threads = 1, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_parallelForEach_addsToASynchronizedList() {
        correct(() -> {
            List<Integer> out = Collections.synchronizedList(new ArrayList<>());
            IntStream.range(0, PARALLEL_ELEMENTS).parallel().forEach(i -> out.add(i));
            use(out.size());
        });
    }

    /** The same adds to an ArrayList, the textbook parallel forEach mistake (#1156). */
    @AsyncTest(threads = 1, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_parallelForEach_addsToAnArrayList() {
        broken(() -> {
            List<Integer> out = new ArrayList<>();
            IntStream.range(0, PARALLEL_ELEMENTS).parallel().forEach(i -> out.add(i));
            use(out.size());
        });
    }

    /** A parallel map whose lambda counts under one lock, collected by toList (#1172). */
    @AsyncTest(threads = 1, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_parallelMap_countsUnderALock() {
        correct(() -> {
            Ledger ledger = new Ledger();
            List<Integer> mapped = IntStream.range(0, PARALLEL_ELEMENTS).boxed().parallel().map(i -> {
                synchronized (ledger.lock) {
                    ledger.balance++;
                }
                return i;
            }).toList();
            use(mapped.size() + ledger.balance);
        });
    }

    /** The same map with no lock (#1172). */
    @AsyncTest(threads = 1, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_parallelMap_plainCounter() {
        broken(() -> {
            Ledger ledger = new Ledger();
            List<Integer> mapped = IntStream.range(0, PARALLEL_ELEMENTS).boxed().parallel().map(i -> {
                ledger.balance++;
                return i;
            }).toList();
            use(mapped.size() + ledger.balance);
        });
    }

    /**
     * The correct way to collect in parallel: one ArrayList per subtask, merged once both halves
     * are done. Lambdas, so that each add is a call site (#1172).
     */
    @AsyncTest(threads = 1, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_parallelCollect_containerPerSubtask() {
        correct(() -> {
            List<Integer> out = IntStream.range(0, PARALLEL_ELEMENTS).boxed().parallel().collect(
                    () -> new ArrayList<Integer>(),
                    (list, i) -> list.add(i),
                    (left, right) -> left.addAll(right));
            use(out.size());
        });
    }

    /** One ArrayList shared by a parallel map's lambda, the same mistake as forEach's (#1172). */
    @AsyncTest(threads = 1, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_parallelMap_addsToAnArrayList() {
        broken(() -> {
            List<Integer> out = new ArrayList<>();
            List<Integer> mapped = IntStream.range(0, PARALLEL_ELEMENTS).boxed().parallel().map(i -> {
                out.add(i);
                return i;
            }).toList();
            use(mapped.size() + out.size());
        });
    }

    /** A parallel IntStream map whose operator counts under one lock, ended by sum (#1172). */
    @AsyncTest(threads = 1, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_parallelIntMap_countsUnderALock() {
        correct(() -> {
            Ledger ledger = new Ledger();
            int sum = IntStream.range(0, PARALLEL_ELEMENTS).parallel().map(i -> {
                synchronized (ledger.lock) {
                    ledger.balance++;
                }
                return i;
            }).sum();
            use(sum + ledger.balance);
        });
    }

    /** The same IntStream map with no lock. A primitive stream's map ran unattributed (#1172). */
    @AsyncTest(threads = 1, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_parallelIntMap_plainCounter() {
        broken(() -> {
            Ledger ledger = new Ledger();
            int sum = IntStream.range(0, PARALLEL_ELEMENTS).parallel().map(i -> {
                ledger.balance++;
                return i;
            }).sum();
            use(sum + ledger.balance);
        });
    }

    /** A parallel Stream's mapToInt counting under one lock, ended by an IntStream sum (#1172). */
    @AsyncTest(threads = 1, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_parallelMapToInt_countsUnderALock() {
        correct(() -> {
            Ledger ledger = new Ledger();
            int sum = IntStream.range(0, PARALLEL_ELEMENTS).boxed().parallel().mapToInt(i -> {
                synchronized (ledger.lock) {
                    ledger.balance++;
                }
                return i;
            }).sum();
            use(sum + ledger.balance);
        });
    }

    /** The same mapToInt with no lock: the lambda sits on a Stream, the terminal on an IntStream. */
    @AsyncTest(threads = 1, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_parallelMapToInt_plainCounter() {
        broken(() -> {
            Ledger ledger = new Ledger();
            int sum = IntStream.range(0, PARALLEL_ELEMENTS).boxed().parallel().mapToInt(i -> {
                ledger.balance++;
                return i;
            }).sum();
            use(sum + ledger.balance);
        });
    }

    /** A parallel map counting under one lock, then sorted, collected by toList (#1172). */
    @AsyncTest(threads = 1, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_parallelMapThenSorted_countsUnderALock() {
        correct(() -> {
            Ledger ledger = new Ledger();
            List<Integer> sorted = IntStream.range(0, PARALLEL_ELEMENTS).boxed().parallel().map(i -> {
                synchronized (ledger.lock) {
                    ledger.balance++;
                }
                return i;
            }).sorted().toList();
            use(sorted.size() + ledger.balance);
        });
    }

    /** The same with no lock: the lambda sits behind sorted, which no hook carried before (#1172). */
    @AsyncTest(threads = 1, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_parallelMapThenSorted_plainCounter() {
        broken(() -> {
            Ledger ledger = new Ledger();
            List<Integer> sorted = IntStream.range(0, PARALLEL_ELEMENTS).boxed().parallel().map(i -> {
                ledger.balance++;
                return i;
            }).sorted().toList();
            use(sorted.size() + ledger.balance);
        });
    }

    /** Long enough per body that Caffeine's striped buffer resizes under contention (#1171). */
    private static final int INTERNS_PER_BODY = 200;

    private static final com.github.benmanes.caffeine.cache.Interner<String> WEAK_INTERNER =
            com.github.benmanes.caffeine.cache.Interner.newWeakInterner();

    /**
     * Every thread keeps interning through Caffeine, whose striped buffer checks {@code tableBusy}
     * with no lock, swaps it from 0 to 1 and writes 0 back. Each flag access is the spinlock's own
     * protocol, and the class documents itself as thread-safe.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 60_000, detectAll = true)
    void idiom_spinLockFlag_contendedInsideCaffeine() {
        correct(() -> {
            for (int i = 0; i < INTERNS_PER_BODY; i++) {
                use(WEAK_INTERNER.intern(new String("shared")).length());
            }
        });
    }

    private static final class CheckedFlag {
        volatile int busy;
        int table;
    }

    private static final Rounds<CheckedFlag> CHECKED_FLAGS = new Rounds<>(CheckedFlag::new);

    /**
     * The same check and release with no compare-and-swap between them, so the flag excludes nobody
     * and is plain shared state: two threads can both find it 0 and both go in.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 60_000, detectAll = true)
    void idiom_spinLockFlag_checkedWithNoSwap() {
        broken(() -> {
            CheckedFlag flag = CHECKED_FLAGS.next().shared();
            for (int i = 0; i < INTERNS_PER_BODY; i++) {
                if (flag.busy == 0) {
                    flag.busy = 1;
                    flag.table++;
                    flag.busy = 0;
                }
            }
        });
    }

    /** How many tasks each runAsync row starts. Two must overlap, and the rest add contention. */
    private static final int ASYNC_TASKS = 8;

    /** Starts {@code count} under the caller's eyes and spins until a second task has started too. */
    private static CompletableFuture<Void> overlappingTasks(Runnable count) {
        AtomicInteger started = new AtomicInteger();
        CompletableFuture<?>[] tasks = new CompletableFuture<?>[ASYNC_TASKS];
        for (int i = 0; i < ASYNC_TASKS; i++) {
            tasks[i] = CompletableFuture.runAsync(() -> {
                started.incrementAndGet();
                for (int spins = 0; started.get() < 2 && spins < SPIN_LIMIT; spins++) {
                    Thread.onSpinWait();
                }
                count.run();
            });
        }
        return CompletableFuture.allOf(tasks);
    }

    /** Tasks on the default executor increment under one lock, and the caller reads after the join. */
    @AsyncTest(threads = 1, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_runAsync_counterUnderALock() {
        correct(() -> {
            Ledger ledger = new Ledger();
            overlappingTasks(() -> {
                synchronized (ledger.lock) {
                    ledger.balance++;
                }
            }).join();
            use(ledger.balance);
        });
    }

    /** The same tasks with no lock (#1158). */
    @AsyncTest(threads = 1, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_runAsync_plainCounter() {
        broken(() -> {
            Ledger ledger = new Ledger();
            overlappingTasks(() -> ledger.balance++).join();
            use(ledger.balance);
        });
    }

    private static final Rounds<List<Integer>> MONITORED_MEMBERS =
            new Rounds<>(() -> Collections.synchronizedList(new ArrayList<>()));
    private static final Rounds<List<Integer>> RACED_MEMBERS =
            new Rounds<>(() -> Collections.synchronizedList(new ArrayList<>()));
    private static final Rounds<CyclicBarrier> MEMBER_CHECKS = new Rounds<>(() -> new CyclicBarrier(THREADS));

    /** Every thread adds one element if absent, holding the list's monitor across both calls. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_synchronizedList_containsThenAddUnderItsMonitor() {
        correct(() -> {
            List<Integer> members = MONITORED_MEMBERS.next().shared();
            synchronized (members) {
                if (!members.contains(1)) {
                    members.add(1);
                }
            }
        });
    }

    /**
     * The same two calls with no monitor. The barrier lines the checks up so that every thread saw
     * the element absent before any adds: the overlap a stress run hopes for, every round (#1159).
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_synchronizedList_containsThenAddWithoutIt() {
        broken(() -> {
            Turn<List<Integer>> turn = RACED_MEMBERS.next();
            CyclicBarrier checked = MEMBER_CHECKS.next().shared();
            List<Integer> members = turn.shared();
            if (!members.contains(1)) {
                awaitAll(checked);
                members.add(1);
            }
        });
    }

    private static void awaitAll(CyclicBarrier barrier) throws InterruptedException {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (BrokenBarrierException | TimeoutException e) {
            throw new IllegalStateException("the workers did not line up", e);
        }
    }

    // --- 15. Lock order: a gate lock, and the tryLock back-off ---------------------------------

    /** The locks one round's threads take: a gate and the pair it orders. */
    private static final class OrderedPair {
        final ReentrantLock gate = new ReentrantLock();
        final ReentrantLock a = new ReentrantLock();
        final ReentrantLock b = new ReentrantLock();
        /**
         * Harness for the broken twins only: keeps the workers out of the inverted region one at
         * a time, so writing the deadlock does not mean suffering it. A semaphore is not a lock to
         * LockOrderValidator, so it is no gate.
         */
        final Semaphore oneAtATime = new Semaphore(1);
        int value;
    }

    private static final Rounds<OrderedPair> GATED_PAIRS = new Rounds<>(OrderedPair::new);
    private static final Rounds<OrderedPair> UNGATED_PAIRS = new Rounds<>(OrderedPair::new);
    private static final Rounds<OrderedPair> BACKED_OFF_PAIRS = new Rounds<>(OrderedPair::new);
    private static final Rounds<OrderedPair> BLOCKING_PAIRS = new Rounds<>(OrderedPair::new);

    /** Takes {@code first} then {@code second}, both blocking, and bumps the value. */
    private static void nestBlocking(OrderedPair pair, ReentrantLock first, ReentrantLock second) {
        first.lock();
        try {
            second.lock();
            try {
                pair.value++;
            } finally {
                second.unlock();
            }
        } finally {
            first.unlock();
        }
    }

    /**
     * Half the threads take a then b, the other half b then a, every one of them inside the gate.
     * Whoever holds the gate holds the pair, so the two orders are never in progress at once.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_lockOrder_bothOrdersUnderOneGate() {
        correct(() -> {
            Turn<OrderedPair> turn = GATED_PAIRS.next();
            OrderedPair pair = turn.shared();
            boolean forward = turn.ticket() % 2 == 0;
            pair.gate.lock();
            try {
                nestBlocking(pair, forward ? pair.a : pair.b, forward ? pair.b : pair.a);
            } finally {
                pair.gate.unlock();
            }
        });
    }

    /** The same two orders with no gate: two threads can each hold one lock and want the other. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_lockOrder_bothOrdersWithoutAGate() {
        broken(() -> {
            Turn<OrderedPair> turn = UNGATED_PAIRS.next();
            OrderedPair pair = turn.shared();
            boolean forward = turn.ticket() % 2 == 0;
            pair.oneAtATime.acquire();
            try {
                nestBlocking(pair, forward ? pair.a : pair.b, forward ? pair.b : pair.a);
            } finally {
                pair.oneAtATime.release();
            }
        });
    }

    /**
     * JCIP listing 13.3: take both with tryLock, and on failure let go of the first, pause a
     * random time and retry. No thread ever waits holding a lock, so no order of the two can
     * deadlock, and the random pause keeps the two orders from livelocking.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_lockOrder_tryLockBackOff() {
        correct(() -> {
            Turn<OrderedPair> turn = BACKED_OFF_PAIRS.next();
            OrderedPair pair = turn.shared();
            boolean forward = turn.ticket() % 2 == 0;
            ReentrantLock first = forward ? pair.a : pair.b;
            ReentrantLock second = forward ? pair.b : pair.a;
            for (int attempt = 0; attempt < SPIN_LIMIT; attempt++) {
                if (first.tryLock()) {
                    try {
                        if (second.tryLock()) {
                            try {
                                pair.value++;
                                return;
                            } finally {
                                second.unlock();
                            }
                        }
                    } finally {
                        first.unlock();
                    }
                }
                // A random pause, as in the listing: two threads retrying in opposite orders
                // with a fixed one can fail in lockstep, and once did for all 200,000 attempts.
                java.util.concurrent.locks.LockSupport.parkNanos(
                        ThreadLocalRandom.current().nextLong(1_000, 100_000));
            }
            throw new AssertionError("the back-off never got both locks");
        });
    }

    /** The same two orders with lock() for both: each thread waits holding the other's lock. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_lockOrder_blockingInsteadOfBackingOff() {
        broken(() -> {
            Turn<OrderedPair> turn = BLOCKING_PAIRS.next();
            OrderedPair pair = turn.shared();
            boolean forward = turn.ticket() % 2 == 0;
            pair.oneAtATime.acquire();
            try {
                nestBlocking(pair, forward ? pair.a : pair.b, forward ? pair.b : pair.a);
            } finally {
                pair.oneAtATime.release();
            }
        });
    }

    // --- 16. A mutable value updated inside ConcurrentHashMap.compute ----------------------

    private static final class Tally {
        int hits;
    }

    private static final Rounds<ConcurrentHashMap<String, Tally>> COMPUTED_TALLIES =
            new Rounds<>(ConcurrentHashMap::new);
    private static final Rounds<ConcurrentHashMap<String, Tally>> FETCHED_TALLIES = new Rounds<>(() -> {
        ConcurrentHashMap<String, Tally> tallies = new ConcurrentHashMap<>();
        tallies.put("k", new Tally());
        return tallies;
    });

    /**
     * Every thread bumps the count inside compute, which ConcurrentHashMap documents as atomic
     * for the key: the read and the write of the value are one step (#1245).
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_concurrentHashMap_computeMutatesTheValue() {
        correct(() -> {
            ConcurrentHashMap<String, Tally> tallies = COMPUTED_TALLIES.next().shared();
            tallies.compute("k", (key, tally) -> {
                Tally t = tally == null ? new Tally() : tally;
                t.hits++;
                return t;
            });
        });
    }

    /** The same bump after a get: the map hands the value out and nothing holds the bump together. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_concurrentHashMap_getThenMutatesTheValue() {
        broken(() -> {
            ConcurrentHashMap<String, Tally> tallies = FETCHED_TALLIES.next().shared();
            tallies.get("k").hits++;
        });
    }

    // --- 17. Counters kept in a plain array (#1250; this lane runs the agent with arrays=true) --

    private static final Rounds<int[]> SLOTTED_COUNTS = new Rounds<>(() -> new int[THREADS]);
    private static final Rounds<int[]> SHARED_COUNTS = new Rounds<>(() -> new int[1]);

    /** A histogram and the lock that guards it, one per round. */
    private static final class Histogram {
        final long[] buckets = new long[4];
    }

    private static final Rounds<Histogram> GUARDED_HISTOGRAMS = new Rounds<>(Histogram::new);
    private static final Rounds<Histogram> RACED_HISTOGRAMS = new Rounds<>(Histogram::new);

    /** Each thread bumps its own slot: no element is touched by two threads. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_intArray_aSlotPerThread() {
        correct(() -> {
            Turn<int[]> turn = SLOTTED_COUNTS.next();
            turn.shared()[turn.ticket() % THREADS]++;
        });
    }

    /** Every thread bumps the one slot: the field-counter lost update, moved into an array. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_intArray_everyThreadOnOneSlot() {
        broken(() -> SHARED_COUNTS.next().shared()[0]++);
    }

    /** Every thread adds to one bucket, under the histogram's own monitor. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_longArray_bucketUnderItsMonitor() {
        correct(() -> {
            Histogram histogram = GUARDED_HISTOGRAMS.next().shared();
            synchronized (histogram) {
                histogram.buckets[1] += 3L;
            }
        });
    }

    /** The same add with no monitor. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_longArray_bucketWithoutIt() {
        broken(() -> RACED_HISTOGRAMS.next().shared().buckets[1] += 3L);
    }

    // --- 18. A CyclicBarrier rendezvous (#1246) -----------------------------------------------

    /** One plain result per thread. */
    private static final class PartyResult {
        int value;
    }

    /** A round's results and the barrier its threads meet at. */
    private static final class Rendezvous {
        final PartyResult[] results = new PartyResult[THREADS];
        final CyclicBarrier barrier = new CyclicBarrier(THREADS);

        Rendezvous() {
            for (int i = 0; i < THREADS; i++) {
                results[i] = new PartyResult();
            }
        }
    }

    private static final Rounds<Rendezvous> RENDEZVOUS = new Rounds<>(Rendezvous::new);
    private static final Rounds<Rendezvous> EARLY_READ_RENDEZVOUS = new Rounds<>(Rendezvous::new);

    /**
     * Each thread writes its own result, awaits the round's barrier, then reads every result. The
     * memory model orders each write before every read after the return.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_cyclicBarrier_readsAfterTheAwait() {
        correct(() -> {
            Turn<Rendezvous> turn = RENDEZVOUS.next();
            Rendezvous round = turn.shared();
            round.results[turn.ticket() % THREADS].value = turn.ticket();
            try {
                round.barrier.await();
            } catch (BrokenBarrierException e) {
                throw new AssertionError("nothing in this row breaks the barrier", e);
            }
            for (PartyResult result : round.results) {
                use(result.value);
            }
        });
    }

    /** The same write and await, with every result read before the await. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_cyclicBarrier_readsBeforeTheAwait() {
        broken(() -> {
            Turn<Rendezvous> turn = EARLY_READ_RENDEZVOUS.next();
            Rendezvous round = turn.shared();
            round.results[turn.ticket() % THREADS].value = turn.ticket();
            for (PartyResult result : round.results) {
                use(result.value);
            }
            try {
                round.barrier.await();
            } catch (BrokenBarrierException e) {
                throw new AssertionError("nothing in this row breaks the barrier", e);
            }
        });
    }

    /** A round's results, the total of them, and a barrier whose action may compute it. */
    private static final class Totalled {
        final PartyResult[] results = new PartyResult[THREADS];
        int total;
        final CyclicBarrier barrier;

        Totalled(boolean actionTotals) {
            for (int i = 0; i < THREADS; i++) {
                results[i] = new PartyResult();
            }
            barrier = actionTotals ? new CyclicBarrier(THREADS, this::sum) : new CyclicBarrier(THREADS);
        }

        void sum() {
            int sum = 0;
            for (PartyResult result : results) {
                sum += result.value;
            }
            total = sum;
        }
    }

    private static final Rounds<Totalled> ACTION_TOTALLED = new Rounds<>(() -> new Totalled(true));
    private static final Rounds<Totalled> PARTY_TOTALLED = new Rounds<>(() -> new Totalled(false));

    /**
     * Each thread writes its own result and awaits the round's barrier, whose action totals every
     * result; then each thread reads the total. The memory model orders each write before the
     * action and the action before every return (#1267).
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_cyclicBarrier_actionTotalsTheResults() {
        correct(() -> {
            Turn<Totalled> turn = ACTION_TOTALLED.next();
            Totalled round = turn.shared();
            round.results[turn.ticket() % THREADS].value = turn.ticket();
            try {
                round.barrier.await();
            } catch (BrokenBarrierException e) {
                throw new AssertionError("nothing in this row breaks the barrier", e);
            }
            use(round.total);
        });
    }

    /**
     * The same results, with one party totalling them after its own return instead of the action,
     * while the others read the total after theirs: nothing orders that write before those reads.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_cyclicBarrier_partyTotalsAfterTheAwait() {
        broken(() -> {
            Turn<Totalled> turn = PARTY_TOTALLED.next();
            Totalled round = turn.shared();
            round.results[turn.ticket() % THREADS].value = turn.ticket();
            try {
                round.barrier.await();
            } catch (BrokenBarrierException e) {
                throw new AssertionError("nothing in this row breaks the barrier", e);
            }
            if (turn.ticket() % THREADS == 0) {
                round.sum();
            } else {
                use(round.total);
            }
        });
    }

    // --- 19. A pool's termination: awaitTermination and close (#1266) -------------------------

    /**
     * A task executed on a pool of the body's own reads the input and writes the output; the body
     * shuts the pool down, awaits its termination, and reads the output. Only the termination
     * orders the task's write before the read: {@code execute} hands back no future. Section 21
     * runs the same pair through {@code newSingleThreadExecutor}'s wrapper.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_executorTermination_readsAfterAwaitTermination() {
        correct(() -> {
            Result result = new Result();
            result.input = (int) Thread.currentThread().threadId();
            ExecutorService executor = Executors.newFixedThreadPool(1);
            executor.execute(() -> result.output = result.input * 2);
            executor.shutdown();
            awaitTerminated(executor);
            use(result.output);
        });
    }

    /** The same task, with the output read before the pool is shut down and awaited. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_executorTermination_readsBeforeAwaitTermination() {
        broken(() -> {
            Result result = new Result();
            result.input = (int) Thread.currentThread().threadId();
            ExecutorService executor = Executors.newFixedThreadPool(1);
            executor.execute(() -> result.output = result.input * 2);
            use(result.output);
            executor.shutdown();
            awaitTerminated(executor);
        });
    }

    /**
     * The same task on a virtual-thread executor in a try-with-resources block, the output read
     * after the block: {@code close} waits for every task, the idiom the JDK documents.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_executorClose_readsAfterTheBlock() {
        correct(() -> {
            Result result = new Result();
            result.input = (int) Thread.currentThread().threadId();
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                executor.execute(() -> result.output = result.input * 2);
            }
            use(result.output);
        });
    }

    /** The same block, with the output read inside it, before {@code close}. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_executorClose_readsInsideTheBlock() {
        broken(() -> {
            Result result = new Result();
            result.input = (int) Thread.currentThread().threadId();
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                executor.execute(() -> result.output = result.input * 2);
                use(result.output);
            }
        });
    }

    private static void awaitTerminated(ExecutorService executor) throws InterruptedException {
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            throw new AssertionError("a pool running one short task did not terminate");
        }
    }

    // --- 20. A phaser's advance (#1265) -------------------------------------------------------

    /** A round's results and the phaser its threads advance. */
    private static final class PhaseRound {
        final PartyResult[] results = new PartyResult[THREADS];
        final Phaser phaser = new Phaser(THREADS);

        PhaseRound() {
            for (int i = 0; i < THREADS; i++) {
                results[i] = new PartyResult();
            }
        }
    }

    private static final Rounds<PhaseRound> PHASE_ROUNDS = new Rounds<>(PhaseRound::new);
    private static final Rounds<PhaseRound> EARLY_READ_PHASE_ROUNDS = new Rounds<>(PhaseRound::new);

    /**
     * Each thread writes its own result, arrives at the round's phaser and waits for the advance,
     * then reads every result: the {@code CyclicBarrier} row above written with the API that
     * replaces it when parties come and go.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_phaser_readsAfterTheAdvance() {
        correct(() -> {
            Turn<PhaseRound> turn = PHASE_ROUNDS.next();
            PhaseRound round = turn.shared();
            round.results[turn.ticket() % THREADS].value = turn.ticket();
            round.phaser.arriveAndAwaitAdvance();
            for (PartyResult result : round.results) {
                use(result.value);
            }
        });
    }

    /** The same write and arrival, with every result read before the arrival. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_phaser_readsBeforeTheAdvance() {
        broken(() -> {
            Turn<PhaseRound> turn = EARLY_READ_PHASE_ROUNDS.next();
            PhaseRound round = turn.shared();
            round.results[turn.ticket() % THREADS].value = turn.ticket();
            for (PartyResult result : round.results) {
                use(result.value);
            }
            round.phaser.arriveAndAwaitAdvance();
        });
    }

    // --- 21. A task executed on newSingleThreadExecutor (#1269) ------------------------------

    /**
     * Section 19's pair on {@code Executors.newSingleThreadExecutor()}, whose pool sits behind a
     * wrapper the JDK builds. Before #1269 {@code execute} through that wrapper ran the task
     * unwrapped, so its accesses never reached the run and both rows were silent.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_singleThreadExecute_readsAfterAwaitTermination() {
        correct(() -> {
            Result result = new Result();
            result.input = (int) Thread.currentThread().threadId();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            executor.execute(() -> result.output = result.input * 2);
            executor.shutdown();
            awaitTerminated(executor);
            use(result.output);
        });
    }

    /** The same task, with the output read before the executor is shut down and awaited. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_singleThreadExecute_readsBeforeAwaitTermination() {
        broken(() -> {
            Result result = new Result();
            result.input = (int) Thread.currentThread().threadId();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            executor.execute(() -> result.output = result.input * 2);
            use(result.output);
            executor.shutdown();
            awaitTerminated(executor);
        });
    }

    // --- 22. Bounded admission on an atomic (#1249) -------------------------------------------

    /**
     * Admissions to a round, bounded by a limit no round reaches. Ticket 0 checks the count, then
     * waits (bounded) until ticket 1 has updated it, so every round holds the interleaving that
     * over-admits: a peer's update between one thread's check and its own update.
     */
    private static final class Admissions {
        static final int LIMIT = 1_000_000;
        final AtomicInteger admitted = new AtomicInteger();
        volatile boolean checked;
        volatile boolean overtaken;

        /** Ticket 0, after its check: let ticket 1 update, and wait until it has. */
        void letAPeerOvertake() {
            checked = true;
            for (int spins = 0; !overtaken && spins < SPIN_LIMIT; spins++) {
                Thread.onSpinWait();
            }
        }

        /** Ticket 1, before its update: wait until ticket 0 has checked. */
        void awaitAPeersCheck() {
            for (int spins = 0; !checked && spins < SPIN_LIMIT; spins++) {
                Thread.onSpinWait();
            }
        }

        /** Ticket 1, after its update. */
        void overtook() {
            overtaken = true;
        }
    }

    private static final Rounds<Admissions> CAS_ADMISSIONS = new Rounds<>(Admissions::new);
    private static final Rounds<Admissions> CHECKED_ADMISSIONS = new Rounds<>(Admissions::new);

    /**
     * Each thread admits itself with a get and compareAndSet loop, which re-checks every lost race:
     * ticket 0's first compareAndSet loses to ticket 1's and its retry reads again.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_boundedAdmission_compareAndSetLoop() {
        correct(() -> {
            Turn<Admissions> turn = CAS_ADMISSIONS.next();
            Admissions admissions = turn.shared();
            if (turn.ticket() == 1) {
                admissions.awaitAPeersCheck();
            }
            boolean first = true;
            for (;;) {
                int now = admissions.admitted.get();
                if (first && turn.ticket() == 0) {
                    admissions.letAPeerOvertake();
                }
                first = false;
                if (now >= Admissions.LIMIT || admissions.admitted.compareAndSet(now, now + 1)) {
                    break;
                }
            }
            if (turn.ticket() == 1) {
                admissions.overtook();
            }
        });
    }

    /**
     * Each thread checks the count, then increments it: ticket 1's increment lands between ticket
     * 0's check and its increment, so both pass the check and the limit can be exceeded.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_boundedAdmission_checkThenIncrement() {
        broken(() -> {
            Turn<Admissions> turn = CHECKED_ADMISSIONS.next();
            Admissions admissions = turn.shared();
            if (turn.ticket() == 1) {
                admissions.awaitAPeersCheck();
            }
            if (admissions.admitted.get() < Admissions.LIMIT) {
                if (turn.ticket() == 0) {
                    admissions.letAPeerOvertake();
                }
                admissions.admitted.incrementAndGet();
            }
            if (turn.ticket() == 1) {
                admissions.overtook();
            }
        });
    }

    // --- 23. Bounded admission on a ConcurrentHashMap (#1249) ---------------------------------

    /**
     * Members admitted to a round's map, bounded by a limit no round reaches, with the same
     * choreography as {@link Admissions}: ticket 0 checks the size, then waits (bounded) until
     * ticket 1 has put, so every round of the raced row holds the interleaving that overfills.
     */
    private static final class Membership {
        static final int LIMIT = 1_000_000;
        final ConcurrentHashMap<String, Object> map = new ConcurrentHashMap<>();
        final Admissions choreography = new Admissions();
    }

    private static final Rounds<Membership> RACED_MEMBERSHIP = new Rounds<>(Membership::new);
    private static final Rounds<Membership> GUARDED_MEMBERSHIP = new Rounds<>(Membership::new);

    /**
     * Each thread checks the size and puts under the map's own monitor, so no put lands between a
     * check and the put it decides.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_boundedMembership_sizeThenPutUnderTheMonitor() {
        correct(() -> {
            Turn<Membership> turn = GUARDED_MEMBERSHIP.next();
            Membership members = turn.shared();
            synchronized (members.map) {
                if (members.map.size() < Membership.LIMIT) {
                    members.map.put("member-" + turn.ticket(), Boolean.TRUE);
                }
            }
        });
    }

    /**
     * Each thread checks the size, then puts its own key: ticket 1's put lands between ticket 0's
     * check and its put, so both pass the check and the map can end over its limit.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_boundedMembership_sizeThenPut() {
        broken(() -> {
            Turn<Membership> turn = RACED_MEMBERSHIP.next();
            Membership members = turn.shared();
            if (turn.ticket() == 1) {
                members.choreography.awaitAPeersCheck();
            }
            if (members.map.size() < Membership.LIMIT) {
                if (turn.ticket() == 0) {
                    members.choreography.letAPeerOvertake();
                }
                members.map.put("member-" + turn.ticket(), Boolean.TRUE);
            }
            if (turn.ticket() == 1) {
                members.choreography.overtook();
            }
        });
    }

    // --- 24. Taking one job from a concurrent queue (#1249) -----------------------------------

    /**
     * A round's jobs, two per thread so no take finds the queue empty, with the choreography of
     * {@link Admissions}: ticket 0 checks, then waits (bounded) until ticket 1 has taken, so every
     * round of the raced row holds a take between ticket 0's check and its remove.
     */
    private static final class Jobs {
        final ConcurrentLinkedQueue<Object> queue = new ConcurrentLinkedQueue<>();
        final Admissions choreography = new Admissions();

        Jobs() {
            for (int i = 0; i < 2 * THREADS; i++) {
                queue.add("job-" + i);
            }
        }
    }

    private static final Rounds<Jobs> CHECKED_TAKES = new Rounds<>(Jobs::new);
    private static final Rounds<Jobs> POLLED_TAKES = new Rounds<>(Jobs::new);

    /**
     * Each thread takes a job with poll and tests the result for null, which no other thread's take
     * can invalidate: ticket 1's take before ticket 0's poll only changes which job ticket 0 gets.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_takeOneJob_pollThenNullCheck() {
        correct(() -> {
            Turn<Jobs> turn = POLLED_TAKES.next();
            Jobs jobs = turn.shared();
            if (turn.ticket() == 1) {
                jobs.choreography.awaitAPeersCheck();
            }
            if (turn.ticket() == 0) {
                jobs.choreography.letAPeerOvertake();
            }
            Object job = jobs.queue.poll();
            if (job != null && turn.ticket() == 1) {
                jobs.choreography.overtook();
            }
        });
    }

    /**
     * Each thread checks the queue is not empty, then removes: ticket 1's take lands between ticket
     * 0's check and its remove, so ticket 0 takes a job its check never saw, and on the last job it
     * would throw NoSuchElementException.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_takeOneJob_isEmptyThenRemove() {
        broken(() -> {
            Turn<Jobs> turn = CHECKED_TAKES.next();
            Jobs jobs = turn.shared();
            if (turn.ticket() == 1) {
                jobs.choreography.awaitAPeersCheck();
            }
            if (!jobs.queue.isEmpty()) {
                if (turn.ticket() == 0) {
                    jobs.choreography.letAPeerOvertake();
                }
                jobs.queue.remove();
            }
            if (turn.ticket() == 1) {
                jobs.choreography.overtook();
            }
        });
    }

    // --- 25. A semaphore as a signal: one thread releases, the others acquire ------------------

    private static final Rounds<Semaphore> SIGNALS = new Rounds<>(() -> new Semaphore(0));
    private static final Rounds<Semaphore> UNSIGNALLED = new Rounds<>(() -> new Semaphore(THREADS));

    /**
     * The round's first thread sends one permit per waiter, and every other thread waits for one.
     * Nobody holds anything: the releases come before the acquisitions they wake, from a thread
     * that never acquires.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_semaphore_signalsItsWaiters() {
        correct(() -> {
            Turn<Semaphore> turn = SIGNALS.next();
            if (turn.opensTheRound()) {
                turn.shared().release(THREADS - 1);
            } else {
                turn.shared().acquire();
            }
        });
    }

    /**
     * The permits are there from the start and nobody sends any: every thread takes one and
     * returns nothing, so the round ends with the semaphore drained.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_semaphore_takesWhatNobodySends() {
        broken(() -> UNSIGNALLED.next().shared().acquire());
    }

    // --- 26. A mutable object handed off through a drain (#1295) ------------------------------

    private static final Rounds<BlockingQueue<Order>> DRAINED_ORDERS = new Rounds<>(LinkedBlockingQueue::new);
    private static final Rounds<BlockingQueue<Order>> KEPT_ORDERS = new Rounds<>(LinkedBlockingQueue::new);

    /** Drains one order into a fresh batch, spinning until a producer's put has landed. */
    private static Order drainOne(BlockingQueue<Order> orders) {
        List<Order> batch = new ArrayList<>();
        while (batch.isEmpty()) {
            orders.drainTo(batch, 1);
            Thread.onSpinWait();
        }
        return batch.get(0);
    }

    /**
     * The hand-off of row 1 with a batch consumer: half the threads put an order, the other half
     * drain one into a list and update it. The drain is the take, and the taker owns what it got.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_drainTo_handsOffAMutableObject() {
        correct(() -> {
            Turn<BlockingQueue<Order>> turn = DRAINED_ORDERS.next();
            if (turn.ticket() % 2 == 0) {
                Order order = new Order();
                order.item = "widget";
                order.quantity = turn.ticket();
                turn.shared().put(order);
            } else {
                Order order = drainOne(turn.shared());
                order.quantity++;
                use(order.item.length() + order.quantity);
            }
        });
    }

    /**
     * The producer keeps the order it put and goes on updating it, so its update and the
     * drainer's are two unordered writes of one field: the put handed over nothing it kept.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_drainTo_producerKeepsWritingAfterThePut() {
        broken(() -> {
            Turn<BlockingQueue<Order>> turn = KEPT_ORDERS.next();
            if (turn.ticket() % 2 == 0) {
                Order order = new Order();
                order.item = "widget";
                order.quantity = turn.ticket();
                turn.shared().put(order);
                order.quantity++;
            } else {
                Order order = drainOne(turn.shared());
                order.quantity++;
                use(order.item.length() + order.quantity);
            }
        });
    }

    // --- 27. A volatile field every thread only writes (#1296) --------------------------------

    private static final class Status {
        volatile int last;
    }

    private static final Rounds<Status> STATUSES = new Rounds<>(Status::new);
    private static final Rounds<Status> COUNTED_STATUSES = new Rounds<>(Status::new);

    /**
     * Last writer wins: every thread stores its own value and none reads the field first. There
     * is no check for another thread's store to invalidate, and the volatile orders the stores.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_volatileStatus_lastWriterWins() {
        correct(() -> {
            Turn<Status> turn = STATUSES.next();
            turn.shared().last = turn.ticket();
        });
    }

    /** The same field read and then written: a volatile makes each step visible, not the pair atomic. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_volatileStatus_countsWithReadThenWrite() {
        broken(() -> {
            Status status = COUNTED_STATUSES.next().shared();
            status.last = status.last + 1;
        });
    }

    // --- 28. A lazily built value in a ConcurrentHashMap.computeIfAbsent cache (#1294) --------

    private static final class Limits {
        int limit;
    }

    private static final Rounds<ConcurrentHashMap<String, Limits>> LIMITS =
            new Rounds<>(ConcurrentHashMap::new);
    private static final Rounds<ConcurrentHashMap<String, Limits>> LATE_LIMITS =
            new Rounds<>(ConcurrentHashMap::new);

    /**
     * The lazy cache: every thread asks for the one entry, the first builds it inside the
     * function, and all of them read what the call returned. The map publishes what it stores.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_computeIfAbsent_buildsACachedValueOnce() {
        correct(() -> {
            Limits limits = LIMITS.next().shared().computeIfAbsent("default", key -> {
                Limits built = new Limits();
                built.limit = 10;
                return built;
            });
            use(limits.limit);
        });
    }

    /** The same cache, with the value filled in after the call that published it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_computeIfAbsent_fillsTheValueInAfterPublishingIt() {
        broken(() -> {
            Turn<ConcurrentHashMap<String, Limits>> turn = LATE_LIMITS.next();
            Limits limits = turn.shared().computeIfAbsent("default", key -> new Limits());
            if (turn.opensTheRound()) {
                limits.limit = 10;
            }
            use(limits.limit);
        });
    }

    // --- 29. Listeners registered in a CopyOnWriteArrayList and walked by a for-each (#1294) --

    private static final class Listener {
        int priority;
    }

    private static final Rounds<CopyOnWriteArrayList<Listener>> LISTENERS =
            new Rounds<>(CopyOnWriteArrayList::new);
    private static final Rounds<CopyOnWriteArrayList<Listener>> LATE_LISTENERS =
            new Rounds<>(CopyOnWriteArrayList::new);

    /** Spins until the round's registration is visible, so every reader walks a listener. */
    private static void awaitARegistration(List<Listener> listeners) {
        while (listeners.isEmpty()) {
            Thread.onSpinWait();
        }
    }

    /**
     * One thread configures a listener and registers it; the others walk the list and read it.
     * The add publishes what was written before it, to every later traversal.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_copyOnWriteListeners_registeredThenWalked() {
        correct(() -> {
            Turn<CopyOnWriteArrayList<Listener>> turn = LISTENERS.next();
            if (turn.opensTheRound()) {
                Listener listener = new Listener();
                listener.priority = 3;
                turn.shared().add(listener);
            } else {
                awaitARegistration(turn.shared());
                for (Listener listener : turn.shared()) {
                    use(listener.priority);
                }
            }
        });
    }

    /** The same registration, with the listener configured after the add that published it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_copyOnWriteListeners_configuredAfterRegistering() {
        broken(() -> {
            Turn<CopyOnWriteArrayList<Listener>> turn = LATE_LISTENERS.next();
            if (turn.opensTheRound()) {
                Listener listener = new Listener();
                turn.shared().add(listener);
                listener.priority = 3;
            } else {
                awaitARegistration(turn.shared());
                for (Listener listener : turn.shared()) {
                    use(listener.priority);
                }
            }
        });
    }

    // --- 30. A registry filled with putIfAbsent and read with getOrDefault (#1294) -----------

    private static final Limits NO_LIMITS = new Limits();
    private static final Rounds<ConcurrentHashMap<String, Limits>> REGISTRY =
            new Rounds<>(ConcurrentHashMap::new);
    private static final Rounds<ConcurrentHashMap<String, Limits>> LATE_REGISTRY =
            new Rounds<>(ConcurrentHashMap::new);

    /** Spins until the round's entry is visible, so every reader reads the registered value. */
    private static void awaitAnEntry(Map<String, Limits> registry) {
        while (registry.isEmpty()) {
            Thread.onSpinWait();
        }
    }

    /** One thread builds a value and registers it; the others look it up with a default. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_putIfAbsent_registersAValueReadWithGetOrDefault() {
        correct(() -> {
            Turn<ConcurrentHashMap<String, Limits>> turn = REGISTRY.next();
            if (turn.opensTheRound()) {
                Limits built = new Limits();
                built.limit = 10;
                turn.shared().putIfAbsent("default", built);
            } else {
                awaitAnEntry(turn.shared());
                use(turn.shared().getOrDefault("default", NO_LIMITS).limit);
            }
        });
    }

    /** The same registry, with the value filled in after the putIfAbsent that published it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_putIfAbsent_fillsTheValueInAfterRegisteringIt() {
        broken(() -> {
            Turn<ConcurrentHashMap<String, Limits>> turn = LATE_REGISTRY.next();
            if (turn.opensTheRound()) {
                Limits built = new Limits();
                turn.shared().putIfAbsent("default", built);
                built.limit = 10;
            } else {
                awaitAnEntry(turn.shared());
                use(turn.shared().getOrDefault("default", NO_LIMITS).limit);
            }
        });
    }

    // --- 31. Listeners notified with forEach and with a stream (#1294) ------------------------

    private static final Rounds<CopyOnWriteArrayList<Listener>> NOTIFIED =
            new Rounds<>(CopyOnWriteArrayList::new);
    private static final Rounds<CopyOnWriteArrayList<Listener>> LATE_NOTIFIED =
            new Rounds<>(CopyOnWriteArrayList::new);

    /** One thread registers a configured listener; the others notify with forEach and a stream. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_copyOnWriteListeners_notifiedWithForEachAndAStream() {
        correct(() -> {
            Turn<CopyOnWriteArrayList<Listener>> turn = NOTIFIED.next();
            if (turn.opensTheRound()) {
                Listener listener = new Listener();
                listener.priority = 3;
                turn.shared().add(listener);
            } else {
                awaitARegistration(turn.shared());
                if (turn.ticket() % 2 == 0) {
                    turn.shared().forEach(listener -> use(listener.priority));
                } else {
                    use(turn.shared().stream().mapToInt(listener -> listener.priority).sum());
                }
            }
        });
    }

    /** The same notification, with the listener configured after the add that published it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_copyOnWriteListeners_notifiedWhileStillBeingConfigured() {
        broken(() -> {
            Turn<CopyOnWriteArrayList<Listener>> turn = LATE_NOTIFIED.next();
            if (turn.opensTheRound()) {
                Listener listener = new Listener();
                turn.shared().add(listener);
                listener.priority = 3;
            } else {
                awaitARegistration(turn.shared());
                if (turn.ticket() % 2 == 0) {
                    turn.shared().forEach(listener -> use(listener.priority));
                } else {
                    use(turn.shared().stream().mapToInt(listener -> listener.priority).sum());
                }
            }
        });
    }

    // --- 32. A shared java.util.BitSet used as a set of flags (#1297) -------------------------

    private static final Rounds<BitSet> GUARDED_FLAGS = new Rounds<>(BitSet::new);
    private static final Rounds<BitSet> RACED_FLAGS = new Rounds<>(BitSet::new);

    /** Every thread sets its own bit and counts the set ones, inside synchronized on the BitSet. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_bitSet_setUnderItsOwnMonitor() {
        correct(() -> {
            Turn<BitSet> turn = GUARDED_FLAGS.next();
            BitSet flags = turn.shared();
            synchronized (flags) {
                flags.set(turn.ticket() % 60);
                use(flags.cardinality());
            }
        });
    }

    /** The same set with no lock: two threads updating one word lose a bit. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_bitSet_setByEveryThread() {
        broken(() -> {
            Turn<BitSet> turn = RACED_FLAGS.next();
            BitSet flags = turn.shared();
            flags.set(turn.ticket() % 60);
            use(flags.cardinality());
        });
    }

    // --- 33. A FutureTask the caller runs itself, the Memoizer idiom (#1307) ------------------

    private static FutureTask<Limits> limitsTask() {
        return new FutureTask<>(() -> {
            Limits built = new Limits();
            built.limit = 10;
            return built;
        });
    }

    private static final Rounds<FutureTask<Limits>> MEMOIZED = new Rounds<>(CorpusIdiomLaneTest::limitsTask);
    private static final Rounds<FutureTask<Limits>> LATE_MEMOIZED =
            new Rounds<>(CorpusIdiomLaneTest::limitsTask);

    /** One thread runs the task on its own thread; the others get its result and read it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_futureTask_runByOneThreadAndGotByTheRest() {
        correct(() -> {
            Turn<FutureTask<Limits>> turn = MEMOIZED.next();
            if (turn.opensTheRound()) {
                turn.shared().run();
            } else {
                use(getUnchecked(turn.shared()).limit);
            }
        });
    }

    /** The same task, with the runner still writing the result after the run published it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_futureTask_resultWrittenAfterTheRun() {
        broken(() -> {
            Turn<FutureTask<Limits>> turn = LATE_MEMOIZED.next();
            if (turn.opensTheRound()) {
                turn.shared().run();
                getUnchecked(turn.shared()).limit = turn.ticket();
            } else {
                use(getUnchecked(turn.shared()).limit);
            }
        });
    }

    // --- 34. Values read through a navigation read and through entrySet() (#1294) -------------

    private static final Rounds<ConcurrentSkipListMap<String, Limits>> ORDERED =
            new Rounds<>(ConcurrentSkipListMap::new);
    private static final Rounds<ConcurrentSkipListMap<String, Limits>> LATE_ORDERED =
            new Rounds<>(ConcurrentSkipListMap::new);
    private static final Rounds<ConcurrentHashMap<String, Limits>> ENTRIES =
            new Rounds<>(ConcurrentHashMap::new);
    private static final Rounds<ConcurrentHashMap<String, Limits>> LATE_ENTRIES =
            new Rounds<>(ConcurrentHashMap::new);

    /** One thread puts a built value in a sorted map; the others read it through firstEntry. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_skipListMap_valueReadThroughFirstEntry() {
        correct(() -> {
            Turn<ConcurrentSkipListMap<String, Limits>> turn = ORDERED.next();
            if (turn.opensTheRound()) {
                Limits built = new Limits();
                built.limit = 10;
                turn.shared().put("default", built);
            } else {
                awaitAnEntry(turn.shared());
                use(turn.shared().firstEntry().getValue().limit);
            }
        });
    }

    /** The same sorted map, with the value filled in after the put that published it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_skipListMap_valueFilledInAfterThePut() {
        broken(() -> {
            Turn<ConcurrentSkipListMap<String, Limits>> turn = LATE_ORDERED.next();
            if (turn.opensTheRound()) {
                Limits built = new Limits();
                turn.shared().put("default", built);
                built.limit = 10;
            } else {
                awaitAnEntry(turn.shared());
                use(turn.shared().firstEntry().getValue().limit);
            }
        });
    }

    /** One thread puts a built value; the others loop over entrySet() and read each value. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_concurrentHashMap_valuesReadThroughEntrySet() {
        correct(() -> {
            Turn<ConcurrentHashMap<String, Limits>> turn = ENTRIES.next();
            if (turn.opensTheRound()) {
                Limits built = new Limits();
                built.limit = 10;
                turn.shared().put("default", built);
            } else {
                awaitAnEntry(turn.shared());
                for (Map.Entry<String, Limits> entry : turn.shared().entrySet()) {
                    use(entry.getValue().limit);
                }
            }
        });
    }

    /** The same loop, with the value filled in after the put that published it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_concurrentHashMap_entryValueFilledInAfterThePut() {
        broken(() -> {
            Turn<ConcurrentHashMap<String, Limits>> turn = LATE_ENTRIES.next();
            if (turn.opensTheRound()) {
                Limits built = new Limits();
                turn.shared().put("default", built);
                built.limit = 10;
            } else {
                awaitAnEntry(turn.shared());
                for (Map.Entry<String, Limits> entry : turn.shared().entrySet()) {
                    use(entry.getValue().limit);
                }
            }
        });
    }

    // --- Harness -----------------------------------------------------------------------------

    /**
     * A ticket counter and one object per round, built before the run.
     *
     * <p>Rounds run one after another and each runs the body exactly {@link #THREADS} times, so
     * ticket {@code t} belongs to round {@code t / THREADS}, and the first ticket of a round is
     * the round's writer. The per-round objects are built on the class-initialising thread, before
     * any round starts. Every row owns its own instance, so each row's tickets start at zero.
     */
    private static final class Rounds<T> {
        private final AtomicInteger tickets = new AtomicInteger();
        private final Object[] perRound = new Object[INVOCATIONS];

        Rounds(Supplier<? extends T> fresh) {
            for (int round = 0; round < INVOCATIONS; round++) {
                perRound[round] = fresh.get();
            }
        }

        @SuppressWarnings("unchecked")
        Turn<T> next() {
            int ticket = tickets.getAndIncrement();
            return new Turn<>((T) perRound[(ticket / THREADS) % INVOCATIONS], ticket);
        }
    }

    /** One body execution's ticket and its round's shared object. */
    private record Turn<T>(T shared, int ticket) {

        boolean opensTheRound() {
            return ticket % THREADS == 0;
        }

        int round() {
            return ticket / THREADS;
        }
    }

    private interface InterruptibleBody {
        void run() throws InterruptedException;
    }

    /**
     * Runs a correct row's body. Nothing is swallowed: a correct idiom that throws is a failure
     * of the row, not noise.
     */
    private static void correct(InterruptibleBody body) {
        CorpusRecorder.countBodyExecution();
        try {
            body.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("nothing in this lane interrupts a worker", e);
        }
    }

    /**
     * Runs a broken twin's body, discarding what the race throws: an ArrayDeque or an ArrayList
     * raced by six threads fails by throwing, after the woven call has already been observed.
     */
    private static void broken(InterruptibleBody body) {
        CorpusRecorder.countBodyExecution();
        try {
            body.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("nothing in this lane interrupts a worker", e);
        } catch (RuntimeException expected) {
            // The bug, behaving like the bug.
        }
    }

    private static <T> T getUnchecked(Future<T> task) throws InterruptedException {
        try {
            return task.get();
        } catch (ExecutionException e) {
            throw new IllegalStateException("the task failed", e.getCause());
        }
    }

    /** Consumes a value so that no read in a body is dead code. */
    private static void use(long value) {
        if (value == Long.MIN_VALUE) {
            throw new AssertionError(value);
        }
    }

    private static BlockingQueue<MessageDigest> digestPool() {
        BlockingQueue<MessageDigest> pool = new LinkedBlockingQueue<>();
        for (int i = 0; i < THREADS; i++) {
            try {
                pool.add(MessageDigest.getInstance("SHA-256"));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is required of every JRE", e);
            }
        }
        return pool;
    }
}