package com.github.asynctest.diagnostics;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects wait/notify patterns without timeout (potential deadlock).
 * 
 * Problem: Using wait() without timeout can cause indefinite blocking
 * if the signal is lost or never sent.
 * 
 * Risky pattern:
 *   synchronized(lock) {
 *       while (!condition) {
 *           lock.wait();  // No timeout - blocks forever!
 *       }
 *   }
 * 
 * Safer pattern:
 *   synchronized(lock) {
 *       while (!condition) {
 *           lock.wait(timeout);  // Timeout allows recovery
 *       }
 *   }
 */
public class WaitTimeoutDetector {

    private final Map<WaitInfo, Set<String>> waitEvents = new ConcurrentHashMap<>();
    private final Set<WaitInfo> infiniteWaits = ConcurrentHashMap.newKeySet();

    /**
     * Register a wait() call without timeout.
     */
    public void recordInfiniteWait(Object monitor, String monitorName, String threadName) {
        WaitInfo info = new WaitInfo(monitor, monitorName);
        waitEvents.computeIfAbsent(info, k -> ConcurrentHashMap.newKeySet())
                  .add(threadName + ":infinite_wait");
        infiniteWaits.add(info);
    }

    /**
     * Register a wait() call with timeout.
     */
    public void recordTimedWait(Object monitor, String monitorName, String threadName, long timeoutMs) {
        WaitInfo info = new WaitInfo(monitor, monitorName);
        waitEvents.computeIfAbsent(info, k -> ConcurrentHashMap.newKeySet())
                  .add(threadName + ":timed_wait(" + timeoutMs + "ms)");
    }

    /**
     * Record a notify() call.
     */
    public void recordNotify(Object monitor, String monitorName) {
        WaitInfo info = new WaitInfo(monitor, monitorName);
        waitEvents.computeIfAbsent(info, k -> ConcurrentHashMap.newKeySet())
                  .add("notify");
    }

    /**
     * Record a notifyAll() call.
     */
    public void recordNotifyAll(Object monitor, String monitorName) {
        WaitInfo info = new WaitInfo(monitor, monitorName);
        waitEvents.computeIfAbsent(info, k -> ConcurrentHashMap.newKeySet())
                  .add("notifyAll");
    }

    /**
     * Analyze wait patterns and return report.
     */
    public WaitTimeoutReport analyze() {
        return new WaitTimeoutReport(waitEvents, infiniteWaits);
    }

    /**
     * Report class for wait timeout analysis.
     */
    public static class WaitTimeoutReport {
        private final Map<WaitInfo, Set<String>> waitEvents;
        private final Set<WaitInfo> infiniteWaits;

        public WaitTimeoutReport(
            Map<WaitInfo, Set<String>> waitEvents,
            Set<WaitInfo> infiniteWaits
        ) {
            this.waitEvents = waitEvents;
            this.infiniteWaits = infiniteWaits;
        }

        public boolean hasIssues() {
            return !infiniteWaits.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("WAIT TIMEOUT ISSUES DETECTED:\n");

            if (!infiniteWaits.isEmpty()) {
                sb.append("  Infinite wait() Calls (no timeout):\n");
                for (WaitInfo info : infiniteWaits) {
                    Set<String> events = waitEvents.get(info);
                    sb.append("    - Monitor '").append(info.monitorName).append("'\n");
                    
                    long infiniteCount = events.stream()
                        .filter(e -> e.contains("infinite_wait"))
                        .count();
                    
                    sb.append("      ").append(infiniteCount).append(" thread(s) called wait() without timeout\n");
                    
                    boolean hasNotify = events.stream().anyMatch(e -> e.contains("notify"));
                    if (!hasNotify) {
                        sb.append("      WARNING: No notify/notifyAll detected - potential deadlock!\n");
                    }
                }
                sb.append("  Fix: Use wait(timeout) instead of wait():\n");
                sb.append("    lock.wait(5000);  // 5 second timeout\n");
                sb.append("    // Handle timeout case\n");
                sb.append("  Or ensure proper notify/notifyAll is called.\n");
            }

            if (!hasIssues()) {
                sb.append("  No wait timeout issues detected.\n");
            }

            return sb.toString();
        }
    }

    /**
     * Internal wait information.
     */
    static class WaitInfo {
        final Object monitor;
        final String monitorName;

        WaitInfo(Object monitor, String monitorName) {
            this.monitor = monitor;
            this.monitorName = monitorName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            WaitInfo waitInfo = (WaitInfo) o;
            return monitor == waitInfo.monitor;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(monitor);
        }
    }
}
