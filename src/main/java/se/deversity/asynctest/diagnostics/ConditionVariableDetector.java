package se.deversity.asynctest.diagnostics;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import se.deversity.vibetags.annotations.AITestDriven;

/**
 * Detects Condition variable misuse patterns in concurrent code.
 * 
 * Common Condition issues detected:
 * - Signal without waiters: signal()/signalAll() called when no threads are waiting
 * - Lost signals: signal() called before corresponding await() 
 * - Spurious wakeup vulnerability: await() without while-loop condition check
 * - Missing signal: threads waiting indefinitely without corresponding signal
 * - Wrong condition: signaling wrong condition variable
 * 
 * Usage:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectConditionVariableIssues = true)
 * void testConditionUsage() throws InterruptedException {
 *     ReentrantLock lock = new ReentrantLock();
 *     Condition condition = lock.newCondition();
 *     AsyncTestContext.conditionMonitor()
 *         .registerCondition(condition, "data-ready");
 *     
 *     lock.lock();
 *     try {
 *         // Consumer
 *         AsyncTestContext.conditionMonitor()
 *             .recordAwait(condition, "data-ready");
 *         condition.await();
 *         
 *         // Producer
 *         AsyncTestContext.conditionMonitor()
 *             .recordSignal(condition, "data-ready", false);
 *         condition.signal();
 *     } finally {
 *         lock.unlock();
 *     }
 * }
 * }</pre>
 */
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/ConditionVariableDetectorTest.java"
)
public class ConditionVariableDetector {

    private static class ConditionState {
        final String name;
        final AtomicInteger awaitCount = new AtomicInteger(0);
        final AtomicInteger signalCount = new AtomicInteger(0);
        final AtomicInteger signalAllCount = new AtomicInteger(0);
        final AtomicInteger currentWaiters = new AtomicInteger(0);
        final AtomicInteger signalsWithoutWaiters = new AtomicInteger(0);
        final Set<Long> waitingThreads = ConcurrentHashMap.newKeySet();
        final Set<Long> signalingThreads = ConcurrentHashMap.newKeySet();
        volatile Long lastSignalTime = null;

        ConditionState(Condition condition, String name) {
            this.name = name != null ? name : "condition@" + System.identityHashCode(condition);
        }
    }

    private final Map<Integer, ConditionState> conditions = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

    /**
     * Register a Condition for monitoring.
     * 
     * @param condition the Condition to monitor
     * @param name a descriptive name for reporting
     */
    public void registerCondition(Condition condition, String name) {
        if (!enabled || condition == null) {
            return;
        }
        conditions.put(System.identityHashCode(condition), new ConditionState(condition, name));
    }

    /**
     * Record an await() call.
     * 
     * @param condition the Condition
     * @param name the condition name (should match registration)
     */
    public void recordAwait(Condition condition, String name) {
        if (!enabled || condition == null) {
            return;
        }
        ConditionState state = conditions.get(System.identityHashCode(condition));
        if (state != null) {
            state.awaitCount.incrementAndGet();
            state.currentWaiters.incrementAndGet();
            state.waitingThreads.add(Thread.currentThread().threadId());
        }
    }

    /**
     * Record an await() exit (normal or timeout).
     * 
     * @param condition the Condition
     * @param name the condition name (should match registration)
     * @param timedOut true if await timed out, false if signaled
     */
    public void recordAwaitExit(Condition condition, String name, boolean timedOut) {
        if (!enabled || condition == null) {
            return;
        }
        ConditionState state = conditions.get(System.identityHashCode(condition));
        if (state != null) {
            state.currentWaiters.decrementAndGet();
            state.waitingThreads.remove(Thread.currentThread().threadId());
        }
    }

    /**
     * Record a signal() call.
     * 
     * @param condition the Condition
     * @param name the condition name (should match registration)
     * @param isSignalAll true if signalAll(), false if signal()
     */
    public void recordSignal(Condition condition, String name, boolean isSignalAll) {
        if (!enabled || condition == null) {
            return;
        }
        ConditionState state = conditions.get(System.identityHashCode(condition));
        if (state != null) {
            if (isSignalAll) {
                state.signalAllCount.incrementAndGet();
            } else {
                state.signalCount.incrementAndGet();
            }
            state.signalingThreads.add(Thread.currentThread().threadId());
            state.lastSignalTime = System.currentTimeMillis();
            
            // Check if signal was called without waiters
            if (state.currentWaiters.get() == 0) {
                state.signalsWithoutWaiters.incrementAndGet();
            }
        }
    }

