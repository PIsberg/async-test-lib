package se.deversity.asynctest.diagnostics;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import org.jspecify.annotations.Nullable;

/**
 * Detects {@link Condition} waits that no signal accounts for.
 *
 * <p><strong>The model.</strong> Every await is paired with the signals that could have woken it,
 * per condition and per thread:
 * <ul>
 *   <li>a {@code signal()} recorded while at least one waiter has no wakeup owed to it owes one
 *       wakeup; a {@code signalAll()} owes one to every such waiter;</li>
 *   <li>an await that exits as woken ({@code timedOut == false}) settles one owed wakeup. If
 *       none is owed, the await returned with no signal behind it and that is the
 *       <em>missing signal</em> finding;</li>
 *   <li>an await that exits with {@code timedOut == true} is a waiter that stopped waiting. It is
 *       not a finding: a bounded poll that is never signalled by design runs exactly this way;</li>
 *   <li>a thread still inside an await when the run is analysed is a <em>stuck waiter</em>;</li>
 *   <li>an exit recorded on a thread that has no open await is ignored, so the waiter count can
 *       never go negative.</li>
 * </ul>
 * Findings are therefore per wait, not per run: one signal anywhere does not silence a second
 * waiter that nothing woke (#583).
 *
 * <p><strong>What is not a finding.</strong> A signal made while nobody waits is how correct
 * code runs whenever the producer gets there first: the consumer tests its predicate before it
 * awaits and never waits at all. The count is shown in the report as a note and does not decide
 * {@link ConditionVariableReport#hasIssues()}. Telling a lost wakeup from a satisfied predicate
 * needs the predicate, which this detector is never shown.
 *
 * <p><strong>Recording contract.</strong> Record a signal while holding the condition's lock,
 * before or right after calling {@code signal()}, so no waiter can record its exit first. Record
 * an exit only for the thread that recorded the await, with {@code timedOut} set from the timed
 * await's return value ({@code !condition.await(t, unit)}, or {@code awaitNanos(...) <= 0}).
 * A spurious wakeup recorded as woken reads as a missing signal; the JDK permits them, so
 * record the exit only once the loop's predicate check is done, or treat such a finding as a
 * prompt rather than a verdict.
 *
 * <p>Usage:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectConditionVariableIssues = true)
 * void consumerAndProducer() throws InterruptedException {
 *     var monitor = AsyncTestContext.conditionVariableDetector();
 *     monitor.registerCondition(ready, "data-ready");
 *
 *     lock.lock();
 *     try {
 *         while (!dataReady) {                       // consumer
 *             monitor.recordAwait(ready, "data-ready");
 *             boolean signalled = ready.await(50, TimeUnit.MILLISECONDS);
 *             monitor.recordAwaitExit(ready, "data-ready", !signalled);
 *             if (!signalled) {
 *                 return;                            // gave up: not a finding
 *             }
 *         }
 *     } finally {
 *         lock.unlock();
 *     }
 * }
 *
 * // Producer, elsewhere, under the same lock:
 * //   dataReady = true;
 * //   monitor.recordSignal(ready, "data-ready", true);
 * //   ready.signalAll();
 * }</pre>
 */
public class ConditionVariableDetector {

    /** Everything recorded about one condition; every field is guarded by the state's monitor. */
    private static final class ConditionState {
        final String name;
        /** Threads currently inside a recorded await on this condition. */
        final Set<Long> waitingThreads = new HashSet<>();
        /** Wakeups owed by signals to current waiters and not yet settled by an exit. */
        int owedWakeups;
        int awaitCount;
        int signalCount;
        int signalAllCount;
        int signalsWithNoWaiter;
        int timedOutAwaits;
        int unsignalledWakeups;
        final Set<Long> signallingThreads = new HashSet<>();
        long lastSignalNanos;
        boolean signalled;

        ConditionState(Condition condition, @Nullable String name) {
            this.name = name != null ? name : "condition@" + System.identityHashCode(condition);
        }
    }

    private final Map<IdentityKey, ConditionState> conditions = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

    /**
     * Register a Condition for monitoring.
     *
     * @param condition the Condition to monitor
     * @param name a descriptive name for reporting
     */
    public void registerCondition(Condition condition, String name) {
        // First registration wins: re-registering a subject must not discard what has
        // been observed about it. An @AsyncTest body runs once per thread, so a consumer
        // registering inside it registers once per worker.
        if (!enabled || condition == null) {
            return;
        }
        conditions.putIfAbsent(new IdentityKey(condition), new ConditionState(condition, name));
    }

    /**
     * Record an await() call, made by the thread that is about to wait.
     *
     * @param condition the condition being awaited, tracked by identity
     * @param name the condition name (should match registration)
     */
    public void recordAwait(Condition condition, String name) {
        if (!enabled || condition == null) {
            return;
        }
        ConditionState state = conditions.get(new IdentityKey(condition));
        if (state == null) {
            return;
        }
        long thread = Thread.currentThread().threadId();
        synchronized (state) {
            if (state.waitingThreads.add(thread)) {
                state.awaitCount++;
            }
        }
    }

