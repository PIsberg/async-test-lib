package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Monitors thread pool / executor health and issues.
 * 
 * Problems detected:
 * - Queue saturation
 * - Task rejection
 * - Worker thread starvation
 * - Queue imbalance across workers
 * - Long-running tasks blocking others
 */
public class ThreadPoolMonitor {
    
    private static class PoolState {
        final String poolName;
        final int maxSize;
        final int queueCapacity;
        final AtomicInteger activeThreads = new AtomicInteger(0);
        final AtomicInteger completedTasks = new AtomicInteger(0);
        final AtomicInteger rejectedTasks = new AtomicInteger(0);
        final AtomicInteger queuedTasks = new AtomicInteger(0);
        volatile long peakQueueSize = 0;
        volatile long maxTaskDuration = 0;
        final List<String> rejections = Collections.synchronizedList(new ArrayList<>());
        /**
         * Whether {@code maxSize} and {@code queueCapacity} came from a caller.
         *
         * <p>False for a pool this monitor invented on a rejection it was told about without a
         * prior registration. Both bounds are 0 there, which is not "a pool of zero threads with
         * a queue of zero" - it is "nobody said". The rules that compare against a bound are
         * skipped for such a pool, because 0 >= 0 made every one of them report "All 0 threads
         * busy" on a pool whose real size nothing here knows (#501).
         */
        final boolean boundsDeclared;

        PoolState(String name, int max, int queue) {
            this(name, max, queue, true);
        }

        PoolState(String name, int max, int queue, boolean boundsDeclared) {
            this.poolName = name;
            this.maxSize = max;
            this.queueCapacity = queue;
            this.boundsDeclared = boundsDeclared;
        }
    }
    
    private final Map<Integer, PoolState> pools = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    
    /**
     * Register a thread pool for monitoring.
     *
     * @param executor the executor being recorded, tracked by identity
     * @param name a label identifying the executor in the report
     * @param coreSize the configured core pool size
     * @param maxSize the configured maximum pool size
     * @param queueCapacity the configured work-queue capacity
     */
    public void registerPool(Object executor, String name, int coreSize, int maxSize, int queueCapacity) {
        if (!enabled) return;
        
        int id = System.identityHashCode(executor);
        pools.putIfAbsent(id, new PoolState(name, maxSize, queueCapacity));
    }
    
    /**
     * Record task submission.
     *
     * @param executor the executor being recorded, tracked by identity
     */
    public void recordTaskSubmitted(Object executor) {
        if (!enabled) return;
        
        int id = System.identityHashCode(executor);
        PoolState state = pools.get(id);
        if (state == null) return;
        
        state.queuedTasks.incrementAndGet();
        state.peakQueueSize = Math.max(state.peakQueueSize, state.queuedTasks.get());
    }
    
    /**
     * Record task execution start.
     *
     * @param executor the executor being recorded, tracked by identity
     */
    public void recordTaskStarted(Object executor) {
        if (!enabled) return;
        
        int id = System.identityHashCode(executor);
        PoolState state = pools.get(id);
        if (state == null) return;
        
        state.activeThreads.incrementAndGet();
        state.queuedTasks.decrementAndGet();
    }
    
    /**
     * Record task completion.
     *
     * @param executor the executor being recorded, tracked by identity
     * @param durationMs the duration in milliseconds
     */
    public void recordTaskCompleted(Object executor, long durationMs) {
        if (!enabled) return;
        
        int id = System.identityHashCode(executor);
        PoolState state = pools.get(id);
        if (state == null) return;
        
        state.activeThreads.decrementAndGet();
        state.completedTasks.incrementAndGet();
        state.maxTaskDuration = Math.max(state.maxTaskDuration, durationMs);
    }
    
