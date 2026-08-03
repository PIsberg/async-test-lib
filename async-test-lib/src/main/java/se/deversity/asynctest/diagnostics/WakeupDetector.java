package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
        int waitingThreads = 0; // guarded by synchronized(this)
        final AtomicLong notifyCount = new AtomicLong(0);
        final AtomicLong spuriousWakeups = new AtomicLong(0);
        final AtomicLong lostNotifications = new AtomicLong(0);
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
            state.currentlyWaiting.add(Thread.currentThread().threadId());
            state.events.add(String.format("T-%d WAIT_ENTER (waiting: %d)",
                Thread.currentThread().threadId(), state.waitingThreads));
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
            state.currentlyWaiting.remove(Thread.currentThread().threadId());
            
            if (!wasNotified) {
                state.spuriousWakeups.incrementAndGet();
                state.events.add(String.format("T-%d SPURIOUS_WAKEUP (waiting: %d)",
                    Thread.currentThread().threadId(), state.waitingThreads));
            } else {
                state.events.add(String.format("T-%d WAIT_EXIT_NOTIFIED (waiting: %d)",
                    Thread.currentThread().threadId(), state.waitingThreads));
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

            if (state.waitingThreads == 0) {
                state.lostNotifications.incrementAndGet();
                state.events.add(String.format("T-%d NOTIFY_LOST (no waiters)",
                    Thread.currentThread().threadId()));
            } else {
                state.events.add(String.format("T-%d NOTIFY%s (waiting: %d)",
                    Thread.currentThread().threadId(), 
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

    /**
     * Standardized alias for {@link #analyzeWakeups()}.
     */
    public WakeupReport analyze() {
        return analyzeWakeups();
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
    
    public static class WakeupReport {
        /** The monitors with spurious wakeups. */
        public final Set<String> monitorsWithSpuriousWakeups = new HashSet<>();
        /** The monitors with lost notifications. */
        public final Set<String> monitorsWithLostNotifications = new HashSet<>();
        /** The always notify without wait. */
        public final Set<String> alwaysNotifyWithoutWait = new HashSet<>();
        
        /** {@return whether there are issues} */
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
                sb.append("""
                          Why: wait() can return spuriously (without a notification) due to OS-level interrupts or JVM internals.
                               Proceeding on a single if-check instead of a while loop causes the thread to act as if the
                               condition is met when it is not, producing logic errors or data corruption.
                          Fix: Always wrap wait() in a while loop: synchronized(lock) { while (!condition) { lock.wait(); } }
                        """);
            }
            
            if (!monitorsWithLostNotifications.isEmpty()) {
                sb.append("\nLost Notifications (notify called with no waiting threads):\n");
                for (String issue : monitorsWithLostNotifications) {
                    sb.append("  - ").append(issue).append("\n");
                }
                sb.append("""
                            Why: A notify() fired before any thread calls wait() is silently lost — the waiting thread will
                                 never receive it and blocks forever. This is the classic "lost wakeup" race.
                            Fix: Set a boolean flag before calling notify(), and check it in a while loop before wait():
                                 ready = true; lock.notifyAll();  // sender
                                 while (!ready) { lock.wait(); } // receiver — handles notify arriving before wait()
                          """);
            }
            
            if (!alwaysNotifyWithoutWait.isEmpty()) {
                sb.append("\nNotify Always Called Without Wait:\n");
                for (String monitor : alwaysNotifyWithoutWait) {
                    sb.append("  - ").append(monitor).append("\n");
                }
                sb.append("""
                            Why: Calling notify() without a corresponding wait() is a no-op that wastes a signal.
                                 It usually indicates that the notify is being fired unconditionally rather than in response
                                 to a state change, breaking the protocol between producer and consumer.
                            Fix: Pair notify() with a state change and pair wait() with a condition check:
                                 // Producer: condition = true; synchronized(lock) { lock.notifyAll(); }
                                 // Consumer: synchronized(lock) { while (!condition) { lock.wait(); } }
                          """);
            }
            
            return sb.toString();
        }
    }
}
