package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects CountDownLatch-style misuse such as missing or extra countdowns.
 *
 * <p>Reachable from a test via {@code AsyncTestContext.latchMisuseDetector()} when
 * {@link se.deversity.asynctest.DetectorType#LATCH_MISUSE} is enabled.
 */
public class LatchMisuseDetector {

    private static class LatchState {
        final String name;
        final int initialCount;
        final AtomicInteger countDownCalls = new AtomicInteger();
        final AtomicInteger awaitCalls = new AtomicInteger();

        LatchState(String name, int initialCount) {
            this.name = name;
            this.initialCount = initialCount;
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
            if (state.awaitCalls.get() > 0 && state.countDownCalls.get() < state.initialCount) {
                report.missingCountDowns.add(String.format(
                    "%s: awaited %d time(s) but only %d/%d countDown() calls were recorded",
                    state.name,
                    state.awaitCalls.get(),
                    state.countDownCalls.get(),
                    state.initialCount
                ));
            }
            if (state.countDownCalls.get() > state.initialCount) {
                report.extraCountDowns.add(String.format(
                    "%s: countDown() called %d times for initial count %d",
                    state.name,
                    state.countDownCalls.get(),
                    state.initialCount
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
