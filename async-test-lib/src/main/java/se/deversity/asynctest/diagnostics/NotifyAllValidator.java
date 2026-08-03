package se.deversity.asynctest.diagnostics;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects notify/notifyAll misuse in classic wait/notify coordination.
 */
public class NotifyAllValidator {

    private static class MonitorState {
        final String monitorName;
        final AtomicInteger waitingThreads = new AtomicInteger();
        /**
         * High-water mark of {@link #waitingThreads}. The live count drains back to zero as
         * waiters wake, and analysis runs after the test — so the live count cannot be the
         * basis for detection, only its peak can.
         */
        final AtomicInteger peakWaitingThreads = new AtomicInteger();
        /** Times {@code notify()} was called while more than one thread was parked. */
        final AtomicInteger notifyWithSeveralWaiters = new AtomicInteger();
        final AtomicInteger notifyCalls = new AtomicInteger();
        final AtomicInteger notifyAllCalls = new AtomicInteger();

        MonitorState(String monitorName) {
            this.monitorName = monitorName;
        }
    }

    private final Map<Integer, MonitorState> monitors = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    /**
     * Records waiter added so it can be analysed at the end of the run.
     *
     * @param monitor the object being used as a monitor, tracked by identity
     * @param monitorName a label identifying the monitor in the report
     */
    public void recordWaiterAdded(Object monitor, String monitorName) {
        if (!enabled || monitor == null) {
            return;
        }

        MonitorState state = monitors.computeIfAbsent(
            System.identityHashCode(monitor),
            ignored -> new MonitorState(monitorName == null || monitorName.isBlank()
                ? monitor.getClass().getSimpleName()
                : monitorName)
        );
        int parked = state.waitingThreads.incrementAndGet();
        state.peakWaitingThreads.updateAndGet(peak -> Math.max(peak, parked));
    }
    /**
     * Records waiter released so it can be analysed at the end of the run.
     *
     * @param monitor the object being used as a monitor, tracked by identity
     */
    public void recordWaiterReleased(Object monitor) {
        if (!enabled || monitor == null) {
            return;
        }

        MonitorState state = monitors.get(System.identityHashCode(monitor));
        if (state != null) {
            state.waitingThreads.updateAndGet(current -> Math.max(0, current - 1));
        }
    }
    /**
     * Records notify so it can be analysed at the end of the run.
     *
     * @param monitor the object being used as a monitor, tracked by identity
     * @param notifyAll {@code true} when {@code notifyAll} was called rather than {@code notify}
     */
    public void recordNotify(Object monitor, boolean notifyAll) {
        if (!enabled || monitor == null) {
            return;
        }

        MonitorState state = monitors.computeIfAbsent(
            System.identityHashCode(monitor),
            ignored -> new MonitorState(monitor.getClass().getSimpleName())
        );

        if (notifyAll) {
            state.notifyAllCalls.incrementAndGet();
        } else {
            state.notifyCalls.incrementAndGet();
            // Capture the waiter count as it stands at the moment of the notify. This is the
            // evidence of a lost wakeup: notify() wakes exactly one of them, and the rest can
            // stay parked. Reading it later is useless — the count drains to zero as they wake.
            if (state.waitingThreads.get() > 1) {
                state.notifyWithSeveralWaiters.incrementAndGet();
            }
        }
    }
    /**
     * Analyses what has been recorded about the observation and builds the report for it.
     *
     * @return the findings this detector collected during the run
     */
    public NotifyAllReport analyze() {
        NotifyAllReport report = new NotifyAllReport();

        for (MonitorState state : monitors.values()) {
            // Two ways to see the lost wakeup, both resting on evidence that survives the
            // waiters draining away:
            //   1. a notify() was observed while several threads were actually parked, or
            //   2. several threads were parked at some point and notify() was the only signal
            //      the monitor ever got.
            boolean caughtInTheAct = state.notifyWithSeveralWaiters.get() > 0;
            boolean onlyEverNotify = state.peakWaitingThreads.get() > 1
                    && state.notifyCalls.get() > 0
                    && state.notifyAllCalls.get() == 0;

            if (caughtInTheAct || onlyEverNotify) {
                report.notifyInsteadOfNotifyAll.add(String.format(
                    "%s: up to %d threads waiting but only notify() was observed",
                    state.monitorName,
                    state.peakWaitingThreads.get()
                ));
            }
        }

        return report;
    }
    /**
     * Clears recorded the observation so this instance can be reused for the next run.
     */
    public void reset() {
        monitors.clear();
    }
    /**
     * Disable.
     */
    public void disable() {
        enabled = false;
    }
    /**
     * Enable.
     */
    public void enable() {
        enabled = true;
    }

    public static class NotifyAllReport {
        /** Monitors signalled with {@code notify} where waiters await different conditions. */
        public final Set<String> notifyInsteadOfNotifyAll = new HashSet<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return !notifyInsteadOfNotifyAll.isEmpty();
        }

        @Override
        public String toString() {
            if (!hasIssues()) {
                return "No notify/notifyAll issues detected.";
            }

            StringBuilder sb = new StringBuilder("NOTIFY/NOTIFYALL ISSUES DETECTED:\n");
            for (String issue : notifyInsteadOfNotifyAll) {
                sb.append("  - ").append(issue).append('\n');
            }
            sb.append("  Fix: use notifyAll() when multiple waiters may need the signal");
            return sb.toString();
        }
    }
}
