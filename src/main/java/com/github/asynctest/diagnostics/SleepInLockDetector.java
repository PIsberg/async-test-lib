package com.github.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects Thread.sleep() calls while holding a lock.
 *
 * Sleeping while holding a lock is a common concurrency anti-pattern that causes:
 * - Unnecessary contention: other threads block unnecessarily
 * - Performance degradation: lock hold times increase dramatically
 * - Potential deadlocks: if the sleeping thread holds multiple locks
 * - Priority inversion: high-priority threads wait for low-priority sleeping threads
 *
 * <p>The correct approach is to either:
 * - Release the lock before sleeping, then reacquire
 * - Use wait()/notify() with a timeout for coordination
 * - Use higher-level constructs like CountDownLatch or Condition
 *
 * <p>Usage:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectSleepInLock = true)
 * void testSleepInLock() throws InterruptedException {
 *     synchronized (lock) {
 *         doWork();
 *         Thread.sleep(100);  // Detector will flag this
 *     }
 * }
 * }</pre>
 *
 * <p>The detector uses stack trace sampling to identify when Thread.sleep()
 * is called from within synchronized blocks or while holding ReentrantLock.
 */
public class SleepInLockDetector {

    private static class SleepInLockEvent {
        final String lockName;
        final String threadName;
        final long sleepDuration;
        final StackTraceElement[] stackTrace;
        final long timestamp;
        final String lockType; // "synchronized" or "ReentrantLock"

        SleepInLockEvent(String lockName, String threadName, long sleepDuration,
                        StackTraceElement[] stackTrace, String lockType) {
            this.lockName = lockName;
            this.threadName = threadName;
            this.sleepDuration = sleepDuration;
            this.stackTrace = stackTrace;
            this.timestamp = System.currentTimeMillis();
            this.lockType = lockType;
        }
    }

    private final List<SleepInLockEvent> events = new ArrayList<>();
    private final AtomicInteger eventCount = new AtomicInteger(0);
    private volatile boolean enabled = true;
    private volatile boolean monitoring = false;

    /**
     * Start monitoring for sleep-in-lock patterns.
     */
    public void startMonitoring() {
        if (!enabled) return;
        monitoring = true;
    }

    /**
     * Stop monitoring.
     */
    public void stopMonitoring() {
        monitoring = false;
    }

    /**
     * Record a Thread.sleep() call. The detector will check if the calling
     * thread holds any locks and record an event if so.
     *
     * @param sleepDurationMs the duration of the sleep in milliseconds
     */
    public void recordSleep(long sleepDurationMs) {
        if (!enabled || !monitoring || sleepDurationMs <= 0) {
            return;
        }

        Thread currentThread = Thread.currentThread();
        StackTraceElement[] stackTrace = currentThread.getStackTrace();

        // Check if current thread holds any locks
        ThreadInfo threadInfo = analyzeThreadLocks(currentThread, stackTrace);

        if (threadInfo.holdsLock) {
            SleepInLockEvent event = new SleepInLockEvent(
                threadInfo.lockName,
                currentThread.getName(),
                sleepDurationMs,
                stackTrace,
                threadInfo.lockType
            );
            synchronized (events) {
                events.add(event);
            }
            eventCount.incrementAndGet();
        }
    }

    private static class ThreadInfo {
        final boolean holdsLock;
        final String lockName;
        final String lockType;

        ThreadInfo(boolean holdsLock, String lockName, String lockType) {
            this.holdsLock = holdsLock;
            this.lockName = lockName;
            this.lockType = lockType;
        }
    }

