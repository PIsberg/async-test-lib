package se.deversity.asynctest;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;
import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AICore;

import java.lang.ref.WeakReference;

/**
 * Internal: what becomes of a thread the test body started when it dies of an uncaught exception
 * (#1253). Public only so the runner and the uncaught-handler detector, in other packages, can
 * call it; not part of the stable public API.
 *
 * <p>The JVM hands an uncaught exception to the dying thread's own handler, else to its
 * {@link ThreadGroup}, which passes it to {@link Thread#getDefaultUncaughtExceptionHandler()} or
 * prints it. Nothing in a run saw it: the test passed with the trace on stderr. While at least one
 * run is in progress the runner sets {@link #install() a default handler} that hands the death to
 * the run the thread works for, then passes it on to the default handler it found, or prints it
 * exactly as the thread group would have. It never swallows.
 *
 * <p><strong>Which run.</strong> A worker carries its run in an inheritable thread-local while it
 * runs the body, so a thread a worker constructs, and every thread that one constructs, carries it
 * too, with or without the agent. With the agent, a thread constructed elsewhere and started by a
 * worker through a woven {@code Thread.start} belongs to the run that started it
 * ({@link AsyncTestContext#agentContext()}), and a pool thread killed by a task a run handed over
 * through a woven {@code execute} belongs to that run, whichever run constructed the thread. A
 * thread constructed under a run that is over and started by a later one belongs to the later. A
 * thread that belongs to no run, or to one whose analysis has started, is only passed on: without
 * the agent, a pool kept in a static belongs to the run that first constructed its thread, so a
 * later run's task that kills it is missed rather than blamed on the wrong test.
 *
 * <p>A thread with a handler of its own never reaches the default one: that handler is the test's
 * decision, and it is left alone rather than wrapped, so
 * {@link Thread#getUncaughtExceptionHandler()} keeps returning what the test set.
 *
 * @since 1.15.0
 */
@API(status = Status.INTERNAL, since = "1.15.0")
@AICore(
    sensitivity = "High",
    note = "This sets the JVM-wide default uncaught exception handler inside somebody else's test suite, so three things must hold. install and uninstall stay counted and paired in a finally (ConcurrencyRunner.execute): runs overlap when test classes execute in parallel, and a boolean, or an uninstall skipped on a failure path, leaves the handler in place for the rest of the build or restores the user's handler while another run still needs this one. The last uninstall restores only when the default is still this handler, so a handler the suite set during a run is never clobbered. And died() always hands the death on, to the default found at install or as ThreadGroup's own print, whatever recording does: a handler that swallows turns every uncaught exception in the build silent. Attribution may miss a death (an outliving thread, a static pool without the agent) but must never give one to a run that did not start the thread, because an AssertionError fails the test it lands in (BodyStartedThreadFailureTest, #1253)."
)
public final class BodyThreadFailures {

    /**
     * The run the calling thread was constructed under. Weak, because a thread a worker constructs
     * may outlive its run by far (a pool kept in a static), and must not keep a finished run's
     * detector state reachable.
     */
    private static final InheritableThreadLocal<WeakReference<AsyncTestContext>> RUN =
            new InheritableThreadLocal<>();

    /**
     * The run a woven hand-off's task worked for when it threw on the calling thread, and what it
     * threw. A pool thread that task kills belongs to that run, whichever run constructed the
     * thread: a pool kept in a static is built under the first run that used it. Matched by the
     * throwable's identity, so a mark left by a task whose executor caught the exception never
     * claims another death. Both weak: the mark may sit on a live pool thread indefinitely.
     */
    private static final ThreadLocal<HandedThrow> HANDED_THROW = new ThreadLocal<>();

    private record HandedThrow(WeakReference<AsyncTestContext> run, WeakReference<Throwable> thrown) {
    }

    private static final Thread.UncaughtExceptionHandler HANDLER = new RunnerHandler();

    /** The runner's handler, a type of its own so it is recognised whatever else is installed. */
    private static final class RunnerHandler implements Thread.UncaughtExceptionHandler {
        @Override
        public void uncaughtException(Thread thread, Throwable thrown) {
            died(thread, thrown);
        }
    }

    private static final Object LOCK = new Object();

    /** Runs in progress; the handler is the JVM default while this is above zero. Guarded by LOCK. */
    private static int installs;

    /**
     * The default handler the first of the overlapping runs found, passed every death. Written
     * under LOCK, read without it by a dying thread. Never cleared, so a thread whose dispatch
     * reached {@link #HANDLER} just before the last run restored the default still delegates.
     */
    private static volatile Thread.@Nullable UncaughtExceptionHandler found;

    private BodyThreadFailures() {
    }

    /**
     * Makes the handler the JVM-wide default for the duration of a run. Nestable: runs that overlap
     * (test classes executing concurrently) share one installation, counted, and the last
     * {@link #uninstall()} restores what the first found. Pair with {@link #uninstall()} in a
     * {@code finally}.
     */
    public static void install() {
        synchronized (LOCK) {
            installs++;
            if (installs == 1) {
                Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
                found = current instanceof RunnerHandler ? null : current;
                Thread.setDefaultUncaughtExceptionHandler(HANDLER);
            }
        }
    }

