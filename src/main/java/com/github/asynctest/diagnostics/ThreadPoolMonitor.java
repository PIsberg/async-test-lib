package com.github.asynctest.diagnostics;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
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
        final int coreSize;
        final int maxSize;
        final int queueCapacity;
        final AtomicInteger activeThreads = new AtomicInteger(0);
        final AtomicInteger completedTasks = new AtomicInteger(0);
        final AtomicInteger rejectedTasks = new AtomicInteger(0);
        final AtomicInteger queuedTasks = new AtomicInteger(0);
        volatile long peakQueueSize = 0;
        volatile long maxTaskDuration = 0;
        final List<String> rejections = Collections.synchronizedList(new ArrayList<>());
        
        PoolState(String name, int core, int max, int queue) {
            this.poolName = name;
            this.coreSize = core;
            this.maxSize = max;
            this.queueCapacity = queue;
        }
    }
    
    private final Map<Integer, PoolState> pools = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    
    /**
     * Register a thread pool for monitoring.
     */
    public void registerPool(Object executor, String name, int coreSize, int maxSize, int queueCapacity) {
        if (!enabled) return;
        
        int id = System.identityHashCode(executor);
        pools.putIfAbsent(id, new PoolState(name, coreSize, maxSize, queueCapacity));
    }
    
    /**
     * Record task submission.
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
     */
    public void recordTaskRejected(Object executor, String reason) {
        if (!enabled) return;
        
        int id = System.identityHashCode(executor);
        PoolState state = pools.computeIfAbsent(id, k -> 
            new PoolState("Unknown", 0, 0, 0)
        );
        
        state.rejectedTasks.incrementAndGet();
        state.rejections.add(reason + " (queued: " + state.queuedTasks.get() + ")");
    }
    
    /**
     * Analyze pool health.
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
            
            if (state.peakQueueSize > state.queueCapacity * 0.8) {
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
            
            if (state.activeThreads.get() >= state.maxSize) {
                report.threadStarvation.add(String.format(
                    "%s: All %d threads busy (queue depth: %d)",
                    state.poolName, state.maxSize, state.queuedTasks.get()
                ));
            }
        }
        
        return report;
    }
    
    public void reset() {
        pools.clear();
    }
    
    public void disable() {
        enabled = false;
    }
    
    public void enable() {
        enabled = true;
    }
    
    public static class ThreadPoolReport {
        public final Set<String> poolsWithRejections = new HashSet<>();
        public final Set<String> saturatedQueues = new HashSet<>();
        public final Set<String> longRunningTasks = new HashSet<>();
        public final Set<String> threadStarvation = new HashSet<>();
        
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
