package com.github.asynctest.diagnostics;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Detects spurious wakeups and lost notifications in wait/notify patterns.
 * 
 * Spurious Wakeup: A thread wakes from wait() without being notified
 * Lost Wakeup: A notify() is called when no thread is waiting
 * 
 * These issues are subtle and hard to debug in production code.
 */
public class WakeupDetector {
    
    private static class MonitorState {
        final Object monitor;
        volatile int waitingThreads = 0;
        final AtomicLong notifyCount = new AtomicLong(0);
        final AtomicLong spuriousWakeups = new AtomicLong(0);
        final AtomicLong lostNotifications = new AtomicLong(0);
        volatile long lastNotifyTime = 0;
        volatile long lastWaitTime = 0;
        final Set<Long> currentlyWaiting = ConcurrentHashMap.newKeySet();
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        
        MonitorState(Object m) {
            this.monitor = m;
        }
    }
    
    private final Map<Integer, MonitorState> monitors = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    
    /**
     * Record that a thread is about to wait on a monitor.
     */
    public void recordWaitEnter(Object monitor) {
        if (!enabled) return;
        
        MonitorState state = monitors.computeIfAbsent(System.identityHashCode(monitor), 
            k -> new MonitorState(monitor)
        );
        
        synchronized (state) {
            state.waitingThreads++;
            state.lastWaitTime = System.nanoTime();
            state.currentlyWaiting.add(Thread.currentThread().getId());
            state.events.add(String.format("T-%d WAIT_ENTER (waiting: %d)",
                Thread.currentThread().getId(), state.waitingThreads));
        }
    }
    
    /**
     * Record that a thread has exited wait (either notified or spurious).
     */
    public void recordWaitExit(Object monitor, boolean wasNotified) {
        if (!enabled) return;
        
        MonitorState state = monitors.get(System.identityHashCode(monitor));
        if (state == null) return;
        
        synchronized (state) {
            state.waitingThreads--;
            state.currentlyWaiting.remove(Thread.currentThread().getId());
            
            if (!wasNotified) {
                state.spuriousWakeups.incrementAndGet();
                state.events.add(String.format("T-%d SPURIOUS_WAKEUP (waiting: %d)",
                    Thread.currentThread().getId(), state.waitingThreads));
            } else {
                state.events.add(String.format("T-%d WAIT_EXIT_NOTIFIED (waiting: %d)",
                    Thread.currentThread().getId(), state.waitingThreads));
            }
        }
    }
    
    /**
     * Record a notify call on a monitor.
     */
    public void recordNotify(Object monitor, boolean notifyAll) {
        if (!enabled) return;
        
        MonitorState state = monitors.computeIfAbsent(System.identityHashCode(monitor),
            k -> new MonitorState(monitor)
        );
        
        synchronized (state) {
            state.notifyCount.incrementAndGet();
            state.lastNotifyTime = System.nanoTime();
            
            if (state.waitingThreads == 0) {
                state.lostNotifications.incrementAndGet();
                state.events.add(String.format("T-%d NOTIFY_LOST (no waiters)",
                    Thread.currentThread().getId()));
            } else {
                state.events.add(String.format("T-%d NOTIFY%s (waiting: %d)",
                    Thread.currentThread().getId(), 
                    notifyAll ? "_ALL" : "",
                    state.waitingThreads));
            }
        }
    }
    
    /**
     * Analyze wakeup patterns for issues.
     */
    public WakeupReport analyzeWakeups() {
        WakeupReport report = new WakeupReport();
        
        for (MonitorState state : monitors.values()) {
            if (state.spuriousWakeups.get() > 0) {
                report.monitorsWithSpuriousWakeups.add(String.format(
                    "%s: %d spurious wakeups out of %d notifies",
                    state.monitor.getClass().getSimpleName(),
                    state.spuriousWakeups.get(),
                    state.notifyCount.get()
                ));
            }
            
            if (state.lostNotifications.get() > 0) {
                report.monitorsWithLostNotifications.add(String.format(
                    "%s: %d lost notifications (notify with no waiters)",
                    state.monitor.getClass().getSimpleName(),
                    state.lostNotifications.get()
                ));
            }
            
            // Detect notify without wait pattern
            if (state.notifyCount.get() > 0 && state.waitingThreads == 0 && state.currentlyWaiting.isEmpty()) {
                report.alwaysNotifyWithoutWait.add(state.monitor.getClass().getSimpleName());
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
    
    public static class WakeupReport {
        public final Set<String> monitorsWithSpuriousWakeups = new HashSet<>();
        public final Set<String> monitorsWithLostNotifications = new HashSet<>();
        public final Set<String> alwaysNotifyWithoutWait = new HashSet<>();
        
        public boolean hasIssues() {
            return !monitorsWithSpuriousWakeups.isEmpty() || !monitorsWithLostNotifications.isEmpty();
        }
        
        @Override
        public String toString() {
            if (!hasIssues()) {
                return "No wakeup issues detected.";
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("WAIT/NOTIFY ISSUES DETECTED:\n");
            
            if (!monitorsWithSpuriousWakeups.isEmpty()) {
                sb.append("\nSpurious Wakeups (thread woke without being notified):\n");
                for (String issue : monitorsWithSpuriousWakeups) {
                    sb.append("  - ").append(issue).append("\n");
                }
                sb.append("  Fix: Wrap wait() in while loop checking condition\n");
            }
            
            if (!monitorsWithLostNotifications.isEmpty()) {
                sb.append("\nLost Notifications (notify called with no waiting threads):\n");
                for (String issue : monitorsWithLostNotifications) {
                    sb.append("  - ").append(issue).append("\n");
                }
                sb.append("  Fix: Ensure notify() comes after another thread calls wait()\n");
            }
            
            if (!alwaysNotifyWithoutWait.isEmpty()) {
                sb.append("\nNotify Always Called Without Wait:\n");
                for (String monitor : alwaysNotifyWithoutWait) {
                    sb.append("  - ").append(monitor).append("\n");
                }
                sb.append("  Fix: Implement proper wait/notify coordination\n");
            }
            
            return sb.toString();
        }
    }
}
