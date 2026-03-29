package com.github.asynctest.diagnostics;

/**
 * Educational content explaining concurrency concepts when issues are detected.
 *
 * @since 1.3.0
 */
public final class LearningContent {

    private LearningContent() { }

    public static String getDeadlockExplanation() {
        return """
            📚 LEARNING: What is a Deadlock?

            A deadlock occurs when two or more threads are blocked forever, each waiting for
            the other to release a lock.

            Visual example:
              Thread A: holds Lock1 → waiting for Lock2
              Thread B: holds Lock2 → waiting for Lock1
                         ↑_____________↓
                         (circular wait - DEADLOCK!)

            The four conditions for deadlock (all must be present):
              1. Mutual exclusion - Only one thread can hold each lock
              2. Hold and wait - Threads hold locks while waiting for others
              3. No preemption - Locks cannot be forcibly taken
              4. Circular wait - A circular chain of threads waiting
            """;
    }

    public static String getRaceConditionExplanation() {
        return """
            📚 LEARNING: What is a Race Condition?

            A race condition occurs when multiple threads access shared data concurrently,
            and at least one thread modifies the data, without proper synchronization.

            Visual example (lost update):
              Initial balance: $100
              Thread A: reads balance ($100)
              Thread B: reads balance ($100)  ← reads same stale value!
              Thread A: adds $10, writes ($110)
              Thread B: adds $20, writes ($120) ← Thread A's update LOST!
              Expected: $130, Actual: $120
            """;
    }

    public static String getVisibilityExplanation() {
        return """
            📚 LEARNING: What is a Memory Visibility Issue?

            Memory visibility issues occur when changes made by one thread are not visible
            to other threads due to CPU caching and compiler optimizations.

            Visual example:
              Thread A (Core 1): sharedFlag = true; (stored in Core 1 cache)
              Thread B (Core 2): while (!sharedFlag) { } ← never sees the change!

            Solutions: volatile keyword, synchronized, atomic variables
            """;
    }

    public static String getFalseSharingExplanation() {
        return """
            📚 LEARNING: What is False Sharing?

            False sharing occurs when threads access different variables on the same
            cache line, causing unnecessary cache invalidation.

            Visual example:
              Cache line (64 bytes): [valueA][valueB][...padding...]
                                        ↑        ↑
                                   Thread A  Thread B
                                   writes    writes (invalidates entire line!)

            Result: Cache ping-pong between cores, severe performance degradation!
            """;
    }

    public static String getCompletableFutureLeakExplanation() {
        return """
            📚 LEARNING: What is a CompletableFuture Completion Leak?

            A CompletableFuture completion leak occurs when a CompletableFuture is created
            but never completed (neither successfully nor exceptionally).

            Visual example:
              CompletableFuture<String> future = new CompletableFuture<>();
              // ... forgot to call future.complete() or completeExceptionally()
              // Any code waiting on future.join() will wait FOREVER!

            Common causes: Exception before complete(), conditional logic skips completion
            """;
    }

    public static String getVirtualThreadPinningExplanation() {
        return """
            📚 LEARNING: What is Virtual Thread Pinning?

            Virtual thread pinning occurs when a virtual thread is blocked inside a
            synchronized block or native method, preventing it from unmounting.

            Visual example:
              Virtual Thread 1 → synchronized(lock) { } → Pinned to Carrier Thread A
              Virtual Thread 2 → synchronized(lock) { } → Pinned to Carrier Thread B
              Virtual Thread 3 → waiting for carrier → BLOCKED!

            Operations that cause pinning: synchronized blocks, native methods
            """;
    }

    public static String getThreadPoolDeadlockExplanation() {
        return """
            📚 LEARNING: What is a Thread Pool Deadlock?

            A thread pool deadlock occurs when tasks submitted to a pool try to submit
            more tasks to the SAME pool and wait for them, but all pool threads are busy.

            Visual example:
              Pool size: 2 threads
              Task 1 (Thread A): submits Task 3 → waits
              Task 2 (Thread B): submits Task 4 → waits
              Task 3 & 4: QUEUED (no free threads!) → DEADLOCK!

            Solution: Use a separate executor for nested submissions
            """;
    }

    public static String getBusyWaitingExplanation() {
        return """
            📚 LEARNING: What is Busy Waiting?

            Busy waiting occurs when a thread repeatedly checks a condition in a tight
            loop, consuming CPU cycles while waiting.

            Visual example:
              // BAD: Busy waiting - uses 100% CPU!
              while (!condition) { }

              // GOOD: Proper waiting - releases CPU
              synchronized(lock) {
                  while (!condition) { lock.wait(); }
              }

            Solutions: wait()/notify(), CountDownLatch, LockSupport.park()
            """;
    }

    public static String getAtomicityViolationExplanation() {
        return """
            📚 LEARNING: What is an Atomicity Violation?

            An atomicity violation occurs when a compound operation (read-modify-write)
            is not executed atomically, allowing interleaving from other threads.

            Visual example:
              counter++ is actually three operations:
              1. READ counter (value: 5)
              2. ADD 1 (result: 6)
              3. WRITE counter (value: 6)

              Thread A: READ(5) → ADD(6) → WRITE(6)
              Thread B:        READ(5) → ADD(6) → WRITE(6)
              Result: counter = 6 (should be 7!)

            Solutions: AtomicReference, synchronized, LongAdder
            """;
    }

    public static String getLockLeakExplanation() {
        return """
            📚 LEARNING: What is a Lock Leak?

            A lock leak occurs when a thread acquires a lock but never releases it,
            causing other threads to wait indefinitely.

            Visual example:
              lock.lock();
              try {
                  doWork();
                  // Oops! Missing lock.unlock() in finally block
              } catch (Exception e) {
                  // Exception thrown, lock never released!
              }
              // Other threads waiting for this lock are now stuck FOREVER

            Solution: Always use try-finally:
              lock.lock();
              try { doWork(); }
              finally { lock.unlock(); }
            """;
    }
}
