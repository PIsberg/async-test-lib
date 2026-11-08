package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;
import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.lang.ref.WeakReference;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
 * {@code KotlinMutexWeaver} records one: each woven {@code lock} names the coroutine's {@code Job},
 * read from the call's continuation, and a {@code lock} that takes the mutex without waiting makes
 * that {@code Job} the holder ({@link #recordAcquired}). A woven {@code unlock}, and a
 * {@code tryLock} or a waited-for {@code lock} that takes it for a coroutine the call cannot
 * name, clear the holder ({@link #recordHolderUnknown}). A later woven {@code lock} that waits, from
 * the holder itself or from a scope the holder is suspended in ({@link #holderAmong}), is the
 * finding ({@link #recordReentry}).
 *
 * <p>It errs toward silence. A holder that waited for the mutex, or took it through
 * {@code tryLock} or {@code select}, is not known, so a reentrant lock under it is missed. A
 * {@code launch} or {@code async} child is not counted as part of its parent: the parent may unlock
 * without waiting for it, and the child then simply waits its turn. An {@code unlock} in a class the
 * agent does not weave leaves the holder recorded; a later {@code lock} from that coroutine that
 * waits for another holder is then reported, and adding that class to {@code includes=} clears it.
 *
 * <p>Usage inside {@code @AsyncTest(includes = DetectorType.KOTLIN_MUTEX_REENTRANCY)}:
 * <pre>{@code
 * var d = AsyncTestContext.kotlinMutexReentrancyDetector();
 * d.recordAcquired(mutex, outerJob);                                  // took it without waiting
 * int held = d.holderAmong(mutex, List.of(innerJob, outerJob));      // 1: the outer job holds it
 * d.recordReentry(mutex, List.of("DispatchedCoroutine", "BlockingCoroutine"), held, Thread.currentThread());
 * }</pre>
 *
 * @since 1.14.1
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER,
    note = "Per-mutex state in the weakly keyed ConcurrentHashMap of AbstractInstanceDetector; the "
        + "holder is a volatile weak reference, and a reentry writes its description before its count.")
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

    static final class MutexState {
        final String label;
        /** The Job that took the mutex without waiting, while it holds it; null when not known. */
        volatile @Nullable WeakReference<Object> holder;
        final AtomicInteger reentries = new AtomicInteger();
        volatile @Nullable String lastPath;
        volatile @Nullable String lastThread;

        MutexState(String label) {
            this.label = label;
        }
    }

    @Override
    MutexState newState(Object instance, String label) {
        return new MutexState(label);
    }

    /**
     * Records that coroutine {@code job} took {@code mutex} without waiting, so it holds it until
     * the next {@link #recordHolderUnknown}.
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
        stateFor(mutex, null, "Mutex").holder = new WeakReference<>(job);
    }

    /**
     * Records that {@code mutex} was unlocked, or taken by a coroutine the call could not name, so
     * its holder is no longer known.
     *
     * @param mutex the kotlinx {@code Mutex}, by identity; {@code null} is ignored
     */
    public void recordHolderUnknown(Object mutex) {
        if (mutex == null) {
            return;
        }
        MutexState state = trackedState(mutex);
        if (state != null) {
            state.holder = null;
        }
    }

    /**
     * {@return the index in {@code waiting} of the coroutine holding {@code mutex}, or -1}
     *
     * @param mutex   the mutex a {@code lock()} is about to wait for
     * @param waiting the calling coroutine's {@code Job} first, then each scope it runs in that its
     *                parent is suspended for, outward
     */
    @SuppressWarnings("ReferenceEquality") // a Job is one coroutine; identity, never equals
    public int holderAmong(Object mutex, List<?> waiting) {
        if (mutex == null || waiting == null || waiting.isEmpty()) {
            return -1;
        }
        MutexState state = trackedState(mutex);
        WeakReference<Object> ref = state == null ? null : state.holder;
        Object holder = ref == null ? null : ref.get();
        if (holder == null) {
            return -1;
        }
        for (int i = 0; i < waiting.size(); i++) {
            if (waiting.get(i) == holder) { // NOPMD CompareObjectsWithEquals - a Job is one coroutine, by identity
                return i;
            }
        }
        return -1;
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
