package com.github.asynctest.runner;

import com.github.asynctest.AsyncTestConfig;
import com.github.asynctest.AsyncTestContext;
import com.github.asynctest.diagnostics.*;
import org.junit.jupiter.api.extension.InvocationInterceptor.Invocation;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class ConcurrencyRunner {

    public static void execute(Invocation<Void> invocation,
                               ReflectiveInvocationContext<Method> invocationContext,
                               AsyncTestConfig config) throws Throwable {

        // Phase 2 context — shared across all threads for this test run
        AsyncTestContext phase2Context = new AsyncTestContext(config);

        // Phase 1 + Phase 3 detectors
        VisibilityMonitor  visibilityMonitor  = config.detectVisibility          ? new VisibilityMonitor()  : null;
        LivelockDetector   livelockDetector   = config.detectLivelocks           ? new LivelockDetector()   : null;
        RaceConditionDetector raceDetector    = config.detectRaceConditions      ? new RaceConditionDetector() : null;
        ThreadLocalMonitor threadLocalMonitor = config.detectThreadLocalLeaks    ? new ThreadLocalMonitor() : null;
        BusyWaitDetector   busyWaitDetector   = config.detectBusyWaiting         ? new BusyWaitDetector()   : null;
        AtomicityValidator atomicityValidator = config.detectAtomicityViolations ? new AtomicityValidator() : null;
        InterruptMonitor   interruptMonitor   = config.detectInterruptMishandling? new InterruptMonitor()   : null;
        MemoryModelValidator jmmValidator     = new MemoryModelValidator();

        // Validate JMM on the test framework itself
        MemoryModelValidator.ValidationResult jmmResult = jmmValidator.validate();
        if (!jmmResult.isValid()) {
            System.err.println("WARNING: JMM validation of test framework failed:");
            System.err.println(jmmResult);
        }

        // Determine actual thread count (stress mode overrides threads param)
        final int actualThreads;
        if (config.virtualThreadStressMode != null && !config.virtualThreadStressMode.equals("OFF")) {
            actualThreads = VirtualThreadStressConfig.StressLevel
                .valueOf(config.virtualThreadStressMode).threadCount;
        } else {
            actualThreads = config.threads;
        }

        ExecutorService executor = config.useVirtualThreads
            ? Executors.newVirtualThreadPerTaskExecutor()
            : Executors.newFixedThreadPool(actualThreads);

        try {
            CompletableFuture<Void> executionFuture = CompletableFuture.runAsync(() -> {
                try {
                    AtomicBoolean proceedCalled = new AtomicBoolean(false);
                    for (int i = 0; i < config.invocations; i++) {
                        if (visibilityMonitor != null) {
                            visibilityMonitor.markInvocationStart();
                        }
                        runSingleInvocationRound(invocation, invocationContext, actualThreads,
                            executor, !proceedCalled.get(), livelockDetector, phase2Context,
                            config.timeoutMs);
                        proceedCalled.set(true);
                    }
                } catch (Throwable t) {
                    throw new CompletionException(unwrap(t));
                }
            });

            try {
                executionFuture.get(config.timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                if (config.detectDeadlocks) {
                    DeadlockDetector.printThreadDump();
                }
                printPhase1Reports(visibilityMonitor, livelockDetector, raceDetector,
                    threadLocalMonitor, busyWaitDetector, atomicityValidator, interruptMonitor);
                printPhase2Reports(phase2Context);
                throw new AssertionError(
                    "Test timed out after " + config.timeoutMs + "ms. Possible deadlock, starvation, or visibility issue.", e);
            } catch (ExecutionException e) {
                printPhase1Reports(visibilityMonitor, livelockDetector, raceDetector,
                    threadLocalMonitor, busyWaitDetector, atomicityValidator, interruptMonitor);
                printPhase2Reports(phase2Context);
                throw unwrap(e.getCause());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static void runSingleInvocationRound(Invocation<Void> invocation,
                                                 ReflectiveInvocationContext<Method> context,
                                                 int threads,
                                                 ExecutorService executor,
                                                 boolean callProceed,
                                                 LivelockDetector livelockDetector,
                                                 AsyncTestContext phase2Context,
                                                 long roundTimeoutMs) throws Throwable {

        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(threads);

        Method method = context.getExecutable();
        Object target = context.getTarget().orElse(null);
        Object[] args = context.getArguments().toArray();
        method.setAccessible(true);

        for (int t = 0; t < threads; t++) {
            final boolean isFirstThread = (t == 0);
            executor.submit(() -> {
                AsyncTestContext.install(phase2Context);
                try {
                    barrier.await();
                    if (isFirstThread && callProceed) {
                        invocation.proceed();
                    } else {
                        method.invoke(target, args);
                    }
                } catch (Throwable ex) {
                    failed.compareAndSet(null, unwrap(ex));
                } finally {
                    AsyncTestContext.uninstall();
                    latch.countDown();
                    if (livelockDetector != null) {
                        livelockDetector.captureSnapshot();
                    }
                }
            });
        }

        // Use a bounded wait so a thread that gets stuck before latch.countDown()
        // (e.g. due to BrokenBarrierException or an infinite loop before the test body)
        // doesn't hang the entire suite forever. Add a generous slack on top of the
        // configured timeout so normal tests are not affected.
        boolean completed = latch.await(roundTimeoutMs + 5_000, TimeUnit.MILLISECONDS);
        if (!completed) {
            throw new AssertionError(
                "Invocation round timed out: " + (threads - (int) latch.getCount()) + "/" + threads
                    + " threads completed within " + (roundTimeoutMs + 5_000) + "ms. "
                    + "A thread may be stuck before the test body (e.g. broken barrier).");
        }

        if (failed.get() != null) {
            throw failed.get();
        }
    }

    private static Throwable unwrap(Throwable t) {
        if (t instanceof InvocationTargetException) {
            return t.getCause();
        }
        if (t instanceof CompletionException) {
            return t.getCause() != null ? t.getCause() : t;
        }
        return t;
    }

    private static void printPhase1Reports(VisibilityMonitor visibilityMonitor,
                                           LivelockDetector livelockDetector,
                                           RaceConditionDetector raceDetector,
                                           ThreadLocalMonitor threadLocalMonitor,
                                           BusyWaitDetector busyWaitDetector,
                                           AtomicityValidator atomicityValidator,
                                           InterruptMonitor interruptMonitor) {
        if (visibilityMonitor != null) {
            VisibilityMonitor.VisibilityReport r = visibilityMonitor.analyzeVisibility();
            if (r.hasIssues()) System.err.println("\n" + r);
        }
        if (livelockDetector != null) {
            LivelockDetector.LivelockReport r = livelockDetector.analyzeLivelocks();
            if (r.hasIssues()) System.err.println("\n" + r);
        }
        if (raceDetector != null) {
            RaceConditionDetector.RaceConditionReport r = raceDetector.analyzeRaceConditions();
            if (r.hasIssues()) System.err.println("\n" + r);
        }
        if (threadLocalMonitor != null) {
            ThreadLocalMonitor.ThreadLocalReport r = threadLocalMonitor.analyzeThreadLocalLeaks();
            if (r.hasIssues()) System.err.println("\n" + r);
        }
        if (busyWaitDetector != null) {
            BusyWaitDetector.BusyWaitReport r = busyWaitDetector.analyzeBusyWaiting();
            if (r.hasIssues()) System.err.println("\n" + r);
        }
        if (atomicityValidator != null) {
            AtomicityValidator.AtomicityReport r = atomicityValidator.analyzeAtomicity();
            if (r.hasIssues()) System.err.println("\n" + r);
        }
        if (interruptMonitor != null) {
            InterruptMonitor.InterruptReport r = interruptMonitor.analyzeInterruptHandling();
            if (r.hasIssues()) System.err.println("\n" + r);
        }
    }

    private static void printPhase2Reports(AsyncTestContext ctx) {
        for (String report : ctx.analyzeAll()) {
            System.err.println("\n" + report);
        }
    }
}
