package com.github.asynctest.runner;

import com.github.asynctest.AfterEachInvocation;
import com.github.asynctest.AsyncTestConfig;
import com.github.asynctest.AsyncTestContext;
import com.github.asynctest.BeforeEachInvocation;
import com.github.asynctest.diagnostics.*;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class ConcurrencyRunner {

    public static void execute(ReflectiveInvocationContext<Method> invocationContext,
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

        // setAccessible once per test, not once per invocation round (Fix 4)
        Method testMethod = invocationContext.getExecutable();
        testMethod.setAccessible(true);

        // Discover @BeforeEachInvocation / @AfterEachInvocation methods once (Fix 5)
        Object testInstance = invocationContext.getTarget().orElse(null);
        List<Method> beforeInvocationMethods = findLifecycleMethods(testInstance, BeforeEachInvocation.class);
        List<Method> afterInvocationMethods  = findLifecycleMethods(testInstance, AfterEachInvocation.class);

        // Fix 6: every thread in every round uses method.invoke() for a uniform
        // code path.  We do NOT call invocation.proceed() at all — as an
        // InvocationInterceptor we are fully responsible for executing the test
        // body, so JUnit does not require proceed() to be called.  Skipping it
        // means the test body is never double-executed and the AsyncTestContext
        // is always installed before the first line of test code runs.

        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.timeoutMs);

        try {
            for (int i = 0; i < config.invocations; i++) {
                long remainingMs = remainingMillis(deadlineNanos);
                if (remainingMs <= 0) {
                    throw timeoutError(config.timeoutMs, null, visibilityMonitor, livelockDetector,
                        raceDetector, threadLocalMonitor, busyWaitDetector, atomicityValidator,
                        interruptMonitor, phase2Context, config.detectDeadlocks);
                }

                if (visibilityMonitor != null) {
                    visibilityMonitor.markInvocationStart();
                }
                invokeLifecycleMethods(testInstance, beforeInvocationMethods);
                try {
                    runSingleInvocationRound(invocationContext, actualThreads,
                        executor, livelockDetector, phase2Context,
                        remainingMs, testMethod);
                } finally {
                    invokeLifecycleMethods(testInstance, afterInvocationMethods);
                }
            }
        } catch (AssertionError e) {
            if (isTimeoutLike(e)) {
                throw timeoutError(config.timeoutMs, e, visibilityMonitor, livelockDetector,
                    raceDetector, threadLocalMonitor, busyWaitDetector, atomicityValidator,
                    interruptMonitor, phase2Context, config.detectDeadlocks);
            }
            printPhase1Reports(visibilityMonitor, livelockDetector, raceDetector,
                threadLocalMonitor, busyWaitDetector, atomicityValidator, interruptMonitor);
            printPhase2Reports(phase2Context);
            throw e;
        } catch (Throwable t) {
            printPhase1Reports(visibilityMonitor, livelockDetector, raceDetector,
                threadLocalMonitor, busyWaitDetector, atomicityValidator, interruptMonitor);
            printPhase2Reports(phase2Context);
            throw unwrap(t);
        } finally {
            executor.shutdownNow();
            try {
                executor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void runSingleInvocationRound(ReflectiveInvocationContext<Method> context,
                                                 int threads,
                                                 ExecutorService executor,
                                                 LivelockDetector livelockDetector,
                                                 AsyncTestContext phase2Context,
                                                 long roundTimeoutMs,
                                                 Method method) throws Throwable {

        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(threads);

        Object target = context.getTarget().orElse(null);
        Object[] args = context.getArguments().toArray();
        // method.setAccessible() was already called once in execute() — no repeat here
        // Every thread uses method.invoke() for a uniform code path (Fix 6)

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                AsyncTestContext.install(phase2Context);
                try {
                    barrier.await();
                    method.invoke(target, args);
                } catch (Throwable ex) {
                    failures.add(unwrap(ex));
                } finally {
                    AsyncTestContext.uninstall();
                    latch.countDown();
                    if (livelockDetector != null) {
                        livelockDetector.captureSnapshot();
                    }
                }
            });
        }

        // Bound each round by the remaining time for the whole test run so we do not
        // depend on a separate coordinator future or the common ForkJoinPool.
        boolean completed = latch.await(roundTimeoutMs, TimeUnit.MILLISECONDS);
        if (!completed) {
            throw new AssertionError(
                "Invocation round timed out: " + (threads - (int) latch.getCount()) + "/" + threads
                    + " threads completed within " + roundTimeoutMs + "ms. "
                    + "A thread may be stuck before the test body (e.g. broken barrier).");
        }

        if (!failures.isEmpty()) {
            throw buildMultiFailureError(failures);
        }
    }

    private static AssertionError buildMultiFailureError(List<Throwable> failures) {
        if (failures.size() == 1) {
            Throwable single = failures.get(0);
            if (single instanceof AssertionError ae) return ae;
            AssertionError ae = new AssertionError(single.getMessage());
            ae.initCause(single);
            return ae;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(failures.size()).append(" concurrent thread(s) failed:\n");
        for (int i = 0; i < failures.size(); i++) {
            Throwable t = failures.get(i);
            sb.append("  [").append(i + 1).append("] ")
              .append(t.getClass().getSimpleName()).append(": ")
              .append(t.getMessage()).append('\n');
        }
        AssertionError combined = new AssertionError(sb.toString().trim());
        // Attach the first failure as cause so stack traces appear in IDEs
        combined.initCause(failures.get(0));
        // Attach remaining as suppressed
        for (int i = 1; i < failures.size(); i++) {
            combined.addSuppressed(failures.get(i));
        }
        return combined;
    }

    private static Throwable unwrap(Throwable t) {
        if (t instanceof InvocationTargetException) {
            return t.getCause();
        }
        return t;
    }

    private static long remainingMillis(long deadlineNanos) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
    }

    private static boolean isTimeoutLike(AssertionError e) {
        String message = e.getMessage();
        return message != null && message.contains("timed out");
    }

    private static AssertionError timeoutError(long timeoutMs,
                                               Throwable cause,
                                               VisibilityMonitor visibilityMonitor,
                                               LivelockDetector livelockDetector,
                                               RaceConditionDetector raceDetector,
                                               ThreadLocalMonitor threadLocalMonitor,
                                               BusyWaitDetector busyWaitDetector,
                                               AtomicityValidator atomicityValidator,
                                               InterruptMonitor interruptMonitor,
                                               AsyncTestContext phase2Context,
                                               boolean detectDeadlocks) {
        if (detectDeadlocks) {
            DeadlockDetector.printThreadDump();
        }
        printPhase1Reports(visibilityMonitor, livelockDetector, raceDetector,
            threadLocalMonitor, busyWaitDetector, atomicityValidator, interruptMonitor);
        printPhase2Reports(phase2Context);
        AssertionError error = new AssertionError(
            "Test timed out after " + timeoutMs + "ms. Possible deadlock, starvation, or visibility issue.");
        if (cause != null) {
            error.initCause(cause);
        }
        return error;
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

    // ---- Per-invocation lifecycle helpers (Fix 5) ----

    private static <A extends java.lang.annotation.Annotation> List<Method> findLifecycleMethods(
            Object target, Class<A> annotationType) {
        if (target == null) return List.of();
        List<Method> found = new ArrayList<>();
        Class<?> klass = target.getClass();
        while (klass != null && klass != Object.class) {
            for (Method m : klass.getDeclaredMethods()) {
                if (m.isAnnotationPresent(annotationType)
                        && m.getParameterCount() == 0
                        && m.getReturnType() == void.class) {
                    m.setAccessible(true);
                    found.add(m);
                }
            }
            klass = klass.getSuperclass();
        }
        return found;
    }

    private static void invokeLifecycleMethods(Object target, List<Method> methods) {
        if (target == null || methods.isEmpty()) return;
        for (Method m : methods) {
            try {
                m.setAccessible(true);
                m.invoke(target);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new RuntimeException("@" + (m.isAnnotationPresent(BeforeEachInvocation.class)
                    ? "BeforeEachInvocation" : "AfterEachInvocation")
                    + " method '" + m.getName() + "' threw: " + cause.getMessage(), cause);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Cannot invoke lifecycle method: " + m.getName(), e);
            }
        }
    }
}
