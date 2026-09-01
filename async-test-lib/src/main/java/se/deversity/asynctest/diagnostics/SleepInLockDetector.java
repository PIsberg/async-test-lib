package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

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
        final @Nullable String lockName;
        final String threadName;
        final long sleepDuration;
        final StackTraceElement[] stackTrace;
        final @Nullable String lockType; // "synchronized" or "ReentrantLock"

        SleepInLockEvent(@Nullable String lockName, String threadName, long sleepDuration,
                        StackTraceElement[] stackTrace, @Nullable String lockType) {
            this.lockName = lockName;
            this.threadName = threadName;
            this.sleepDuration = sleepDuration;
            this.stackTrace = stackTrace;
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
     * Record a {@code Thread.sleep()} call, letting the JVM say which monitors the caller holds.
     *
     * <p><strong>Platform threads only.</strong> This asks
     * {@code ThreadMXBean.getThreadInfo(id, true, true)}, which does not report virtual threads,
     * so on the default {@code @AsyncTest} runner it finds no lock however deep inside a
     * {@code synchronized} block the caller is. Use {@link #recordSleep(long, Object)}, which
     * names the monitor and works on any thread. See issue #373.
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
        ThreadInfo threadInfo = analyzeThreadLocks(currentThread);

        if (threadInfo.holdsLock) {
            record(new SleepInLockEvent(
                threadInfo.lockName,
                currentThread.getName(),
                sleepDurationMs,
                stackTrace,
                threadInfo.lockType
            ));
        }
    }
    /**
     * Record a {@code Thread.sleep()} made while holding {@code monitor}.
     *
     * <p>Prefer this over {@link #recordSleep(long)}. That one asks the JVM which monitors the
     * calling thread holds, through {@code ThreadMXBean.getThreadInfo(id)}, and that call does not
     * report virtual threads - which is what {@code @AsyncTest} runs its workers on by default, so
     * the detector saw no lock however deep inside a {@code synchronized} block the caller was.
     * Measured on {@code examples/74-sleep-in-lock}: silent by default, reported with
     * {@code useVirtualThreads = false}, same subject and same instrumentation. See issue #373.
     *
     * <p>{@link Thread#holdsLock(Object)} answers the same question exactly, on any thread and any
     * JDK, for nothing. It is also stronger evidence than the other overload rather than weaker:
     * the JVM confirms the specific claim, where the JMX path infers from whichever monitor
     * {@code getLockedMonitors()} happens to return first. A caller that names a monitor it does
     * not hold records nothing, so this cannot be used to assert a finding into existence.
     *
     * @param sleepDurationMs the duration of the sleep in milliseconds
     * @param monitor the object whose monitor the caller believes it holds; ignored when it does
     *                not, and when it is {@code null}
     */
    public void recordSleep(long sleepDurationMs, @Nullable Object monitor) {
        if (!enabled || !monitoring || sleepDurationMs <= 0 || monitor == null) {
            return;
        }
        if (!Thread.holdsLock(monitor)) {
            return;
        }
        Thread currentThread = Thread.currentThread();
        record(new SleepInLockEvent(
                monitor.getClass().getName() + "@" + System.identityHashCode(monitor),
                currentThread.getName(),
                sleepDurationMs,
                currentThread.getStackTrace(),
                "synchronized"));
    }

    /** Adds one event under the list's own lock and keeps the count in step with it. */
    private void record(SleepInLockEvent event) {
        synchronized (events) {
            events.add(event);
        }
        eventCount.incrementAndGet();
    }

    private static class ThreadInfo {
        final boolean holdsLock;
        final @Nullable String lockName;
        final @Nullable String lockType;

        ThreadInfo(boolean holdsLock, @Nullable String lockName, @Nullable String lockType) {
            this.holdsLock = holdsLock;
            this.lockName = lockName;
            this.lockType = lockType;
        }
    }

    private ThreadInfo analyzeThreadLocks(Thread thread) {
        // Ask the JVM which locks the thread actually holds. The previous
        // stack-trace heuristic could not work in either direction: entering a
        // synchronized block leaves no stack frame to find (so real
        // sleep-in-synchronized was never detected), while matching frame
        // method names against "lock"/"Lock" flagged any caller that merely
        // had "Lock" in its method name (a false positive with no lock held).
        try {
            java.lang.management.ThreadMXBean bean =
                java.lang.management.ManagementFactory.getThreadMXBean();
            if (!bean.isObjectMonitorUsageSupported() || !bean.isSynchronizerUsageSupported()) {
                return new ThreadInfo(false, null, null);
            }
            java.lang.management.ThreadInfo[] infos =
                bean.getThreadInfo(new long[] {thread.threadId()}, true, true);
            if (infos.length == 0 || infos[0] == null) {
                return new ThreadInfo(false, null, null);
            }

            java.lang.management.MonitorInfo[] monitors = infos[0].getLockedMonitors();
            if (monitors.length > 0) {
                return new ThreadInfo(true, monitors[0].getClassName(), "synchronized");
            }

            for (java.lang.management.LockInfo sync : infos[0].getLockedSynchronizers()) {
                // A running ThreadPoolExecutor$Worker holds its own AQS for the
                // duration of every task — that is executor plumbing, not a lock
                // the user code took, so it must not be reported.
                if (sync.getClassName().startsWith("java.util.concurrent.ThreadPoolExecutor")) {
                    continue;
                }
                return new ThreadInfo(true, sync.getClassName(), "ReentrantLock");
            }
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // Lock introspection unavailable on this JVM — report no lock rather than guess.
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
            return new SleepInLockReport(List.of(), 0);
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

        return new SleepInLockReport(snapshots, eventCount.get());
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
    /**
     * Disable.
     */
    public void disable() {
        this.enabled = false;
    }

    /**
     * Immutable snapshot of a sleep-in-lock event.
     */
    public static class SleepInLockEventSnapshot {
        /** Label identifying the lock that was held while sleeping. */
        public final @Nullable String lockName;
        /** Label identifying the sleeping thread in the report. */
        public final String threadName;
        /** How long the thread slept while holding the lock, in milliseconds: what both recordSleep overloads take and what the report prints. */
        public final long sleepDuration;
        /** Where the sleep happened. */
        public final StackTraceElement[] stackTrace;
        /** Whether the lock held was {@code synchronized} or a {@code ReentrantLock}. */
        public final @Nullable String lockType;

        SleepInLockEventSnapshot(@Nullable String lockName, String threadName,
                                long sleepDuration, StackTraceElement[] stackTrace,
                                @Nullable String lockType) {
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

        SleepInLockReport(List<SleepInLockEventSnapshot> events, int totalCount) {
            this.events = events;
            this.totalCount = totalCount;
        }

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return !events.isEmpty();
        }

        /**
         * {@return the events}
         */
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
