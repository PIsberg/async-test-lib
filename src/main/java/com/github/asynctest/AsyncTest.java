package com.github.asynctest;

import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an asynchronous stress test.
 * The test will be executed concurrently across multiple threads
 * using a CyclicBarrier to maximize the chance of race conditions.
 * 
 * Supports detection of:
 * - Deadlocks (with thread dump analysis)
 * - Visibility issues (missing volatile keywords)
 * - Livelocks and thread starvation
 * - Virtual thread pinning issues (Java 21+)
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@TestTemplate
@ExtendWith(com.github.asynctest.extension.AsyncTestExtension.class)
public @interface AsyncTest {

    /**
     * Number of threads to run concurrently per invocation.
     * Each thread will execute the test method once per invocation round.
     */
    int threads() default 10;

    /**
     * Number of times the entire concurrent execution is repeated.
     */
    int invocations() default 100;

    /**
     * Whether to use Virtual Threads (Project Loom) instead of standard platform threads.
     * Requires Java 21+.
     */
    boolean useVirtualThreads() default true;

    /**
     * Maximum time to wait for the entire test (all threads and invocations) to complete.
     * If exceeded, a deadlock is assumed and a JVM Thread dump will be triggered.
     * Default is 5000ms.
     */
    long timeoutMs() default 5000;

    /**
     * Enable deadlock detection with detailed lock analysis.
     * When test times out, provides information about which threads hold which locks.
     */
    boolean detectDeadlocks() default true;

    /**
     * Enable visibility monitoring (stale memory detection).
     * Detects missing volatile keywords and insufficient synchronization.
     * This adds overhead, so only enable when testing code with potential visibility issues.
     */
    boolean detectVisibility() default false;

    /**
     * Enable livelock and starvation detection.
     * Monitors for threads that change state rapidly without making progress,
     * or threads that never get CPU time.
     */
    boolean detectLivelocks() default false;

    /**
     * Virtual thread stress test mode. When enabled, uses aggressive thread counts
     * to detect thread-pinning issues (e.g., synchronized blocks pinning virtual threads).
     * Only applicable when useVirtualThreads=true.
     * 
     * Values:
     * - "OFF" (default): normal testing
     * - "LOW": 100 threads
     * - "MEDIUM": 1,000 threads
     * - "HIGH": 10,000 threads
     * - "EXTREME": 100,000+ threads (may require heap size adjustment)
     */
    String virtualThreadStressMode() default "OFF";

    /**
     * Enable ALL detectors in one shot.
     * When {@code true}, every individual {@code detect*} / {@code validate*} / {@code monitor*}
     * flag is treated as enabled, regardless of its own default value.
     * Individual flags can still be set to {@code false} to opt out of specific detectors.
     * <p>Example: {@code @AsyncTest(detectAll = true)} — no further flags needed.
     */
    boolean detectAll() default false;

    /**
     * Specific detectors to exclude when {@code detectAll = true}.
     * Use {@link DetectorType} to specify which detectors to skip.
     * <p>Example: {@code @AsyncTest(detectAll = true, excludes = {DetectorType.BUSY_WAITING})}
     */
    DetectorType[] excludes() default {};

    // ============= Phase 2: Advanced Detectors =============

    /**
     * Enable false sharing detection.
     * Detects when multiple threads access adjacent memory locations in the same cache line,
     * causing excessive cache coherency traffic.
     */
    boolean detectFalseSharing() default false;

    /**
     * Enable wait/notify issue detection.
     * Detects spurious wakeups, lost notifications, and improper wait/notify coordination.
     */
    boolean detectWakeupIssues() default false;

    /**
     * Enable constructor safety validation.
     * Verifies objects are fully constructed before being shared across threads.
     */
    boolean validateConstructorSafety() default false;

    /**
     * Enable ABA problem detection.
     * Detects ABA scenarios in atomic operations and CAS loops that can cause data corruption.
     */
    boolean detectABAProblem() default false;

    /**
     * Enable lock order validation.
     * Detects inconsistent lock orderings across threads that can cause deadlocks.
     */
    boolean validateLockOrder() default false;

    /**
     * Enable synchronizer monitoring (barriers, phasers, latches).
     * Detects synchronization issues like incomplete barrier advances.
     */
    boolean monitorSynchronizers() default false;

    /**
     * Enable thread pool health monitoring.
     * Detects queue saturation, task rejection, worker starvation.
     */
    boolean monitorThreadPool() default false;

    /**
     * Enable memory ordering violation detection.
     * Detects compiler/CPU reordering that causes incorrect synchronization.
     */
    boolean detectMemoryOrderingViolations() default false;

    /**
     * Enable async pipeline monitoring.
     * Detects signal loss, missing events, and processing failures in event pipelines.
     */
    boolean monitorAsyncPipeline() default false;

    /**
     * Enable read-write lock fairness monitoring.
     * Detects writer starvation and unfair lock distributions.
     */
    boolean monitorReadWriteLockFairness() default false;

    /**
     * Enable race condition detection.
     * Detects concurrent field access patterns and unsynchronized mutations.
     */
    boolean detectRaceConditions() default false;

    /**
     * Enable ThreadLocal leak detection.
     * Detects ThreadLocal values not cleaned up, causing memory leaks in thread pools.
     */
    boolean detectThreadLocalLeaks() default false;

    /**
     * Enable busy-waiting detection.
     * Detects CPU-intensive spin loops and polling patterns without proper synchronization.
     */
    boolean detectBusyWaiting() default false;

    /**
     * Enable atomicity violation detection.
     * Detects check-then-act patterns and compound operations that aren't properly synchronized.
     */
    boolean detectAtomicityViolations() default false;

    /**
     * Enable interrupt handling monitoring.
     * Detects caught but ignored InterruptException and improper thread cancellation handling.
     */
    boolean detectInterruptMishandling() default false;

    // ============= Phase 2: Additional Monitors =============

    /**
     * Enable semaphore misuse monitoring.
     * Detects permit leaks, over-release, and unreleased permits at completion.
     */
    boolean monitorSemaphore() default false;

    /**
     * Enable CompletableFuture exception monitoring.
     * Detects unhandled exceptions, missing handlers, and swallowed exceptions in async chains.
     */
    boolean detectCompletableFutureExceptions() default false;

    /**
     * Enable concurrent modification detection.
     * Detects collection modifications during iteration and concurrent mutations.
     */
    boolean detectConcurrentModifications() default false;

    /**
     * Enable lock leak detection.
     * Detects locks acquired but never released and excessive hold times.
     */
    boolean detectLockLeaks() default false;

    /**
     * Enable shared Random detection.
     * Detects concurrent access to non-thread-safe Random instances.
     */
    boolean detectSharedRandom() default false;

    /**
     * Enable BlockingQueue misuse detection.
     * Detects silent failures, queue saturation, and producer/consumer imbalance.
     */
    boolean detectBlockingQueueIssues() default false;

    /**
     * Enable Condition variable misuse detection.
     * Detects lost signals, stuck waiters, and missing signals.
     */
    boolean detectConditionVariableIssues() default false;

    /**
     * Enable SimpleDateFormat misuse detection.
     * Detects concurrent access to non-thread-safe date formatters.
     */
    boolean detectSimpleDateFormatIssues() default false;

    /**
     * Enable parallel stream misuse detection.
     * Detects stateful lambdas, non-thread-safe collectors, and side effects.
     */
    boolean detectParallelStreamIssues() default false;

    /**
     * Enable resource leak detection.
     * Detects AutoCloseable resources not properly closed.
     */
    boolean detectResourceLeaks() default false;
}

