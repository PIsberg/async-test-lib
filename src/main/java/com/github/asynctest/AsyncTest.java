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
     * <p><strong>Default is {@code true}</strong> — {@code @AsyncTest} alone enables all detectors.
     * <p>Example: {@code @AsyncTest} — all detectors enabled automatically.
     * <p>Example: {@code @AsyncTest(detectAll = false, detectDeadlocks = true)} — only deadlock detection.
     */
    boolean detectAll() default true;

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
    boolean detectFalseSharing() default true;

    /**
     * Enable wait/notify issue detection.
     * Detects spurious wakeups, lost notifications, and improper wait/notify coordination.
     */
    boolean detectWakeupIssues() default true;

    /**
     * Enable constructor safety validation.
     * Verifies objects are fully constructed before being shared across threads.
     */
    boolean validateConstructorSafety() default true;

    /**
     * Enable ABA problem detection.
     * Detects ABA scenarios in atomic operations and CAS loops that can cause data corruption.
     */
    boolean detectABAProblem() default true;

    /**
     * Enable lock order validation.
     * Detects inconsistent lock orderings across threads that can cause deadlocks.
     */
    boolean validateLockOrder() default true;

    /**
     * Enable synchronizer monitoring (barriers, phasers, latches).
     * Detects synchronization issues like incomplete barrier advances.
     */
    boolean monitorSynchronizers() default true;

    /**
     * Enable thread pool health monitoring.
     * Detects queue saturation, task rejection, worker starvation.
     */
    boolean monitorThreadPool() default true;

    /**
     * Enable memory ordering violation detection.
     * Detects compiler/CPU reordering that causes incorrect synchronization.
     */
    boolean detectMemoryOrderingViolations() default true;

    /**
     * Enable async pipeline monitoring.
     * Detects signal loss, missing events, and processing failures in event pipelines.
     */
    boolean monitorAsyncPipeline() default true;

    /**
     * Enable read-write lock fairness monitoring.
     * Detects writer starvation and unfair lock distributions.
     */
    boolean monitorReadWriteLockFairness() default true;

    /**
     * Enable race condition detection.
     * Detects concurrent field access patterns and unsynchronized mutations.
     */
    boolean detectRaceConditions() default true;

    /**
     * Enable ThreadLocal leak detection.
     * Detects ThreadLocal values not cleaned up, causing memory leaks in thread pools.
     */
    boolean detectThreadLocalLeaks() default true;

    /**
     * Enable busy-waiting detection.
     * Detects CPU-intensive spin loops and polling patterns without proper synchronization.
     */
    boolean detectBusyWaiting() default true;

    /**
     * Enable atomicity violation detection.
     * Detects check-then-act patterns and compound operations that aren't properly synchronized.
     */
    boolean detectAtomicityViolations() default true;

    /**
     * Enable interrupt handling monitoring.
     * Detects caught but ignored InterruptException and improper thread cancellation handling.
     */
    boolean detectInterruptMishandling() default true;

    // ============= Phase 2: Additional Monitors =============

    /**
     * Enable semaphore misuse monitoring.
     * Detects permit leaks, over-release, and unreleased permits at completion.
     */
    boolean monitorSemaphore() default true;

    /**
     * Enable CompletableFuture exception monitoring.
     * Detects unhandled exceptions, missing handlers, and swallowed exceptions in async chains.
     */
    boolean detectCompletableFutureExceptions() default true;

    /**
     * Enable CompletableFuture completion leak monitoring.
     * Detects CompletableFutures created but never completed (completable future leaks).
     * @since 1.2.0
     */
    boolean detectCompletableFutureCompletionLeaks() default true;

    /**
     * Enable virtual thread pinning detection.
     * Detects virtual threads pinned to carrier threads by synchronized blocks or native calls.
     * Requires Java 21+ with virtual thread support.
     * @since 1.2.0
     */
    boolean detectVirtualThreadPinning() default true;

    /**
     * Enable thread pool deadlock detection.
     * Detects tasks submitting nested tasks to the same pool, which can cause deadlock.
     * @since 1.2.0
     */
    boolean detectThreadPoolDeadlocks() default true;

    /**
     * Enable concurrent modification detection.
     * Detects collection modifications during iteration and concurrent mutations.
     */
    boolean detectConcurrentModifications() default true;

    /**
     * Enable lock leak detection.
     * Detects locks acquired but never released and excessive hold times.
     */
    boolean detectLockLeaks() default true;

    /**
     * Enable shared Random detection.
     * Detects concurrent access to non-thread-safe Random instances.
     */
    boolean detectSharedRandom() default true;

    /**
     * Enable BlockingQueue misuse detection.
     * Detects silent failures, queue saturation, and producer/consumer imbalance.
     */
    boolean detectBlockingQueueIssues() default true;

    /**
     * Enable Condition variable misuse detection.
     * Detects lost signals, stuck waiters, and missing signals.
     */
    boolean detectConditionVariableIssues() default true;

    /**
     * Enable SimpleDateFormat misuse detection.
     * Detects concurrent access to non-thread-safe date formatters.
     */
    boolean detectSimpleDateFormatIssues() default true;

    /**
     * Enable parallel stream misuse detection.
     * Detects stateful lambdas, non-thread-safe collectors, and side effects.
     */
    boolean detectParallelStreamIssues() default true;

    /**
     * Enable resource leak detection.
     * Detects AutoCloseable resources not properly closed.
     */
    boolean detectResourceLeaks() default true;

    // ============= Phase 2: Additional Concurrency Detectors =============

    /**
     * Enable CountDownLatch misuse detection.
     * Detects latch timeout, missing countDown, and extra countDown calls.
     */
    boolean detectCountDownLatchIssues() default true;

    /**
     * Enable CyclicBarrier misuse detection.
     * Detects barrier timeout, broken barriers, and missing participants.
     */
    boolean detectCyclicBarrierIssues() default true;

    /**
     * Enable ReentrantLock issue detection.
     * Detects lock starvation, unfair acquisition, and lock timeouts.
     */
    boolean detectReentrantLockIssues() default true;

    /**
     * Enable volatile array issue detection.
     * Detects multi-thread access to volatile array elements (which are not volatile).
     */
    boolean detectVolatileArrayIssues() default true;

    /**
     * Enable broken double-checked locking detection.
     * Detects DCL patterns without volatile keyword.
     */
    boolean detectDoubleCheckedLocking() default true;

    /**
     * Enable wait timeout detection.
     * Detects wait() calls without timeout (potential deadlock).
     */
    boolean detectWaitTimeout() default true;

    // ============= Phase 2: Advanced Concurrency Utilities =============

    /**
     * Enable Phaser misuse detection.
     * Detects missing arrive() calls, timeouts, and termination issues.
     */
    boolean detectPhaserIssues() default true;

    /**
     * Enable StampedLock issue detection.
     * Detects unvalidated optimistic reads and stamp release issues.
     */
    boolean detectStampedLockIssues() default true;

    /**
     * Enable Exchanger misuse detection.
     * Detects exchange timeouts and missing partners.
     */
    boolean detectExchangerIssues() default true;

    /**
     * Enable ScheduledExecutorService issue detection.
     * Detects missing shutdown, long-running tasks, and exceptions.
     */
    boolean detectScheduledExecutorIssues() default true;

    /**
     * Enable ForkJoinPool issue detection.
     * Detects fork without join and task exceptions.
     */
    boolean detectForkJoinPoolIssues() default true;

    /**
     * Enable ThreadFactory issue detection.
     * Detects missing exception handlers and poor thread naming.
     */
    boolean detectThreadFactoryIssues() default true;

    // ============= Phase 4: Infrastructure & Resource Management =============

    /**
     * Enable thread leak detection.
     * Detects threads created but never terminated, leading to resource exhaustion.
     */
    boolean detectThreadLeaks() default true;

    /**
     * Enable sleep-in-lock detection.
     * Detects Thread.sleep() calls while holding locks.
     */
    boolean detectSleepInLock() default true;

    /**
     * Enable unbounded queue detection.
     * Detects BlockingQueue instances without capacity bounds.
     */
    boolean detectUnboundedQueue() default true;

    /**
     * Enable thread starvation detection.
     * Detects tasks waiting excessively long before execution.
     */
    boolean detectThreadStarvation() default true;

    // ============= Benchmarking =============

    /**
     * Enable benchmarking for this test method.
     * When true, execution times are recorded and compared against baselines.
     */
    boolean enableBenchmarking() default false;

    /**
     * Regression threshold percentage.
     * If execution time increases by more than this percentage compared to baseline,
     * a regression is detected.
     * Default is 20% (0.2 = 20%).
     */
    double benchmarkRegressionThreshold() default 0.2;

    /**
     * Fail the test on benchmark regression.
     * If true, a regression exceeding the threshold will cause test failure.
     * If false, only a warning is logged.
     */
    boolean failOnBenchmarkRegression() default false;
}

