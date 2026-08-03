package se.deversity.asynctest.runner;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AILoadBearing;
import se.deversity.vibetags.annotations.AIThreadSafe;
import se.deversity.asynctest.AfterEachInvocation;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.AsyncTestListenerRegistry;
import se.deversity.asynctest.BeforeEachInvocation;
import se.deversity.asynctest.benchmark.BenchmarkRecorder;
import se.deversity.asynctest.diagnostics.AtomicityValidator;
import se.deversity.asynctest.diagnostics.DeadlockDetector;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.diagnostics.MemoryModelValidator;
import se.deversity.asynctest.diagnostics.Phase1DetectorSet;
import se.deversity.asynctest.diagnostics.VirtualThreadStressConfig;
import se.deversity.asynctest.report.Baseline;
import se.deversity.asynctest.telemetry.TelemetryBridge;
import se.deversity.asynctest.telemetry.TelemetryRegistry;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    /** See {@link #resolveTimeoutMultiplier()}. */
    private static final String TIMEOUT_MULTIPLIER_PROPERTY = "async-test.timeout.multiplier";

    /** See {@link #resolveTimeoutMultiplier()}. */
    private static final String TIMEOUT_MULTIPLIER_ENV_VAR = "ASYNC_TEST_TIMEOUT_MULTIPLIER";

    /**
     * Runs {@code testMethod} N×M times (see the class Javadoc), scaling
     * {@link AsyncTestConfig#timeoutMs} by {@link #resolveTimeoutMultiplier()} before it
     * becomes the effective budget for the overall deadline, each round's timeout, and —
     * transitively, since both are derived from the round timeout — the {@code CyclicBarrier}
     * await and the async-body {@code CompletionStage} wait. See
     * {@link #resolveTimeoutMultiplier()} for the CI-scaling mechanism itself.
     *
     * @param invocationContext the invocation context
     * @param config the config
     */
    @AILoadBearing(
        invariant = "The timeoutAlreadyReported flag, and the per-step guarded cleanup in the "
                  + "finally block, are both deliberate. A pre-round deadline check throws an "
                  + "error that has already been through timeoutError(), and each cleanup step is "
                  + "wrapped in its own try so one failure cannot suppress the next.",
        breaksIf = "The flag is removed as redundant — the catch block then sends the same error "
                 + "through timeoutError() a second time, producing two onTimeout callbacks and "
                 + "two copies of every report for one timeout. Or the cleanup steps are merged "
                 + "into one try, at which point a failing AsyncTestContext.uninstall() skips the "
                 + "livelock snapshot and leaks context into the next test."
    )
    public static void execute(ReflectiveInvocationContext<Method> invocationContext,
                               AsyncTestConfig config) throws Throwable {

        // Process-wide license check — cached by config fingerprint, so this is
        // a ConcurrentHashMap hit after the first invocation per JVM.
        LicenseGuard.check(config);

        // Determine actual thread count (stress mode overrides threads param). Computed
        // before the benchmark recorder so the recorder can label baselines with the
        // thread count actually used, not the possibly-overridden config.threads.
        final int actualThreads;
        if (config.virtualThreadStressMode != null && !"OFF".equals(config.virtualThreadStressMode)) {
            actualThreads = VirtualThreadStressConfig.StressLevel
                .valueOf(config.virtualThreadStressMode).threadCount;
        } else {
            actualThreads = config.threads;
        }

        // Benchmarking setup
        BenchmarkRecorder benchmarkRecorder = null;
        if (config.enableBenchmarking) {
            Object testInstance = invocationContext.getTarget().orElse(null);
            String testClass = testInstance != null ? testInstance.getClass().getName() : "unknown";
            String testMethod = invocationContext.getExecutable().getName();
            benchmarkRecorder = new BenchmarkRecorder(config, testClass, testMethod, actualThreads);
        }

        // Phase 2 context — shared across all threads for this test run
        AsyncTestContext phase2Context = new AsyncTestContext(config);

        // Memoizes phase2Context.analyzeAll() so it runs at most once per execute(),
        // regardless of how many report/gate call sites need the result — see the
        // Phase2Analysis Javadoc.
        Phase2Analysis phase2Analysis = new Phase2Analysis(phase2Context);

        // Phase 1 + Phase 3 detectors — grouped in a value-holder to avoid
        // long parameter lists in helper methods. Passing phase2Context lets these
        // detectors reuse phase2Context's DetectorRegistry-backed instances instead of
        // constructing disconnected duplicates (see Phase1DetectorSet.from javadoc).
        Phase1DetectorSet phase1 = Phase1DetectorSet.from(config, phase2Context);

        // Attach the agent's only consumer for the duration of this run.
        //
        // AsyncTestAgent weaves accessors and publishes every intercepted access to the
        // telemetry ring buffer; TelemetryRegistry drains that buffer to whatever callback is
        // registered, and TelemetryBridge is the callback that forwards events into this run's
        // AtomicityValidator. Nothing registered it, so premain's no-argument start() left the
        // callback null and the drain handed every event to a discard lambda: with the agent
        // attached, captured accesses were thrown away and no detector ever saw one.
        //
        // The filter is a live set rather than a snapshot because the workers do not exist
        // yet — the executor creates them per round, and under virtual threads each round
        // brings new ones — so each worker adds its own id as it starts.
        //
        // Gated on isRunning() so this costs nothing when the agent is absent: activate()
        // would otherwise start the drain thread for a pipeline with no producer. The
        // registry holds one callback, so two @AsyncTest runs executing concurrently in one
        // JVM would take it from each other; the per-run filter means the loser under-reports
        // rather than mis-attributing another run's threads.
        AtomicityValidator telemetryTarget = phase2Context.sharedAtomicityValidator();
        @Nullable Set<Long> workerThreadIds =
                (telemetryTarget != null && TelemetryRegistry.isRunning())
                        ? ConcurrentHashMap.newKeySet()
                        : null;
        TelemetryBridge telemetryBridge = null;
        if (telemetryTarget != null && workerThreadIds != null) {
            telemetryBridge = TelemetryBridge.activateWithFilter(telemetryTarget, workerThreadIds::contains);
        }

        // Validate JMM on the test framework itself
        MemoryModelValidator jmmValidator = new MemoryModelValidator();
        MemoryModelValidator.ValidationResult jmmResult = jmmValidator.validate();
        if (!jmmResult.isValid()) {
            log.warn("JMM validation of test framework failed: {}", jmmResult);
        } else {
            log.debug("runner.jmm ok=true");
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

        // Single choke point where config.timeoutMs becomes the effective budget: every
        // downstream timing value (deadlineNanos below, each round's remainingMs, the
        // CyclicBarrier await in createBarrier, and the CompletionStage wait in
        // runSingleInvocationRound) is derived from effectiveTimeoutMs, never from
        // config.timeoutMs directly, so scaling happens exactly once per execute() call.
        double timeoutMultiplier = resolveTimeoutMultiplier();
        long effectiveTimeoutMs = (timeoutMultiplier == 1.0)
            ? config.timeoutMs
            : Math.max(1L, Math.round(config.timeoutMs * timeoutMultiplier));

        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(effectiveTimeoutMs);

        // One event carrying every value that decides how this test runs. When a test behaves
        // differently on CI than locally, this line is the difference: the multiplier, the
        // thread count actually used (stress mode overrides the annotation) and whether the
        // executor is virtual are all resolved here and nowhere else.
        if (log.isDebugEnabled()) {
            log.debug("runner.config test={} threads={} invocations={} timeoutMs={} "
                    + "multiplier={} effectiveTimeoutMs={} virtualThreads={} stressMode={} "
                    + "replaySeed={} benchmarking={}",
                testMethod.getName(), actualThreads, config.invocations, config.timeoutMs,
                timeoutMultiplier, effectiveTimeoutMs, config.useVirtualThreads,
                config.virtualThreadStressMode, config.replaySeed, config.enableBenchmarking);
        }

        // Replay-seed source: explicit @AsyncTest(replaySeed=N) makes every
        // round use N; default 0 generates a fresh seed per round so failures
        // are reproducible after the runner logs the value.
        java.util.random.RandomGenerator seedSource = (config.replaySeed != 0L)
                ? null
                : new java.util.Random();
        long currentSeed = 0L;

        // Set by the pre-round deadline check below, read by the catch block: the error it
        // throws has already been through timeoutError() — listeners notified, thread dump
        // printed, Phase 1 and Phase 2 reports flushed. Its message then satisfies
        // isTimeoutLike, so without this flag the catch sent it through timeoutError a
        // second time and one timeout produced two onTimeout callbacks and two copies of
        // every report. (Phase2Analysis already memoized the analysis itself; nothing
        // around it was memoized.)
        boolean timeoutAlreadyReported = false;

        try {
            for (int i = 0; i < config.invocations; i++) {
                long remainingMs = remainingMillis(deadlineNanos);
                if (remainingMs <= 0) {
                    timeoutAlreadyReported = true;
                    throw timeoutError(effectiveTimeoutMs, null, phase1, phase2Analysis,
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
                log.debug("runner.round.start test={} round={} seed={} remainingMs={}",
                    testMethod.getName(), i, currentSeed, remainingMs);
                invokeLifecycleMethods(testInstance, beforeInvocationMethods);
                try {
                    runSingleInvocationRound(invocationContext, actualThreads,
                        executor, phase1, phase2Context, remainingMs, workerThreadIds, testMethod);
                } finally {
                    invokeLifecycleMethods(testInstance, afterInvocationMethods);
                }
                long roundDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - roundStartNanos);
                log.debug("runner.round.done test={} round={} seed={} durationMs={}",
                    testMethod.getName(), i, currentSeed, roundDurationMs);
                AsyncTestListenerRegistry.fireInvocationCompleted(i, roundDurationMs);

                if (benchmarkRecorder != null) {
                    benchmarkRecorder.recordInvocationEnd(benchmarkStart);
                }
            }
        } catch (AssertionError e) {
            if (timeoutAlreadyReported) {
                // Thrown by the pre-round deadline check above, which already went through
                // timeoutError(): re-reporting it here would duplicate the onTimeout event,
                // the thread dump and every detector report for a single timeout.
                throw e;
            }
            if (isTimeoutLike(e)) {
                throw timeoutError(effectiveTimeoutMs, e, phase1, phase2Analysis,
                        config.detectDeadlocks);
            }
            // Surface the seed of the failing round so the user can reproduce by
            // pasting it into @AsyncTest(replaySeed=N).
            System.err.println("[AsyncTest] Failure with replaySeed=" + currentSeed
                    + "L — paste into @AsyncTest(replaySeed=...) to reproduce.");
            AsyncTestListenerRegistry.fireTestFailed(e);
            phase1.printReports();
            // Report-only: the failOn gate (analyzeAndGate, below) intentionally runs
            // only on the success path — a test that already failed doesn't need a
            // second, synthetic failure from detector findings on top of its own.
            printPhase2Reports(phase2Analysis);
            throw e;
        } catch (Throwable t) {
            System.err.println("[AsyncTest] Failure with replaySeed=" + currentSeed
                    + "L — paste into @AsyncTest(replaySeed=...) to reproduce.");
            AsyncTestListenerRegistry.fireTestFailed(t);
            phase1.printReports();
            // Report-only — see the comment in the AssertionError branch above.
            printPhase2Reports(phase2Analysis);
            throw unwrap(t);
        } finally {
            executor.shutdownNow();
            try {
                executor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Drain, then detach — in that order, and after the executor is down.
            //
            // close() clears the callback, so anything still sitting in the ring buffer at
            // that point is discarded on the next drain. analyzeAndGate() runs after this
            // finally block, so detaching before flushing threw away the accesses from the
            // last round of every passing test. Shutting the executor down first means there
            // are no producers left to publish behind the flush.
            //
            // Each step is guarded on its own, matching the rule the worker cleanup follows:
            // a failure in one must not skip the next.
            if (telemetryBridge != null) {
                try {
                    TelemetryRegistry.flush();
                } catch (RuntimeException e) {
                    log.warn("Telemetry flush failed: {}", e.toString(), e);
                }
                try {
                    telemetryBridge.close();
                } catch (RuntimeException e) {
                    log.warn("Telemetry bridge detach failed: {}", e.toString(), e);
                }
            }

            if (benchmarkRecorder != null) {
                try {
                    benchmarkRecorder.complete();
                } catch (Exception e) {
                    log.warn("Benchmark completion failed: {}", e.toString(), e);
                }
            }
        }

        // Success path only (the catch blocks above rethrow): analyze detectors,
        // surface findings to stderr and listeners, and apply the failOn gate.
        // Runs on the caller thread after all workers finished and the executor
        // shut down — no shared mutable state is touched concurrently. The failOn
        // gate is intentionally success-path-only; failure/timeout paths above are
        // report-only (see the comments in the catch blocks and in timeoutError).
        analyzeAndGate(config, testMethod, phase1, phase2Analysis);
    }

    /**
     * Resolves the CI timeout-scaling multiplier applied to {@link AsyncTestConfig#timeoutMs}
     * for the current {@link #execute} call.
     *
     * <p><strong>Motivation:</strong> {@code timeoutMs} is a fixed per-test budget, but the
     * clock in {@link #execute} starts before {@code detectAll}'s ~120 detectors finish their
     * setup — overhead that is negligible on a fast, dedicated runner but can consume a
     * meaningful slice of a short (3-5s) budget on a slow or oversubscribed shared runner
     * (e.g. a 3-core macOS CI runner), producing a timeout that reflects runner speed rather
     * than a real concurrency bug. Rather than bumping every {@code @AsyncTest(timeoutMs=...)}
     * annotation across the suite, CI can scale every budget globally with one knob.
     *
     * <p><strong>Resolution order</strong> (first present wins):
     * <ol>
     *   <li>System property {@code async-test.timeout.multiplier} — set per-invocation via
     *       {@code -Dasync-test.timeout.multiplier=3}.</li>
     *   <li>Environment variable {@code ASYNC_TEST_TIMEOUT_MULTIPLIER} — unlike a system
     *       property passed on the {@code mvn} command line, an environment variable is
     *       automatically inherited by Surefire's forked test JVMs, which is why CI sets
     *       this one rather than {@code -D}.</li>
     *   <li>{@code 1.0} — identical to pre-multiplier behavior.</li>
     * </ol>
     *
     * <p>Parsing is defensive: a missing, blank, non-numeric, zero, or negative value falls
     * back to {@code 1.0} rather than throwing, so a CI misconfiguration degrades to today's
     * behavior instead of failing every test.
     *
     * <p>Resolved fresh on every {@link #execute} call — deliberately not cached in a
     * {@code static final} — so the property/env var can still be changed between test runs
     * within the same JVM (as the accompanying unit tests do).
     */
    private static double resolveTimeoutMultiplier() {
        String raw = System.getProperty(TIMEOUT_MULTIPLIER_PROPERTY);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(TIMEOUT_MULTIPLIER_ENV_VAR);
        }
        if (raw == null || raw.isBlank()) {
            return 1.0;
        }
        try {
            double parsed = Double.parseDouble(raw.trim());
            return parsed > 0.0 ? parsed : 1.0;
        } catch (NumberFormatException e) {
            return 1.0;
        }
    }

    /**
     * Post-run detector analysis for tests whose body passed.
     *
     * <p>Before 1.7.0 detector findings were only reported when the test body had
     * already failed or timed out; a passing test never surfaced findings. This
     * method closes that gap: every enabled detector is analyzed, findings are
     * printed and fired to listeners, and findings at or above
     * {@link AsyncTestConfig#failOn} fail the test — unless suppressed by the
     * baseline file ({@code -Dasync-test.baseline=<path>}) or recorded to it in
     * update mode ({@code -Dasync-test.baseline.update=true}).
     *
     * <p>Only called on the success path (see {@link #execute}); failure/timeout paths
     * call {@link #printPhase2Reports} directly and never reach the failOn gate below.
     */
    private static void analyzeAndGate(AsyncTestConfig config,
                                       Method testMethod,
                                       Phase1DetectorSet phase1,
                                       Phase2Analysis phase2Analysis) {
        Map<String, String> reports = new LinkedHashMap<>(phase1.collectReports());
        for (Map.Entry<String, String> finding : phase2Analysis.get().entrySet()) {
            reports.putIfAbsent(finding.getKey(), "\n" + finding.getValue());
        }
        if (reports.isEmpty()) {
            return;
        }

        String testId = testMethod.getDeclaringClass().getName() + "#" + testMethod.getName();
        Baseline baseline = Baseline.fromSystemProperties();

        int suppressed = 0;
        List<String> failing = new ArrayList<>();
        for (Map.Entry<String, String> e : reports.entrySet()) {
            if (baseline.contains(testId, e.getKey())) {
                suppressed++;
                continue;
            }
            System.err.println(e.getValue());
            AsyncTestListenerRegistry.fireDetectorReport(e.getKey(), e.getValue());
            if (config.failOn.triggeredBy(IssueSeverity.fromReport(e.getValue()))) {
                failing.add(e.getKey());
            }
        }
        if (suppressed > 0) {
            log.info("[AsyncTest] {} baselined finding(s) suppressed for {}", suppressed, testId);
        }
        if (failing.isEmpty()) {
            return;
        }

        if (Baseline.updateMode()) {
            int added = Baseline.record(testId, failing);
            log.warn("[AsyncTest] Baseline update mode: recorded {} finding(s) for {} instead of failing",
                    added, testId);
            return;
        }

        AssertionError error = new AssertionError(
            "[AsyncTest] " + failing.size() + " detector finding(s) at or above failOn=" + config.failOn
            + " for " + testId + ": " + String.join(", ", failing)
            + ". Full reports above. To accept known findings, run with -D" + Baseline.PATH_PROPERTY
            + "=<file> (add -D" + Baseline.UPDATE_PROPERTY + "=true once to record them).");
        AsyncTestListenerRegistry.fireTestFailed(error);
        throw error;
    }

    /**
     * Abstraction over CyclicBarrier and SpinContentionBarrier.
     * Lets runSingleInvocationRound stay barrier-implementation-agnostic.
     *
     * <p>Declares the union of checked exceptions from both implementations:
     * {@link InterruptedException} (both), {@link BrokenBarrierException}
     * (CyclicBarrier only — SpinContentionBarrier never throws it) and
     * {@link TimeoutException} (the CyclicBarrier path only — see
     * {@link #createBarrier}; SpinContentionBarrier has no timed {@code await}
     * overload, so that path never throws it, but the interface must still declare it
     * for the CyclicBarrier lambda to type-check).
     */
    @FunctionalInterface
    private interface ContentionBarrier {
        void arrive() throws InterruptedException, BrokenBarrierException, TimeoutException;
    }

    /**
     * Creates a {@link ContentionBarrier} for {@code threads} participants.
     *
     * <p>When the system property {@code async-test.spin-barrier.enabled} is {@code true},
     * a lock-free {@link SpinContentionBarrier} is used, releasing all threads within a
     * sub-microsecond window to maximise collision density. Otherwise the default
     * {@link CyclicBarrier} is used for compatibility with virtual-thread schedulers that
     * may not benefit from busy-spinning.
     *
     * <p>The {@link CyclicBarrier} path waits with {@code timeoutMs} (the full round
     * timeout — generous enough that a healthy round, where all {@code threads}
     * participants arrive promptly, never trips it) rather than the bare no-arg
     * {@code await()}. Without this, a worker that dies (or throws) before ever calling
     * {@code arrive()} — e.g. {@code AsyncTestContext.install} failing — leaves every
     * other worker parked at the barrier forever: {@code CyclicBarrier} has no way to
     * notice a party will never show up, so only the outer {@code latch.await} timeout
     * and the final {@code executor.shutdownNow()} would eventually reclaim them. With
     * the timed {@code await}, the first stranded peer to hit {@code timeoutMs} breaks
     * the barrier for all the others too (they each get {@link BrokenBarrierException}
     * immediately rather than waiting out their own timeout), so the round fails fast
     * with a clear diagnosis instead of hanging silently.
     *
     * <p>{@link SpinContentionBarrier} has no timed {@code await} overload to mirror
     * this with (see its class Javadoc — it's a hand-tuned lock-free spin, not
     * something to graft a deadline onto here). Its periodic
     * {@link Thread#interrupted()} check still responds promptly to the
     * {@code Future.cancel(true)} calls added to {@code runSingleInvocationRound} for
     * the same scenario, so stranded spin-barrier workers are still reclaimed at the
     * round-timeout boundary rather than only at the final {@code shutdownNow()}.
     */
    private static ContentionBarrier createBarrier(int threads, long timeoutMs) {
        if (Boolean.getBoolean("async-test.spin-barrier.enabled")) {
            SpinContentionBarrier spin = new SpinContentionBarrier(threads);
            return spin::await;
        }
        CyclicBarrier cyclic = new CyclicBarrier(threads);
        return () -> cyclic.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    private static void runSingleInvocationRound(ReflectiveInvocationContext<Method> context,
                                                 int threads,
                                                 ExecutorService executor,
                                                 Phase1DetectorSet phase1,
                                                 AsyncTestContext phase2Context,
                                                 long roundTimeoutMs,
                                                 @Nullable Set<Long> workerThreadIds,
                                                 Method method) throws Throwable {

        ContentionBarrier barrier = createBarrier(threads, roundTimeoutMs);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(threads);

        Object target = context.getTarget().orElse(null);
        Object[] args = context.getArguments().toArray();

        // Futures are retained (not ignored) so a round timeout below can cancel(true)
        // any worker still stuck, instead of leaving it to linger until execute()'s
        // outer executor.shutdownNow(). Worker completion itself is still tracked via
        // the CountDownLatch; exceptions are still caught inside the lambda into
        // `failures`, so nothing here changes normal (non-timeout) behavior.
        List<Future<?>> workerFutures = new ArrayList<>(threads);
        for (int t = 0; t < threads; t++) {
            workerFutures.add(executor.submit(() -> {
                // latch.countDown() MUST always run, regardless of any failure in
                // install / test body / uninstall / snapshot. Without this guarantee
                // the runner blocks on latch.await() until timeoutMs, turning any
                // bug in worker cleanup into a fake "deadlock detected" report.
                // Join the set the telemetry bridge filters on, so accesses this worker makes
                // are attributed to this run. Done first: a failure below still leaves the id
                // registered, which only ever means one extra id in a set that dies with the run.
                if (workerThreadIds != null) {
                    workerThreadIds.add(Thread.currentThread().threadId());
                }

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
                            log.warn("Livelock snapshot failed: {}", snapErr.toString(), snapErr);
                        }
                    }
                    latch.countDown();
                }
            }));
        }

        boolean completed = latch.await(roundTimeoutMs, TimeUnit.MILLISECONDS);
        if (!completed) {
            // Interrupt any worker still stuck (e.g. blocked on a now-broken barrier, or
            // never reached it at all) instead of leaving it to run until execute()'s
            // outer executor.shutdownNow(). cancel(true) on an already-finished future
            // is a documented no-op, so this is safe regardless of how many of the
            // `threads` workers are actually still outstanding.
            for (Future<?> future : workerFutures) {
                future.cancel(true);
            }
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

    /**
     * Reports a timeout exactly once — listeners notified, thread dump printed, Phase 1 and
     * Phase 2 reports flushed — and returns the {@link AssertionError} to throw.
     *
     * <p>Callers that throw the returned error from inside {@link #execute}'s try block must
     * set {@code timeoutAlreadyReported} first: the message ("Test timed out after …")
     * satisfies {@link #isTimeoutLike}, so the enclosing {@code catch (AssertionError)} would
     * otherwise route it through here a second time.
     */
    private static AssertionError timeoutError(long timeoutMs,
                                               @Nullable Throwable cause,
                                               Phase1DetectorSet phase1,
                                               Phase2Analysis phase2Analysis,
                                               boolean detectDeadlocks) {
        AsyncTestListenerRegistry.fireTimeout(timeoutMs);
        if (detectDeadlocks) {
            DeadlockDetector.printThreadDump();
            DeadlockDetector.printLearningAndFix();
        }
        phase1.printReports();
        // Report-only: this is a failure/timeout path, so the failOn gate in
        // analyzeAndGate (success-path-only) is never reached for this run.
        printPhase2Reports(phase2Analysis);
        AssertionError error = new AssertionError(
            "Test timed out after " + timeoutMs + "ms. Possible deadlock, starvation, or visibility issue.");
        if (cause != null) {
            error.initCause(cause);
        }
        return error;
    }

    private static void printPhase2Reports(Phase2Analysis phase2Analysis) {
        for (Map.Entry<String, String> finding : phase2Analysis.get().entrySet()) {
            System.err.println("\n" + finding.getValue());
            AsyncTestListenerRegistry.fireDetectorReport(finding.getKey(), finding.getValue());
        }
    }

    /**
     * Memoizes {@link AsyncTestContext#analyzeAll()} so it runs at most once per
     * {@link #execute}, no matter how many of the report/gate call sites
     * ({@link #printPhase2Reports}, {@link #timeoutError}, {@link #analyzeAndGate})
     * need the result.
     *
     * <p>{@code analyzeAll} is not guaranteed idempotent for stateful legacy detectors —
     * some accumulate state (deduplicators, violation lists) across calls rather than
     * computing purely from immutable snapshots — so calling it twice in the same run
     * risked double-counting or diverging findings between the two callers. Before this
     * cache existed, the pre-round deadline check in {@link #execute} could trigger
     * exactly that: it throws via {@link #timeoutError} (one {@code analyzeAll} call),
     * and since the resulting {@link AssertionError}'s message satisfies
     * {@link #isTimeoutLike}, the surrounding {@code catch (AssertionError e)} block
     * re-wrapped it via a second {@code timeoutError} call — running {@code analyzeAll}
     * a second time for the same test run.
     *
     * <p>Not synchronized: {@link #execute} only ever calls {@code get()} from the
     * single caller thread, after all worker threads for the current round have
     * finished (see the class Javadoc on {@link AsyncTestContext#analyzeAll()}).
     */
    private static final class Phase2Analysis {
        private final AsyncTestContext ctx;
        private @Nullable Map<String, String> reports;

        Phase2Analysis(AsyncTestContext ctx) {
            this.ctx = ctx;
        }

        /** {@return the findings of this run, keyed by the detector that produced each} */
        Map<String, String> get() {
            Map<String, String> memo = reports;
            if (memo == null) {
                // Last chance for agent-captured accesses still in the ring buffer to reach
                // the detectors. The periodic drain runs every millisecond, so without this the
                // final round could contribute events or not depending on timing. No-op when
                // the registry is not running, i.e. whenever the agent is not attached.
                TelemetryRegistry.flush();
                memo = ctx.analyzeAllNamed();
                reports = memo;
            }
            return memo;
        }
    }

    // ---- Per-invocation lifecycle helpers ----

    private static <A extends java.lang.annotation.Annotation> List<Method> findLifecycleMethods(
            @Nullable Object target, Class<A> annotationType) {
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

    private static void invokeLifecycleMethods(@Nullable Object target, List<Method> methods) {
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
