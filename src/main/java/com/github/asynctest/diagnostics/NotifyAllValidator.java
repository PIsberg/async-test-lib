package com.github.asynctest.diagnostics;

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
        final AtomicInteger notifyCalls = new AtomicInteger();
        final AtomicInteger notifyAllCalls = new AtomicInteger();

        MonitorState(String monitorName) {
            this.monitorName = monitorName;
        }
    }

    private final Map<Integer, MonitorState> monitors = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

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
        state.waitingThreads.incrementAndGet();
    }

    public void recordWaiterReleased(Object monitor) {
        if (!enabled || monitor == null) {
            return;
        }

        MonitorState state = monitors.get(System.identityHashCode(monitor));
        if (state != null) {
            state.waitingThreads.updateAndGet(current -> Math.max(0, current - 1));
        }
    }

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
        }
    }

    public NotifyAllReport analyze() {
        NotifyAllReport report = new NotifyAllReport();

        for (MonitorState state : monitors.values()) {
            if (state.waitingThreads.get() > 1 && state.notifyCalls.get() > 0 && state.notifyAllCalls.get() == 0) {
                report.notifyInsteadOfNotifyAll.add(String.format(
                    "%s: %d waiting threads but only notify() was observed",
                    state.monitorName,
                    state.waitingThreads.get()
                ));
            }
        }

        return report;
    }

    public void reset() {
        monitors.clear();
    }

    public void disable() {
        enabled = false;
    }

    public void enable() {
        enabled = true;
    }

    public static class NotifyAllReport {
        public final Set<String> notifyInsteadOfNotifyAll = new HashSet<>();

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
