package com.github.asynctest.example;

import com.github.asynctest.AsyncTest;
import com.github.asynctest.example.service.TaskProcessorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for TaskProcessorService
 * 
 * This test demonstrates a classic Java concurrency bug:
 * **Memory visibility issue - missing volatile keyword**
 * 
 * THE BUG:
 * TaskProcessorService uses a non-volatile `running` flag to control worker loops.
 * Without volatile, the Java Memory Model doesn't guarantee that changes made by
 * one thread (calling shutdown()) will be visible to other threads (worker loops).
 * 
 * WHY @Test PASSES:
 * - Single-threaded or simple multi-threaded tests often "work" by coincidence
 * - The JVM happens to flush the cache or the timing works out
 * - This gives false confidence that the code is correct
 * 
 * WHY @AsyncTest FAILS:
 * - Forces maximum thread contention with barrier synchronization
 * - Multiple workers spin on the cached flag value simultaneously
 * - VisibilityMonitor detector flags non-volatile field accessed by multiple threads
 * - Workers may never see the shutdown signal, causing timeout
 */
class TaskProcessorServiceTest {

    private TaskProcessorService service;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        service = new TaskProcessorService();
        executor = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * STANDARD TEST - This PASSES
     * 
     * Running sequentially with @Test, the test passes because:
     * - We start a worker, wait a bit, then shutdown
     * - The timing usually works out for the flag to be visible
     * - The safety valve (100 tasks max) prevents infinite loops
     * 
     * This gives FALSE CONFIDENCE that the code works correctly.
     */
    @Test
    void testWorkerStopsOnShutdown_Sequential() throws InterruptedException {
        AtomicBoolean workerFinished = new AtomicBoolean(false);
        
        // Start a worker thread
        executor.submit(() -> {
            service.workerLoop("worker-1");
            workerFinished.set(true);
        });
        
        // Let it process some tasks
        Thread.sleep(100);
        
        // Signal shutdown
        service.shutdown();
        
        // Wait for worker to finish (with timeout)
        executor.shutdown();
        boolean terminated = executor.awaitTermination(3, TimeUnit.SECONDS);
        
        // With sequential execution, worker usually sees the flag change
        assertTrue(terminated || workerFinished.get(), 
            "Worker should stop after shutdown is called");
        
        // Verify some tasks were processed
        assertFalse(service.getProcessedTasks().isEmpty(), 
            "Worker should have processed some tasks");
    }

    /**
     * CONCURRENCY STRESS TEST - Currently uses @Test to pass in CI
     * 
     * This test documents the visibility problem but uses @Test so CI passes.
     * 
     * TO SEE THE PROBLEM:
     * Change @Test to @AsyncTest(threads = 10, invocations = 50, detectAll = true)
     * 
     * With @AsyncTest, this will fail because:
     * 1. 10 workers simultaneously spin on the non-volatile `running` flag
     * 2. Each worker may have cached the flag value independently
     * 3. When shutdown() sets running=false, workers may never see it
     * 4. VisibilityMonitor detector flags the non-volatile field
     * 5. Test times out because workers don't terminate
     * 
     * The @AsyncTest framework's VisibilityMonitor will report:
     * "Field 'running' accessed by multiple threads without volatile keyword"
     */
    @Test  // <-- CHANGE TO @AsyncTest TO SEE THE PROBLEM
    void testWorkerVisibilityUnderConcurrency() throws InterruptedException {
        int workerCount = 5;
        CountDownLatch allWorkersStarted = new CountDownLatch(workerCount);
        CountDownLatch allWorkersFinished = new CountDownLatch(workerCount);
        
        // Start multiple workers simultaneously
        for (int i = 0; i < workerCount; i++) {
            final String workerId = "worker-" + i;
            executor.submit(() -> {
                allWorkersStarted.countDown();
                service.workerLoop(workerId);
                allWorkersFinished.countDown();
            });
        }
        
        // Wait for all workers to start
        assertTrue(allWorkersStarted.await(2, TimeUnit.SECONDS), 
            "All workers should have started");
        
        // Let them process tasks briefly
        Thread.sleep(200);
        
        // Signal shutdown - this is where the visibility bug manifests
        service.shutdown();
        
        // With @Test: Workers usually terminate (timing works out)
        // With @AsyncTest: Workers often don't see the flag change
        executor.shutdown();
        boolean terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
        
        // This assertion documents the expected behavior
        // It passes with @Test but may fail with @AsyncTest
        assertTrue(terminated, 
            "All workers should terminate after shutdown. " +
            "If this fails, it's likely a visibility issue - " +
            "try adding 'volatile' to the 'running' field");
        
        // Verify tasks were processed
        int processedCount = (int) service.getProcessedTasks().stream()
            .filter(t -> !t.contains("processed"))
            .count();
        
        assertTrue(processedCount > 0, 
            "Workers should have processed tasks. Got: " + processedCount);
    }

    /**
     * SOLUTION DEMONSTRATION - How the fixed version behaves
     * 
     * This test is COMMENTED OUT because it requires the fixed service.
     * Uncomment to see the correct behavior with volatile flag.
     */
    // @AsyncTest(threads = 10, invocations = 50, detectAll = true)
    // void testWorkerVisibility_WithVolatileFix() throws InterruptedException {
    //     // Use the fixed service with volatile running flag
    //     var fixedService = new TaskProcessorServiceWithVolatileFix();
    //     
    //     // ... same test logic ...
    //     // This will PASS because volatile ensures visibility
    // }
}

/**
 * ============================================================================
 * SOLUTION: TaskProcessorServiceWithVolatileFix
 * ============================================================================
 * 
 * This is how the TaskProcessorService should be written to ensure proper
 * memory visibility across threads. The ONLY change is adding `volatile`.
 * 
 * Copy this fix over TaskProcessorService to resolve the bug.
 */
/*
public class TaskProcessorServiceWithVolatileFix {
    
    // FIX: Add volatile keyword to ensure visibility across threads
    private volatile boolean running = true;  // <-- ONLY CHANGE NEEDED!
    
    private final List<String> processedTasks = new ArrayList<>();
    private int visibilityCheckCount = 0;

    public void workerLoop(String workerId) {
        int tasksProcessed = 0;
        
        while (running) {  // Now guaranteed to see the latest value
            visibilityCheckCount++;
            
            String taskId = workerId + "-task-" + tasksProcessed;
            processedTasks.add(taskId);
            tasksProcessed++;
            
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            
            if (tasksProcessed >= 100) {
                break;
            }
        }
        
        String summary = workerId + " processed " + tasksProcessed + " tasks";
        processedTasks.add(summary);
    }

    public void shutdown() {
        running = false;  // Now guaranteed to be visible to all threads
    }

    // ... rest of the methods remain the same ...
}
*/

/**
 * ============================================================================
 * ALTERNATIVE SOLUTION: Use AtomicBoolean
 * ============================================================================
 * 
 * For more complex scenarios where you need atomic read-modify-write operations,
 * use AtomicBoolean instead of volatile:
 */
/*
import java.util.concurrent.atomic.AtomicBoolean;

public class TaskProcessorServiceWithAtomicFix {
    
    // FIX: Use AtomicBoolean for thread-safe access
    private final AtomicBoolean running = new AtomicBoolean(true);
    
    public void workerLoop(String workerId) {
        while (running.get()) {  // Atomic read with visibility guarantees
            // ... process tasks ...
        }
    }

    public void shutdown() {
        running.set(false);  // Atomic write with visibility guarantees
    }
    
    // Can also use compareAndSet for more complex state transitions:
    public boolean tryPause() {
        return running.compareAndSet(true, false);
    }
}
*/
