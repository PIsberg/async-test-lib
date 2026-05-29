package se.deversity.asynctest.runner;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIThreadSafe;
import se.deversity.asynctest.AfterEachInvocation;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.AsyncTestListenerRegistry;
import se.deversity.asynctest.BeforeEachInvocation;
import se.deversity.asynctest.benchmark.BenchmarkRecorder;
import se.deversity.asynctest.diagnostics.DeadlockDetector;
import se.deversity.asynctest.diagnostics.MemoryModelValidator;
import se.deversity.asynctest.diagnostics.Phase1DetectorSet;
import se.deversity.asynctest.diagnostics.VirtualThreadStressConfig;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Orchestrates the N-invocations × M-threads test execution pattern for
 * {@code @AsyncTest} methods.
 *
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Set up Phase 1 (via {@link Phase1DetectorSet}) and Phase 2 (via
 *       {@link AsyncTestContext}) detectors for the test run</li>
 *   <li>Run the test body N×M times using a {@link CyclicBarrier} to force
 *       maximum thread contention on each invocation</li>
 *   <li>Collect and report failures from all threads</li>
 *   <li>Print detector reports on failure or timeout</li>
 *   <li>Manage optional benchmarking</li>
 * </ul>
 *
 * <p>This class is intentionally stateless — all state lives in the per-call
 * local variables of {@link #execute}.
 */
@AICore(
    sensitivity = "Critical",
    note = "Core stress-test execution engine. The CyclicBarrier pattern forces maximum thread contention. Timeout logic and AsyncTestContext install/uninstall are carefully calibrated — subtle changes introduce flaky tests or missed detector activations."
)
@AIAudit(checkFor = {"Thread Safety issues", "Resource Leaks"})
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "Coordinates concurrency using CyclicBarrier to maximize thread contention.")
public class ConcurrencyRunner {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyRunner.class);

    public static void execute(ReflectiveInvocationContext<Method> invocationContext,
                               AsyncTestConfig config) throws Throwable {

        // Process-wide license check — cached by config fingerprint, so this is
        // a ConcurrentHashMap hit after the first invocation per JVM.
        LicenseGuard.check(config);

        // Benchmarking setup
        BenchmarkRecorder benchmarkRecorder = null;
        if (config.enableBenchmarking) {
            Object testInstance = invocationContext.getTarget().orElse(null);
            String testClass = testInstance != null ? testInstance.getClass().getName() : "unknown";
            String testMethod = invocationContext.getExecutable().getName();
            benchmarkRecorder = new BenchmarkRecorder(config, testClass, testMethod);
        }

        // Phase 2 context — shared across all threads for this test run
        AsyncTestContext phase2Context = new AsyncTestContext(config);

        // Phase 1 + Phase 3 detectors — grouped in a value-holder to avoid
        // long parameter lists in helper methods
        Phase1DetectorSet phase1 = Phase1DetectorSet.from(config);

        // Validate JMM on the test framework itself
        MemoryModelValidator jmmValidator = new MemoryModelValidator();
        MemoryModelValidator.ValidationResult jmmResult = jmmValidator.validate();
        if (!jmmResult.isValid()) {
            log.warn("JMM validation of test framework failed: {}", jmmResult);
        }

        // Determine actual thread count (stress mode overrides threads param)
        final int actualThreads;
        if (config.virtualThreadStressMode != null && !"OFF".equals(config.virtualThreadStressMode)) {
            actualThreads = VirtualThreadStressConfig.StressLevel
                .valueOf(config.virtualThreadStressMode).threadCount;
        } else {
            actualThreads = config.threads;
        }

        ExecutorService executor = config.useVirtualThreads
            ? Executors.newVirtualThreadPerTaskExecutor()
            : Executors.newFixedThreadPool(actualThreads);

        // setAccessible once per test, not once per invocation round
        Method testMethod = invocationContext.getExecutable();
        testMethod.setAccessible(true);

        // Discover @BeforeEachInvocation / @AfterEachInvocation methods once
        Object testInstance = invocationContext.getTarget().orElse(null);
        List<Method> beforeInvocationMethods = findLifecycleMethods(testInstance, BeforeEachInvocation.class);
        List<Method> afterInvocationMethods  = findLifecycleMethods(testInstance, AfterEachInvocation.class);

        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.timeoutMs);

        // Replay-seed source: explicit @AsyncTest(replaySeed=N) makes every
        // round use N; default 0 generates a fresh seed per round so failures
        // are reproducible after the runner logs the value.
        java.util.random.RandomGenerator seedSource = (config.replaySeed != 0L)
                ? null
                : new java.util.Random();
        long currentSeed = 0L;

        try {
            for (int i = 0; i < config.invocations; i++) {
                long remainingMs = remainingMillis(deadlineNanos);
                if (remainingMs <= 0) {
                    throw timeoutError(config.timeoutMs, null, phase1, phase2Context,
                            config.detectDeadlocks);
                }

                // Seed for this round — fixed from annotation, or freshly drawn.
                currentSeed = (seedSource != null) ? seedSource.nextLong() : config.replaySeed;
                phase2Context.setReplaySeedForRound(currentSeed);

                long benchmarkStart = 0;
                if (benchmarkRecorder != null) {
                    benchmarkStart = benchmarkRecorder.recordInvocationStart();
                }

                if (phase1.visibility != null) {
                    phase1.visibility.markInvocationStart();
                }
                AsyncTestListenerRegistry.fireInvocationStarted(i, actualThreads);
                long roundStartNanos = System.nanoTime();
                invokeLifecycleMethods(testInstance, beforeInvocationMethods);
                try {
                    runSingleInvocationRound(invocationContext, actualThreads,
                        executor, phase1, phase2Context, remainingMs, testMethod);
                } finally {
                    invokeLifecycleMethods(testInstance, afterInvocationMethods);
                }
                long roundDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - roundStartNanos);
                AsyncTestListenerRegistry.fireInvocationCompleted(i, roundDurationMs);

                if (benchmarkRecorder != null) {
                    benchmarkRecorder.recordInvocationEnd(benchmarkStart);
                }
            }
        } catch (AssertionError e) {
            if (isTimeoutLike(e)) {
                throw timeoutError(config.timeoutMs, e, phase1, phase2Context,
                        config.detectDeadlocks);
            }
            // Surface the seed of the failing round so the user can reproduce by
            // pasting it into @AsyncTest(replaySeed=N).
            System.err.println("[AsyncTest] Failure with replaySeed=" + currentSeed
                    + "L — paste into @AsyncTest(replaySeed=...) to reproduce.");
            AsyncTestListenerRegistry.fireTestFailed(e);
            phase1.printReports();
            printPhase2Reports(phase2Context);
            throw e;
        } catch (Throwable t) {
            System.err.println("[AsyncTest] Failure with replaySeed=" + currentSeed
                    + "L — paste into @AsyncTest(replaySeed=...) to reproduce.");
            AsyncTestListenerRegistry.fireTestFailed(t);
            phase1.printReports();
            printPhase2Reports(phase2Context);
            throw unwrap(t);
        } finally {
            executor.shutdownNow();
            try {
                executor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (benchmarkRecorder != null) {
                try {
                    benchmarkRecorder.complete();
                } catch (Exception e) {
                    log.warn("Benchmark completion failed: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Abstraction over CyclicBarrier and SpinContentionBarrier.
     * Lets runSingleInvocationRound stay barrier-implementation-agnostic.
     *
     * <p>Declares the union of checked exceptions from both implementations:
     * {@link InterruptedException} (both) and {@link BrokenBarrierException}
     * (CyclicBarrier only — SpinContentionBarrier never throws it).
     */
    @FunctionalInterface
    private interface ContentionBarrier {
        void arrive() throws InterruptedException, BrokenBarrierException;
    }

    /**
     * Creates a {@link ContentionBarrier} for {@code threads} participants.
     *
     * <p>When the system property {@code async-test.spin-barrier.enabled} is {@code true},
     * a lock-free {@link SpinContentionBarrier} is used, releasing all threads within a
     * sub-microsecond window to maximise collision density. Otherwise the default
     * {@link CyclicBarrier} is used for compatibility with virtual-thread schedulers that
     * may not benefit from busy-spinning.
     */
    private static ContentionBarrier createBarrier(int threads) {
        if (Boolean.getBoolean("async-test.spin-barrier.enabled")) {
            SpinContentionBarrier spin = new SpinContentionBarrier(threads);
            return spin::await;
        }
        CyclicBarrier cyclic = new CyclicBarrier(threads);
        return cyclic::await;
    }

    @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_BAD_PRACTICE")
    private static void runSingleInvocationRound(ReflectiveInvocationContext<Method> context,
                                                 int threads,
                                                 ExecutorService executor,
                                                 Phase1DetectorSet phase1,
                                                 AsyncTestContext phase2Context,
                                                 long roundTimeoutMs,
                                                 Method method) throws Throwable {

        ContentionBarrier barrier = createBarrier(threads);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(threads);

        Object target = context.getTarget().orElse(null);
        Object[] args = context.getArguments().toArray();

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                // latch.countDown() MUST always run, regardless of any failure in
                // install / test body / uninstall / snapshot. Without this guarantee
                // the runner blocks on latch.await() until timeoutMs, turning any
                // bug in worker cleanup into a fake "deadlock detected" report.
                boolean installed = false;
                try {
                    AsyncTestContext.install(phase2Context);
                    installed = true;
                    try {
                        barrier.arrive();
                        Object result = method.invoke(target, args);
                        // Async test body support: if the method returns a CompletionStage,
                        // wait for it to complete (or fail) before counting this worker done.
                        // Without this, the test would "succeed" the instant invoke() returned,
                        // long before the async work finished — defeating the whole point of
                        // running stress tests on async code.
                        if (result instanceof CompletionStage<?> stage) {
                            stage.toCompletableFuture()
                                 .get(roundTimeoutMs, TimeUnit.MILLISECONDS);
                        }
                    } catch (Throwable ex) {
                        failures.add(unwrap(ex));
                    }
                } catch (Throwable installErr) {
                    failures.add(installErr);
                } finally {
                    // Symmetry rule (CLAUDE.md): only uninstall if install succeeded.
                    // Each cleanup step is independently guarded so one failure can't
                    // suppress the next.
                    if (installed) {
                        try {
                            AsyncTestContext.uninstall();
                        } catch (Throwable uninstallErr) {
                            failures.add(uninstallErr);
                        }
                    }
                    if (phase1.livelock != null) {
                        try {
                            phase1.livelock.captureSnapshot();
                        } catch (Throwable snapErr) {
                            // Diagnostic-only path; never fail the test on this.
                            log.warn("Livelock snapshot failed: {}", snapErr.getMessage());
                        }
                    }
                    latch.countDown();
                }
            });
        }

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
        combined.initCause(failures.get(0));
        for (int i = 1; i < failures.size(); i++) {
            combined.addSuppressed(failures.get(i));
        }
        return combined;
    }

    private static Throwable unwrap(Throwable t) {
        if (t instanceof InvocationTargetException && t.getCause() != null) {
            return t.getCause();
        }
        // CompletableFuture.get(...) wraps async-body failures in ExecutionException.
        // Strip that layer so user assertions surface intact.
        if (t instanceof ExecutionException && t.getCause() != null) {
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
                                               Phase1DetectorSet phase1,
                                               AsyncTestContext phase2Context,
                                               boolean detectDeadlocks) {
        AsyncTestListenerRegistry.fireTimeout(timeoutMs);
        if (detectDeadlocks) {
            DeadlockDetector.printThreadDump();
            DeadlockDetector.printLearningAndFix();
        }
        phase1.printReports();
        printPhase2Reports(phase2Context);
        AssertionError error = new AssertionError(
            "Test timed out after " + timeoutMs + "ms. Possible deadlock, starvation, or visibility issue.");
        if (cause != null) {
            error.initCause(cause);
        }
        return error;
    }

    private static void printPhase2Reports(AsyncTestContext ctx) {
        for (String report : ctx.analyzeAll()) {
            System.err.println("\n" + report);
            // Extract detector name from report (first line before newline)
            String detectorName = extractDetectorName(report);
            AsyncTestListenerRegistry.fireDetectorReport(detectorName, report);
        }
    }

    private static String extractDetectorName(String report) {
        // Report format is typically "DetectorName: ..." or starts with detector name
        int newlineIdx = report.indexOf('\n');
        if (newlineIdx > 0) {
            String firstLine = report.substring(0, newlineIdx);
            int colonIdx = firstLine.indexOf(':');
            if (colonIdx > 0) {
                return firstLine.substring(0, colonIdx).trim();
            }
            return firstLine.trim();
        }
        return report.trim();
    }

    // ---- Per-invocation lifecycle helpers ----

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