    /**
     * Record task rejection.
     *
     * @param executor the executor being recorded, tracked by identity
     * @param reason why the event was recorded, shown in the report
     */
    public void recordTaskRejected(Object executor, String reason) {
        if (!enabled) return;
        
        int id = System.identityHashCode(executor);
        PoolState state = pools.computeIfAbsent(id, k -> 
            new PoolState("Unregistered pool", 0, 0, false)
        );
        
        state.rejectedTasks.incrementAndGet();
        state.rejections.add(reason + " (queued: " + state.queuedTasks.get() + ")");
    }
    
    /**
     * Analyze pool health.
     *
     * @return the findings this detector collected during the run
     */
    public ThreadPoolReport analyzePoolHealth() {
        ThreadPoolReport report = new ThreadPoolReport();
        
        for (PoolState state : pools.values()) {
            if (state.rejectedTasks.get() > 0) {
                report.poolsWithRejections.add(String.format(
                    "%s: %d tasks rejected",
                    state.poolName, state.rejectedTasks.get()
                ));
            }
            
            if (state.boundsDeclared && state.peakQueueSize > state.queueCapacity * 0.8) {
                report.saturatedQueues.add(String.format(
                    "%s: Queue near capacity (peak: %d)",
                    state.poolName, state.peakQueueSize
                ));
            }
            
            if (state.maxTaskDuration > 10000) {
                report.longRunningTasks.add(String.format(
                    "%s: Max task duration %dms (may block other tasks)",
                    state.poolName, state.maxTaskDuration
                ));
            }
            
            if (state.boundsDeclared && state.activeThreads.get() >= state.maxSize) {
                report.threadStarvation.add(String.format(
                    "%s: All %d threads busy (queue depth: %d)",
                    state.poolName, state.maxSize, state.queuedTasks.get()
                ));
            }
        }
        
        return report;
    }

    /**
     * Standardized alias for {@link #analyzePoolHealth()}.
     *
     * @return the findings this detector collected during the run
     */
    public ThreadPoolReport analyze() {
        return analyzePoolHealth();
    }
    /**
     * Clears recorded the observation so this instance can be reused for the next run.
     */
    public void reset() {
        pools.clear();
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
    
    public static class ThreadPoolReport {
        /** Pools that rejected at least one submission. */
        public final Set<String> poolsWithRejections = new HashSet<>();
        /** Work queues observed at their capacity. */
        public final Set<String> saturatedQueues = new HashSet<>();
        /** Tasks that ran past the reporting threshold. */
        public final Set<String> longRunningTasks = new HashSet<>();
        /** Pools where work waited because no worker was free. */
        public final Set<String> threadStarvation = new HashSet<>();
        
        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return !poolsWithRejections.isEmpty() || !saturatedQueues.isEmpty() || 
                   !longRunningTasks.isEmpty() || !threadStarvation.isEmpty();
        }
        
        @Override
        public String toString() {
            if (!hasIssues()) {
                return "No thread pool issues detected.";
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("THREAD POOL ISSUES DETECTED:\n");
            
            if (!poolsWithRejections.isEmpty()) {
                sb.append("\nPools with task rejections:\n");
                for (String issue : poolsWithRejections) {
                    sb.append("  - ").append(issue).append("\n");
                }
                sb.append("  Fix: Increase pool size or queue capacity\n");
            }
            
            if (!saturatedQueues.isEmpty()) {
                sb.append("\nSaturated queues:\n");
                for (String issue : saturatedQueues) {
                    sb.append("  - ").append(issue).append("\n");
                }
            }
            
            if (!longRunningTasks.isEmpty()) {
                sb.append("\nLong-running tasks:\n");
                for (String issue : longRunningTasks) {
                    sb.append("  - ").append(issue).append("\n");
                }
                sb.append("  Fix: Consider dedicated threads or async/non-blocking patterns\n");
            }
            
            if (!threadStarvation.isEmpty()) {
                sb.append("\nThread starvation:\n");
                for (String issue : threadStarvation) {
                    sb.append("  - ").append(issue).append("\n");
                }
            }
            
            return sb.toString();
        }
    }
}
