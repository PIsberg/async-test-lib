package se.deversity.asynctest.diagnostics;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * Detects CountDownLatch misuse patterns:
 * - Latch timeout (await with timeout expiring)
 * - Missing countDown (latch never reaches zero)
 * - Extra countDown (more countDown() calls than initial count)
 * - Latch reuse (attempting to reuse single-use latch)
 */
public class CountDownLatchDetector {

    private final Map<CountDownLatch, LatchInfo> latchRegistry = new ConcurrentHashMap<>();
    private final Set<CountDownLatch> timedOutLatches = ConcurrentHashMap.newKeySet();
    private final Set<CountDownLatch> extraCountDownLatches = ConcurrentHashMap.newKeySet();

    /**
     * Register a CountDownLatch for monitoring.
     *
     * @param latch the latch
     * @param name the name
     * @param initialCount the initial count
     */
    public void registerLatch(CountDownLatch latch, String name, int initialCount) {
        latchRegistry.put(latch, new LatchInfo(name, initialCount));
    }

    /**
     * Record a countDown() call.
     *
     * @param latch the latch
     */
    public void recordCountDown(CountDownLatch latch) {
        LatchInfo info = latchRegistry.get(latch);
        if (info != null) {
            boolean extra = info.countDown();
            if (extra) {
                extraCountDownLatches.add(latch);
            }
        }
    }

    /**
     * Record an await() call that timed out.
     *
     * @param latch the latch
     */
    public void recordTimeout(CountDownLatch latch) {
        timedOutLatches.add(latch);
    }

    /**
     * Record a successful await() call.
     *
     * @param latch the latch
     */
    public void recordAwaitSuccess(CountDownLatch latch) {
        LatchInfo info = latchRegistry.get(latch);
        if (info != null) {
            info.awaitSuccess = true;
        }
    }

    /**
     * Analyze latch usage and return report.
     *
     * @return the analyze
     */
    public CountDownLatchReport analyze() {
        return new CountDownLatchReport(
            latchRegistry,
            timedOutLatches,
            extraCountDownLatches
        );
    }

    /**
     * Report class for CountDownLatch analysis.
     */
    public static class CountDownLatchReport {
        private final Map<CountDownLatch, LatchInfo> latchRegistry;
        private final Set<CountDownLatch> timedOutLatches;
        private final Set<CountDownLatch> extraCountDownLatches;

        public CountDownLatchReport(
            Map<CountDownLatch, LatchInfo> latchRegistry,
            Set<CountDownLatch> timedOutLatches,
            Set<CountDownLatch> extraCountDownLatches
        ) {
            this.latchRegistry = Collections.unmodifiableMap(new HashMap<>(latchRegistry));
            this.timedOutLatches = Collections.unmodifiableSet(new HashSet<>(timedOutLatches));
            this.extraCountDownLatches = Collections.unmodifiableSet(new HashSet<>(extraCountDownLatches));
        }

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return !timedOutLatches.isEmpty() || !extraCountDownLatches.isEmpty();
        }

        /**
         * Registry lookup that always yields a non-null {@code LatchInfo}.
         *
         * <p>Nothing requires a {@code record*} call's subject to have been passed to the matching
         * {@code register*} first — no precondition, no runtime check — and the two are written at
         * different places in a test. When the registration is missed the lookup returns
         * {@code null} and dereferencing it threw out of {@code toString()}. That NPE never reached
         * the user: {@code DetectorRegistry.ifIssue} catches it so one detector cannot discard the
         * whole sweep, so the finding was simply dropped and the report the user needed never
         * appeared. A placeholder keeps the finding and says plainly which subject was not
         * registered.
         */
        private LatchInfo infoFor(CountDownLatch latch) {
            LatchInfo info = latchRegistry.get(latch);
            return info != null ? info : new LatchInfo("<unregistered latch>", 0);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("COUNTDOWNLATCH ISSUES DETECTED:\n");

            if (!timedOutLatches.isEmpty()) {
                sb.append("  Timed Out Latches:\n");
                for (CountDownLatch latch : timedOutLatches) {
                    LatchInfo info = infoFor(latch);
                    sb.append("    - ").append(info.name)
                      .append(" (expected ").append(info.initialCount)
                      .append(" countDown() calls, but await() timed out)\n");
                }
                sb.append("  Why: If countDown() is not called enough times the latch count never reaches zero and await() blocks forever;\n");
                sb.append("       the test or application hangs waiting for threads that never signal completion.\n");
                sb.append("  Fix: Ensure every participating thread calls countDown() exactly once — even on exception paths (use try/finally)\n");
            }

            if (!extraCountDownLatches.isEmpty()) {
                sb.append("  Extra countDown() Calls:\n");
                for (CountDownLatch latch : extraCountDownLatches) {
                    LatchInfo info = infoFor(latch);
                    sb.append("    - ").append(info.name)
                      .append(" (initial count: ").append(info.initialCount)
                      .append(", but countDown() called more times)\n");
                }
                sb.append("  Why: Calling countDown() more times than the initial count has no effect (the latch stays at zero),\n");
                sb.append("       but it indicates a logic error in thread coordination that can mask real bugs.\n");
                sb.append("  Fix: Verify countDown() is called exactly once per thread — track with an AtomicInteger if needed\n");
            }

            if (!hasIssues()) {
                sb.append("  No CountDownLatch issues detected.\n");
            }

            return sb.toString();
        }
    }

    /**
     * Internal latch information.
     */
    static class LatchInfo {
        final String name;
        final int initialCount;
        int currentCount;
        boolean awaitSuccess = false;

        LatchInfo(String name, int initialCount) {
            this.name = name;
            this.initialCount = initialCount;
            this.currentCount = initialCount;
        }

        /** Decrements count. Returns true if count dropped below zero (extra countDown). */
        synchronized boolean countDown() {
            currentCount--;
            return currentCount < 0;
        }
    }
}
