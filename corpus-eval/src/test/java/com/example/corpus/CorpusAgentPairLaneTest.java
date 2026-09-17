package com.example.corpus;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.databind.util.StdDateFormat;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.Monitor;
import com.google.common.util.concurrent.Uninterruptibles;
import com.zaxxer.hikari.util.UtilityElf;
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
@ExtendWith({SubjectTracking.class, LibraryRowsOnly.class})
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

    /** The Matcher every thread hands to Groovy; its own instance, so the JDK row's counts stay apart. */
    private static final Matcher SHARED_GROOVY_MATCHER = PATTERN.matcher(MATCH_INPUT);

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
        occupyTheMonitorFromAnotherThread();
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
        if (lane == CorpusLane.AGENT_PAIRS_LIBRARY_EXCLUDED) {
            // Only library rows ran, so the deadlock ordering premise and the full pair-lane gates
            // have nothing to check here; this lane asks one question and gates on it alone.
            CorpusGates.checkLibraryExclusionLane(CorpusRecorder.findings(),
                    CorpusAgentPairLaneTest.class, THREADS * INVOCATIONS);
            return;
        }
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

    /**
     * Fills a queue of two, then offers a third as a statement and never looks at the answer.
     *
     * <p>The offer is rejected and its {@code false} is popped: that is the instruction the
     * weaver's lookahead reads, and the drop it reports is the finding (#454). Saturation fires
     * here too, at two of two, so this row alone would not distinguish the two; its twin does.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_blockingQueue_rejectionDiscarded() {
        counted(() -> {
            BlockingQueue<String> full = new ArrayBlockingQueue<>(2);
            full.put("first");
            full.put("second");
            full.offer("third");
        });
    }

    /**
     * The same puts and the same offer-as-a-statement, with a poll after each put.
     *
     * <p>The offer is accepted, so the popped boolean is {@code true}, and the peak never leaves
     * one, so saturation cannot carry a finding either. This is the commonest shape offer takes
     * in production, and a lookahead that reported every popped result would fire on it.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_blockingQueue_discardedOfferAccepted() {
        counted(() -> {
            BlockingQueue<String> roomy = new ArrayBlockingQueue<>(2);
            roomy.put("first");
            roomy.poll();
            roomy.put("second");
            roomy.poll();
            roomy.offer("third");
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

    /**
     * Sleeps with a StampedLock write stamp held.
     *
     * <p>A StampedLock keeps no owner, so neither {@code Thread.holdsLock} nor an owner query can
     * say this thread holds it. The detector confirms it from the thread's own lockset entry,
     * which the woven {@code writeLock} put there, and from the lock being write-locked (#543).
     * Before that, the sleep was dropped whatever the lock's state.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_sleepStamped_whileHoldingTheWriteStamp() {
        counted(() -> {
            StampedLock lock = new StampedLock();
            long stamp = lock.writeLock();
            try {
                Thread.sleep(1);
            } finally {
                lock.unlockWrite(stamp);
            }
        });
    }

    /** The same stamp taken and released, and the same sleep after it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_sleepStamped_afterReleasingTheWriteStamp() {
        counted(() -> {
            StampedLock lock = new StampedLock();
            long stamp = lock.writeLock();
            try {
                Thread.onSpinWait();
            } finally {
                lock.unlockWrite(stamp);
            }
            Thread.sleep(1);
        });
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
        CorpusGates.theDeadlockRowsRanInOrder(SILENT_ROW_RAN_ON_A_CLEAN_JVM.get(), DEADLOCK_STARTED.get());
    }

    // --- Through library bytecode -----------------------------------------------------------
    //
    //     Every pair above calls the JDK type from this file, so the substituted call site is one
    //     the test author wrote. That proves the detector's model and says nothing about the
    //     question a user has: does the agent see the same call when it sits three frames down,
    //     inside a jar nobody here compiled? These pairs move the call site into the library. The
    //     body calls a public Guava, Jackson or HikariCP method, and the JDK call the detector is
    //     fed by is an instruction in that library's own class file, woven at load time.
    //
    //     The shape of each pair is the one the JDK rows use - the bug against its fix, through
    //     the same library methods - so a silent half is still evidence of a decision rather than
    //     of a call nobody made.

    /** A fixed instant, so every date-format body formats the same value. */
    private static final Date EPOCH = new Date(0L);

    /** A resolved Jackson type, which is immutable; the builder passed to it is the shared state. */
    private static final JavaType STRING_TYPE =
            TypeFactory.defaultInstance().constructType(String.class);

    /**
     * One Guava hasher for every thread.
     *
     * <p>{@code HashFunction.newHasher()} clones its prototype {@code MessageDigest} into a fresh
     * {@code MessageDigestHasher}, and that hasher's {@code update} is the woven call site. Kept
     * open for the whole run: {@code putBytes} never finishes it, so no body trips the hasher's
     * own single-use check and the digest call is reached on every execution.
     */
    private static final Hasher SHARED_HASHER = Hashing.sha256().newHasher();

    /** One Jackson date format for every thread; it caches its {@code Calendar} on first use. */
    private static final StdDateFormat SHARED_STD_DATE_FORMAT = new StdDateFormat();

    /** The builder every thread hands to Jackson's signature writer. */
    private static final StringBuilder SHARED_SIGNATURE = new StringBuilder();

    /** The lock-order pair's two Guava monitors, static for the reason LOCK_A and LOCK_B are. */
    private static final Monitor MONITOR_A = new Monitor();

    private static final Monitor MONITOR_B = new Monitor();

    /**
     * A Guava monitor another thread occupies for the whole run.
     *
     * <p>{@code tryEnter} is a {@code ReentrantLock.tryLock}, which succeeds for a thread that
     * already holds the lock, so the trick the StampedLock row uses to force a failure does not
     * carry over. A lock held by a thread that is not a worker does: every worker's
     * {@code tryEnter} returns false, whoever else is running, which keeps the outcome structural.
     */
    private static final Monitor OCCUPIED_MONITOR = new Monitor();

    /**
     * Occupies {@link #OCCUPIED_MONITOR} from a daemon thread and returns once it is held.
     *
     * <p>Started before any row, so no detector context exists yet and the daemon's own
     * {@code lock} feeds nothing. It never leaves: the fork exits around it, like the deadlock
     * daemons below.
     */
    private static void occupyTheMonitorFromAnotherThread() {
        Thread occupant = new Thread(() -> {
            OCCUPIED_MONITOR.enter();
            while (true) {
                java.util.concurrent.locks.LockSupport.park();
            }
        }, "corpus-monitor-occupant");
        occupant.setDaemon(true);
        occupant.start();
        while (!OCCUPIED_MONITOR.isOccupied()) {
            Thread.onSpinWait();
        }
    }

    /**
     * Every thread feeds the one Guava hasher.
     *
     * <p>The digest update is inside {@code MessageDigestHashFunction.MessageDigestHasher}, so
     * this body contains no {@code MessageDigest} call for the weaver to substitute. If the
     * detector fires, it heard about the digest from Guava's bytecode.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_guavaHasher_oneHasherForEveryThread() {
        swallowingTheRace(() -> SHARED_HASHER.putBytes(PAYLOAD));
    }

    /** A hasher per hash, which is what {@code HashFunction.newHasher()} is documented to hand out. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_guavaHasher_oneHasherPerHash() {
        swallowingTheRace(() -> Hashing.sha256().newHasher().putBytes(PAYLOAD).hash());
    }

    /**
     * Every thread formats through the one Jackson {@code StdDateFormat}.
     *
     * <p>The instance lazily clones a {@code GregorianCalendar} into a field and then reads the
     * year, month and the rest back out of it with {@code Calendar.get}, which is the woven call.
     * Jackson itself never shares one: its own configuration clones the format per use.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_jacksonStdDateFormat_oneFormatForEveryThread() {
        swallowingTheRace(() -> SHARED_STD_DATE_FORMAT.format(EPOCH));
    }

    /** The same format call on an instance of its own, so the cached calendar is confined too. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_jacksonStdDateFormat_oneFormatPerCall() {
        swallowingTheRace(() -> new StdDateFormat().format(EPOCH));
    }

    /**
     * Every thread asks Jackson to write a type signature into the one builder.
     *
     * <p>{@code TypeBase._classSignature} appends character by character, and those appends are
     * the woven call sites. The builder is reset after each call so the run does not accumulate
     * 240 signatures; the reset races too, which is the point.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_jacksonSignature_oneBuilderForEveryThread() {
        swallowingTheRace(() -> {
            STRING_TYPE.getGenericSignature(SHARED_SIGNATURE);
            SHARED_SIGNATURE.setLength(0);
        });
    }

    /** The same signature written into a builder this call made. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_jacksonSignature_oneBuilderPerCall() {
        swallowingTheRace(() -> {
            StringBuilder mine = new StringBuilder();
            STRING_TYPE.getGenericSignature(mine);
            mine.setLength(0);
        });
    }

    // --- Through a wider static type (#542). The three pairs below reach their JDK object only
    //     through DateFormat, NumberFormat.parse or Appendable, which is how these libraries hold
    //     it. Before the weaver matched those owners, all three firing rows would have been silent.

    /** RFC 1123 text, which StdDateFormat hands to the SimpleDateFormat it keeps as a DateFormat. */
    private static final String RFC_1123_EPOCH = "Thu, 01 Jan 1970 00:00:00 GMT";

    /** One Jackson date format every thread parses RFC 1123 text with. */
    private static final StdDateFormat SHARED_RFC_PARSER = new StdDateFormat();

    /** One DecimalFormat every thread hands to Spring's NumberUtils. */
    private static final java.text.NumberFormat SHARED_NUMBER_FORMAT =
            new java.text.DecimalFormat("#.##",
                    java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ROOT));

    /** The builder every thread hands to Guava's Joiner. */
    private static final StringBuilder SHARED_JOINED = new StringBuilder();

    /** A thread-safe Joiner, as Guava documents; only the builder passed to it is shared state. */
    private static final com.google.common.base.Joiner JOINER =
            com.google.common.base.Joiner.on(',');

    /**
     * Every thread parses RFC 1123 text through the one Jackson {@code StdDateFormat}.
     *
     * <p>{@code StdDateFormat} lazily clones a {@code SimpleDateFormat} into a field typed
     * {@code DateFormat} and calls {@code parse(String, ParsePosition)} on it. The owner of that
     * call is {@code DateFormat}, which is what the weaver could not match.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_jacksonRfc1123Parse_oneFormatForEveryThread() {
        swallowingTheRace(() -> {
            try {
                SHARED_RFC_PARSER.parse(RFC_1123_EPOCH);
            } catch (java.text.ParseException raced) {
                // A shared format mangled mid-parse is the bug doing what the bug does.
            }
        });
    }

    /** The same RFC 1123 parse on a format this call built. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_jacksonRfc1123Parse_oneFormatPerCall() {
        swallowingTheRace(() -> {
            try {
                new StdDateFormat().parse(RFC_1123_EPOCH);
            } catch (java.text.ParseException unexpected) {
                throw new IllegalStateException("a confined format parses RFC 1123", unexpected);
            }
        });
    }

    /**
     * Every thread parses with the one DecimalFormat, through Spring's {@code NumberUtils}.
     *
     * <p>{@code parseNumber(String, Class, NumberFormat)} calls {@code NumberFormat.parse} on the
     * format it is handed, which the weaver did not substitute at all while only {@code format}
     * was in its table.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_springParseNumber_oneFormatForEveryThread() {
        swallowingTheRace(() -> org.springframework.util.NumberUtils.parseNumber(
                "12.5", Double.class, SHARED_NUMBER_FORMAT));
    }

    /** The same Spring call with a format built for it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_springParseNumber_oneFormatPerCall() {
        swallowingTheRace(() -> org.springframework.util.NumberUtils.parseNumber(
                "12.5", Double.class, new java.text.DecimalFormat("#.##",
                        java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ROOT))));
    }

    /**
     * Every thread asks Guava to join into the one builder.
     *
     * <p>{@code Joiner.appendTo(StringBuilder, Iterable)} writes through {@code Appendable}, so
     * the append the detector hears is an {@code Appendable.append} call in Guava's class file.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_guavaJoinerAppendTo_oneBuilderForEveryThread() {
        swallowingTheRace(() -> {
            JOINER.appendTo(SHARED_JOINED, java.util.List.of("a", "b", "c"));
            SHARED_JOINED.setLength(0);
        });
    }

    /** The same join into a builder this call made. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_guavaJoinerAppendTo_oneBuilderPerCall() {
        swallowingTheRace(() -> {
            StringBuilder mine = new StringBuilder();
            JOINER.appendTo(mine, java.util.List.of("a", "b", "c"));
            mine.setLength(0);
        });
    }

    /** The Formatter every thread hands to commons-lang3's FormattableUtils. */
    private static final Formatter SHARED_FORMATTABLE_SINK = new Formatter(new StringBuilder());

    /**
     * Every thread asks commons-lang3 to pad a value into the one Formatter.
     *
     * <p>{@code FormattableUtils.append} is the helper a {@code Formattable.formatTo} implementation
     * calls with the {@code Formatter} it was handed; it pads the text and then calls
     * {@code formatter.format(...)} itself. That call is the woven one, in commons-lang3's class
     * file. The class is deprecated in favour of commons-text's copy, which does the same thing.
     */
    @SuppressWarnings("deprecation")
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_lang3FormattableAppend_oneFormatterForEveryThread() {
        swallowingTheRace(() -> org.apache.commons.lang3.text.FormattableUtils.append(
                "corpus", SHARED_FORMATTABLE_SINK, 0, 8, -1));
    }

    /** The same padding into a Formatter this call made. */
    @SuppressWarnings("deprecation")
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_lang3FormattableAppend_oneFormatterPerCall() {
        swallowingTheRace(() -> {
            try (Formatter mine = new Formatter(new StringBuilder())) {
                org.apache.commons.lang3.text.FormattableUtils.append("corpus", mine, 0, 8, -1);
            }
        });
    }
    /**
     * Every thread asks Groovy to count the matches in the one Matcher.
     *
     * <p>{@code StringGroovyMethods.getCount(Matcher)} calls {@code reset()} and then
     * {@code find()} in a loop on the Matcher it is given; those calls are in Groovy's class file.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_groovyMatcherCount_oneMatcherForEveryThread() {
        swallowingTheRace(() -> org.codehaus.groovy.runtime.StringGroovyMethods.getCount(
                SHARED_GROOVY_MATCHER));
    }

    /** The same Groovy call on a Matcher this call took from the shared Pattern. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_groovyMatcherCount_oneMatcherPerCall() {
        swallowingTheRace(() -> org.codehaus.groovy.runtime.StringGroovyMethods.getCount(
                PATTERN.matcher(MATCH_INPUT)));
    }

    /**
     * Leaves a Guava monitor after a {@code tryEnter} that returned false.
     *
     * <p>The Monitor javadoc says a boolean enter belongs in the condition of an {@code if}; this
     * is the version that ignores it. {@code leave} calls {@code ReentrantLock.unlock} on a lock
     * the worker never took, which throws, and the unlock is what the detector hears. Both calls
     * are inside Guava.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_guavaMonitorTryEnter_leftAfterFailing() {
        swallowingTheRace(() -> {
            OCCUPIED_MONITOR.tryEnter();
            try {
                Thread.onSpinWait();
            } finally {
                OCCUPIED_MONITOR.leave();
            }
        });
    }

    /**
     * The javadoc's own shape, on the occupied monitor and on one this call can enter.
     *
     * <p>The second monitor is there so that this half reaches {@code unlock} as well: a silent
     * row whose only tryEnter always fails would be silent partly because it never unlocked, and
     * the detector keys on the thread's last outcome for a lock, which an honest unlock after a
     * successful tryEnter is the case most likely to be misjudged.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_guavaMonitorTryEnter_leftOnlyWhenEntered() {
        swallowingTheRace(() -> {
            if (OCCUPIED_MONITOR.tryEnter()) {
                try {
                    Thread.onSpinWait();
                } finally {
                    OCCUPIED_MONITOR.leave();
                }
            }
            Monitor mine = new Monitor();
            if (mine.tryEnter()) {
                try {
                    Thread.onSpinWait();
                } finally {
                    mine.leave();
                }
            }
        });
    }

    /**
     * Enters a Guava monitor and never leaves it.
     *
     * <p>A monitor per body execution, so the leak cannot block another worker. The lock the
     * detector sees held at analysis is the {@code ReentrantLock} Guava keeps inside it.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_guavaMonitorEnter_neverLeft() {
        counted(() -> new Monitor().enter());
    }

    /** Enter, then try/finally leave, which is the first snippet in the Monitor javadoc. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_guavaMonitorEnter_leftInFinally() {
        counted(() -> {
            Monitor mine = new Monitor();
            mine.enter();
            try {
                Thread.onSpinWait();
            } finally {
                mine.leave();
            }
        });
    }

    /**
     * Nests two Guava monitors one way and then the other, serialised for the reason the
     * ReentrantLock row gives.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_guavaMonitorOrder_nestedBothWays() {
        counted(() -> {
            synchronized (ORDER_GUARD) {
                MONITOR_A.enter();
                try {
                    MONITOR_B.enter();
                    MONITOR_B.leave();
                } finally {
                    MONITOR_A.leave();
                }
                MONITOR_B.enter();
                try {
                    MONITOR_A.enter();
                    MONITOR_A.leave();
                } finally {
                    MONITOR_B.leave();
                }
            }
        });
    }

    /** The same two monitors, always A before B. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_guavaMonitorOrder_nestedOneWay() {
        counted(() -> {
            synchronized (ORDER_GUARD) {
                MONITOR_A.enter();
                try {
                    MONITOR_B.enter();
                    MONITOR_B.leave();
                } finally {
                    MONITOR_A.leave();
                }
                MONITOR_A.enter();
                try {
                    MONITOR_B.enter();
                    MONITOR_B.leave();
                } finally {
                    MONITOR_A.leave();
                }
            }
        });
    }

    /**
     * HikariCP's sleep helper, called while occupying a Guava monitor.
     *
     * <p>Two libraries and no JDK call in the body. The lock is taken by Guava's woven
     * {@code lock}, which puts it in the thread's lockset; the sleep is HikariCP's woven
     * {@code Thread.sleep}, which asks the lockset what is held. Neither library knows the other
     * is there, which is how a sleep under a lock is usually written.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_hikariSleep_whileOccupyingAMonitor() {
        counted(() -> {
            Monitor mine = new Monitor();
            mine.enter();
            try {
                UtilityElf.quietlySleep(1);
            } finally {
                mine.leave();
            }
        });
    }

    /** The same monitor traffic and the same sleep, with the sleep after the leave. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_hikariSleep_afterLeavingTheMonitor() {
        counted(() -> {
            Monitor mine = new Monitor();
            mine.enter();
            try {
                Thread.onSpinWait();
            } finally {
                mine.leave();
            }
            UtilityElf.quietlySleep(1);
        });
    }

    /**
     * Waits through Guava on a latch nothing counts down.
     *
     * <p>{@code Uninterruptibles.awaitUninterruptibly} makes the timed {@code await} itself, so
     * the timeout the detector reports is observed in Guava's class file. The body then drops the
     * boolean Guava hands back.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_guavaLatchAwait_timedOut() {
        counted(() -> Uninterruptibles.awaitUninterruptibly(
                new CountDownLatch(1), 1, TimeUnit.MILLISECONDS));
    }

    /** The same Guava await, on a latch this thread counted down first. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_guavaLatchAwait_sawItsCount() {
        counted(() -> {
            CountDownLatch reached = new CountDownLatch(1);
            reached.countDown();
            Uninterruptibles.awaitUninterruptibly(reached, 1, TimeUnit.SECONDS);
        });
    }

    /**
     * Waits through Guava on a latch nothing ever counts down, for the misuse detector.
     *
     * <p>The same body as {@link #agent_guavaLatchAwait_timedOut}, kept apart so each detector's
     * row has its own method and its own report line.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_guavaLatchAwait_neverCountedDown() {
        counted(() -> Uninterruptibles.awaitUninterruptibly(
                new CountDownLatch(1), 1, TimeUnit.MILLISECONDS));
    }

    /** The same Guava await, on a latch this thread counted down first. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_guavaLatchAwait_countedDownBeforeTheAwait() {
        counted(() -> {
            CountDownLatch reached = new CountDownLatch(1);
            reached.countDown();
            Uninterruptibles.awaitUninterruptibly(reached, 1, TimeUnit.SECONDS);
        });
    }

    /**
     * Fills a queue of two through Guava's put before taking anything back.
     *
     * <p>{@code putUninterruptibly} is the woven {@code BlockingQueue.put}. The take goes through
     * Guava too, and is not a substituted call at all, so what the detector counts is the puts.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_guavaQueuePut_filledToCapacity() {
        counted(() -> {
            BlockingQueue<String> saturated = new ArrayBlockingQueue<>(2);
            Uninterruptibles.putUninterruptibly(saturated, "first");
            Uninterruptibles.putUninterruptibly(saturated, "second");
            Uninterruptibles.takeUninterruptibly(saturated);
        });
    }

    /** The same puts and takes, alternated, so the queue never holds more than one. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_guavaQueuePut_drainedAsItFilled() {
        counted(() -> {
            BlockingQueue<String> keepingUp = new ArrayBlockingQueue<>(2);
            Uninterruptibles.putUninterruptibly(keepingUp, "first");
            Uninterruptibles.takeUninterruptibly(keepingUp);
            Uninterruptibles.putUninterruptibly(keepingUp, "second");
            Uninterruptibles.takeUninterruptibly(keepingUp);
        });
    }

    /**
     * Takes a permit through Guava and never gives it back.
     *
     * <p>The acquisition is Guava's woven {@code tryAcquire(int, long, TimeUnit)}. The release
     * in the twin is written here, because Guava has no release helper; the acquisition, which
     * is the half the leak is made of, is the library's in both.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_guavaSemaphore_permitNeverReturned() {
        counted(() -> Uninterruptibles.tryAcquireUninterruptibly(
                new Semaphore(1), 1, 1, TimeUnit.SECONDS));
    }

    /** The same Guava acquisition, with the release in a finally. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_guavaSemaphore_permitReturnedInFinally() {
        counted(() -> {
            Semaphore balanced = new Semaphore(1);
            if (Uninterruptibles.tryAcquireUninterruptibly(balanced, 1, 1, TimeUnit.SECONDS)) {
                try {
                    Thread.onSpinWait();
                } finally {
                    balanced.release();
                }
            }
        });
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
