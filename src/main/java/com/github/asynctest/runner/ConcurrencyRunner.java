package com.github.asynctest.runner;

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
                               int threads,
                               int invocations,
                               boolean useVirtualThreads,
                               long timeoutMs,
                               boolean detectDeadlocks,
                               boolean detectVisibility,
                               boolean detectLivelocks,
                               String virtualThreadStressMode) throws Throwable {

        // Initialize detectors
        VisibilityMonitor visibilityMonitor = detectVisibility ? new VisibilityMonitor() : null;
        LivelockDetector livelockDetector = detectLivelocks ? new LivelockDetector() : null;
        MemoryModelValidator jmmValidator = new MemoryModelValidator();
        
        // Validate JMM on test framework itself
        MemoryModelValidator.ValidationResult jmmResult = jmmValidator.validate();
        if (!jmmResult.isValid()) {
            System.err.println("WARNING: JMM validation of test framework failed:");
            System.err.println(jmmResult);
        }

        // Determine thread count based on stress mode
        final int actualThreads;
        if (virtualThreadStressMode != null && !virtualThreadStressMode.equals("OFF")) {
            VirtualThreadStressConfig.StressLevel stressLevel = 
                VirtualThreadStressConfig.StressLevel.valueOf(virtualThreadStressMode);
            actualThreads = stressLevel.threadCount;
        } else {
            actualThreads = threads;
        }

        ExecutorService executor = useVirtualThreads
                ? Executors.newVirtualThreadPerTaskExecutor()
                : Executors.newFixedThreadPool(actualThreads);

        try {
            CompletableFuture<Void> executionFuture = CompletableFuture.runAsync(() -> {
                try {
                    AtomicBoolean proceedCalled = new AtomicBoolean(false);
                    for (int i = 0; i < invocations; i++) {
                        if (visibilityMonitor != null) {
                            visibilityMonitor.markInvocationStart();
                        }
                        runSingleInvocationRound(invocation, invocationContext, actualThreads, 
                                                executor, !proceedCalled.get(), livelockDetector);
                        proceedCalled.set(true);
                    }
                } catch (Throwable t) {
                    throw new CompletionException(unwrap(t));
                }
            });

            try {
                executionFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                if (detectDeadlocks) {
                    DeadlockDetector.printThreadDump();
                }
                
                if (visibilityMonitor != null) {
                    VisibilityMonitor.VisibilityReport visReport = visibilityMonitor.analyzeVisibility();
                    if (visReport.hasIssues()) {
                        System.err.println("\n" + visReport);
                    }
                }
                
                if (livelockDetector != null) {
                    LivelockDetector.LivelockReport liveReport = livelockDetector.analyzeLivelocks();
                    if (liveReport.hasIssues()) {
                        System.err.println("\n" + liveReport);
                    }
                }
                
                throw new AssertionError("Test timed out after " + timeoutMs + "ms. Possible deadlock, starvation, or visibility issue.", e);
            } catch (ExecutionException e) {
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
                                                 LivelockDetector livelockDetector) throws Throwable {

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
                try {
                    barrier.await(); // Sync threads right before execution
                    
                    if (isFirstThread && callProceed) {
                        invocation.proceed();
                    } else {
                        method.invoke(target, args);
                    }
                } catch (Throwable ex) {
                    failed.compareAndSet(null, unwrap(ex));
                } finally {
                    latch.countDown();
                    if (livelockDetector != null) {
                        livelockDetector.captureSnapshot();
                    }
                }
            });
        }

        latch.await();

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
}
