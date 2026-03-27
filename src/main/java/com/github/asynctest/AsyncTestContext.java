package com.github.asynctest;

import com.github.asynctest.diagnostics.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Per-test context that holds Phase 2 detector instances and makes them accessible
 * to test code via static accessor methods.
 *
 * <p>The runner installs one shared {@code AsyncTestContext} into every worker thread's
 * {@link ThreadLocal} before the test body executes, so all threads point at the same
 * detector instances and can record events concurrently.
 *
 * <p>Usage inside an {@code @AsyncTest} method:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectFalseSharing = true)
 * void myTest() {
 *     AsyncTestContext.falseSharingDetector()
 *         .recordFieldAccess(this, "counter", int.class);
 * }
 * }</pre>
 *
 * <p>After the test run completes (or times out), the runner calls {@link #analyzeAll()}
 * and prints any Phase 2 reports that have issues.
 */
public final class AsyncTestContext {

    private static final ThreadLocal<AsyncTestContext> CURRENT = new ThreadLocal<>();

    // Package-private so ConcurrencyRunner can read them for reporting
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
    final SemaphoreMisuseDetector    semaphoreMisuseDetector;
    final CompletableFutureExceptionDetector completableFutureExceptionDetector;
    final ConcurrentModificationDetector concurrentModificationDetector;
    final LockLeakDetector lockLeakDetector;
    final SharedRandomDetector sharedRandomDetector;
    final BlockingQueueDetector blockingQueueDetector;
    final ConditionVariableDetector conditionVariableDetector;
    final SimpleDateFormatDetector simpleDateFormatDetector;
    final ParallelStreamDetector parallelStreamDetector;
    final ResourceLeakDetector resourceLeakDetector;
    final CountDownLatchDetector countDownLatchDetector;
    final CyclicBarrierDetector cyclicBarrierDetector;
    final ReentrantLockDetector reentrantLockDetector;
    final VolatileArrayDetector volatileArrayDetector;
    final DoubleCheckedLockingDetector doubleCheckedLockingDetector;
    final WaitTimeoutDetector waitTimeoutDetector;

    public AsyncTestContext(AsyncTestConfig cfg) {
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
        completableFutureExceptionDetector = cfg.detectCompletableFutureExceptions ? new CompletableFutureExceptionDetector() : null;
        concurrentModificationDetector = cfg.detectConcurrentModifications ? new ConcurrentModificationDetector() : null;
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
    }

    // ---- Lifecycle (package-private, called by ConcurrencyRunner) ----

    /** Installs {@code ctx} into the calling thread's ThreadLocal. */
    public static void install(AsyncTestContext ctx) {
        CURRENT.set(ctx);
    }

    /** Removes the context from the calling thread's ThreadLocal. */
    public static void uninstall() {
        CURRENT.remove();
    }

    /**
     * Returns the context active on the current thread, or {@code null} if called
     * outside an {@code @AsyncTest} method.
     */
    public static AsyncTestContext get() {
        return CURRENT.get();
    }

    // ---- Public static detector accessors ----

    /**
     * Returns the {@link FalseSharingDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectFalseSharing = false}
     */
    public static FalseSharingDetector falseSharingDetector() {
        return require("detectFalseSharing", c -> c.falseSharingDetector);
    }

    /**
     * Returns the {@link WakeupDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectWakeupIssues = false}
     */
    public static WakeupDetector wakeupDetector() {
        return require("detectWakeupIssues", c -> c.wakeupDetector);
    }

    /**
     * Returns the {@link ConstructorSafetyValidator} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code validateConstructorSafety = false}
     */
    public static ConstructorSafetyValidator constructorSafetyValidator() {
        return require("validateConstructorSafety", c -> c.constructorSafetyValidator);
    }

    /**
     * Returns the {@link ABAProblemDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectABAProblem = false}
     */
    public static ABAProblemDetector abaProblemDetector() {
        return require("detectABAProblem", c -> c.abaProblemDetector);
    }

    /**
     * Returns the {@link LockOrderValidator} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code validateLockOrder = false}
     */
    public static LockOrderValidator lockOrderValidator() {
        return require("validateLockOrder", c -> c.lockOrderValidator);
    }

    /**
     * Returns the {@link SynchronizerMonitor} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code monitorSynchronizers = false}
     */
    public static SynchronizerMonitor synchronizerMonitor() {
        return require("monitorSynchronizers", c -> c.synchronizerMonitor);
    }

    /**
     * Returns the {@link ThreadPoolMonitor} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code monitorThreadPool = false}
     */
    public static ThreadPoolMonitor threadPoolMonitor() {
        return require("monitorThreadPool", c -> c.threadPoolMonitor);
    }

    /**
     * Returns the {@link MemoryOrderingMonitor} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectMemoryOrderingViolations = false}
     */
    public static MemoryOrderingMonitor memoryOrderingMonitor() {
        return require("detectMemoryOrderingViolations", c -> c.memoryOrderingMonitor);
    }

    /**
     * Returns the {@link PipelineMonitor} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code monitorAsyncPipeline = false}
     */
    public static PipelineMonitor pipelineMonitor() {
        return require("monitorAsyncPipeline", c -> c.pipelineMonitor);
    }

    /**
     * Returns the {@link ReadWriteLockMonitor} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code monitorReadWriteLockFairness = false}
     */
    public static ReadWriteLockMonitor readWriteLockMonitor() {
        return require("monitorReadWriteLockFairness", c -> c.readWriteLockMonitor);
    }

    /**
     * Returns the {@link SemaphoreMisuseDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code monitorSemaphore = false}
     */
    public static SemaphoreMisuseDetector semaphoreMonitor() {
        return require("monitorSemaphore", c -> c.semaphoreMisuseDetector);
    }

    /**
     * Returns the {@link CompletableFutureExceptionDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCompletableFutureExceptions = false}
     */
    public static CompletableFutureExceptionDetector completableFutureMonitor() {
        return require("detectCompletableFutureExceptions", c -> c.completableFutureExceptionDetector);
    }

    /**
     * Returns the {@link ConcurrentModificationDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectConcurrentModifications = false}
     */
    public static ConcurrentModificationDetector concurrentModificationMonitor() {
        return require("detectConcurrentModifications", c -> c.concurrentModificationDetector);
    }

    /**
     * Returns the {@link LockLeakDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectLockLeaks = false}
     */
    public static LockLeakDetector lockLeakMonitor() {
        return require("detectLockLeaks", c -> c.lockLeakDetector);
    }

    /**
     * Returns the {@link SharedRandomDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSharedRandom = false}
     */
    public static SharedRandomDetector sharedRandomMonitor() {
        return require("detectSharedRandom", c -> c.sharedRandomDetector);
    }

    /**
     * Returns the {@link BlockingQueueDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectBlockingQueueIssues = false}
     */
    public static BlockingQueueDetector blockingQueueMonitor() {
        return require("detectBlockingQueueIssues", c -> c.blockingQueueDetector);
    }

    /**
     * Returns the {@link ConditionVariableDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectConditionVariableIssues = false}
     */
    public static ConditionVariableDetector conditionMonitor() {
        return require("detectConditionVariableIssues", c -> c.conditionVariableDetector);
    }

    /**
     * Returns the {@link SimpleDateFormatDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectSimpleDateFormatIssues = false}
     */
    public static SimpleDateFormatDetector simpleDateFormatMonitor() {
        return require("detectSimpleDateFormatIssues", c -> c.simpleDateFormatDetector);
    }

    /**
     * Returns the {@link ParallelStreamDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectParallelStreamIssues = false}
     */
    public static ParallelStreamDetector parallelStreamMonitor() {
        return require("detectParallelStreamIssues", c -> c.parallelStreamDetector);
    }

    /**
     * Returns the {@link ResourceLeakDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectResourceLeaks = false}
     */
    public static ResourceLeakDetector resourceLeakMonitor() {
        return require("detectResourceLeaks", c -> c.resourceLeakDetector);
    }

    /**
     * Returns the {@link CountDownLatchDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCountDownLatchIssues = false}
     */
    public static CountDownLatchDetector countDownLatchMonitor() {
        return require("detectCountDownLatchIssues", c -> c.countDownLatchDetector);
    }

    /**
     * Returns the {@link CyclicBarrierDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectCyclicBarrierIssues = false}
     */
    public static CyclicBarrierDetector cyclicBarrierMonitor() {
        return require("detectCyclicBarrierIssues", c -> c.cyclicBarrierDetector);
    }

    /**
     * Returns the {@link ReentrantLockDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectReentrantLockIssues = false}
     */
    public static ReentrantLockDetector reentrantLockMonitor() {
        return require("detectReentrantLockIssues", c -> c.reentrantLockDetector);
    }

    /**
     * Returns the {@link VolatileArrayDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectVolatileArrayIssues = false}
     */
    public static VolatileArrayDetector volatileArrayMonitor() {
        return require("detectVolatileArrayIssues", c -> c.volatileArrayDetector);
    }

    /**
     * Returns the {@link DoubleCheckedLockingDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectDoubleCheckedLocking = false}
     */
    public static DoubleCheckedLockingDetector doubleCheckedLockingMonitor() {
        return require("detectDoubleCheckedLocking", c -> c.doubleCheckedLockingDetector);
    }

    /**
     * Returns the {@link WaitTimeoutDetector} for the current test.
     * @throws IllegalStateException if not inside {@code @AsyncTest} or {@code detectWaitTimeout = false}
     */
    public static WaitTimeoutDetector waitTimeoutMonitor() {
        return require("detectWaitTimeout", c -> c.waitTimeoutDetector);
    }

    // ---- Internal reporting ----

    /**
     * Runs all enabled Phase 2 detectors and returns the {@code toString()} of any that
     * report issues. Called by {@link com.github.asynctest.runner.ConcurrencyRunner} after
     * the test completes or times out.
     */
    public List<String> analyzeAll() {
        List<String> out = new ArrayList<>();

        if (falseSharingDetector != null) {
            FalseSharingDetector.FalseSharingReport r = falseSharingDetector.analyzeFalseSharing();
            if (r.hasIssues()) out.add(r.toString());
        }
        if (wakeupDetector != null) {
            WakeupDetector.WakeupReport r = wakeupDetector.analyzeWakeups();
            if (r.hasIssues()) out.add(r.toString());
        }
        if (constructorSafetyValidator != null) {
            ConstructorSafetyValidator.ConstructorSafetyReport r = constructorSafetyValidator.validateConstructorSafety();
            if (r.hasIssues()) out.add(r.toString());
        }
        if (abaProblemDetector != null) {
            ABAProblemDetector.ABAReport r = abaProblemDetector.analyzeABA();
            if (r.hasIssues()) out.add(r.toString());
        }
        if (lockOrderValidator != null) {
            LockOrderValidator.LockOrderReport r = lockOrderValidator.validateLockOrder();
            if (r.hasIssues()) out.add(r.toString());
        }
        if (synchronizerMonitor != null) {
            SynchronizerMonitor.SynchronizerReport r = synchronizerMonitor.analyzeSynchronizers();
            if (r.hasIssues()) out.add(r.toString());
        }
        if (threadPoolMonitor != null) {
            ThreadPoolMonitor.ThreadPoolReport r = threadPoolMonitor.analyzePoolHealth();
            if (r.hasIssues()) out.add(r.toString());
        }
        if (memoryOrderingMonitor != null) {
            MemoryOrderingMonitor.MemoryOrderingReport r = memoryOrderingMonitor.analyzeOrdering();
            if (r.hasIssues()) out.add(r.toString());
        }
        if (pipelineMonitor != null) {
            PipelineMonitor.PipelineReport r = pipelineMonitor.analyzePipeline();
            if (r.hasIssues()) out.add(r.toString());
        }
        if (readWriteLockMonitor != null) {
            ReadWriteLockMonitor.ReadWriteLockReport r = readWriteLockMonitor.analyzeFairness();
            if (r.hasFairnessIssues()) out.add(r.toString());
        }
        if (semaphoreMisuseDetector != null) {
            SemaphoreMisuseDetector.SemaphoreMisuseReport r = semaphoreMisuseDetector.analyze();
            if (r.hasIssues()) out.add(r.toString());
        }
        if (completableFutureExceptionDetector != null) {
            CompletableFutureExceptionDetector.CompletableFutureExceptionReport r = completableFutureExceptionDetector.analyze();
            if (r.hasIssues()) out.add(r.toString());
        }
        if (concurrentModificationDetector != null) {
            ConcurrentModificationDetector.ConcurrentModificationReport r = concurrentModificationDetector.analyze();
            if (r.hasIssues()) out.add(r.toString());
        }
        if (lockLeakDetector != null) {
            LockLeakDetector.LockLeakReport r = lockLeakDetector.analyze();
            if (r.hasIssues()) out.add(r.toString());
        }
        if (sharedRandomDetector != null) {
            SharedRandomDetector.SharedRandomReport r = sharedRandomDetector.analyze();
            if (r.hasIssues()) out.add(r.toString());
        }
        if (blockingQueueDetector != null) {
            BlockingQueueDetector.BlockingQueueReport r = blockingQueueDetector.analyze();
            if (r.hasIssues()) out.add(r.toString());
        }
        if (conditionVariableDetector != null) {
            ConditionVariableDetector.ConditionVariableReport r = conditionVariableDetector.analyze();
            if (r.hasIssues()) out.add(r.toString());
        }
        if (simpleDateFormatDetector != null) {
            SimpleDateFormatDetector.SimpleDateFormatReport r = simpleDateFormatDetector.analyze();
            if (r.hasIssues()) out.add(r.toString());
        }
        if (parallelStreamDetector != null) {
            ParallelStreamDetector.ParallelStreamReport r = parallelStreamDetector.analyze();
            if (r.hasIssues()) out.add(r.toString());
        }
        if (resourceLeakDetector != null) {
            ResourceLeakDetector.ResourceLeakReport r = resourceLeakDetector.analyze();
            if (r.hasIssues()) out.add(r.toString());
        }
        if (countDownLatchDetector != null) {
            CountDownLatchDetector.CountDownLatchReport r = countDownLatchDetector.analyze();
            if (r.hasIssues()) out.add(r.toString());
        }
        if (cyclicBarrierDetector != null) {
            CyclicBarrierDetector.CyclicBarrierReport r = cyclicBarrierDetector.analyze();
            if (r.hasIssues()) out.add(r.toString());
        }
        if (reentrantLockDetector != null) {
            ReentrantLockDetector.ReentrantLockReport r = reentrantLockDetector.analyze();
            if (r.hasIssues()) out.add(r.toString());
        }
        if (volatileArrayDetector != null) {
            VolatileArrayDetector.VolatileArrayReport r = volatileArrayDetector.analyze();
            if (r.hasIssues()) out.add(r.toString());
        }
        if (doubleCheckedLockingDetector != null) {
            DoubleCheckedLockingDetector.DoubleCheckedLockingReport r = doubleCheckedLockingDetector.analyze();
            if (r.hasIssues()) out.add(r.toString());
        }
        if (waitTimeoutDetector != null) {
            WaitTimeoutDetector.WaitTimeoutReport r = waitTimeoutDetector.analyze();
            if (r.hasIssues()) out.add(r.toString());
        }

        return out;
    }

    // ---- Helpers ----

    private static <T> T require(String flag, Function<AsyncTestContext, T> fn) {
        AsyncTestContext ctx = CURRENT.get();
        if (ctx == null) {
            throw new IllegalStateException(
                "AsyncTestContext is not active — this accessor can only be called inside an @AsyncTest method.");
        }
        T val = fn.apply(ctx);
        if (val == null) {
            throw new IllegalStateException(
                "Detector not active: set " + flag + " = true on @AsyncTest to enable it," +
                " or use detectAll = true to enable every detector at once.");
        }
        return val;
    }
}
