package com.github.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects thread pool deadlock scenarios.
 *
 * <p>Thread pool deadlocks occur when tasks submitted to a pool attempt to submit
 * more tasks to the same pool, and all threads become blocked waiting for the
 * submitted tasks to complete. This is a common mistake with fixed-size thread pools.
 *
 * <p><strong>Common scenario:</strong>
 * <pre>{@code
 * ExecutorService pool = Executors.newFixedThreadPool(2);
 *
 * // Task 1: submits another task and waits for it
 * pool.submit(() -> {
 *     Future<?> f = pool.submit(() -> { }); // Deadlock!
 *     f.get(); // Waits forever - no free threads
 * });
 * }</pre>
 *
 * <p><strong>Usage:</strong>
 * <pre>{@code
 * @AsyncTest(threads = 4, detectThreadPoolDeadlocks = true)
 * void testThreadPool() {
 *     ExecutorService pool = Executors.newFixedThreadPool(4);
 *
 *     AsyncTestContext.threadPoolDeadlockDetector()
 *         .registerPool(pool, "my-pool");
 *
 *     pool.submit(() -> {
 *         // Detector tracks nested submissions
 *         AsyncTestContext.threadPoolDeadlockDetector()
 *             .recordNestedSubmission(pool, "my-pool");
 *     });
 *
 *     ThreadPoolDeadlockReport report = AsyncTestContext
 *         .threadPoolDeadlockDetector().analyze();
 *     if (report.hasDeadlockRisk()) {
 *         // Handle potential deadlock
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Report includes:</strong>
 * <ul>
 *   <li>Pool name and size</li>
 *   <li>Number of nested submissions detected</li>
 *   <li>Stack traces showing where nested submissions occurred</li>
 *   <li>Recommendations to avoid deadlock</li>
 * </ul>
 *
 * @since 1.2.0
 */
public class ThreadPoolDeadlockDetector {

    private static class PoolState {
        final ExecutorService pool;
        final String name;
        final int poolSize;
        final AtomicInteger activeTaskCount = new AtomicInteger(0);
        final AtomicInteger nestedSubmissionCount = new AtomicInteger(0);
        final List<NestedSubmissionEvent> nestedSubmissions = Collections.synchronizedList(new ArrayList<>());

        PoolState(ExecutorService pool, String name, int poolSize) {
            this.pool = pool;
            this.name = name;
            this.poolSize = poolSize;
        }
    }

    private static class NestedSubmissionEvent {
        final long timestamp = System.nanoTime();
        final String poolName;
        final StackTraceElement[] stackTrace;
        final int activeTasksAtTime;

        NestedSubmissionEvent(String poolName, int activeTasks) {
            this.poolName = poolName;
            this.activeTasksAtTime = activeTasks;
            this.stackTrace = Thread.currentThread().getStackTrace();
        }
    }

    private final Map<Integer, PoolState> registeredPools = new ConcurrentHashMap<>();
    private final AtomicInteger deadlockRiskCount = new AtomicInteger(0);
    private volatile boolean enabled = true;

    /**
     * Register an ExecutorService for deadlock monitoring.
     *
     * @param pool the executor service to monitor
     * @param name a descriptive name for reporting
     */
    public void registerPool(ExecutorService pool, String name) {
        if (!enabled || pool == null) {
            return;
        }

        int identity = System.identityHashCode(pool);
        int poolSize = estimatePoolSize(pool);
        registeredPools.put(identity, new PoolState(pool, name, poolSize));
    }

    /**
     * Record a nested submission to a registered pool.
     *
     * <p>Call this when a task submits another task to the same pool.
     *
     * @param pool the pool being submitted to
     * @param name the pool name used in registration
     */
    public void recordNestedSubmission(ExecutorService pool, String name) {
        if (!enabled || pool == null) {
            return;
        }

        int identity = System.identityHashCode(pool);
        PoolState state = registeredPools.get(identity);
        if (state != null) {
            int activeTasks = state.activeTaskCount.incrementAndGet();
            state.nestedSubmissionCount.incrementAndGet();

            NestedSubmissionEvent event = new NestedSubmissionEvent(name, activeTasks);
            state.nestedSubmissions.add(event);

            // Check for deadlock risk: active tasks >= pool size
            if (activeTasks >= state.poolSize) {
                deadlockRiskCount.incrementAndGet();
            }
        }
    }

    /**
     * Record that a task has completed.
     *
     * @param pool the pool the task was submitted to
     */
    public void recordTaskCompleted(ExecutorService pool) {
        if (!enabled || pool == null) {
            return;
        }

        int identity = System.identityHashCode(pool);
        PoolState state = registeredPools.get(identity);
        if (state != null) {
            state.activeTaskCount.decrementAndGet();
        }
    }

    /**
     * Analyze for thread pool deadlock risks.
     *
     * @return report containing deadlock risk analysis
     */
    public ThreadPoolDeadlockReport analyze() {
        if (!enabled) {
            return new ThreadPoolDeadlockReport(Collections.emptyList(), 0);
        }

        List<PoolDeadlockRisk> risks = new ArrayList<>();
        for (PoolState state : registeredPools.values()) {
            if (state.nestedSubmissionCount.get() > 0) {
                risks.add(new PoolDeadlockRisk(
                    state.name,
                    state.poolSize,
                    state.nestedSubmissionCount.get(),
                    state.activeTaskCount.get(),
                    new ArrayList<>(state.nestedSubmissions)
                ));
            }
        }

        return new ThreadPoolDeadlockReport(risks, deadlockRiskCount.get());
    }

    /**
     * Check if there are any deadlock risks.
     *
     * @return true if nested submissions were detected
     */
    public boolean hasDeadlockRisk() {
        return enabled && deadlockRiskCount.get() > 0;
    }

    /**
     * Get the number of pools with deadlock risks.
     *
     * @return count of risky pools
     */
    public int getDeadlockRiskCount() {
        return deadlockRiskCount.get();
    }

    /**
     * Enable or disable monitoring.
     *
     * @param enabled true to enable, false to disable
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Clear all registered pools and events.
     */
    public void clear() {
        registeredPools.clear();
        deadlockRiskCount.set(0);
    }

    /**
     * Estimate the pool size using reflection if possible.
     */
    private int estimatePoolSize(ExecutorService pool) {
        try {
            // Try to get pool size via reflection for common pool types
            if (pool instanceof java.util.concurrent.ThreadPoolExecutor) {
                return ((java.util.concurrent.ThreadPoolExecutor) pool).getCorePoolSize();
            }
        } catch (Exception e) {
            // Ignore and return default
        }
        // Default assumption: small fixed pool
        return 4;
    }

    /**
     * Report of thread pool deadlock analysis.
     */
    public static class ThreadPoolDeadlockReport {
        private final List<PoolDeadlockRisk> risks;
        private final int totalDeadlockRisks;

        ThreadPoolDeadlockReport(List<PoolDeadlockRisk> risks, int totalDeadlockRisks) {
            this.risks = risks;
            this.totalDeadlockRisks = totalDeadlockRisks;
        }

        /**
         * @return list of pool-specific deadlock risks
         */
        public List<PoolDeadlockRisk> getRisks() {
            return risks;
        }

        /**
         * @return total number of deadlock risk scenarios detected
         */
        public int getTotalDeadlockRisks() {
            return totalDeadlockRisks;
        }

        /**
         * @return true if any deadlock risks were detected
         */
        public boolean hasDeadlockRisk() {
            return !risks.isEmpty();
        }

        @Override
        public String toString() {
            if (risks.isEmpty()) {
                return "ThreadPoolDeadlockReport: No deadlock risks detected - thread pool usage appears safe";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("ThreadPoolDeadlockReport: ")
              .append(risks.size())
              .append(" pool(s) with potential deadlock scenarios\n");

            for (int i = 0; i < risks.size(); i++) {
                PoolDeadlockRisk risk = risks.get(i);
                sb.append("\n  [").append(i + 1).append("] Pool: ").append(risk.poolName)
                  .append("\n      Pool size: ").append(risk.poolSize)
                  .append("\n      Nested submissions: ").append(risk.nestedSubmissionCount)
                  .append("\n      Peak active tasks: ").append(risk.peakActiveTasks);

                if (risk.peakActiveTasks >= risk.poolSize) {
                    sb.append(" ⚠️  DEADLOCK RISK - active tasks reached pool capacity!");
                }

                // Show up to 3 nested submission stack traces
                List<NestedSubmissionSnapshot> snapshots = risk.getNestedSubmissions();
                for (int j = 0; j < Math.min(3, snapshots.size()); j++) {
                    NestedSubmissionSnapshot snapshot = snapshots.get(j);
                    sb.append("\n      Nested submission #").append(j + 1)
                      .append(" (active tasks: ").append(snapshot.activeTasksAtTime).append("):");
                    if (snapshot.stackTrace != null && snapshot.stackTrace.length > 3) {
                        for (int k = 3; k < Math.min(6, snapshot.stackTrace.length); k++) {
                            sb.append("\n        at ").append(snapshot.stackTrace[k]);
                        }
                    }
                }
            }

            sb.append("\n\n  Recommendations:");
            sb.append("\n    - Avoid submitting tasks to the same pool from within pool tasks");
            sb.append("\n    - Use a separate executor for nested task submissions");
            sb.append("\n    - Consider using a cached thread pool for nested submissions");
            sb.append("\n    - Use CompletableFuture.supplyAsync() with a different executor");
            sb.append("\n    - Increase pool size if nested submissions are unavoidable");

            return sb.toString();
        }
    }

    /**
     * Deadlock risk for a specific pool.
     */
    public static class PoolDeadlockRisk {
        private final String poolName;
        private final int poolSize;
        private final int nestedSubmissionCount;
        private final int peakActiveTasks;
        private final List<NestedSubmissionSnapshot> nestedSubmissions;

        PoolDeadlockRisk(String poolName, int poolSize, int nestedSubmissionCount,
                         int peakActiveTasks, List<NestedSubmissionEvent> events) {
            this.poolName = poolName;
            this.poolSize = poolSize;
            this.nestedSubmissionCount = nestedSubmissionCount;
            this.peakActiveTasks = peakActiveTasks;
            this.nestedSubmissions = new ArrayList<>();
            for (NestedSubmissionEvent event : events) {
                this.nestedSubmissions.add(new NestedSubmissionSnapshot(
                    event.poolName, event.activeTasksAtTime, event.stackTrace));
            }
        }

        /**
         * @return pool name
         */
        public String getPoolName() {
            return poolName;
        }

        /**
         * @return pool size
         */
        public int getPoolSize() {
            return poolSize;
        }

        /**
         * @return number of nested submissions
         */
        public int getNestedSubmissionCount() {
            return nestedSubmissionCount;
        }

        /**
         * @return peak number of active tasks
         */
        public int getPeakActiveTasks() {
            return peakActiveTasks;
        }

        /**
         * @return list of nested submission snapshots
         */
        public List<NestedSubmissionSnapshot> getNestedSubmissions() {
            return nestedSubmissions;
        }
    }

    /**
     * Snapshot of a nested submission event.
     */
    public static class NestedSubmissionSnapshot {
        private final String poolName;
        private final int activeTasksAtTime;
        private final StackTraceElement[] stackTrace;

        NestedSubmissionSnapshot(String poolName, int activeTasks, StackTraceElement[] stackTrace) {
            this.poolName = poolName;
            this.activeTasksAtTime = activeTasks;
            this.stackTrace = stackTrace;
        }

        /**
         * @return pool name
         */
        public String getPoolName() {
            return poolName;
        }

        /**
         * @return number of active tasks when submission occurred
         */
        public int getActiveTasksAtTime() {
            return activeTasksAtTime;
        }

        /**
         * @return stack trace at submission point
         */
        public StackTraceElement[] getStackTrace() {
            return stackTrace;
        }
    }
}
