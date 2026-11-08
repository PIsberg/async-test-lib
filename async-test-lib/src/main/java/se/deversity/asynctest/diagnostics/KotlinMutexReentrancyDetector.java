package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;
import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.lang.ref.WeakReference;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Detects a Kotlin coroutine {@code Mutex} locked again by the coroutine that holds it (#1007).
 *
 * <p>{@code kotlinx.coroutines.sync.Mutex} is not reentrant, unlike {@code synchronized} or a
 * {@code ReentrantLock}. A {@code lock()} by the coroutine that holds it suspends until the mutex is
 * unlocked, which only that same coroutine can do, so it never resumes. The usual shape hides the
 * second lock behind a scope: {@code mutex.withLock { withContext(Dispatchers.IO) { mutex.withLock { } } } }.
 * The block of {@code withContext}, {@code coroutineScope}, {@code supervisorScope} or
 * {@code withTimeout} runs as a child coroutine its caller suspends for, so the outer coroutine
 * cannot unlock until the inner {@code lock()} returns, and that waits for the unlock. Nothing
 * fails: the coroutine hangs, or a {@code withTimeout} around it cancels it and reports a timeout
 * that says nothing about the mutex.
 *
 * <p>kotlinx records no holder unless the caller passes an {@code owner}, so the agent's
 * {@code KotlinMutexWeaver} passes one: a woven {@code lock} called without an owner is made with a
 * fresh token of the agent's own, and {@link #recordLocked} keeps it with the calling coroutine's
 * {@code Job}, read from the call's continuation, whether the lock took the mutex at once or waits
 * for it. Before a later woven {@code lock}, {@link #holderAmong(Object, List, Predicate)} asks the
 * mutex, through {@code holdsLock}, whether one of those tokens holds it right now for the caller or
 * a scope the caller's parent is suspended in. If one does and the lock waits, that is the finding
 * ({@link #recordReentry}). The mutex itself answers, so an {@code unlock} the agent never saw
 * cannot leave a holder behind (#1221), and a lock that waited holds its token once it is granted
 * (#1220).
 *
 * <p>A {@code tryLock} takes no continuation, so the agent passes the one of the suspend function or
 * lambda it is called in, and a mutex it takes is kept with that {@code Job} too (#1220).
 *
 * <p>It errs toward silence. A coroutine that took the mutex through {@code select}, through a
 * {@code tryLock} outside any suspend function, or in a class the agent does not weave, holds no
 * token of the agent's, so a reentrant lock under it is missed. A {@code launch} or {@code async} child is not counted as part
 * of its parent: the parent may unlock without waiting for it, and the child then simply waits its
 * turn.
 *
 * <p>The holder can also be recorded by hand, without kotlinx: {@link #recordAcquired} names it,
 * {@link #recordHolderUnknown} forgets it, and {@link #holderAmong(Object, List)} finds it.
 *
 * <p>Usage inside {@code @AsyncTest(includes = DetectorType.KOTLIN_MUTEX_REENTRANCY)}:
 * <pre>{@code
 * var d = AsyncTestContext.kotlinMutexReentrancyDetector();
 * d.recordAcquired(mutex, outerJob);                                  // took it without waiting
 * int held = d.holderAmong(mutex, List.of(innerJob, outerJob));      // 1: the outer job holds it
 * d.recordReentry(mutex, List.of("DispatchedCoroutine", "BlockingCoroutine"), held, Thread.currentThread());
 * }</pre>
 *
 * @since 1.14.2
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER,
    note = "Per-mutex state in the weakly keyed ConcurrentHashMap of AbstractInstanceDetector; the "
        + "holdings are a ConcurrentLinkedDeque of weakly held Jobs, and a reentry writes its "
        + "description before its count.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/KotlinMutexReentrancyDetectorTest.java"
)
public final class KotlinMutexReentrancyDetector
        extends AbstractInstanceDetector<KotlinMutexReentrancyDetector.MutexState> {

    private static final String REMEDY = "kotlinx Mutex is not reentrant: do not lock it again inside "
            + "its own withLock, including through withContext, coroutineScope or withTimeout. Pass "
            + "what the inner code needs in, or split out a function that assumes the lock is held.";

    /**
     * Locks kept per mutex before the oldest is dropped. A token is dropped on its own once the
     * mutex is seen to have let it go; this bounds the ones that never held it, the locks still
     * waiting or cancelled while they waited. Dropping one only ever misses a finding.
     */
    static final int MAX_HOLDINGS = 64;

    static final class MutexState {
        final String label;
        /** The locks that may hold the mutex, oldest first; at most one token holds it at a time. */
        final Deque<Holding> holdings = new ConcurrentLinkedDeque<>();
        final AtomicInteger reentries = new AtomicInteger();
        volatile @Nullable String lastPath;
        volatile @Nullable String lastThread;

        MutexState(String label) {
            this.label = label;
        }
    }

    /** One coroutine's lock on a mutex. */
    static final class Holding {
        final WeakReference<Object> job;
        /**
         * The owner the lock was made with, which the mutex can be asked about; {@code null} for a
         * holder recorded by hand, which holds until {@link #recordHolderUnknown}.
         */
        final @Nullable Object token;
        /** Seen holding the mutex: once it no longer does, it never will again. */
        volatile boolean seenHolding;

        Holding(Object job, @Nullable Object token, boolean seenHolding) {
            this.job = new WeakReference<>(job);
            this.token = token;
            this.seenHolding = seenHolding;
        }
    }

    @Override
    MutexState newState(Object instance, String label) {
        return new MutexState(label);
    }

    /**
     * Records that coroutine {@code job} called {@code lock(token)} on {@code mutex}: it holds the
     * mutex while {@code mutex.holdsLock(token)} says so, now if the lock took it at once, or once
     * the wait ends if it suspended.
     *
     * @param mutex the kotlinx {@code Mutex}, by identity; {@code null} records nothing
     * @param job   the {@code Job} of the coroutine that called it; {@code null} records nothing
     * @param token the owner the lock was made with; {@code null} records nothing
     * @param taken whether the lock took the mutex without waiting
     * @since 1.14.2
     */
    @SuppressWarnings("ReferenceEquality") // a token is one lock call; identity, never equals
    public void recordLocked(Object mutex, @Nullable Object job, @Nullable Object token, boolean taken) {
        if (mutex == null || job == null || token == null) {
            return;
        }
        MutexState state = stateFor(mutex, null, "Mutex");
        // An owner the caller passes may be reused: the newest lock made with it is the one to ask about.
        state.holdings.removeIf(h -> h.token == token); // NOPMD CompareObjectsWithEquals - by identity
        state.holdings.addLast(new Holding(job, token, taken));
        while (state.holdings.size() > MAX_HOLDINGS) {
            state.holdings.pollFirst();
        }
    }

    /**
     * Records that coroutine {@code job} holds {@code mutex}, until the next
     * {@link #recordHolderUnknown}. For recording by hand; the agent records through
     * {@link #recordLocked}, which the mutex itself confirms.
     *
     * @param mutex the mutex, tracked by identity
     * @param job   the {@code Job} of the coroutine that took it, or {@code null} when it has none
     */
    public void recordAcquired(Object mutex, @Nullable Object job) {
        if (mutex == null) {
            return;
        }
        if (job == null) {
            recordHolderUnknown(mutex);
            return;
        }
        MutexState state = stateFor(mutex, null, "Mutex");
        state.holdings.removeIf(h -> h.token == null);
        state.holdings.addLast(new Holding(job, null, true));
    }

    /**
     * Forgets the holder {@link #recordAcquired} named for {@code mutex}. Locks recorded through
     * {@link #recordLocked} are unaffected: the mutex says whether they still hold it.
     *
     * @param mutex the mutex, by identity; {@code null} is ignored
     */
    public void recordHolderUnknown(Object mutex) {
        if (mutex == null) {
            return;
        }
        MutexState state = trackedState(mutex);
        if (state != null) {
            state.holdings.removeIf(h -> h.token == null);
        }
    }

    /**
     * {@return the index in {@code waiting} of the coroutine {@link #recordAcquired} named as holding
     * {@code mutex}, or -1}
     *
     * @param mutex   the mutex a {@code lock()} is about to wait for
     * @param waiting the calling coroutine's {@code Job} first, then each scope it runs in that its
     *                parent is suspended for, outward
     */
    public int holderAmong(Object mutex, List<?> waiting) {
        return holderAmong(mutex, waiting, null);
    }

    /**
     * {@return the index in {@code waiting} of the coroutine holding {@code mutex}, or -1}
     *
     * <p>A lock recorded through {@link #recordLocked} counts while {@code holds} says its token
     * holds the mutex; one that held it and no longer does is dropped. A holder named through
     * {@link #recordAcquired} counts until {@link #recordHolderUnknown}.
     *
     * @param mutex   the mutex a {@code lock()} is about to wait for
     * @param waiting the calling coroutine's {@code Job} first, then each scope it runs in that its
     *                parent is suspended for, outward
     * @param holds   asks the mutex whether a token holds it, as {@code Mutex.holdsLock}; {@code null}
     *                leaves every token out
     * @since 1.14.2
     */
    @SuppressWarnings("ReferenceEquality") // a Job is one coroutine; identity, never equals
    public int holderAmong(Object mutex, List<?> waiting, @Nullable Predicate<Object> holds) {
        if (mutex == null || waiting == null || waiting.isEmpty()) {
            return -1;
        }
        MutexState state = trackedState(mutex);
        if (state == null) {
            return -1;
        }
        int found = -1;
        for (Iterator<Holding> it = state.holdings.iterator(); it.hasNext(); ) {
            Holding h = it.next();
            Object job = h.job.get();
            if (job == null) {
                it.remove();
                continue;
            }
            boolean holding;
            if (h.token == null) {
                holding = true;
            } else if (holds == null) {
                continue;
            } else {
                holding = holds.test(h.token);
                if (holding) {
                    h.seenHolding = true;
                } else if (h.seenHolding) {
                    it.remove();
                    continue;
                }
            }
            if (holding) {
                for (int i = 0; i < waiting.size() && (found < 0 || i < found); i++) {
                    if (waiting.get(i) == job) { // NOPMD CompareObjectsWithEquals - a Job is one coroutine, by identity
                        found = i;
                    }
                }
            }
        }
        return found;
    }

    /**
     * Records a {@code lock()} that waits for a mutex its own coroutine, or a coroutine suspended
     * until it completes, holds.
     *
     * @param mutex       the kotlinx {@code Mutex} locked again, by identity; {@code null} records nothing
     * @param path        a name per coroutine, the caller first and then outward, as given to
     *                    {@link #holderAmong}
     * @param holderIndex the holder's index in {@code path}, as {@link #holderAmong} returned it
     * @param thread      the thread the {@code lock()} was called on
     */
    public void recordReentry(Object mutex, List<String> path, int holderIndex, Thread thread) {
        if (mutex == null || path == null || thread == null || holderIndex < 0 || holderIndex >= path.size()) {
            return;
        }
        MutexState state = stateFor(mutex, null, "Mutex");
        state.lastPath = describe(path, holderIndex);
        state.lastThread = thread.getName();
        // The count last: analyze reads it first, so a count above zero has a description behind it.
        state.reentries.incrementAndGet();
    }

    private static String describe(List<String> path, int holderIndex) {
        if (holderIndex == 0) {
            return path.get(0) + " holds it and called lock() on it again";
        }
        List<String> inner = new ArrayList<>(path.subList(0, holderIndex));
        java.util.Collections.reverse(inner);
        return path.get(holderIndex) + " holds it and is suspended until " + String.join(" -> ", inner)
                + " completes, which called lock() on it";
    }

    /**
     * Analyses what has been recorded about the observation and builds the report for it.
     *
     * @return the findings this detector collected during the run
     */
    public Report analyze() {
        Report r = new Report();
        for (MutexState s : states()) {
            int count = s.reentries.get();
            String path = s.lastPath;
            String thread = s.lastThread;
            if (count == 0 || path == null || thread == null) {
                continue;
            }
            String msg = String.format(
                    "Mutex '%s' locked again inside the coroutine that holds it (%d time(s), last on thread '%s'): %s. "
                            + "The lock waits for an unlock only that coroutine can make, so neither resumes "
                            + "unless a timeout or cancellation ends the wait.",
                    s.label, count, thread, path);
            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                    "KotlinMutexReentrancy",
                    IssueSeverity.HIGH,
                    msg,
                    List.of(),
                    Map.of("mutex", s.label, "reentries", count, "path", path, "thread", thread),
                    Instant.now()));
        }
        return DetectorFailurePolicy.checkedReport(this, r);
    }

    /** Clears every recorded mutex. */
    public void reset() {
        clearStates();
    }

    /** What {@link #analyze()} found. */
    public static final class Report {
        /** Findings as human-readable lines, for the text report. */
        public final List<String> violations = new ArrayList<>();
        /** The same findings as {@link Violation} objects, for machine-readable reports. */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return !violations.isEmpty();
        }

        @Override
        public String toString() {
            if (violations.isEmpty()) {
                return "KOTLIN MUTEX REENTRANCY - clean";
            }
            StringBuilder sb = new StringBuilder("KOTLIN MUTEX REENTRANCY DETECTED:\n");
            for (String v : violations) {
                sb.append("  - ").append(v).append('\n');
            }
            sb.append("  Fix: ").append(REMEDY).append('\n');
            return sb.toString();
        }
    }
}
