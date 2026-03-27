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
                "Detector not active: set " + flag + " = true on @AsyncTest to enable it.");
        }
        return val;
    }
}
