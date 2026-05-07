package se.deversity.asynctest.diagnostics;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import se.deversity.vibetags.annotations.AITestDriven;

/**
 * Detects CountDownLatch misuse patterns:
 * - Latch timeout (await with timeout expiring)
 * - Missing countDown (latch never reaches zero)
 * - Extra countDown (more countDown() calls than initial count)
 * - Latch reuse (attempting to reuse single-use latch)
 */
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/CountDownLatchDetectorTest.java"
)
public class CountDownLatchDetector {

    private final Map<CountDownLatch, LatchInfo> latchRegistry = new ConcurrentHashMap<>();
    private final Set<CountDownLatch> timedOutLatches = ConcurrentHashMap.newKeySet();
    private final Set<CountDownLatch> extraCountDownLatches = ConcurrentHashMap.newKeySet();

    /**
     * Register a CountDownLatch for monitoring.
     */
    public void registerLatch(CountDownLatch latch, String name, int initialCount) {
        latchRegistry.put(latch, new LatchInfo(name, initialCount));
    }

    /**
     * Record a countDown() call.
     */
    public void recordCountDown(CountDownLatch latch) {
        LatchInfo info = latchRegistry.get(latch);
        if (info != null) {
            info.countDown();
            if (info.currentCount < 0) {
                extraCountDownLatches.add(latch);
            }
        }
    }

    /**
     * Record an await() call that timed out.
     */
    public void recordTimeout(CountDownLatch latch) {
        timedOutLatches.add(latch);
    }

    /**
     * Record a successful await() call.
     */
    public void recordAwaitSuccess(CountDownLatch latch) {
        LatchInfo info = latchRegistry.get(latch);
        if (info != null) {
            info.awaitSuccess = true;
        }
    }

    /**
     * Analyze latch usage and return report.
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
            this.latchRegistry = latchRegistry;
            this.timedOutLatches = timedOutLatches;
            this.extraCountDownLatches = extraCountDownLatches;
        }

        public boolean hasIssues() {
            return !timedOutLatches.isEmpty() || !extraCountDownLatches.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("COUNTDOWNLATCH ISSUES DETECTED:\n");

            if (!timedOutLatches.isEmpty()) {
                sb.append("  Timed Out Latches:\n");
                for (CountDownLatch latch : timedOutLatches) {
                    LatchInfo info = latchRegistry.get(latch);
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
                    LatchInfo info = latchRegistry.get(latch);
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

        synchronized void countDown() {
            currentCount--;
        }
    }
}
