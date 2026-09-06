package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects CountDownLatch-style misuse such as missing or extra countdowns.
 *
 * <p>Reachable from a test via {@code AsyncTestContext.latchMisuseDetector()} when
 * {@link se.deversity.asynctest.DetectorType#LATCH_MISUSE} is enabled.
 *
 * <h4>The weaving boundary</h4>
 *
 * <p>This detector counts what it was told about. Without the agent that is what the test called
 * explicitly; with it, what the agent wove. A latch counted down inside code the agent never
 * transformed - a third-party executor's task, a class outside the instrumented packages -
 * contributes no {@link #recordCountDown}, so the recorded total falls short of the starting
 * count while the latch itself reached zero.
 *
 * <p>{@link #recordAwaitReturned} is what keeps that from becoming a finding: {@code await()}
 * returns only at zero, so one returned await proves the latch was fully counted down whatever
 * this detector saw. The missing-countdown finding therefore needs an await that was recorded and
 * never came back. The remaining blind spot is a latch nobody ever awaits through a woven call
 * site, where there is nothing to observe in either direction.
 */
public class LatchMisuseDetector {

    private static class LatchState {
        final String name;
        // The count the latch started from: declared by registerLatch, or inferred by
        // observeLatch as the largest count anything has seen the latch hold. Mutable because the
        // inference only converges as observations arrive.
        final AtomicInteger initialCount;
        final AtomicInteger countDownCalls = new AtomicInteger();
        final AtomicInteger awaitCalls = new AtomicInteger();
        /**
         * Awaits that came back. {@code await()} returns only once the latch has reached zero, so
         * one of these is proof the latch was fully counted down whatever this detector managed
         * to see (#499).
         */
        final AtomicInteger awaitReturns = new AtomicInteger();

        LatchState(String name, int initialCount) {
            this.name = name;
            this.initialCount = new AtomicInteger(initialCount);
        }
    }

    private final Map<Integer, LatchState> latches = new ConcurrentHashMap<>();
    /**
     * Registers latch for tracking.
     *
     * @param latch the latch being recorded, tracked by identity
     * @param name a label identifying the latch in the report
     * @param initialCount the count the latch was created with
     */
    public void registerLatch(Object latch, String name, int initialCount) {
        if (latch == null) {
            return;
        }
        latches.putIfAbsent(System.identityHashCode(latch),
            new LatchState(name == null || name.isBlank() ? "CountDownLatch" : name, initialCount));
    }

    /**
     * Registers a latch nobody instrumented, reading its starting count off the latch itself.
     *
     * <p>Called by the agent's woven {@code countDown} and {@code await} hooks <em>before</em> the
     * operation runs, which is what makes the inference sound. {@code getCount()} never increases,
     * and every count-down the run performs is preceded, on that same thread, by one of these
     * observations. So the globally first count-down is preceded by an observation that no
     * count-down has yet touched, and that observation reads the latch's starting count exactly.
     * Taking the maximum over all observations therefore recovers it, whatever order the threads
     * arrived in.
     *
     * <p>The maximum is also what keeps an explicit {@link #registerLatch} authoritative: a
     * declared count is the true starting count, and no later reading can exceed it.
     *
     * <p>Anything that is not a {@link CountDownLatch} is ignored, because the count is the only
     * thing here worth inferring and nothing else carries one.
     *
     * @param latch the latch the woven call site is about to operate on
     * @since 1.11.1
     */
    public void observeLatch(Object latch) {
        if (!(latch instanceof CountDownLatch countDownLatch)) {
            return;
        }
        int key = System.identityHashCode(latch);
        int observed = (int) Math.min(countDownLatch.getCount(), Integer.MAX_VALUE);
        latches.computeIfAbsent(key, absent -> new LatchState("CountDownLatch@" + absent, observed))
            .initialCount.accumulateAndGet(observed, Math::max);
    }
    /**
     * Records await so it can be analysed at the end of the run.
     *
     * @param latch the latch being recorded, tracked by identity
     */
    public void recordAwait(Object latch) {
        LatchState state = stateFor(latch);
        if (state != null) {
            state.awaitCalls.incrementAndGet();
        }
    }
    /**
     * Records count down so it can be analysed at the end of the run.
     *
     * @param latch the latch being recorded, tracked by identity
     */
    public void recordCountDown(Object latch) {
        LatchState state = stateFor(latch);
        if (state != null) {
            state.countDownCalls.incrementAndGet();
        }
    }

    /**
     * Records that an {@code await()} came back, which it can only do at zero.
     *
     * <p>Call this after the await returns - the agent's woven hooks do, and for the timed
     * overload only when it returned {@code true}. It is what separates a latch nobody counted
     * down from a latch counted down where this detector could not see it: countdowns performed
     * in unwoven code, an executor task the agent never transformed, are invisible here, and
     * without this the shortfall read as CRITICAL "missing countDown()" on a run that worked.
     *
     * @param latch the latch being recorded, tracked by identity
     * @since 1.11.2
     */
    public void recordAwaitReturned(Object latch) {
        LatchState state = stateFor(latch);
        if (state != null) {
            state.awaitReturns.incrementAndGet();
        }
    }

    private @Nullable LatchState stateFor(Object latch) {
        return latch == null ? null : latches.get(System.identityHashCode(latch));
    }
    /**
     * Analyses what has been recorded about the observation and builds the report for it.
     *
     * @return the findings this detector collected during the run
     */
    public LatchMisuseReport analyze() {
        LatchMisuseReport report = new LatchMisuseReport();

        for (LatchState state : latches.values()) {
            // An await that returned settles it: the latch reached zero, so a shortfall in what
            // this detector recorded is a gap in observation rather than a missing countDown().
            if (state.awaitCalls.get() > 0 && state.awaitReturns.get() == 0
                    && state.countDownCalls.get() < state.initialCount.get()) {
                report.missingCountDowns.add(String.format(
                    "%s: awaited %d time(s) but only %d/%d countDown() calls were recorded",
                    state.name,
                    state.awaitCalls.get(),
                    state.countDownCalls.get(),
                    state.initialCount.get()
                ));
            }
            if (state.countDownCalls.get() > state.initialCount.get()) {
                report.extraCountDowns.add(String.format(
                    "%s: countDown() called %d times for initial count %d",
                    state.name,
                    state.countDownCalls.get(),
                    state.initialCount.get()
                ));
            }
        }

        return report;
    }

    public static class LatchMisuseReport {
        /** Latches never counted down to zero. */
        public final Set<String> missingCountDowns = new HashSet<>();
        /** Latches counted down more times than they were created for. */
        public final Set<String> extraCountDowns = new HashSet<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return !missingCountDowns.isEmpty() || !extraCountDowns.isEmpty();
        }

        @Override
        public String toString() {
            if (!hasIssues()) {
                return "No latch misuse detected.";
            }

            StringBuilder sb = new StringBuilder("LATCH MISUSE DETECTED:\n");
            for (String issue : missingCountDowns) {
                sb.append("  - ").append(issue).append('\n');
            }
            for (String issue : extraCountDowns) {
                sb.append("  - ").append(issue).append('\n');
            }
            sb.append("""
  Why: If countDown() is called fewer times than await() needs, the latch count never reaches zero
       and await() blocks indefinitely — the test or downstream logic hangs silently.
       Calling countDown() more times than the initial count is a no-op after zero but indicates
       a logic error in how threads are coordinated.
  Fix:
    - Ensure every thread that participates in the latch calls countDown() exactly once, even on exception paths
    - Use try/finally to guarantee countDown() is called: try { doWork(); } finally { latch.countDown(); }
    - Prefer CompletableFuture or CyclicBarrier when the one-shot guarantee of CountDownLatch is not needed\
""");
            return sb.toString();
        }
    }
}