    /**
     * Record an await() exit, made by the thread that recorded the await.
     *
     * @param condition the condition that was awaited, tracked by identity
     * @param name the condition name (should match registration)
     * @param timedOut true if the await gave up because its time ran out, false if it returned
     *                 as woken
     */
    public void recordAwaitExit(Condition condition, String name, boolean timedOut) {
        if (!enabled || condition == null) {
            return;
        }
        ConditionState state = conditions.get(new IdentityKey(condition));
        if (state == null) {
            return;
        }
        long thread = Thread.currentThread().threadId();
        synchronized (state) {
            if (!state.waitingThreads.remove(thread)) {
                return; // no open await on this thread: nothing to pair
            }
            if (timedOut) {
                state.timedOutAwaits++;
            } else if (state.owedWakeups > 0) {
                state.owedWakeups--;
            } else {
                state.unsignalledWakeups++;
            }
            // A signal can only be owed to a waiter that is still waiting. A timed-out waiter
            // may have been the one a signal() counted; the JDK hands that signal on to the
            // next waiter, so keep the debt but never more of it than there are waiters.
            state.owedWakeups = Math.min(state.owedWakeups, state.waitingThreads.size());
        }
    }

    /**
     * Record a signal() or signalAll() call, made while holding the condition's lock.
     *
     * @param condition the condition being signalled, tracked by identity
     * @param name the condition name (should match registration)
     * @param isSignalAll true if signalAll(), false if signal()
     */
    public void recordSignal(Condition condition, String name, boolean isSignalAll) {
        if (!enabled || condition == null) {
            return;
        }
        ConditionState state = conditions.get(new IdentityKey(condition));
        if (state == null) {
            return;
        }
        long thread = Thread.currentThread().threadId();
        synchronized (state) {
            if (isSignalAll) {
                state.signalAllCount++;
            } else {
                state.signalCount++;
            }
            state.signallingThreads.add(thread);
            state.lastSignalNanos = System.nanoTime();
            state.signalled = true;

            int unwoken = state.waitingThreads.size() - state.owedWakeups;
            if (unwoken <= 0) {
                state.signalsWithNoWaiter++;
            } else {
                state.owedWakeups += isSignalAll ? unwoken : 1;
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
            synchronized (state) {
                analyze(state, report);
            }
        }

        return report;
    }

    private static void analyze(ConditionState state, ConditionVariableReport report) {
        if (state.unsignalledWakeups > 0) {
            report.missingSignals.add(String.format(
                "%s: %d await(s) returned as woken with no signal()/signalAll() recorded while "
                    + "they waited (%d signal(s) recorded in total, %d with nobody waiting)",
                state.name, state.unsignalledWakeups,
                state.signalCount + state.signalAllCount, state.signalsWithNoWaiter));
        }

        if (!state.waitingThreads.isEmpty()) {
            report.stuckWaiters.add(String.format(
                "%s: %d thread(s) still waiting at analysis (%s)",
                state.name, state.waitingThreads.size(),
                state.signalled
                    ? "last signal " + (System.nanoTime() - state.lastSignalNanos) / 1_000_000 + "ms ago"
                    : "never signalled"));
        }

        if (state.signalsWithNoWaiter > 0) {
            report.signalsWithNoWaiter.add(String.format(
                "%s: %d signal(s) made with nobody waiting",
                state.name, state.signalsWithNoWaiter));
        }

        if (state.awaitCount > 0 || !state.signallingThreads.isEmpty()) {
            report.threadActivity.put(state.name, String.format(
                "%d awaits (%d timed out), %d signalling threads, %d signals, %d signalAll",
                state.awaitCount, state.timedOutAwaits,
                state.signallingThreads.size(),
                state.signalCount,
                state.signalAllCount));
        }
    }

    /**
     * Report class for Condition variable analysis.
     */
    public static class ConditionVariableReport {
        private boolean enabled = true;
        final java.util.List<String> stuckWaiters = new java.util.ArrayList<>();
        final java.util.List<String> missingSignals = new java.util.ArrayList<>();
        /** Notes, not findings: a signal with nobody waiting is normal predicate-guarded code. */
        final java.util.List<String> signalsWithNoWaiter = new java.util.ArrayList<>();
        final Map<String, String> threadActivity = new ConcurrentHashMap<>();

        /**
         * Check if any issues were detected.
         *
         * @return {@code true} when an await returned with no signal behind it, or a thread was
         *         still waiting at analysis
         */
        public boolean hasIssues() {
            return !stuckWaiters.isEmpty() || !missingSignals.isEmpty();
        }

        @Override
        public String toString() {
            if (!enabled) {
                return "ConditionVariableReport: disabled";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("CONDITION VARIABLE ISSUES DETECTED:\n");

            if (!missingSignals.isEmpty()) {
                sb.append("  Missing Signals (await woke with no signal behind it):\n");
                for (String issue : missingSignals) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!stuckWaiters.isEmpty()) {
                sb.append("  Stuck Waiters:\n");
                for (String issue : stuckWaiters) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!signalsWithNoWaiter.isEmpty()) {
                sb.append("  Note, not a finding (signals with nobody waiting; correct when the "
                        + "consumer tests its predicate before it awaits):\n");
                for (String note : signalsWithNoWaiter) {
                    sb.append("    - ").append(note).append("\n");
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

            sb.append("""
  Why: A thread still parked in await() at the end of the run is waiting for a signal nobody sent:
     the producer signalled a different condition, signalled before changing the state, or used
     signal() where two waiters needed waking. An await that returns as woken with no signal behind
     it means a signal site is not recorded, or a spurious wakeup was taken as a signal.
  Fix:
    - Always await() in a while loop: while (!ready) { condition.await(); }
    - Change the state, then signal the condition its waiters are parked on: ready = true; condition.signalAll();
    - Prefer signalAll() over signal() unless every waiter is interchangeable and exactly one should wake\
""");
            return sb.toString();
        }
    }
}