    private ThreadInfo analyzeThreadLocks(Thread thread, StackTraceElement[] stackTrace) {
        // Check for synchronized blocks via Thread.getAllStackTraces()
        Map<Thread, StackTraceElement[]> allStacks = Thread.getAllStackTraces();
        StackTraceElement[] currentStack = allStacks.get(thread);
        
        if (currentStack != null) {
            // Look for synchronized blocks (native monitor enter)
            for (StackTraceElement element : currentStack) {
                if (element.isNativeMethod() && element.getMethodName().contains("monitor")) {
                    return new ThreadInfo(true, element.getClassName(), "synchronized");
                }
            }
        }

        // Check for ReentrantLock via stack trace patterns
        for (StackTraceElement element : stackTrace) {
            if (element.getMethodName().contains("lock") ||
                element.getMethodName().contains("Lock")) {
                return new ThreadInfo(true, element.getClassName(), "ReentrantLock");
            }
        }

        return new ThreadInfo(false, null, null);
    }

    /**
     * Analyze and return a report of sleep-in-lock events.
     *
     * @return the analysis report
     */
    public SleepInLockReport analyze() {
        if (!enabled) {
            return new SleepInLockReport(List.of(), 0, false);
        }

        List<SleepInLockEventSnapshot> snapshots;
        synchronized (events) {
            snapshots = events.stream()
                .map(e -> new SleepInLockEventSnapshot(
                    e.lockName, e.threadName, e.sleepDuration,
                    e.stackTrace, e.lockType
                ))
                .toList();
        }

        return new SleepInLockReport(snapshots, eventCount.get(), true);
    }

    /**
     * Clear all recorded events.
     */
    public void clear() {
        synchronized (events) {
            events.clear();
        }
        eventCount.set(0);
    }

    public void disable() {
        this.enabled = false;
    }

    /**
     * Immutable snapshot of a sleep-in-lock event.
     */
    public static class SleepInLockEventSnapshot {
        public final String lockName;
        public final String threadName;
        public final long sleepDuration;
        public final StackTraceElement[] stackTrace;
        public final String lockType;

        SleepInLockEventSnapshot(String lockName, String threadName, long sleepDuration,
                                StackTraceElement[] stackTrace, String lockType) {
            this.lockName = lockName;
            this.threadName = threadName;
            this.sleepDuration = sleepDuration;
            this.stackTrace = stackTrace;
            this.lockType = lockType;
        }
    }

    /**
     * Report of sleep-in-lock analysis.
     */
    public static class SleepInLockReport {
        private final List<SleepInLockEventSnapshot> events;
        private final int totalCount;
        private final boolean enabled;

        SleepInLockReport(List<SleepInLockEventSnapshot> events, int totalCount, boolean enabled) {
            this.events = events;
            this.totalCount = totalCount;
            this.enabled = enabled;
        }

        public boolean hasIssues() {
            return !events.isEmpty();
        }

        public List<SleepInLockEventSnapshot> getEvents() {
            return List.copyOf(events);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("SleepInLockReport:\n");
            sb.append("  Total events: ").append(totalCount).append("\n");

            if (events.isEmpty()) {
                sb.append("  Status: No sleep-in-lock patterns detected ✓\n");
            } else {
                sb.append("  SLEEP-IN-LOCK PATTERNS DETECTED:\n");
                for (int i = 0; i < events.size(); i++) {
                    SleepInLockEventSnapshot event = events.get(i);
                    sb.append("  [").append(i + 1).append("] ").append(event.threadName);
                    sb.append(" slept for ").append(event.sleepDuration).append("ms\n");
                    sb.append("      Lock type: ").append(event.lockType).append("\n");
                    sb.append("      Lock: ").append(event.lockName).append("\n");
                    sb.append("      Problem: Sleeping while holding a lock causes unnecessary contention\n");
                    sb.append("      Fix: Release lock before sleeping, or use wait()/notify() or Condition\n");
                    if (event.stackTrace != null && event.stackTrace.length > 3) {
                        sb.append("      Stack trace:\n");
                        for (int j = 3; j < Math.min(7, event.stackTrace.length); j++) {
                            sb.append("        at ").append(event.stackTrace[j]).append("\n");
                        }
                    }
                }
            }
            return sb.toString();
        }
    }
}
