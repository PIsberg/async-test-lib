package com.github.asynctest;

import com.github.asynctest.diagnostics.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Holds all Phase 2 detector instances for a single test run and orchestrates
 * their post-run analysis.
 *
 * <p>This class was extracted from {@link AsyncTestContext} to separate two
 * concerns: detector lifecycle (this class) from ThreadLocal context management
 * ({@link AsyncTestContext}).
 *
 * <p>A {@code DetectorRegistry} is created once per test method execution by
 * {@link com.github.asynctest.runner.ConcurrencyRunner} and shared across all
 * worker threads via the {@link AsyncTestContext} ThreadLocal.
 *
 * <p>All detector fields are package-private so that {@link AsyncTestContext}
 * static accessors can read them directly without reflection overhead.
 */
final class DetectorRegistry {

    // ---- Phase 2: Core ----
    final FalseSharingDetector       falseSharingDetector;
    final WakeupDetector             wakeupDetector;
    final ConstructorSafetyValidator constructorSafetyValidator;
    final ABAProblemDetector         abaProblemDetector;
    final LockOrderValidator         lockOrderValidator;
    final SynchronizerMonitor        synchronizerMonitor;
    final ThreadPoolMonitor          threadPoolMonitor;
    final MemoryOrderingMonitor      memoryOrderingMonitor;
    final PipelineMonitor            pipelineMonitor;
    final ReadWriteLockMonitor       readWriteLockMonitor;

    // ---- Phase 2: Additional monitors ----
    final SemaphoreMisuseDetector              semaphoreMisuseDetector;
    final CompletableFutureExceptionDetector   completableFutureExceptionDetector;
    final CompletableFutureCompletionLeakDetector completableFutureCompletionLeakDetector;
    final VirtualThreadPinningDetector         virtualThreadPinningDetector;
    final ThreadPoolDeadlockDetector           threadPoolDeadlockDetector;
    final ConcurrentModificationDetector       concurrentModificationDetector;
    final LockLeakDetector                     lockLeakDetector;
    final SharedRandomDetector                 sharedRandomDetector;
    final BlockingQueueDetector                blockingQueueDetector;
    final ConditionVariableDetector            conditionVariableDetector;
    final SimpleDateFormatDetector             simpleDateFormatDetector;
    final ParallelStreamDetector               parallelStreamDetector;
    final ResourceLeakDetector                 resourceLeakDetector;

    // ---- Phase 2: Additional concurrency ----
    final CountDownLatchDetector       countDownLatchDetector;
    final CyclicBarrierDetector        cyclicBarrierDetector;
    final ReentrantLockDetector        reentrantLockDetector;
    final VolatileArrayDetector        volatileArrayDetector;
    final DoubleCheckedLockingDetector doubleCheckedLockingDetector;
    final WaitTimeoutDetector          waitTimeoutDetector;

    // ---- Phase 2: Advanced concurrency utilities ----
    final PhaserDetector             phaserDetector;
    final StampedLockDetector        stampedLockDetector;
    final ExchangerDetector          exchangerDetector;
    final ScheduledExecutorDetector  scheduledExecutorDetector;
    final ForkJoinPoolDetector       forkJoinPoolDetector;
    final ThreadFactoryDetector      threadFactoryDetector;

    // ---- Phase 4: Infrastructure & Resource Management ----
    final ThreadLeakDetector         threadLeakDetector;
    final SleepInLockDetector        sleepInLockDetector;
    final UnboundedQueueDetector     unboundedQueueDetector;
    final ThreadStarvationDetector   threadStarvationDetector;

