package com.github.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects missed (lost) signals — situations where {@code notify()} or
 * {@code notifyAll()} is called on a condition before any thread is waiting
 * on it, causing the signal to be silently discarded.
 *
 * <p>The missed-signal bug typically looks like this:
 *
 * <pre>{@code
 * // Thread A (producer — runs first)
 * synchronized (monitor) {
 *     dataReady = true;
 *     monitor.notify();   // ← signal lost: Thread B hasn't called wait() yet!
 * }
 *
 * // Thread B (consumer — runs second)
 * synchronized (monitor) {
 *     while (!dataReady) {
 *         monitor.wait(); // ← blocks forever — missed the notify
 *     }
 * }
 * }</pre>
 *
 * <p>This detector tracks the number of threads currently waiting on each
 * named condition.  If {@code notify()} or {@code notifyAll()} is called
 * when the waiter count is zero, the signal is recorded as missed.
 *
 * <p>Usage:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectMissedSignals = true)
 * void testMissedSignal() throws InterruptedException {
 *     MissedSignalDetector detector = AsyncTestContext.missedSignalDetector();
 *
 *     synchronized (monitor) {
 *         // Before waiting, register interest
 *         detector.recordWait("dataReady");
 *         monitor.wait(100);
 *         detector.recordWakeup("dataReady");
 *     }
 * }
 * }</pre>
 */
public class MissedSignalDetector {

    private static final class ConditionState {
        final String name;
        final AtomicInteger waiters       = new AtomicInteger();
        final AtomicInteger missedSignals = new AtomicInteger();
        final AtomicInteger totalNotifies = new AtomicInteger();

        ConditionState(String name) {
            this.name = name;
        }
    }

    private final Map<String, ConditionState> conditions = new ConcurrentHashMap<>();

    // ---- Public API --------------------------------------------------------

    /**
     * Records that the calling thread is about to call {@code wait()} on the
     * named condition.  Call this while already holding the associated monitor.
     *
     * @param conditionName a stable logical name for the condition, e.g. {@code "dataReady"}
     */
    public void recordWait(String conditionName) {
        if (conditionName == null) return;
        resolve(conditionName).waiters.incrementAndGet();
    }

    /**
     * Records that the calling thread returned from {@code wait()} (either via
     * a signal or a spurious wakeup / timeout).
     *
     * @param conditionName the same name passed to {@link #recordWait}
     */
    public void recordWakeup(String conditionName) {
        if (conditionName == null) return;
        ConditionState state = resolve(conditionName);
        int w = state.waiters.decrementAndGet();
        if (w < 0) state.waiters.set(0);
    }

    /**
     * Records a {@code notify()} call on the named condition.
     * If no thread is currently waiting, the signal is counted as missed.
     *
     * @param conditionName the name of the condition being signalled
     */
    public void recordNotify(String conditionName) {
        if (conditionName == null) return;
        ConditionState state = resolve(conditionName);
        state.totalNotifies.incrementAndGet();
        if (state.waiters.get() == 0) {
            state.missedSignals.incrementAndGet();
        }
    }

    /**
     * Records a {@code notifyAll()} call on the named condition.
     * If no thread is currently waiting, the signal is counted as missed.
     *
     * @param conditionName the name of the condition being signalled
     */
    public void recordNotifyAll(String conditionName) {
        recordNotify(conditionName);
    }

    // ---- Analysis ----------------------------------------------------------

    /**
     * Analyses recorded signal/wait data and returns a report of conditions
     * that suffered missed signals.
     */
    public MissedSignalReport analyze() {
        MissedSignalReport report = new MissedSignalReport();

        for (ConditionState state : conditions.values()) {
            int missed = state.missedSignals.get();
            int total  = state.totalNotifies.get();
            if (missed > 0) {
                report.missedConditions.add(String.format(
                        "%s: %d of %d notify call(s) were missed (no thread was waiting) — SIGNAL LOST, potential indefinite wait!",
                        state.name, missed, total));
            }
        }

        return report;
    }

    // ---- Internal ----------------------------------------------------------

    private ConditionState resolve(String conditionName) {
        return conditions.computeIfAbsent(conditionName, ConditionState::new);
    }

    // ---- Report ------------------------------------------------------------

    /**
     * Report produced by {@link #analyze()}.
     */
    public static class MissedSignalReport {

        final List<String> missedConditions = new ArrayList<>();

        /** Returns {@code true} when at least one condition suffered a missed signal. */
        public boolean hasIssues() {
            return !missedConditions.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("MISSED SIGNAL ISSUES DETECTED:\n");

            if (!missedConditions.isEmpty()) {
                sb.append("  Conditions with lost notify() calls:\n");
                for (String entry : missedConditions) {
                    sb.append("    - ").append(entry).append("\n");
                }
            } else {
                sb.append("  No missed signals detected.\n");
            }

            sb.append("  Fix: always check a state predicate in a loop before waiting")
              .append(" (while (!ready) { monitor.wait(); }) so that re-checking after the fact")
              .append(" handles signals that arrive before wait().");
            return sb.toString();
        }
    }
}
