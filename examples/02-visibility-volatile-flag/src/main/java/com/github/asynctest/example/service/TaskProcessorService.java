package com.github.asynctest.example.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Real-world task processing service that uses a flag to signal shutdown.
 * 
 * This is production-grade code that could exist in any background worker,
 * message consumer, or scheduled task processor.
 * 
 * COMMON ASYNC PROBLEM: Memory Visibility / Missing volatile Keyword
 * 
 * THE BUG: The `running` flag is not declared as `volatile`.
 * 
 * In Java, without the volatile keyword:
 * - Each thread may cache the variable in its own CPU cache/register
 * - Changes made by one thread are NOT guaranteed to be visible to other threads
 * - The JIT compiler may optimize reads/writes, assuming the value doesn't change
 * 
 * In production, this manifests as:
 * - Worker threads don't stop when shutdown() is called
 * - Graceful shutdown hangs
 * - Resources (connections, file handles) are never released
 * - Application doesn't terminate cleanly
 * 
 * WHY @Test PASSES:
 * - Single-threaded execution means the same thread reads/writes the flag
 * - No CPU cache coherence issues on a single core
 * - The JIT compiler doesn't optimize away the read in simple loops
 * 
 * WHY @AsyncTest FAILS:
 * - Multiple worker threads cache the `running` flag independently
 * - When main thread sets running=false, workers may never see it
 * - VisibilityMonitor detector flags the non-volatile field being accessed
 *   by multiple threads
 * - Test times out because workers never terminate
 * 
 * ROOT CAUSE:
 * private boolean running = true;  // BUG: Not volatile!
 * 
 * SOLUTION:
 * private volatile boolean running = true;  // FIX: Ensures visibility across threads
 * 
 * The volatile keyword establishes a "happens-before" relationship:
 * - Writes to volatile fields are immediately flushed to main memory
 * - Reads from volatile fields always fetch from main memory
 * - Prevents the JIT/compiler from caching or reordering around the field
 */
public class TaskProcessorService {

    // BUG: This flag is NOT volatile!
    // Worker threads may cache this value and never see updates from other threads
    private boolean running = true;
    
    // Track processed tasks
    private final List<String> processedTasks = new ArrayList<>();
    
    // Track how many times workers checked the flag before seeing it change
    private int visibilityCheckCount = 0;

    /**
     * Worker thread that processes tasks until shutdown is signaled.
     * 
     * BUG: This loop reads the non-volatile `running` flag.
     * Under concurrent execution, the worker may never see the flag change
     * and will run indefinitely (or until the JVM decides to refresh the cache).
     */
    public void workerLoop(String workerId) {
        int tasksProcessed = 0;
        
        while (running) {  // BUG: May read stale cached value!
            visibilityCheckCount++;
            
            // Simulate processing a task
            String taskId = workerId + "-task-" + tasksProcessed;
            processedTasks.add(taskId);
            tasksProcessed++;
            
            // Simulate work taking some time
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            
            // Safety valve: don't process more than 100 tasks per invocation
            if (tasksProcessed >= 100) {
                break;
            }
        }
        
        // Record final count
        String summary = workerId + " processed " + tasksProcessed + " tasks";
        processedTasks.add(summary);
    }

    /**
     * Signal all workers to stop.
     * 
     * BUG: Without volatile, this write may not be visible to worker threads
     * that have already cached the `running` flag value.
     */
    public void shutdown() {
        running = false;  // BUG: May not be visible to workers!
    }

    public boolean isRunning() {
        return running;
    }

    public List<String> getProcessedTasks() {
        return List.copyOf(processedTasks);
    }

    public int getVisibilityCheckCount() {
        return visibilityCheckCount;
    }

    public void reset() {
        running = true;
        processedTasks.clear();
        visibilityCheckCount = 0;
    }
}
