package se.deversity.asynctest.diagnostics;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
     *
     * @param monitor the monitor
     * @param monitorName the monitor name
     * @param threadName the thread name
     */
    public void recordInfiniteWait(Object monitor, String monitorName, String threadName) {
        WaitInfo info = new WaitInfo(monitor, monitorName);
        waitEvents.computeIfAbsent(info, k -> ConcurrentHashMap.newKeySet())
                  .add(threadName + ":infinite_wait");
        infiniteWaits.add(info);
    }

    /**
     * Register a wait() call with timeout.
     *
     * @param monitor the monitor
     * @param monitorName the monitor name
     * @param threadName the thread name
     * @param timeoutMs the timeout in milliseconds
     */
    public void recordTimedWait(Object monitor, String monitorName, String threadName, long timeoutMs) {
        WaitInfo info = new WaitInfo(monitor, monitorName);
        waitEvents.computeIfAbsent(info, k -> ConcurrentHashMap.newKeySet())
                  .add(threadName + ":timed_wait(" + timeoutMs + "ms)");
    }

    /**
     * Record a notify() call.
     *
     * @param monitor the monitor
     * @param monitorName the monitor name
     */
    public void recordNotify(Object monitor, String monitorName) {
        WaitInfo info = new WaitInfo(monitor, monitorName);
        waitEvents.computeIfAbsent(info, k -> ConcurrentHashMap.newKeySet())
                  .add("notify");
    }

    /**
     * Record a notifyAll() call.
     *
     * @param monitor the monitor
     * @param monitorName the monitor name
     */
    public void recordNotifyAll(Object monitor, String monitorName) {
        WaitInfo info = new WaitInfo(monitor, monitorName);
        waitEvents.computeIfAbsent(info, k -> ConcurrentHashMap.newKeySet())
                  .add("notifyAll");
    }

    /**
     * Analyze wait patterns and return report.
     *
     * @return the analyze
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
            this.waitEvents = Collections.unmodifiableMap(new HashMap<>(waitEvents));
            this.infiniteWaits = Collections.unmodifiableSet(new HashSet<>(infiniteWaits));
        }

        /**
         * {@return whether there are issues}
         */
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
                    Set<String> recorded = waitEvents.get(info);
                    Set<String> events = recorded != null ? recorded : Set.of();
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
                sb.append("  Why: Calling wait() without a timeout means the thread can only wake up via notify()/notifyAll().\n");
                sb.append("       If the notification is never sent (due to a bug) or fires before wait() is called (a missed signal),\n");
                sb.append("       the thread hangs indefinitely — freezing the test or the application.\n");
                sb.append("  Fix: always supply a timeout so the thread can detect stalls and bail out:\n");
                sb.append("    lock.wait(5000);  // 5 second timeout — check the condition again after waking\n");
                sb.append("    // Handle timeout: log warning, retry, or fail fast\n");
                sb.append("  Or ensure a matching notifyAll() is always called when the condition becomes true.\n");
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
        @SuppressWarnings("EqualsGetClass") // subclass-distinct equality is intended for this non-final class
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
