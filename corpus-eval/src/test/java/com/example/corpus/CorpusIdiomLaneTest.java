package com.example.corpus;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.diagnostics.RaceConditionDetector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Exchanger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

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
 * {@code detectAll = true} and the agent attached as {@code fields=true,collections=true}, and
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
     * The parent writes the input, starts a child that computes, joins it, reads the output.
     * Manual API: the agent drops a thread the runner did not start (#500), so the child's half
     * is reported by the body; the detector is fetched on the worker, where the context lives.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_threadStartJoin_ordersTheChildsWrite() {
        correct(() -> {
            RaceConditionDetector races = AsyncTestContext.raceConditionDetector();
            Result result = new Result();
            races.recordFieldWrite(result, "input");
            result.input = (int) Thread.currentThread().threadId();
            Thread child = new Thread(() -> {
                races.recordFieldRead(result, "input");
                int input = result.input;
                races.recordFieldWrite(result, "output");
                result.output = input * 2;
            });
            child.start();
            child.join();
            races.recordFieldRead(result, "output");
            use(result.output);
        });
    }

    /** The same child, with the output read before the join instead of after it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_threadStartJoin_readsBeforeTheJoin() {
        broken(() -> {
            RaceConditionDetector races = AsyncTestContext.raceConditionDetector();
            Result result = new Result();
            races.recordFieldWrite(result, "input");
            result.input = (int) Thread.currentThread().threadId();
            Thread child = new Thread(() -> {
                races.recordFieldRead(result, "input");
                int input = result.input;
                races.recordFieldWrite(result, "output");
                result.output = input * 2;
            });
            child.start();
            races.recordFieldRead(result, "output");
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
    // --- Known gaps: correct idioms the happens-before model does not see yet ------------------

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
     * Submit a task that reads the input and writes the output, get() it, read the output.
     * Manual API: the pool thread is not a runner worker, so the agent drops its half (#500).
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_executorSubmit_futureGetOrdersTheTask() {
        correct(() -> {
            RaceConditionDetector races = AsyncTestContext.raceConditionDetector();
            Result result = new Result();
            races.recordFieldWrite(result, "input");
            result.input = (int) Thread.currentThread().threadId();
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                Future<?> task = executor.submit(() -> {
                    races.recordFieldRead(result, "input");
                    int input = result.input;
                    races.recordFieldWrite(result, "output");
                    result.output = input * 2;
                });
                getUnchecked(task);
                races.recordFieldRead(result, "output");
                use(result.output);
            }
        });
    }

    /** The same task, with the output read before the get(). */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000, detectAll = true)
    void idiom_executorSubmit_readsBeforeTheGet() {
        broken(() -> {
            RaceConditionDetector races = AsyncTestContext.raceConditionDetector();
            Result result = new Result();
            races.recordFieldWrite(result, "input");
            result.input = (int) Thread.currentThread().threadId();
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                Future<?> task = executor.submit(() -> {
                    races.recordFieldRead(result, "input");
                    int input = result.input;
                    races.recordFieldWrite(result, "output");
                    result.output = input * 2;
                });
                races.recordFieldRead(result, "output");
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

    private static void getUnchecked(Future<?> task) throws InterruptedException {
        try {
            task.get();
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