    /**
     * Instantiates detectors based on the enabled flags in {@code cfg}.
     * Detectors whose flag is {@code false} are set to {@code null} and incur
     * zero overhead during the test run.
     */
    DetectorRegistry(AsyncTestConfig cfg) {
        falseSharingDetector       = cfg.detectFalseSharing             ? new FalseSharingDetector()       : null;
        wakeupDetector             = cfg.detectWakeupIssues             ? new WakeupDetector()             : null;
        constructorSafetyValidator = cfg.validateConstructorSafety      ? new ConstructorSafetyValidator() : null;
        abaProblemDetector         = cfg.detectABAProblem               ? new ABAProblemDetector()         : null;
        lockOrderValidator         = cfg.validateLockOrder              ? new LockOrderValidator()         : null;
        synchronizerMonitor        = cfg.monitorSynchronizers           ? new SynchronizerMonitor()        : null;
        threadPoolMonitor          = cfg.monitorThreadPool              ? new ThreadPoolMonitor()          : null;
        memoryOrderingMonitor      = cfg.detectMemoryOrderingViolations ? new MemoryOrderingMonitor()      : null;
        pipelineMonitor            = cfg.monitorAsyncPipeline           ? new PipelineMonitor()            : null;
        readWriteLockMonitor       = cfg.monitorReadWriteLockFairness   ? new ReadWriteLockMonitor()       : null;
        semaphoreMisuseDetector    = cfg.monitorSemaphore               ? new SemaphoreMisuseDetector()    : null;
        completableFutureExceptionDetector = cfg.detectCompletableFutureExceptions
                ? new CompletableFutureExceptionDetector() : null;
        completableFutureCompletionLeakDetector = cfg.detectCompletableFutureCompletionLeaks
                ? new CompletableFutureCompletionLeakDetector() : null;
        virtualThreadPinningDetector = cfg.detectVirtualThreadPinning
                ? new VirtualThreadPinningDetector() : null;
        threadPoolDeadlockDetector = cfg.detectThreadPoolDeadlocks
                ? new ThreadPoolDeadlockDetector() : null;
        concurrentModificationDetector = cfg.detectConcurrentModifications
                ? new ConcurrentModificationDetector() : null;
        lockLeakDetector           = cfg.detectLockLeaks                ? new LockLeakDetector()           : null;
        sharedRandomDetector       = cfg.detectSharedRandom             ? new SharedRandomDetector()       : null;
        blockingQueueDetector      = cfg.detectBlockingQueueIssues      ? new BlockingQueueDetector()      : null;
        conditionVariableDetector  = cfg.detectConditionVariableIssues  ? new ConditionVariableDetector()  : null;
        simpleDateFormatDetector   = cfg.detectSimpleDateFormatIssues   ? new SimpleDateFormatDetector()   : null;
        parallelStreamDetector     = cfg.detectParallelStreamIssues     ? new ParallelStreamDetector()     : null;
        resourceLeakDetector       = cfg.detectResourceLeaks            ? new ResourceLeakDetector()       : null;
        countDownLatchDetector     = cfg.detectCountDownLatchIssues     ? new CountDownLatchDetector()     : null;
        cyclicBarrierDetector      = cfg.detectCyclicBarrierIssues      ? new CyclicBarrierDetector()      : null;
        reentrantLockDetector      = cfg.detectReentrantLockIssues      ? new ReentrantLockDetector()      : null;
        volatileArrayDetector      = cfg.detectVolatileArrayIssues      ? new VolatileArrayDetector()      : null;
        doubleCheckedLockingDetector = cfg.detectDoubleCheckedLocking   ? new DoubleCheckedLockingDetector() : null;
        waitTimeoutDetector        = cfg.detectWaitTimeout              ? new WaitTimeoutDetector()        : null;
        phaserDetector             = cfg.detectPhaserIssues             ? new PhaserDetector()             : null;
        stampedLockDetector        = cfg.detectStampedLockIssues        ? new StampedLockDetector()        : null;
        exchangerDetector          = cfg.detectExchangerIssues          ? new ExchangerDetector()          : null;
        scheduledExecutorDetector  = cfg.detectScheduledExecutorIssues  ? new ScheduledExecutorDetector()  : null;
        forkJoinPoolDetector       = cfg.detectForkJoinPoolIssues       ? new ForkJoinPoolDetector()       : null;
        threadFactoryDetector      = cfg.detectThreadFactoryIssues      ? new ThreadFactoryDetector()      : null;
        threadLeakDetector         = cfg.detectThreadLeaks              ? new ThreadLeakDetector()         : null;
        sleepInLockDetector        = cfg.detectSleepInLock              ? new SleepInLockDetector()        : null;
        unboundedQueueDetector     = cfg.detectUnboundedQueue           ? new UnboundedQueueDetector()     : null;
        threadStarvationDetector   = cfg.detectThreadStarvation         ? new ThreadStarvationDetector()   : null;
    }

