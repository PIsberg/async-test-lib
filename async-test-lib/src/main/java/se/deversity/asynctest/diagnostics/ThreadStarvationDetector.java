package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Detects thread starvation in thread pools.
 *
 * Thread starvation occurs when tasks wait excessively long before execution
 * due to:
 * - Undersized thread pools (e.g., single-threaded executor with many tasks)
 * - Long-running tasks blocking all available threads
 * - Unfair thread scheduling causing some tasks to wait indefinitely
 * - Queue ordering causing priority inversion
 *
 * <p>The detector tracks task submission and execution times, then reports
 * tasks that waited beyond acceptable thresholds before starting execution.
 *
 * <p>Usage:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectThreadStarvation = true)
 * void testStarvation() {
 *     ExecutorService executor = Executors.newFixedThreadPool(2);
 *     AsyncTestContext.threadStarvationDetector()
 *         .registerExecutor(executor, "worker-pool", 2);
 *
 *     for (int i = 0; i < 100; i++) {
 *         long submitTime = System.nanoTime();
 *         executor.submit(() -> {
 *             AsyncTestContext.threadStarvationDetector()
 *                 .recordTaskStart("worker-pool", submitTime);
 *             try {
 *                 doWork();
 *             } finally {
 *                 AsyncTestContext.threadStarvationDetector()
 *                     .recordTaskEnd("worker-pool");
 *             }
 *         });
 *     }
 * }
 * }</pre>
 */
public class ThreadStarvationDetector {

    private static class ExecutorState {
        final String name;
        final AtomicInteger submittedTasks = new AtomicInteger(0);
        final AtomicInteger startedTasks = new AtomicInteger(0);
        final AtomicInteger completedTasks = new AtomicInteger(0);
        final AtomicLong totalWaitTime = new AtomicLong(0);
        final AtomicLong maxWaitTime = new AtomicLong(0);
        volatile int peakQueueDepth = 0;
        final AtomicInteger currentQueueDepth = new AtomicInteger(0);

        ExecutorState(String name) {
            this.name = name;
        }
    }

    private static class TaskEvent {
        final String executorName;
        final long waitTime;
        final long executionTime;
        final String threadName;

        TaskEvent(String executorName, long submitTime, long startTime, long endTime) {
            this.executorName = executorName;
            this.waitTime = startTime - submitTime;
            this.executionTime = endTime - startTime;
            this.threadName = Thread.currentThread().getName();
        }
    }

    private final Map<Integer, ExecutorState> trackedExecutors = new ConcurrentHashMap<>();
    private final List<TaskEvent> starvationEvents = new ArrayList<>();
    private volatile boolean enabled = true;
    private volatile long starvationThresholdMs = 1000; // 1 second default

    /**
     * Register an executor for starvation monitoring.
     *
     * @param executor the executor
     * @param name a descriptive name
     * @param poolSize the number of threads in the pool
     */
    public void registerExecutor(ExecutorService executor, String name, int poolSize) {
        if (!enabled || executor == null) return;

        trackedExecutors.put(System.identityHashCode(executor),
            new ExecutorState(name));
    }

    /**
     * Record a task being submitted to an executor.
     *
     * @param executor the executor
     * @return the submission timestamp in nanoseconds
     */
    public long recordTaskSubmission(ExecutorService executor) {
        if (!enabled || executor == null) return 0;

        ExecutorState state = trackedExecutors.get(System.identityHashCode(executor));
        if (state != null) {
            state.submittedTasks.incrementAndGet();
            int depth = state.currentQueueDepth.incrementAndGet();
            if (depth > state.peakQueueDepth) {
                state.peakQueueDepth = depth;
            }
        }
        return System.nanoTime();
    }

    /**
     * Record a task starting execution.
     *
     * @param executorName the executor name
     * @param submitTimeNanos the submission time (from recordTaskSubmission)
     */
    public void recordTaskStart(String executorName, long submitTimeNanos) {
        if (!enabled) return;

        long startTime = System.nanoTime();
        long waitTimeNs = startTime - submitTimeNanos;
        long waitTimeMs = TimeUnit.NANOSECONDS.toMillis(waitTimeNs);

        // Find the executor state by name
        for (ExecutorState state : trackedExecutors.values()) {
            if (state.name.equals(executorName)) {
                state.startedTasks.incrementAndGet();
                state.currentQueueDepth.updateAndGet(v -> Math.max(0, v - 1));
                state.totalWaitTime.addAndGet(waitTimeNs);
                long maxWait = state.maxWaitTime.get();
                while (waitTimeNs > maxWait) {
                    if (state.maxWaitTime.compareAndSet(maxWait, waitTimeNs)) {
                        break;
                    }
                    maxWait = state.maxWaitTime.get();
                }

                // Check for starvation
                if (waitTimeMs >= starvationThresholdMs) {
                    synchronized (starvationEvents) {
                        starvationEvents.add(new TaskEvent(
                            executorName,
                            submitTimeNanos,
                            startTime,
                            0
                        ));
                    }
                }
                break;
            }
        }
    }

