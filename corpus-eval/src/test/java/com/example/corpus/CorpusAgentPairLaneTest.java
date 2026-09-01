package com.example.corpus;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import se.deversity.asynctest.AsyncTest;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Formatter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.StampedLock;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The agent-pair lane: JDK types, with test bodies that record nothing at all.
 *
 * <p><strong>Why it exists.</strong> The recording lane reaches a detector by calling the
 * {@code record*} API on its behalf. Eighteen detectors have no such call to make: their input is
 * a JDK call site the agent rewrites, and nothing in {@code AsyncTestContext} stands in for it.
 * They were therefore unpaired in every lane - present in the roster, exercised by nothing, with
 * "no false positive from detector X" and "X never ran" the same row all over again, which is the
 * exact failure the recording lane was built to end.
 *
 * <p><strong>How a pair works here.</strong> There is no instrumentation to write, so the two
 * halves are the bug and its fix, written the way they are written in real code. The MUST_FIRE
 * row puts one instance in a static field and lets every thread call it; the MUST_STAY_SILENT row
 * gives each thread its own. Everything else is the same: the same methods, on the same types,
 * through the same substituted call sites. What separates the rows is which object is on the
 * receiver end, which is precisely the question these detectors claim to answer.
 *
 * <p><strong>What holds it honest.</strong> {@link AgentRowPremise} fails the lane if any body
 * touches the recording API, because a row that fed its own detector would pass while measuring
 * nothing. {@link CorpusGates#checkPairLane} requires the agent to be attached with
 * {@code -javaagent} here, for the same reason the recording lane requires it to be absent: with
 * only one feed live, a finding has exactly one possible source.
 *
 * <p><strong>Why the bodies swallow exceptions.</strong> Sharing these instances is not merely
 * unsound, it throws - {@code SimpleDateFormat.format} from inside its own {@code Calendar},
 * {@code StringBuilder.append} from the array copy, {@code Matcher.group} with no match in
 * progress. That is the bug doing what the bug does, and it happens after the substituted call
 * site has already reported. Letting it out would fail the run for succeeding.
 */
@ExtendWith(SubjectTracking.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CorpusAgentPairLaneTest {

    static final int THREADS = 6;
    static final int INVOCATIONS = 40;

    /** The pattern every matcher row compiles against; Pattern itself is thread-safe. */
    private static final Pattern PATTERN = Pattern.compile("([a-z]+)-([0-9]+)");

    /** The input both matcher rows match, so the two differ only in the Matcher's scope. */
    private static final String MATCH_INPUT = "corpus-42";

    /** The bytes both digest rows feed, so the two differ only in the digest's scope. */
    private static final byte[] PAYLOAD = "corpus".getBytes(StandardCharsets.UTF_8);

    // --- The shared halves. One instance each, reached by every thread of every round, which is
    //     what a cached formatter in a static field is.

    private static final SimpleDateFormat SHARED_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");

    private static final Matcher SHARED_MATCHER = PATTERN.matcher(MATCH_INPUT);

    private static MessageDigest sharedDigest;

    private static final Calendar SHARED_CALENDAR = Calendar.getInstance();

    private static final StringBuilder SHARED_BUILDER = new StringBuilder();

    private static final DecimalFormat SHARED_DECIMAL_FORMAT = new DecimalFormat("#,##0.00");

    private static final Formatter SHARED_FORMATTER = new Formatter(new StringBuilder());

    // --- The lock-order pair'"'"'s two locks. Static, because the detector pools its edges by lock
    //     identity across the whole run: a fresh pair per body execution would produce a fresh
    //     pair of node names and never close a cycle.

    private static final ReentrantLock LOCK_A = new ReentrantLock();

    private static final ReentrantLock LOCK_B = new ReentrantLock();

    /**
     * Serialises the lock-order rows, so that writing an inversion does not mean suffering one.
     *
     * <p>A monitor rather than a Lock on purpose: monitor acquisitions are not delivered to
     * LockOrderValidator, so this adds no edge to the graph the pair is measuring.
     */
    private static final Object ORDER_GUARD = new Object();

    // --- The confined halves. A ThreadLocal where building the instance is the expense that made
    //     someone cache it in the first place, and a plain local where it is not.

    private static final ThreadLocal<SimpleDateFormat> CONFINED_DATE_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));

    private static final ThreadLocal<MessageDigest> CONFINED_DIGEST =
            ThreadLocal.withInitial(CorpusAgentPairLaneTest::newDigest);

    private static final ThreadLocal<DecimalFormat> CONFINED_DECIMAL_FORMAT =
            ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.00"));

    @BeforeAll
    static void installRecorder() throws NoSuchAlgorithmException {
        CorpusRecorder.install();
        sharedDigest = MessageDigest.getInstance("SHA-256");
    }

    @AfterAll
    static void reportAndGate() throws IOException {
        CorpusRecorder.uninstall();
        SHARED_FORMATTER.close();
        CorpusLane lane = CorpusLane.current();
        Path report = CorpusReport.writeRecording(
                CorpusRecorder.findings(), THREADS, INVOCATIONS, lane);
        System.out.println("Corpus agent-pair-lane report written to " + report.toAbsolutePath());
        System.out.println(CorpusReport.recordingSummary(CorpusRecorder.findings(), lane));
        theDeadlockRowsRanInOrder();
        CorpusGates.checkPairLane(
                CorpusRecorder.findings(), lane, CorpusAgentPairLaneTest.class);
    }

    // --- SimpleDateFormat --------------------------------------------------------------------

    /**
     * Every thread formats through the one instance in the static field.
     *
     * <p>{@code format} runs the date into the instance's own {@code Calendar} and then reads the
     * fields back out of it, so a second thread's write lands between the first thread's write and
     * its read. The output is silently wrong when it is not an exception.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_simpleDateFormat_oneInstanceForEveryThread() {
        swallowingTheRace(() -> SHARED_DATE_FORMAT.format(new Date()));
    }

    /** The same call, on the per-thread instance the class javadoc tells you to use. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_simpleDateFormat_oneInstancePerThread() {
        swallowingTheRace(() -> CONFINED_DATE_FORMAT.get().format(new Date()));
    }

    // --- Matcher -----------------------------------------------------------------------------

    /**
     * Every thread drives the one Matcher in the static field.
     *
     * <p>A Matcher holds the region, the append position and the group bounds of the last match.
     * Two threads in one instance means {@code group} reads bounds another thread has already
     * replaced, or none at all.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_matcher_oneInstanceForEveryThread() {
        swallowingTheRace(() -> {
            SHARED_MATCHER.reset();
            if (SHARED_MATCHER.find()) {
                SHARED_MATCHER.group(1);
            }
        });
    }

    /** A Matcher per call from the shared Pattern, which is how the API is meant to be used. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_matcher_oneInstancePerThread() {
        swallowingTheRace(() -> {
            Matcher mine = PATTERN.matcher(MATCH_INPUT);
            mine.reset();
            if (mine.find()) {
                mine.group(1);
            }
        });
    }

    // --- MessageDigest -----------------------------------------------------------------------

    /**
     * Every thread accumulates into the one digest.
     *
     * <p>{@code update} appends to the instance's buffer and {@code digest} drains and resets it,
     * so what comes out is a hash of an interleaving of every thread's input. Nothing throws and
     * nothing looks wrong; the value is just not the hash of anything anyone asked for.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_messageDigest_oneInstanceForEveryThread() {
        swallowingTheRace(() -> {
            sharedDigest.update(PAYLOAD);
            sharedDigest.digest();
        });
    }

    /** The same two calls against a digest the thread owns, which is the documented pattern. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_messageDigest_oneInstancePerThread() {
        swallowingTheRace(() -> {
            MessageDigest mine = CONFINED_DIGEST.get();
            mine.update(PAYLOAD);
            mine.digest();
        });
    }

    // --- Calendar ----------------------------------------------------------------------------

    /**
     * Every thread reads and writes the one Calendar.
     *
     * <p>{@code get} computes the whole field set from the instance's time on the first call after
     * a change and caches it. A {@code set} from another thread invalidates that cache under a
     * read already in flight.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_calendar_oneInstanceForEveryThread() {
        swallowingTheRace(() -> {
            SHARED_CALENDAR.set(Calendar.MILLISECOND, 0);
            SHARED_CALENDAR.get(Calendar.DAY_OF_YEAR);
        });
    }

    /** The same field traffic against a Calendar nothing else can see. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_calendar_oneInstancePerThread() {
        swallowingTheRace(() -> {
            Calendar mine = Calendar.getInstance();
            mine.set(Calendar.MILLISECOND, 0);
            mine.get(Calendar.DAY_OF_YEAR);
        });
    }

    // --- StringBuilder -----------------------------------------------------------------------

    /**
     * Every thread appends to the one builder.
     *
     * <p>{@code append} reads {@code count}, writes the backing array at that index and writes
     * {@code count} back, with no synchronization anywhere - that is the whole difference between
     * this class and {@code StringBuffer}. A shared one loses appends or throws out of the copy.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_stringBuilder_oneInstanceForEveryThread() {
        swallowingTheRace(() -> SHARED_BUILDER.append("x"));
    }

    /** A builder local to the body, which is what the compiler emits for {@code "a" + b}. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_stringBuilder_oneInstancePerThread() {
        swallowingTheRace(() -> new StringBuilder().append("x").append("y"));
    }

    // --- DecimalFormat -----------------------------------------------------------------------

    /**
     * Every thread formats through the one DecimalFormat.
     *
     * <p>It formats through the mutable digit list it inherits from {@code NumberFormat}, whose
     * javadoc states that number formats are not synchronized, so two numbers interleave into one
     * buffer.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_decimalFormat_oneInstanceForEveryThread() {
        swallowingTheRace(() -> SHARED_DECIMAL_FORMAT.format(1234.5));
    }

    /** The same call on the per-thread instance the javadoc's remedy produces. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_decimalFormat_oneInstancePerThread() {
        swallowingTheRace(() -> CONFINED_DECIMAL_FORMAT.get().format(1234.5));
    }

    // --- Formatter ---------------------------------------------------------------------------

    /**
     * Every thread formats into the one Formatter.
     *
     * <p>A Formatter writes through to the Appendable it was constructed over and keeps the last
     * {@code IOException} as instance state, so sharing one interleaves the output and the error
     * flag together.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_formatter_oneInstanceForEveryThread() {
        swallowingTheRace(() -> SHARED_FORMATTER.format("%d;", 1));
    }

    /** A Formatter over a local builder, which is what {@code String.format} builds per call. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_formatter_oneInstancePerThread() {
        swallowingTheRace(() -> {
            try (Formatter mine = new Formatter(new StringBuilder())) {
                mine.format("%d;", 1);
            }
        });
    }

    /**
     * Sleeps one millisecond with this class's monitor held, which is the finding.
     */
    private static synchronized void sleepHoldingTheClassMonitor() throws InterruptedException {
        Thread.sleep(1);
    }

    /** The same millisecond with nothing held, which is not. */
    private static void sleepHoldingNothing() throws InterruptedException {
        Thread.sleep(1);
    }

    // --- Semaphore ---------------------------------------------------------------------------

    /**
     * Takes a permit and never gives it back.
     *
     * <p>One semaphore per body execution, so the leak cannot starve the other workers and hang
     * the round. The detector counts acquisitions against releases, and one unmatched acquire is
     * the whole precondition.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_semaphore_permitNeverReturned() {
        counted(() -> {
            Semaphore leaked = new Semaphore(1);
            leaked.acquire();
        });
    }

    /** The same two call sites with the release where it belongs. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_semaphore_permitReturnedInFinally() {
        counted(() -> {
            Semaphore balanced = new Semaphore(1);
            balanced.acquire();
            try {
                Thread.onSpinWait();
            } finally {
                balanced.release();
            }
        });
    }

    // --- CountDownLatch ----------------------------------------------------------------------

    /**
     * Waits on a latch nothing will ever count down, and drops the false.
     *
     * <p>The latch is local and its count is one, so no thread in the run can reach it. The timed
     * await must return false, which makes the finding a property of the code rather than of the
     * scheduler.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_countDownLatch_awaitTimedOut() {
        counted(() -> {
            CountDownLatch unreachable = new CountDownLatch(1);
            unreachable.await(1, TimeUnit.MILLISECONDS);
        });
    }

    /** The same timed await, on a latch this thread has already counted down. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_countDownLatch_awaitSawItsCount() {
        counted(() -> {
            CountDownLatch reached = new CountDownLatch(1);
            reached.countDown();
            reached.await(1, TimeUnit.SECONDS);
        });
    }

    // --- Latch misuse -------------------------------------------------------------------------

    /**
     * Counts a latch of one down twice, which is one count-down more than it was created for.
     *
     * <p>Nothing here says what the count is. {@code LatchMisuseDetector} has to read it off the
     * latch through the woven {@code countDown} call site, before the first count-down takes it
     * away, and this row fails if it reads anything but one: two recorded count-downs against an
     * inferred count of two is not a finding.
     *
     * <p>The latch is created in the body, so the arithmetic is over this execution's two calls
     * and nothing else.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_latchMisuse_countedDownPastItsCount() {
        counted(() -> {
            CountDownLatch overCounted = new CountDownLatch(1);
            overCounted.countDown();
            overCounted.countDown();
            overCounted.await(1, TimeUnit.SECONDS);
        });
    }

    /** The same two call sites on the same latch, counted down as many times as it was made for. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_latchMisuse_countedDownExactly() {
        counted(() -> {
            CountDownLatch exact = new CountDownLatch(1);
            exact.countDown();
            exact.await(1, TimeUnit.SECONDS);
        });
    }

    // --- Blocking queue -----------------------------------------------------------------------

    /**
     * Fills a queue of two before draining any of it, so its observed peak reaches its bound.
     *
     * <p>The bound is nowhere in this body either: {@code BlockingQueueDetector} has to take it
     * from the queue, as {@code remainingCapacity() + size()}, through the same woven call sites
     * that deliver the operations. The variable is typed as the interface because that is the
     * owner the weaver substitutes on - an {@code ArrayBlockingQueue}-typed call site is a
     * different method reference and would go unwoven.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_blockingQueue_filledToCapacity() {
        counted(() -> {
            BlockingQueue<String> saturated = new ArrayBlockingQueue<>(2);
            saturated.put("first");
            saturated.offer("second");
            saturated.poll();
        });
    }

    /** The same three call sites on the same bound, with the poll moved in between. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_blockingQueue_drainedAsItFilled() {
        counted(() -> {
            BlockingQueue<String> keepingUp = new ArrayBlockingQueue<>(2);
            keepingUp.put("first");
            keepingUp.poll();
            keepingUp.offer("second");
            keepingUp.poll();
        });
    }

    // --- Thread.sleep ------------------------------------------------------------------------

    /**
     * Sleeps inside a synchronized method, so the monitor is held for the duration.
     *
     * <p>The sleep is one level down because the substitution keyed to it is conditional on the
     * enclosing method being synchronized: that is where the monitor it reports comes from. The
     * two rows therefore differ in the {@code synchronized} modifier on the helper and in nothing
     * else, and the same-calls gate has nothing to compare here - the MUST_FIRE half having to
     * fire is what carries this pair.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_sleep_whileHoldingTheMonitor() {
        counted(CorpusAgentPairLaneTest::sleepHoldingTheClassMonitor);
    }

    /** The same one-millisecond sleep with no monitor held anywhere above it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_sleep_holdingNothing() {
        counted(CorpusAgentPairLaneTest::sleepHoldingNothing);
    }

    // --- Lock order --------------------------------------------------------------------------

    /**
     * Nests the two locks one way and then the other.
     *
     * <p>The edges are pooled by lock identity across the whole run, so the two orderings need not
     * come from two threads: one body doing both closes the cycle. That is deliberate. Writing the
     * inversion across threads would be writing a real deadlock, and the corpus would hang on it
     * rather than report it. {@code ORDER_GUARD} serialises the region for the same reason, and
     * contributes no edge of its own because a monitor is not delivered to this detector - only
     * {@code Lock} acquisitions are.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_lockOrder_nestedBothWays() {
        counted(() -> {
            synchronized (ORDER_GUARD) {
                LOCK_A.lock();
                try {
                    LOCK_B.lock();
                    LOCK_B.unlock();
                } finally {
                    LOCK_A.unlock();
                }
                LOCK_B.lock();
                try {
                    LOCK_A.lock();
                    LOCK_A.unlock();
                } finally {
                    LOCK_B.unlock();
                }
            }
        });
    }

    /** The same two locks, always A before B, which is the consistent global order. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_lockOrder_nestedOneWay() {
        counted(() -> {
            synchronized (ORDER_GUARD) {
                LOCK_A.lock();
                try {
                    LOCK_B.lock();
                    LOCK_B.unlock();
                } finally {
                    LOCK_A.unlock();
                }
                LOCK_A.lock();
                try {
                    LOCK_B.lock();
                    LOCK_B.unlock();
                } finally {
                    LOCK_A.unlock();
                }
            }
        });
    }

    // --- Lock leaks --------------------------------------------------------------------------

    /**
     * Acquires a lock and returns without releasing it.
     *
     * <p>A fresh lock per body execution, so the leak cannot block another worker. What the
     * detector sees is still an acquire with no matching release, and a lock still held when the
     * run is analysed.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_lock_acquiredAndNeverReleased() {
        counted(() -> {
            ReentrantLock leaked = new ReentrantLock();
            leaked.lock();
        });
    }

    /** The shape the ReentrantLock javadoc's own example shows. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_lock_releasedInFinally() {
        counted(() -> {
            ReentrantLock balanced = new ReentrantLock();
            balanced.lock();
            try {
                Thread.onSpinWait();
            } finally {
                balanced.unlock();
            }
        });
    }

    // --- tryLock -----------------------------------------------------------------------------

    /**
     * Unlocks after a tryLock that returned false.
     *
     * <p>Forcing the failure without another thread is what makes this structural. StampedLock is
     * not reentrant, so a write lock the calling thread already holds refuses its own
     * {@code tryLock} on every attempt, whoever else is running. The {@code unlock} then releases
     * the lock the earlier {@code lock()} took, which is exactly the bug: the code believes the
     * tryLock handed it something.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_tryLock_unlockedAfterFailing() {
        counted(() -> {
            Lock write = new StampedLock().asWriteLock();
            write.lock();
            write.tryLock();
            write.unlock();
        });
    }

    /**
     * The same call sites with the unlock inside the branch the tryLock guards.
     *
     * <p>It takes and releases the lock first so that both halves of the pair go through
     * {@code lock} as well as {@code tryLock} and {@code unlock}. The detector keys on the
     * thread's last recorded outcome for a lock, so a row that skipped the plain acquisition
     * would be skipping the shape most likely to be misjudged.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_tryLock_unlockedOnlyWhenAcquired() {
        counted(() -> {
            Lock write = new StampedLock().asWriteLock();
            write.lock();
            write.unlock();
            if (write.tryLock()) {
                try {
                    Thread.onSpinWait();
                } finally {
                    write.unlock();
                }
            }
        });
    }

    /**
     * Refuses a pass the silent deadlock row would not have earned.
     *
     * <p>Its claim is that the detector saw a clean JVM and said nothing. That holds only if it
     * ran before its twin deadlocked two threads for good, and method order is a weaker guarantee
     * than an assertion. Without this, a reordering would leave the row passing for the opposite
     * reason - silent because the finding was already everywhere - and nothing would say so.
     */
    private static void theDeadlockRowsRanInOrder() {
        assertTrue(SILENT_ROW_RAN_ON_A_CLEAN_JVM.get(),
                "the silent deadlock row has to run before the row that deadlocks two threads "
                        + "permanently, or its silence is measuring the wrong JVM. It observed "
                        + "DEADLOCK_STARTED=" + DEADLOCK_STARTED.get() + " when it ran");
    }

    // --- Deadlock ----------------------------------------------------------------------------

    /** The two monitors the deadlock rows take, in opposite orders. */
    private static final Object DEAD_A = new Object();

    private static final Object DEAD_B = new Object();

    /** True once the deadlocked pair has been started, which is a one-way door. */
    private static final AtomicBoolean DEADLOCK_STARTED = new AtomicBoolean();

    /** What the silent row observed about the JVM it ran in. */
    private static final AtomicBoolean SILENT_ROW_RAN_ON_A_CLEAN_JVM = new AtomicBoolean();

    /**
     * Runs the same monitor traffic with no deadlock anywhere in the JVM.
     *
     * <p>Unlike every other silent row in this lane, this one does not have to call anything to be
     * evidence: {@code DeadlockDetector.analyze()} samples
     * {@code ThreadMXBean.findDeadlockedThreads()} on its own, on every invocation, whether or not
     * the body did a thing. So its silence is the detector deciding, not the detector being
     * unfed - which is the distinction {@link SilentRowPremise} exists to enforce elsewhere.
     *
     * <p>It must run before its twin, because a real deadlock does not end and the JVM is never
     * clean again afterwards. {@code @Order} arranges that; {@link #theDeadlockRowsRanInOrder()}
     * refuses to let the row pass if it ever stops being true.
     */
    @Order(Integer.MAX_VALUE - 1)
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_deadlock_noThreadBlockedOnAnother() {
        counted(() -> {
            SILENT_ROW_RAN_ON_A_CLEAN_JVM.set(!DEADLOCK_STARTED.get());
            synchronized (DEAD_A) {
                synchronized (DEAD_B) {
                    Thread.sleep(1);
                }
            }
        });
    }

    /**
     * Deadlocks two daemon threads on the same two monitors and leaves them there.
     *
     * <p>The workers are deliberately not the threads that deadlock.
     * {@code findDeadlockedThreads()} reports any deadlocked thread in the JVM, so the corpus can
     * write a genuine deadlock and still finish its rounds - which is the only way this detector
     * gets a MUST_FIRE row at all. A deadlock among the workers would end the run in a round
     * timeout rather than a finding.
     *
     * <p>The pair is started once and never released, because a deadlock cannot be released. The
     * threads are daemons so the fork can still exit, and this row is ordered last so that nothing
     * else runs in the JVM it has permanently changed.
     */
    @Order(Integer.MAX_VALUE)
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_deadlock_twoThreadsBlockedOnEachOther() {
        counted(() -> {
            deadlockTwoDaemonsOnce();
            synchronized (DEAD_C) {
                Thread.sleep(1);
            }
        });
    }

    /** A third monitor, so the firing row's own body cannot join the deadlock it creates. */
    private static final Object DEAD_C = new Object();

    /**
     * Starts the deadlocked pair on the first call and waits for it to be genuinely stuck.
     *
     * <p>Each thread takes one monitor, sleeps long enough for the other to take the other, and
     * then asks for the one it does not have. Neither ever gets it.
     */
    private static void deadlockTwoDaemonsOnce() {
        if (!DEADLOCK_STARTED.compareAndSet(false, true)) {
            return;
        }
        startDaemon("corpus-deadlock-a-then-b", DEAD_A, DEAD_B);
        startDaemon("corpus-deadlock-b-then-a", DEAD_B, DEAD_A);
        settle();
    }

    private static void startDaemon(String name, Object first, Object second) {
        Thread thread = new Thread(() -> {
            synchronized (first) {
                settle();
                synchronized (second) {
                    throw new AssertionError("this row's whole claim is that neither thread "
                            + "reaches the second monitor");
                }
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
    }

    /** Long enough for both daemons to hold one monitor and be blocked on the other. */
    private static void settle() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A body that may throw a checked exception, which every coordination row can. */
    @FunctionalInterface
    private interface InterruptibleBody {
        void run() throws InterruptedException;
    }

    /**
     * Runs {@code body} and counts the execution.
     *
     * <p>Nothing in this lane interrupts a worker, so an {@code InterruptedException} here is the
     * harness misbehaving rather than the subject. It fails the run rather than being folded into
     * the silence a row might be claiming.
     *
     * @param body the JDK calls under measurement
     */
    private static void counted(InterruptibleBody body) {
        CorpusRecorder.countBodyExecution();
        try {
            body.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("nothing in this lane interrupts a worker", e);
        }
    }

    /**
     * Runs {@code body}, discarding whatever the race throws out of it.
     *
     * <p>The shared rows exist to be raced, and these JDK types fail the race by throwing:
     * {@code ArrayIndexOutOfBoundsException} out of a builder's array copy, {@code
     * IllegalStateException} out of a matcher with no match in progress, {@code
     * NumberFormatException} out of a date format's own calendar. The substituted call site has
     * already reported by then. Only unchecked exceptions are caught, so a failure in the harness
     * itself still fails the run.
     *
     * @param body the JDK calls under measurement
     */
    private static void swallowingTheRace(Runnable body) {
        // The corpus's own bookkeeping, not a detector feed: without it the report would print
        // "Body executions: 0" for a lane that ran 3,360 of them. Counted here rather than in the
        // fourteen bodies so that it cannot drift between a pair's two halves.
        CorpusRecorder.countBodyExecution();
        try {
            body.run();
        } catch (RuntimeException expected) {
            // The bug, behaving like the bug. See the javadoc.
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JRE", e);
        }
    }
}