    /**
     * Runs every enabled Phase 2 detector's analysis and returns the
     * {@code toString()} of any that report issues.
     *
     * <p>Called by {@link com.github.asynctest.runner.ConcurrencyRunner} after the
     * test completes or times out.
     *
     * @return list of non-empty issue reports; never {@code null}
     */
    List<String> analyzeAll() {
        List<String> out = new ArrayList<>();

        ifIssue(falseSharingDetector,
                d -> d.analyzeFalseSharing(),
                FalseSharingDetector.FalseSharingReport::hasIssues, out);
        ifIssue(wakeupDetector,
                d -> d.analyzeWakeups(),
                WakeupDetector.WakeupReport::hasIssues, out);
        ifIssue(constructorSafetyValidator,
                d -> d.validateConstructorSafety(),
                ConstructorSafetyValidator.ConstructorSafetyReport::hasIssues, out);
        ifIssue(abaProblemDetector,
                d -> d.analyzeABA(),
                ABAProblemDetector.ABAReport::hasIssues, out);
        ifIssue(lockOrderValidator,
                d -> d.validateLockOrder(),
                LockOrderValidator.LockOrderReport::hasIssues, out);
        ifIssue(synchronizerMonitor,
                d -> d.analyzeSynchronizers(),
                SynchronizerMonitor.SynchronizerReport::hasIssues, out);
        ifIssue(threadPoolMonitor,
                d -> d.analyzePoolHealth(),
                ThreadPoolMonitor.ThreadPoolReport::hasIssues, out);
        ifIssue(memoryOrderingMonitor,
                d -> d.analyzeOrdering(),
                MemoryOrderingMonitor.MemoryOrderingReport::hasIssues, out);
        ifIssue(pipelineMonitor,
                d -> d.analyzePipeline(),
                PipelineMonitor.PipelineReport::hasIssues, out);
        // ReadWriteLock uses hasFairnessIssues() — report it as an issue when
        // writer starvation or imbalance is detected
        if (readWriteLockMonitor != null) {
            ReadWriteLockMonitor.ReadWriteLockReport r = readWriteLockMonitor.analyzeFairness();
            if (r.hasFairnessIssues()) out.add(r.toString());
        }
        ifIssue(semaphoreMisuseDetector,
                d -> d.analyze(),
                SemaphoreMisuseDetector.SemaphoreMisuseReport::hasIssues, out);
        ifIssue(completableFutureExceptionDetector,
                d -> d.analyze(),
                CompletableFutureExceptionDetector.CompletableFutureExceptionReport::hasIssues, out);
        if (completableFutureCompletionLeakDetector != null) {
            CompletableFutureCompletionLeakDetector.CompletionLeakReport r = 
                completableFutureCompletionLeakDetector.analyze();
            if (r.hasLeaks()) out.add(r.toString());
        }
        if (virtualThreadPinningDetector != null) {
            VirtualThreadPinningDetector.PinningReport r = 
                virtualThreadPinningDetector.analyzePinning();
            if (r.hasPinningIssues()) out.add(r.toString());
        }
        if (threadPoolDeadlockDetector != null) {
            ThreadPoolDeadlockDetector.ThreadPoolDeadlockReport r = 
                threadPoolDeadlockDetector.analyze();
            if (r.hasDeadlockRisk()) out.add(r.toString());
        }
        ifIssue(concurrentModificationDetector,
                d -> d.analyze(),
                ConcurrentModificationDetector.ConcurrentModificationReport::hasIssues, out);
        ifIssue(lockLeakDetector,
                d -> d.analyze(),
                LockLeakDetector.LockLeakReport::hasIssues, out);
        ifIssue(sharedRandomDetector,
                d -> d.analyze(),
                SharedRandomDetector.SharedRandomReport::hasIssues, out);
        ifIssue(blockingQueueDetector,
                d -> d.analyze(),
                BlockingQueueDetector.BlockingQueueReport::hasIssues, out);
        ifIssue(conditionVariableDetector,
                d -> d.analyze(),
                ConditionVariableDetector.ConditionVariableReport::hasIssues, out);
        ifIssue(simpleDateFormatDetector,
                d -> d.analyze(),
                SimpleDateFormatDetector.SimpleDateFormatReport::hasIssues, out);
        ifIssue(parallelStreamDetector,
                d -> d.analyze(),
                ParallelStreamDetector.ParallelStreamReport::hasIssues, out);
        ifIssue(resourceLeakDetector,
                d -> d.analyze(),
                ResourceLeakDetector.ResourceLeakReport::hasIssues, out);
        ifIssue(countDownLatchDetector,
                d -> d.analyze(),
                CountDownLatchDetector.CountDownLatchReport::hasIssues, out);
        ifIssue(cyclicBarrierDetector,
                d -> d.analyze(),
                CyclicBarrierDetector.CyclicBarrierReport::hasIssues, out);
        ifIssue(reentrantLockDetector,
                d -> d.analyze(),
                ReentrantLockDetector.ReentrantLockReport::hasIssues, out);
        ifIssue(volatileArrayDetector,
                d -> d.analyze(),
                VolatileArrayDetector.VolatileArrayReport::hasIssues, out);
        ifIssue(doubleCheckedLockingDetector,
                d -> d.analyze(),
                DoubleCheckedLockingDetector.DoubleCheckedLockingReport::hasIssues, out);
        ifIssue(waitTimeoutDetector,
                d -> d.analyze(),
                WaitTimeoutDetector.WaitTimeoutReport::hasIssues, out);
        ifIssue(phaserDetector,
                d -> d.analyze(),
                PhaserDetector.PhaserReport::hasIssues, out);
        ifIssue(stampedLockDetector,
                d -> d.analyze(),
                StampedLockDetector.StampedLockReport::hasIssues, out);
        ifIssue(exchangerDetector,
                d -> d.analyze(),
                ExchangerDetector.ExchangerReport::hasIssues, out);
        ifIssue(scheduledExecutorDetector,
                d -> d.analyze(),
                ScheduledExecutorDetector.ScheduledExecutorReport::hasIssues, out);
        ifIssue(forkJoinPoolDetector,
                d -> d.analyze(),
                ForkJoinPoolDetector.ForkJoinPoolReport::hasIssues, out);
        ifIssue(threadFactoryDetector,
                d -> d.analyze(),
                ThreadFactoryDetector.ThreadFactoryReport::hasIssues, out);

        // ---- Phase 4: Infrastructure & Resource Management ----
        ifIssue(threadLeakDetector,
                d -> d.analyzeLeaks(),
                ThreadLeakDetector.ThreadLeakReport::hasIssues, out);
        ifIssue(sleepInLockDetector,
                d -> d.analyze(),
                SleepInLockDetector.SleepInLockReport::hasIssues, out);
        ifIssue(unboundedQueueDetector,
                d -> d.analyze(),
                UnboundedQueueDetector.UnboundedQueueReport::hasIssues, out);
        ifIssue(threadStarvationDetector,
                d -> d.analyze(),
                ThreadStarvationDetector.ThreadStarvationReport::hasIssues, out);

        return out;
    }

    // ---- Helper ----

    /**
     * If {@code detector} is non-null and the report from {@code analyze} has issues,
     * appends the report's {@code toString()} to {@code out}.
     */
    private static <D, R> void ifIssue(D detector,
                                       Function<D, R> analyze,
                                       Function<R, Boolean> hasIssues,
                                       List<String> out) {
        if (detector == null) return;
        R report = analyze.apply(detector);
        if (Boolean.TRUE.equals(hasIssues.apply(report))) {
            out.add(report.toString());
        }
    }
}