    /**
     * Record a task completing execution.
     *
     * @param executorName the executor name
     */
    public void recordTaskEnd(String executorName) {
        if (!enabled) return;

        for (ExecutorState state : trackedExecutors.values()) {
            if (state.name.equals(executorName)) {
                state.completedTasks.incrementAndGet();
                // Estimate execution time from last event (simplified)
                break;
            }
        }
    }

    /**
     * Analyze thread starvation patterns.
     *
     * @return analysis report
     */
    public ThreadStarvationReport analyze() {
        if (!enabled) {
            return new ThreadStarvationReport(List.of(), 0, 0, 0);
        }

        List<StarvationEventSnapshot> snapshots;
        synchronized (starvationEvents) {
            snapshots = starvationEvents.stream()
                .map(e -> new StarvationEventSnapshot(
                    e.executorName,
                    TimeUnit.NANOSECONDS.toMillis(e.waitTime),
                    TimeUnit.NANOSECONDS.toMillis(e.executionTime),
                    e.threadName
                ))
                .toList();
        }

        int totalStarved = snapshots.size();
        int totalTracked = trackedExecutors.values().stream()
            .mapToInt(s -> s.submittedTasks.get())
            .sum();
        int maxWaitTime = trackedExecutors.values().stream()
            .mapToInt(s -> (int) TimeUnit.NANOSECONDS.toMillis(s.maxWaitTime.get()))
            .max()
            .orElse(0);

        return new ThreadStarvationReport(
            snapshots,
            totalStarved,
            totalTracked,
            maxWaitTime
        );
    }

    /**
     * Clear all tracked data.
     */
    public void clear() {
        trackedExecutors.clear();
        synchronized (starvationEvents) {
            starvationEvents.clear();
        }
    }

    public void disable() {
        this.enabled = false;
    }

    /**
     * Set the starvation threshold in milliseconds.
     */
    public void setStarvationThresholdMs(long thresholdMs) {
        this.starvationThresholdMs = thresholdMs;
    }

    /**
     * Immutable snapshot of a starvation event.
     */
    public static class StarvationEventSnapshot {
        public final String executorName;
        public final long waitTimeMs;
        public final long executionTimeMs;
        public final String threadName;

        StarvationEventSnapshot(String executorName, long waitTimeMs,
                               long executionTimeMs, String threadName) {
            this.executorName = executorName;
            this.waitTimeMs = waitTimeMs;
            this.executionTimeMs = executionTimeMs;
            this.threadName = threadName;
        }
    }

    /**
     * Report of thread starvation analysis.
     */
    public static class ThreadStarvationReport {
        private final List<StarvationEventSnapshot> events;
        private final int totalStarved;
        private final int totalTracked;
        private final int maxWaitTimeMs;

        ThreadStarvationReport(List<StarvationEventSnapshot> events, int totalStarved,
                              int totalTracked, int maxWaitTimeMs) {
            this.events = events;
            this.totalStarved = totalStarved;
            this.totalTracked = totalTracked;
            this.maxWaitTimeMs = maxWaitTimeMs;
        }

        public boolean hasIssues() {
            return !events.isEmpty();
        }

        public List<StarvationEventSnapshot> getEvents() {
            return List.copyOf(events);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("ThreadStarvationReport:\n");
            sb.append("  Total tasks tracked: ").append(totalTracked).append("\n");
            sb.append("  Starved tasks: ").append(totalStarved).append("\n");
            sb.append("  Max wait time: ").append(maxWaitTimeMs).append("ms\n");

            if (events.isEmpty()) {
                sb.append("  Status: No thread starvation detected ✓\n");
            } else {
                sb.append("  THREAD STARVATION DETECTED:\n");
                for (int i = 0; i < Math.min(10, events.size()); i++) {
                    StarvationEventSnapshot event = events.get(i);
                    sb.append("  [").append(i + 1).append("] ").append(event.executorName).append("\n");
                    sb.append("      Wait time: ").append(event.waitTimeMs).append("ms\n");
                    sb.append("      Thread: ").append(event.threadName).append("\n");
                    sb.append("      Why: The thread pool is saturated — tasks queue faster than workers finish them.\n");
                    sb.append("           Persistent starvation means time-sensitive tasks (health-checks, user requests, timeouts)\n");
                    sb.append("           can be delayed indefinitely, causing cascading failures across dependent services.\n");
                    sb.append("      Fix:\n");
                    sb.append("        - Increase pool size: Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2)\n");
                    sb.append("        - Reduce per-task duration: move I/O-bound work to a dedicated I/O pool; use async I/O\n");
                    sb.append("        - Use a work-stealing pool: Executors.newWorkStealingPool() balances load across idle threads\n");
                    sb.append("        - Apply backpressure: reject or throttle submissions when the queue exceeds a threshold\n");
                }
                if (events.size() > 10) {
                    sb.append("  ... and ").append(events.size() - 10).append(" more events\n");
                }
            }
            return sb.toString();
        }
    }
}