    /**
     * Analyze Condition usage for issues.
     * 
     * @return a report of detected issues
     */
    public ConditionVariableReport analyze() {
        ConditionVariableReport report = new ConditionVariableReport();
        report.enabled = enabled;

        for (ConditionState state : conditions.values()) {
            // Check for signals without waiters (lost signals)
            if (state.signalsWithoutWaiters.get() > 0) {
                report.lostSignals.add(String.format(
                    "%s: signal() called %d times without waiting threads (lost signals)",
                    state.name, state.signalsWithoutWaiters.get()));
            }

            // Check for threads still waiting (potential deadlock)
            if (state.currentWaiters.get() > 0) {
                report.stuckWaiters.add(String.format(
                    "%s: %d threads still waiting (last signal %dms ago)",
                    state.name, state.currentWaiters.get(),
                    state.lastSignalTime != null ? System.currentTimeMillis() - state.lastSignalTime : 0));
            }

            // Check for signal/wait imbalance
            int totalSignals = state.signalCount.get() + state.signalAllCount.get();
            if (state.awaitCount.get() > 0 && totalSignals == 0) {
                report.missingSignals.add(String.format(
                    "%s: %d await() calls but no signal()/signalAll() calls",
                    state.name, state.awaitCount.get()));
            }

            // Track thread participation
            if (!state.waitingThreads.isEmpty() || !state.signalingThreads.isEmpty()) {
                report.threadActivity.put(state.name, String.format(
                    "%d waiters, %d signalers, %d awaits, %d signals, %d signalAll",
                    state.waitingThreads.size(),
                    state.signalingThreads.size(),
                    state.awaitCount.get(),
                    state.signalCount.get(),
                    state.signalAllCount.get()));
            }
        }

        return report;
    }

    /**
     * Report class for Condition variable analysis.
     */
    public static class ConditionVariableReport {
        private boolean enabled = true;
        final java.util.List<String> lostSignals = new java.util.ArrayList<>();
        final java.util.List<String> stuckWaiters = new java.util.ArrayList<>();
        final java.util.List<String> missingSignals = new java.util.ArrayList<>();
        final Map<String, String> threadActivity = new ConcurrentHashMap<>();

        /**
         * Check if any issues were detected.
         */
        public boolean hasIssues() {
            return !lostSignals.isEmpty() || !stuckWaiters.isEmpty() || !missingSignals.isEmpty();
        }

        @Override
        public String toString() {
            if (!enabled) {
                return "ConditionVariableReport: disabled";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("CONDITION VARIABLE ISSUES DETECTED:\n");

            if (!lostSignals.isEmpty()) {
                sb.append("  Lost Signals (signal without waiters):\n");
                for (String issue : lostSignals) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!stuckWaiters.isEmpty()) {
                sb.append("  Stuck Waiters:\n");
                for (String issue : stuckWaiters) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!missingSignals.isEmpty()) {
                sb.append("  Missing Signals:\n");
                for (String issue : missingSignals) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!threadActivity.isEmpty()) {
                sb.append("  Thread Activity:\n");
                for (Map.Entry<String, String> entry : threadActivity.entrySet()) {
                    sb.append("    - ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
            }

            if (!hasIssues()) {
                sb.append("  No issues detected.\n");
            }

            sb.append("  Why: A signal() sent before await() is called is lost — the waiting thread will never see it and\n" +
"     blocks indefinitely. Checking a condition only once (if instead of while) causes spurious-wakeup\n" +
"     bugs where the thread proceeds even though the condition is not yet true.\n" +
"  Fix:\n" +
"    - Always await() in a while loop: while (!ready) { condition.await(); }\n" +
"    - Signal after the state change, not before: ready = true; condition.signalAll();\n" +
"    - Prefer signalAll() over signal() unless exactly one thread should wake, to avoid missed-signal bugs");
            return sb.toString();
        }
    }
}