    /**
     * Ends one run's share of the installation. The last one restores the default the first found,
     * unless something replaced the handler during the runs: then the replacement is left in place.
     * An unmatched call does nothing.
     */
    public static void uninstall() {
        synchronized (LOCK) {
            if (installs == 0) {
                return;
            }
            installs--;
            if (installs == 0
                    && Thread.getDefaultUncaughtExceptionHandler() instanceof RunnerHandler) {
                Thread.setDefaultUncaughtExceptionHandler(found);
            }
        }
    }

    /**
     * {@return the JVM-wide default handler other than the runner's own: the one a death the
     * runner's handler receives is passed to, or {@code null} when it is printed}
     *
     * <p>What a thread with no handler of its own is covered by, which the uncaught-handler
     * detector samples (#758).
     */
    public static Thread.@Nullable UncaughtExceptionHandler userDefaultHandler() {
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        return current instanceof RunnerHandler ? found : current;
    }

    /**
     * Marks the calling worker, and every thread it constructs from now on, as working for
     * {@code run}. Called from {@code AsyncTestContext.install}, cleared by {@link #exit()} from
     * {@code uninstall}, under the same symmetry rule.
     */
    static void enter(AsyncTestContext run) {
        RUN.set(new WeakReference<>(run));
    }

    /** Clears the calling thread's run. Threads it already constructed keep theirs. */
    static void exit() {
        RUN.remove();
    }

    /**
     * {@return the run the calling thread was constructed under, while that run's analysis has not
     * started, or {@code null}} Reads only.
     *
     * <p>What lets a thread the body started reach the run's detectors through the static
     * accessors (#1388). A thread that outlives its run gets {@code null}: anything it recorded
     * would land after the analysis and be lost.
     */
    static @Nullable AsyncTestContext openRunOfCallingThread() {
        WeakReference<AsyncTestContext> constructedUnder = RUN.get();
        AsyncTestContext run = constructedUnder == null ? null : constructedUnder.get();
        return run == null || run.isAnalysed() ? null : run;
    }

    /**
     * Called by the agent's hand-off wrapper when a task {@code run} handed over throws on the
     * calling pool thread, before the throwable leaves the task: if it kills the thread, the death
     * is {@code run}'s. A {@code null} run, a task handed over outside any run, clears the mark.
     *
     * @param run    the run that handed the task over, or {@code null}
     * @param thrown what the task threw
     */
    static void handedTaskThrew(@Nullable AsyncTestContext run, Throwable thrown) {
        if (run == null) {
            HANDED_THROW.remove();
        } else {
            HANDED_THROW.set(new HandedThrow(new WeakReference<>(run), new WeakReference<>(thrown)));
        }
    }

    @SuppressFBWarnings(value = "INFORMATION_EXPOSURE_THROUGH_AN_ERROR_MESSAGE",
            justification = "the JDK's own print for an uncaught exception, which this replaces "
                    + "while the runner's handler is the default; skipping it would swallow the death")
    private static void died(Thread thread, Throwable thrown) {
        try {
            AsyncTestContext run = runOf(thread, thrown);
            if (run != null) {
                run.bodyThreadDied(thread, thrown);
            }
        } catch (Throwable recordingFailed) { // NOPMD - must never stand between a death and its handler
            thrown.addSuppressed(recordingFailed);
        }
        Thread.UncaughtExceptionHandler next = found;
        if (next != null) {
            next.uncaughtException(thread, thrown);
        } else {
            // What ThreadGroup.uncaughtException prints when no default handler is set.
            System.err.print("Exception in thread \"" + thread.getName() + "\" ");
            thrown.printStackTrace(System.err);
        }
    }

    /** The run {@code thread} works for, read on the dying thread itself, as the JVM dispatches. */
    @SuppressWarnings("ReferenceEquality") // the very throwable the handed task threw, not an equal one
    private static @Nullable AsyncTestContext runOf(Thread thread, Throwable thrown) {
        if (thread != Thread.currentThread()) { // NOPMD CompareObjectsWithEquals - by identity
            return null; // a handler invoked by hand for another thread: nothing to read
        }
        HandedThrow handed = HANDED_THROW.get();
        if (handed != null) {
            HANDED_THROW.remove();
            AsyncTestContext handedBy = handed.run().get();
            if (handedBy != null && handed.thrown().get() == thrown) { // NOPMD CompareObjectsWithEquals
                return handedBy;
            }
        }
        WeakReference<AsyncTestContext> constructedUnder = RUN.get();
        AsyncTestContext run = constructedUnder == null ? null : constructedUnder.get();
        if (run != null && !run.isAnalysed()) {
            return run;
        }
        // Constructed under a run that is over, or under none: a run that started the thread
        // through a woven Thread.start since then is the one it works for.
        AsyncTestContext startedBy = AsyncTestContext.agentContext();
        return startedBy != null ? startedBy : run;
    }
}
