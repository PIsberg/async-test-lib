package se.deversity.asynctest.runner;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AILoadBearing;
import se.deversity.vibetags.annotations.AIThreadSafe;
import se.deversity.asynctest.AfterEachInvocation;
import se.deversity.asynctest.AgentThreadHooks;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.BodyThreadFailures;
import se.deversity.asynctest.AsyncTestListenerRegistry;
import se.deversity.asynctest.BeforeEachInvocation;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.Preset;
import se.deversity.asynctest.benchmark.BenchmarkRecorder;
import se.deversity.asynctest.benchmark.BenchmarkRegressionException;
import se.deversity.asynctest.diagnostics.AtomicityValidator;
import se.deversity.asynctest.diagnostics.DeadlockDetector;
import se.deversity.asynctest.diagnostics.DetectorDefaultSeverity;
import se.deversity.asynctest.diagnostics.DetectorTrust;
import se.deversity.asynctest.diagnostics.ExecutorShutdownDetector;
import se.deversity.asynctest.diagnostics.GradedFindings;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.diagnostics.JfrPinningStream;
import se.deversity.asynctest.diagnostics.MemoryModelValidator;
import se.deversity.asynctest.diagnostics.Phase1DetectorSet;
import se.deversity.asynctest.diagnostics.TrustTier;
import se.deversity.asynctest.diagnostics.VirtualThreadPinningDetector;
import se.deversity.asynctest.diagnostics.VirtualThreadStressConfig;
import se.deversity.asynctest.report.Baseline;
import se.deversity.asynctest.telemetry.ControlledSchedule;
import se.deversity.asynctest.telemetry.TelemetryBridge;
import se.deversity.asynctest.telemetry.TelemetryRegistry;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
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
import java.util.concurrent.atomic.AtomicInteger;

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
 *
 * Core execution engine for {@code @AsyncTest} methods.
 * 
 * <p>This runner executes the test method multiple times concurrently, using a 
 * {@link java.util.concurrent.CyclicBarrier} to maximize thread contention and 
 * expose race conditions. It also integrates with the detector ecosystem to monitor 
 * for deadlocks, livelocks, visibility issues, and more.
 * 
 * @since 1.0.0
 */
@AICore(
    sensitivity = "Critical",
    note = "Core stress-test execution engine. The CyclicBarrier pattern forces maximum thread contention. Timeout logic and AsyncTestContext install/uninstall are carefully calibrated — subtle changes introduce flaky tests or missed detector activations."
)
@AIAudit(checkFor = {"Thread Safety issues", "Resource Leaks"})
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "Coordinates concurrency using CyclicBarrier to maximize thread contention.")
public class ConcurrencyRunner {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyRunner.class);

    /** Set by {@link #executeBeforeTeardown} for the length of its {@link #execute} call. */
    private static final ThreadLocal<Boolean> TEARDOWN_FOLLOWS = new ThreadLocal<>();

    /** Set by {@link #executeForCodeIn} for the length of its {@link #execute} call. */
    private static final ThreadLocal<Class<?>> CODE_UNDER_TEST = new ThreadLocal<>();

    /** Set by {@link #executeForCodeIn(ReflectiveInvocationContext, AsyncTestConfig, Class, String)} for the length of its {@link #execute} call. */
    private static final ThreadLocal<String> DISPLAY_NAME = new ThreadLocal<>();

    private static String displayNameOf(Method testMethod) {
        String base = DISPLAY_NAME.get();
        return base != null && !base.isBlank() ? base : testMethod.getName();
    }

    /**
     * The check {@link #afterTeardown} makes once the test's {@code @AfterEach} methods have run,
     * left on the test thread by the {@link #execute} call before it (#996).
     */
    private static final ThreadLocal<PendingTeardown> PENDING_TEARDOWN = new ThreadLocal<>();

    /**
     * What {@link #afterTeardown} needs: the run's executor detector, holding its "never shut
     * down" verdicts, and whether the run reached the failOn gate, which only then applies to them.
     */
    private record PendingTeardown(ExecutorShutdownDetector detector, AsyncTestConfig config,
                                   Method testMethod, boolean gate) { }

    /**
     * One-shot latch for the {@code runner.agent.absent} INFO event in {@link #execute}.
     * The agent's absence is a JVM-global fact, so announcing it on every {@code @AsyncTest}
     * in a large suite would only add noise; announcing it never leaves the user believing
     * detectors observed accesses that nothing recorded. Package-visible so the log-contract
     * test can rearm it.
     */
    static final java.util.concurrent.atomic.AtomicBoolean AGENT_ABSENCE_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Said once per JVM, for the same reason as {@link #AGENT_ABSENCE_LOGGED}: a detector that
     * is enabled but cannot observe anything in this configuration.
     *
     * <p>A platform thread inherits its daemon flag from the thread that created it, and every
     * worker this runner hands a body to is daemon: virtual threads always are, and the platform
     * workers were made daemon so that a deadlocked one could not hold the JVM open (#479). So
     * every {@code new Thread(...)} started from a test body is already daemon before the body
     * can get it wrong, in either thread mode, and {@code DaemonThreadHygieneDetector} skips
     * exactly those. A user who switches the detector on and gets a clean report has learned
     * nothing, and has no way to tell that from a report meaning their code is fine.
     *
     * <p>What is left is worth naming in the hint, because it is the difference between a limit
     * and a dead detector: a {@code ThreadFactory} that sets the flag itself decides it whoever
     * calls it, and {@code Executors.defaultThreadFactory()}, the factory behind every JDK thread
     * pool, sets it to false. A leaked pool thread is still reported. See issues #352 and #479,
     * and {@code DaemonThreadHygieneObservabilityTest}, which pins both directions.
     *
     * <p>Package-visible so the log-contract test can rearm it.
     */
    static final java.util.concurrent.atomic.AtomicBoolean DAEMON_HYGIENE_INERT_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * One-shot latch for the {@code runner.selection.partial} INFO event in {@link #execute}
     * (#956). Since 1.13.0 a bare {@code @AsyncTest} runs the {@code ESSENTIALS} preset where
     * 1.12 ran every detector, and a test that passed before passes after with nothing saying
     * fewer detectors looked; {@code runner.config} says it, but only at DEBUG. Once per JVM,
     * for the same reason as {@link #AGENT_ABSENCE_LOGGED}: per test, a suite of bare
     * annotations would repeat it hundreds of times. Package-visible so the log-contract test
     * can rearm it.
     */
    static final java.util.concurrent.atomic.AtomicBoolean PARTIAL_SELECTION_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();


    /**
     * Latches the one-shot announcement that {@code DeadlockDetector} cannot see the runner's
     * own workers.
     *
     * <p>The detector asks {@code ThreadMXBean.findDeadlockedThreads()}, which reports platform
     * threads. The runner's workers are virtual threads by default, so a circular monitor wait
     * between them - which is the whole premise of {@code @AsyncTest}, eight threads colliding
     * on one subject - is not in the cycle JMX can close, and the report comes back clean.
     * Measured on {@code examples/06-deadlock}, whose subject deadlocks on a lock pair: silent
     * with the default runner, "CIRCULAR DEADLOCK DETECTED" with
     * {@code @AsyncTest(useVirtualThreads = false)}, same code both times.
     *
     * <p>Both flags default to on, so this is the configuration everybody runs. The thread dump
     * the timeout path prints is still printed and still shows the stuck threads; what is
     * missing is the finding, and with it the difference between "no deadlock" and "no deadlock
     * that this can see". Announced for the same reason as the daemon-hygiene case below.
     *
     * <p>Package-visible so the log-contract test can rearm it.
     */
    static final java.util.concurrent.atomic.AtomicBoolean DEADLOCK_INERT_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();


    /** See {@link #resolveTimeoutMultiplier()}. */
    private static final String TIMEOUT_MULTIPLIER_PROPERTY = "async-test.timeout.multiplier";

    /** See {@link #resolveTimeoutMultiplier()}. */
    private static final String TIMEOUT_MULTIPLIER_ENV_VAR = "ASYNC_TEST_TIMEOUT_MULTIPLIER";

    /** See {@link #resolveQuiesceGraceMillis()}. */
    private static final String QUIESCE_GRACE_PROPERTY = "async-test.quiesce.grace.ms";

    /**
     * {@code -Dasync-test.report.full=true} prints PROMPT and ADVISORY reports in full on a
     * passing run; by default each is one line (see {@link #analyzeAndGate}).
     */
    static final String FULL_REPORT_PROPERTY = "async-test.report.full";

    /** Default for {@link #resolveQuiesceGraceMillis()}. */
    private static final long DEFAULT_QUIESCE_GRACE_MS = 2_000L;

    /** Name prefix for worker threads — see the executor construction in {@link #execute}. */
    private static final String WORKER_THREAD_PREFIX = "async-test-worker-";

    /**
     * Numbers workers JVM-wide, so no two runs share a worker name (#861). A detector that
     * identifies a lock's holder by thread name otherwise reads another parallel run's live
     * {@code async-test-worker-0} as this run's finished one. A number, not a run id in the name,
     * because a baseline fingerprint masks digits and committed lines read
     * {@code async-test-worker-#}.
     */
    private static final java.util.concurrent.atomic.AtomicLong WORKER_SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * Runs {@code testMethod} N×M times (see the class Javadoc), scaling
     * {@link AsyncTestConfig#timeoutMs} by {@link #resolveTimeoutMultiplier()} before it
     * becomes the effective budget for the overall deadline, each round's timeout, and —
     * transitively, since both are derived from the round timeout — the {@code CyclicBarrier}
     * await and the async-body {@code CompletionStage} wait. See
     * {@link #resolveTimeoutMultiplier()} for the CI-scaling mechanism itself.
     *
     * @param invocationContext the JUnit invocation context carrying the test instance and method to run
     * @param config the resolved configuration deciding threads, rounds, timeout and detectors
     *
     * @throws Throwable the failure from the test body, unwrapped, or an {@link AssertionError}
     *     raised by the timeout path or the {@code failOn} gate, or a
     *     {@link se.deversity.asynctest.benchmark.BenchmarkRegressionException} when
     *     {@code failOnBenchmarkRegression} is set and the run regressed, thrown after every round
     *     passed and the {@code failOn} gate let the run through
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
                 + "latch.countDown() and the round waits out its timeout for nothing."
    )
    public static void execute(ReflectiveInvocationContext<Method> invocationContext,
                               AsyncTestConfig config) throws Throwable {

        // Process-wide license check — cached by config fingerprint, so this is
        // a ConcurrentHashMap hit after the first invocation per JVM.
        LicenseGuard.check(config);

        // Before the benchmark recorder, so its baselines carry the thread count actually used.
        final int actualThreads = threadCountFor(config);
        // A controlled schedule runs one worker at a time, so virtual workers buy it nothing, and
        // on JDK 21 a virtual worker the schedule parks inside synchronized pins its carrier: with
        // enough of them the worker granted the next turn has no carrier left to run on.
        final boolean virtualWorkers = config.useVirtualThreads && !config.controlledSchedule;

        @Nullable BenchmarkRecorder benchmarkRecorder = config.enableBenchmarking
                ? newBenchmarkRecorder(invocationContext, config, actualThreads)
                : null;
        @Nullable BenchmarkRegressionException benchmarkRegression = null;

        // Phase 2 context — shared across all threads for this test run
        AsyncTestContext phase2Context = new AsyncTestContext(config);

        // Memoizes phase2Context.analyzeAll() so it runs at most once per execute(),
        // regardless of how many report/gate call sites need the result — see the
        // Phase2Analysis Javadoc.
        Phase2Analysis phase2Analysis = new Phase2Analysis(phase2Context,
                invocationContext.getExecutable().getName());

        // Phase 1 + Phase 3 detectors — grouped in a value-holder to avoid
        // long parameter lists in helper methods. Passing phase2Context lets these
        // detectors reuse phase2Context's DetectorRegistry-backed instances instead of
        // constructing disconnected duplicates (see Phase1DetectorSet.from javadoc).
        Phase1DetectorSet phase1 = Phase1DetectorSet.from(config, phase2Context);

        // Attach the agent before isRunning() is consulted below, so a user with the agent artifact
        // on the test classpath (or -Dasynctest.agent=...) gets the pipeline running for this very
        // run rather than the next one. Self-attach retransforms already-loaded classes, so test
        // classes loaded before this point are still woven.
        @Nullable Class<?> codeUnderTest = CODE_UNDER_TEST.get();
        AgentAutoAttach.attachIfRequested(codeUnderTest != null ? codeUnderTest : invocationContext.getTargetClass());
        AtomicityValidator telemetryTarget = phase2Context.sharedAtomicityValidator();
        boolean telemetryRunning = telemetryTarget != null && TelemetryRegistry.isRunning();
        // The JVM's jdk.VirtualThreadPinned events are filtered by the same worker set, plus the
        // threads the workers started when the agent attributes them (#983). Only a virtual thread
        // can pin, so platform workers leave the feed off unless the agent is there to attribute
        // a virtual thread the body starts.
        @Nullable VirtualThreadPinningDetector pinningTarget =
                virtualWorkers || TelemetryRegistry.isRunning()
                        ? phase2Context.sharedVirtualThreadPinningDetector()
                        : null;
        // The deadlock detector reads the same set to tell this run's deadlock from one that forms
        // in another run in progress at the same time (#1385).
        @Nullable Set<Long> workerThreadIds = (telemetryRunning || pinningTarget != null || config.detectDeadlocks)
                ? ConcurrentHashMap.newKeySet()
                : null;

        announceBlindSpots(config, telemetryTarget != null, virtualWorkers,
                invocationContext.getExecutable().getName());

        validateMemoryModelIfRequested();

        ExecutorService executor = newWorkerExecutor(virtualWorkers, actualThreads);

        // setAccessible once per test, not once per invocation round
        Method testMethod = invocationContext.getExecutable();
        testMethod.setAccessible(true);

        // A pool the test class closes in @AfterEach is closed after analysis, so the "never shut
        // down" verdict waits for afterTeardown when JUnit will call it (#996). Left on this thread
        // ungated now, so a failed run still reports it, and gated once the run passes the gate.
        @Nullable ExecutorShutdownDetector heldShutdowns = Boolean.TRUE.equals(TEARDOWN_FOLLOWS.get())
                ? phase2Context.sharedExecutorShutdownDetector() : null;
        if (heldShutdowns != null) {
            heldShutdowns.holdUnclosedUntilTeardown();
            PENDING_TEARDOWN.set(new PendingTeardown(heldShutdowns, config, testMethod, false));
        }

        // Discover @BeforeEachInvocation / @AfterEachInvocation methods once
        Object testInstance = invocationContext.getTarget().orElse(null);
        List<Method> beforeInvocationMethods =
                findLifecycleMethods(testInstance, BeforeEachInvocation.class, true);
        List<Method> afterInvocationMethods =
                findLifecycleMethods(testInstance, AfterEachInvocation.class, false);

        // Single choke point where config.timeoutMs becomes the effective budget: every
        // downstream timing value (deadlineNanos below, each round's remainingMs, the
        // CyclicBarrier await in createBarrier, and the CompletionStage wait in
        // RoundRunner.invokeBody) is derived from effectiveTimeoutMs, never from
        // config.timeoutMs directly, so scaling happens exactly once per execute() call.
        double timeoutMultiplier = resolveTimeoutMultiplier();
        long effectiveTimeoutMs = scaledTimeoutMs(config.timeoutMs, timeoutMultiplier);

        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(effectiveTimeoutMs);

        narrateRunConfig(config, testMethod.getName(), actualThreads, virtualWorkers,
                timeoutMultiplier, effectiveTimeoutMs);

        // Replay-seed source: explicit @AsyncTest(replaySeed=N) makes every
        // round use N; default 0 generates a fresh seed per round so failures
        // are reproducible after the runner logs the value.
        java.util.random.RandomGenerator seedSource = (config.replaySeed != 0L)
                ? null
                : new java.util.Random();
        long currentSeed = 0L;
        // The last controlled round's schedule, read by the failure report: whether the printed
        // seed replays the round's interleaving depends on whether any worker escaped it.
        ControlledSchedule.@Nullable Summary lastSchedule = null;
        boolean unpointedLogged = false;

        RoundRunner rounds = new RoundRunner(invocationContext, testMethod, actualThreads, executor,
                phase1, phase2Context, virtualWorkers, workerThreadIds);

        // Set by the pre-round deadline check below, read by the catch block: the error it
        // throws has already been through timeoutError() — listeners notified, thread dump
        // printed, detector reports flushed. Its message then satisfies
        // isTimeoutLike, so without this flag the catch sent it through timeoutError a
        // second time and one timeout produced two onTimeout callbacks and two copies of
        // every report. (Phase2Analysis already memoized the analysis itself; nothing
        // around it was memoized.)
        boolean timeoutAlreadyReported = false;

        // How far the run got, for what a timeout says about it (#1386). Replaced at each round's
        // start; read on the timeout paths below.
        RunProgress progress = new RunProgress(0, config.invocations, effectiveTimeoutMs);

        // Opened immediately before the try whose finally closes it, so nothing can throw in
        // between and leave a JFR recording running for the life of the JVM. start() never
        // throws: without a usable JFR it returns null and the detector keeps its recorded feed.
        @Nullable JfrPinningStream pinningStream = (pinningTarget != null && workerThreadIds != null)
                ? JfrPinningStream.start(pinningTarget, workerThreadIds, phase2Context.spawnedThreadIds(),
                        testMethod.getName())
                : null;
        phase2Analysis.drainBeforeAnalysis(pinningStream);

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
        //
        // The callback slot is JVM-wide, so the bridge is activated immediately before the try
        // whose finally detaches it, like the stream above: nothing can throw in between and
        // leave it registered once this run is over.
        @Nullable TelemetryBridge telemetryBridge =
                (telemetryRunning && telemetryTarget != null && workerThreadIds != null)
                        ? TelemetryBridge.activateWithFilter(telemetryTarget, workerThreadIds::contains)
                        : null;

        // A thread the body starts that dies of an uncaught exception is the run's to report
        // (#1253). Process-wide state, so counted and restored in the finally below; installed
        // immediately before that try for the reason the stream above is opened there.
        BodyThreadFailures.install();
        // Whether another run overlapped this one decides whose a deadlock with no thread known to
        // be this run's is (#1385). Process-wide and counted like the handler above, so entered
        // right before the same try and exited in its finally.
        RunOverlap overlap = RunOverlap.enter();
        if (workerThreadIds != null) {
            phase2Context.attributeDeadlocks(workerThreadIds, overlap::overlapped);
        }
        try {
            for (int i = 0; i < config.invocations; i++) {
                long remainingMs = remainingMillis(deadlineNanos);
                progress = new RunProgress(i, config.invocations, remainingMs);
                if (remainingMs <= 0) {
                    timeoutAlreadyReported = true;
                    throw timeoutError(effectiveTimeoutMs, null, phase2Analysis,
                            config.detectDeadlocks, progress);
                }

                // Seed for this round — fixed from annotation, or freshly drawn.
                currentSeed = (seedSource != null) ? seedSource.nextLong() : config.replaySeed;
                phase2Context.setReplaySeedForRound(currentSeed);

                long benchmarkStart = 0;
                if (benchmarkRecorder != null) {
                    benchmarkStart = benchmarkRecorder.recordInvocationStart();
                }

                markRoundStart(phase1, phase2Context, pinningStream);
                // Before the workers exist, so every one of them sees this round's rendezvous.
                phase2Context.openRendezvousForRound(actualThreads, remainingMs);
                AsyncTestListenerRegistry.fireInvocationStarted(i, actualThreads);
                long roundStartNanos = System.nanoTime();
                log.debug("runner.round.start test={} round={} seed={} remainingMs={}",
                    testMethod.getName(), i, currentSeed, remainingMs);
                invokeLifecycleMethods(testInstance, beforeInvocationMethods);
                Throwable roundFailure = null;
                // Opened immediately before the try whose finally closes it, so nothing can throw
                // in between and leave the JVM-wide open count raised for every later hook call.
                @Nullable ControlledSchedule schedule = config.controlledSchedule
                    ? ControlledSchedule.open(actualThreads, currentSeed)
                    : null;
                try {
                    rounds.run(remainingMs, schedule);
                } catch (Throwable t) { // NOPMD - rethrown below; caught only to order teardown
                    roundFailure = t;
                } finally {
                    // First, so a worker of a timed-out round still waiting for a turn is released
                    // before the quiesce below waits for it to stop.
                    if (schedule != null) {
                        lastSchedule = closeSchedule(schedule, testMethod.getName(), i, currentSeed);
                        if (lastSchedule.steps() == 0 && !unpointedLogged) {
                            unpointedLogged = true;
                            announceUnpointedSchedule(testMethod.getName());
                        }
                    }
                    // Quiesce before teardown when the round failed. RoundRunner.run
                    // throws right after future.cancel(true), and cancellation only requests
                    // interruption: workers can still be unwinding inside the test body. Running
                    // after-hooks then would tear down state a live worker is still touching, and
                    // a hook that blocks on worker-held state would hang the runner thread with
                    // the deadline loop already exited and nothing left to bound it.
                    if (roundFailure != null) {
                        quiesceWorkers(executor, testMethod);
                    }
                    roundFailure = runAfterHooks(testInstance, afterInvocationMethods, roundFailure);
                }
                if (roundFailure != null) {
                    throw roundFailure;
                }
                long roundDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - roundStartNanos);
                log.debug("runner.round.done test={} round={} seed={} durationMs={}",
                    testMethod.getName(), i, currentSeed, roundDurationMs);
                AsyncTestListenerRegistry.fireInvocationCompleted(i, roundDurationMs);

                if (benchmarkRecorder != null) {
                    benchmarkRecorder.recordInvocationEnd(benchmarkStart);
                }
            }
            // A thread the body started without joining it can fail after its round was checked.
            // Give the ones the agent saw started a moment to end first, so one that fails just
            // after the last round is not missed (#1379).
            joinBriefly(phase2Context.spawnedThreadsAlive());
            List<Throwable> late = new ArrayList<>(phase2Context.takeBodyThreadAssertions());
            if (!late.isEmpty()) {
                throw buildMultiFailureError(late);
            }
        } catch (AssertionError e) {
            if (timeoutAlreadyReported) {
                // Thrown by the pre-round deadline check above, which already went through
                // timeoutError(): re-reporting it here would duplicate the onTimeout event,
                // the thread dump and every detector report for a single timeout. (No
                // quiescence needed either: the deadline check runs between rounds, when
                // every worker of the previous round has already counted down the latch.)
                throw e;
            }
            // A timed-out round leaves cancelled workers that may still be unwinding, and
            // the analysis below (timeoutError → printPhase2Reports → analyzeAll) reads
            // the detector state those workers are still writing. Torn reads produce wrong
            // findings, and a ConcurrentModificationException inside one detector is
            // contained by DetectorRegistry.ifIssue as a silently empty report — on
            // exactly the timeout runs where the diagnosis matters most. Quiesce first.
            // For an AssertionError from a completed round the workers have already
            // finished and this is a fast no-op. Every path here has also passed the
            // quiesce in the round's finally, so this call is redundant by construction:
            // PIT reports its deletion surviving, and that is expected rather than a
            // missing test (#426). It stays as the guard local to this branch.
            quiesceWorkers(executor, testMethod);
            if (isTimeoutLike(e)) {
                throw timeoutError(effectiveTimeoutMs, e, phase2Analysis, config.detectDeadlocks,
                        progressAfter(e, progress));
            }
            reportFailure(e, currentSeed, lastSchedule, phase2Analysis);
            throw e;
        } catch (Throwable t) {
            // Same quiescence rule as the AssertionError branch: this path is reachable
            // with every worker of the round still alive (e.g. the runner thread itself
            // interrupted out of latch.await by a JUnit-level timeout), and the reports
            // below must not read detector state that live workers are still writing.
            quiesceWorkers(executor, testMethod);
            reportFailure(t, currentSeed, lastSchedule, phase2Analysis);
            throw unwrap(t);
        } finally {
            BodyThreadFailures.uninstall();
            overlap.exit();
            shutDownWorkers(executor);
            // After the executor is down, so no producer is left to publish behind the flush.
            if (telemetryBridge != null) {
                detachTelemetry(telemetryBridge, testMethod.getName());
            }
            announceUntrackedArrayAccesses(phase2Context.untrackedArrayAccesses(), testMethod.getName());
            if (pinningStream != null) {
                closePinningStream(pinningStream, testMethod.getName());
            }
            if (benchmarkRecorder != null) {
                benchmarkRegression = completeBenchmark(benchmarkRecorder);
            }
        }

        // Success path only (the catch blocks above rethrow): analyze detectors,
        // surface findings to stderr and listeners, and apply the failOn gate.
        // Runs on the caller thread after all workers finished and the executor
        // shut down — no shared mutable state is touched concurrently. The failOn
        // gate is intentionally success-path-only; failure/timeout paths above are
        // report-only (see reportFailure and timeoutError).
        analyzeAndGate(config, testMethod, phase2Analysis);
        if (heldShutdowns != null) {
            PENDING_TEARDOWN.set(new PendingTeardown(heldShutdowns, config, testMethod, true));
        }
        if (benchmarkRegression != null) {
            throw benchmarkRegression;
        }
    }

    /**
     * {@return how many workers each round runs: the stress level's count when virtual-thread
     * stress mode is on, which overrides {@code threads}, otherwise {@code config.threads}}
     *
     * @param config the run's configuration
     */
    private static int threadCountFor(AsyncTestConfig config) {
        if (config.virtualThreadStressMode != null && !"OFF".equals(config.virtualThreadStressMode)) {
            return VirtualThreadStressConfig.StressLevel
                .valueOf(config.virtualThreadStressMode).threadCount;
        }
        return config.threads;
    }

    /**
     * {@return a recorder labelled with the test's class, its display name and the thread count
     * actually used, which is not {@code config.threads} under stress mode}
     */
    private static BenchmarkRecorder newBenchmarkRecorder(ReflectiveInvocationContext<Method> invocationContext,
                                                          AsyncTestConfig config, int actualThreads) {
        Object testInstance = invocationContext.getTarget().orElse(null);
        String testClass = testInstance != null ? testInstance.getClass().getName() : "unknown";
        String testMethod = displayNameOf(invocationContext.getExecutable());
        return new BenchmarkRecorder(config, testClass, testMethod, actualThreads);
    }

    /**
     * Says, once per JVM each and at INFO, when an enabled detector is going to see less than its
     * name promises in this JVM.
     *
     * <p>INFO rather than DEBUG because the user each one affects does not have DEBUG on, and a
     * clean report from a detector that could not look reads as a clean bill of health. Each latch
     * is process-wide, so a suite of tests with the same gap hears about it once.
     *
     * @param config         the run's configuration
     * @param atomicityOn    whether the run has an atomicity validator to feed
     * @param virtualWorkers whether the round's workers are virtual threads
     * @param testName       the test method's name, for {@code test=}
     */
    private static void announceBlindSpots(AsyncTestConfig config, boolean atomicityOn,
                                           boolean virtualWorkers, String testName) {
        // The atomicity detector is enabled but the agent's telemetry pipeline is not
        // running: nothing auto-records field accesses, so the validator only sees what
        // the test body records explicitly. This silent gap is the most common reason a bare
        // @AsyncTest detects less than its detector count suggests.
        if (atomicityOn && !TelemetryRegistry.isRunning()
                && AGENT_ABSENCE_LOGGED.compareAndSet(false, true)) {
            log.info("runner.agent.absent test={} detector=AtomicityValidator "
                    + "hint=\"field accesses are not auto-recorded; start the test JVM with "
                    + "-javaagent:async-test-agent-<version>.jar (the README quick start has the "
                    + "build snippet), or record accesses explicitly via AsyncTestContext\"",
                testName);
        }

        // The daemon-hygiene detector is enabled, and every thread the body creates inherits the
        // daemon flag of a worker that is daemon in either thread mode — so the detector's rule
        // ("skip anything that was already daemon") has nothing left to judge for those threads.
        // Not conditioned on useVirtualThreads any more: that was the documented way to make the
        // detector work until the platform workers became daemon too (#479), and announcing it
        // only for virtual threads left the user who followed that advice with silence.
        // With Thread.setDaemon woven the detector judges the decision rather than the flag
        // (#731), so the limitation this announces does not hold and saying it would be wrong.
        if (config.detectDaemonThreadHygiene
                && !AgentThreadHooks.isThreadWeavingInstalled()
                && DAEMON_HYGIENE_INERT_LOGGED.compareAndSet(false, true)) {
            log.info("runner.detector.inert test={} detector=DaemonThreadHygieneDetector "
                    + "reason=\"the runner's workers are daemon threads in both thread modes "
                    + "(#479) and a thread inherits the daemon flag of the thread that created "
                    + "it, so a thread the body constructs is already daemon, and this detector "
                    + "only reports non-daemon threads\" hint=\"attach the agent with "
                    + "collections=true, which sees Thread.start and setDaemon, or record a "
                    + "thread whose factory "
                    + "sets the flag itself, such as Executors.defaultThreadFactory() or any JDK "
                    + "thread pool, or one created outside the body; otherwise read the report as "
                    + "'not observed' rather than 'clean'\"",
                testName);
        }

        // The deadlock detector is enabled, the runner is on virtual threads, and this JVM
        // cannot answer the question for them: findDeadlockedThreads() reports platform threads
        // and the JSON thread dump does not name monitors here either. A clean deadlock report is
        // the most reassuring output this library produces, and here it is not evidence of
        // anything.
        //
        // The capability check is what keeps this honest in both directions. On a JDK whose dump
        // does name monitors the detector reads the cycle out of it (issue #367) and nothing is
        // announced, because nothing is inert.
        if (config.detectDeadlocks && virtualWorkers
                && !DeadlockDetector.canSeeVirtualThreadDeadlocks()
                && DEADLOCK_INERT_LOGGED.compareAndSet(false, true)) {
            log.info("runner.detector.inert test={} detector=DeadlockDetector "
                    + "reason=\"findDeadlockedThreads() reports platform threads, and this "
                    + "JVM's thread dump does not name monitors either, so a cycle between the "
                    + "virtual workers cannot be seen here\" hint=\"set "
                    + "@AsyncTest(useVirtualThreads = false) on the test whose deadlock is "
                    + "between the runner's own threads, or read a clean report as 'not "
                    + "observed' rather than 'no deadlock'\"",
                testName);
        }
    }

    /**
     * Validates the JVM's own memory model when {@code -Dasynctest.validate.jmm=true}; off by
     * default.
     *
     * <p>It cost every test method ~70ms of sleeps and seven platform threads to check axioms
     * the JLS already guarantees, so on a working JVM it cannot find anything true. What it
     * could find was something false: the checks wait a fixed interval instead of joining, so a
     * loaded CI runner that does not schedule the writer in time produced "JMM validation of
     * test framework failed" at WARN — which this project's logging contract reserves for "a
     * human must act" — on precisely the machines where somebody is already hunting a flaky
     * concurrency failure. Kept behind a flag because it is genuinely useful when bringing the
     * library up on an unfamiliar JVM or an emulated architecture.
     */
    private static void validateMemoryModelIfRequested() {
        if (!Boolean.getBoolean("asynctest.validate.jmm")) {
            return;
        }
        MemoryModelValidator.ValidationResult jmmResult = new MemoryModelValidator().validate();
        if (!jmmResult.isValid()) {
            log.warn("JMM validation of test framework failed: {}", jmmResult);
        } else {
            log.debug("runner.jmm ok=true");
        }
    }

    /**
     * {@return the executor the rounds submit their workers to: one virtual thread per task, or
     * a fixed pool of {@code actualThreads} platform threads}
     *
     * <p>Workers carry the {@code async-test-worker-} prefix so thread dumps, deadlock reports
     * and the quiesce stack dump read as the harness's own workers instead of anonymous
     * {@code pool-N-thread-M} entries. Neither executor starts a thread before the first
     * submission, so one that is never used holds nothing.
     */
    private static ExecutorService newWorkerExecutor(boolean virtualWorkers, int actualThreads) {
        return virtualWorkers
            ? Executors.newThreadPerTaskExecutor(namedWorkerFactory(Thread.ofVirtual().factory()))
            : Executors.newFixedThreadPool(actualThreads,
                namedWorkerFactory(Executors.defaultThreadFactory()));
    }

    /**
     * {@return {@code timeoutMs} scaled by {@code multiplier}, never below one millisecond, or
     * {@code timeoutMs} itself when the multiplier is exactly {@code 1.0}}
     */
    private static long scaledTimeoutMs(long timeoutMs, double multiplier) {
        return (multiplier == 1.0)
            ? timeoutMs
            : Math.max(1L, Math.round(timeoutMs * multiplier));
    }

    /**
     * Logs {@code runner.config}, the one event carrying every value that decides how this test
     * runs, and, once per JVM at INFO, {@code runner.selection.partial} when the selection left
     * detectors out.
     *
     * <p>When a test behaves differently on CI than locally, {@code runner.config} is the
     * difference: the multiplier, the thread count actually used (stress mode overrides the
     * annotation) and whether the executor is virtual are all resolved in {@link #execute} and
     * nowhere else. The selection is there too: since 1.13.0 the same bare annotation runs 12
     * detectors where it ran 146 (#956), and a passing run is otherwise silent about how many
     * looked.
     */
    private static void narrateRunConfig(AsyncTestConfig config, String testName, int actualThreads,
                                         boolean virtualWorkers, double timeoutMultiplier,
                                         long effectiveTimeoutMs) {
        if (log.isDebugEnabled()) {
            log.debug("runner.config test={} threads={} invocations={} timeoutMs={} "
                    + "multiplier={} effectiveTimeoutMs={} virtualThreads={} stressMode={} "
                    + "replaySeed={} schedule={} benchmarking={} detectors={} selection={}",
                testName, actualThreads, config.invocations, config.timeoutMs,
                timeoutMultiplier, effectiveTimeoutMs, virtualWorkers,
                config.virtualThreadStressMode, config.replaySeed,
                config.controlledSchedule ? "controlled" : "os", config.enableBenchmarking,
                config.enabledDetectors().size(), selectionLabel(config.enabledDetectors()));
        }
        if (!PARTIAL_SELECTION_LOGGED.get()
                && config.enabledDetectors().size() < DetectorType.values().length
                && PARTIAL_SELECTION_LOGGED.compareAndSet(false, true)) {
            log.info("runner.selection.partial test={} detectors={} of={} selection={} "
                    + "hint=\"not every detector ran; -Dasync-test.detectAll=true runs all of them "
                    + "for one build without editing an annotation\"",
                testName, config.enabledDetectors().size(),
                DetectorType.values().length, selectionLabel(config.enabledDetectors()));
        }
    }

    /**
     * {@return what a resolved selection is, for {@code runner.config}: {@code all} for every
     * detector, a preset's name when the set is exactly that preset's, otherwise {@code custom}}
     *
     * <p>Read from the set alone, so it names what ran, not what the annotation said.
     *
     * @param enabled the run's resolved selection
     */
    static String selectionLabel(Set<DetectorType> enabled) {
        if (enabled.size() == DetectorType.values().length) {
            return "all";
        }
        for (Preset preset : Preset.values()) {
            if (!preset.isAll() && enabled.equals(preset.enabled())) {
                return preset.name();
            }
        }
        return "custom";
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
     * <p>On the console, a block none of whose findings is FACT or VERDICT grade prints as one
     * line ({@link #foldedLine}) unless {@value #FULL_REPORT_PROPERTY} is {@code true} or the
     * block fails the run. A detectAll run enables every detector, and printed in full those
     * blocks buried the findings the library stands behind. Listeners get the full text always.
     *
     * <p>Only called on the success path (see {@link #execute}); failure/timeout paths
     * call {@link #printPhase2Reports} directly and never reach the failOn gate below.
     */
    private static void analyzeAndGate(AsyncTestConfig config,
                                       Method testMethod,
                                       Phase2Analysis phase2Analysis) {
        Map<String, String> reports = new LinkedHashMap<>();
        for (Map.Entry<String, String> finding : phase2Analysis.get().entrySet()) {
            reports.putIfAbsent(finding.getKey(), "\n" + finding.getValue());
        }
        if (reports.isEmpty()) {
            return;
        }

        reportAndGate(config, testMethod, reports, phase2Analysis.grades(), phase2Analysis.severities(),
                phase2Analysis.messages(), true);
    }

    /**
     * {@link #analyzeAndGate}'s reporting half, for any set of reports: prints each, hands it to the
     * listeners, and, when {@code gate} is set, fails the test on the ones at or above failOn that
     * no baseline accepts. {@link #afterTeardown} reuses it for the verdicts held past
     * {@code @AfterEach}, ungated when the run had already failed.
     */
    private static void reportAndGate(AsyncTestConfig config, Method testMethod, Map<String, String> reports,
                                      Map<String, List<GradedFindings.Grade>> graded,
                                      Map<String, IssueSeverity> structured,
                                      Map<String, List<String>> findingMessages, boolean gate) {
        String testId = testMethod.getDeclaringClass().getName() + "#" + displayNameOf(testMethod);
        Baseline baseline = Baseline.fromSystemProperties();

        boolean fingerprinting = baseline.size() > 0 || Baseline.updateMode();
        boolean fullReports = Boolean.getBoolean(FULL_REPORT_PROPERTY);
        int folded = 0;
        int suppressed = 0;
        List<String> failing = new ArrayList<>();
        Map<String, List<String>> failingFingerprints = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : reports.entrySet()) {
            List<GradedFindings.Grade> grades = graded.getOrDefault(e.getKey(), List.of());
            List<String> fingerprints = fingerprinting
                    ? Baseline.fingerprints(e.getValue(), grades) : List.of();
            List<String> uncovered = new ArrayList<>();
            for (String fingerprint : fingerprints) {
                if (!baseline.covers(testId, e.getKey(), fingerprint)) {
                    uncovered.add(fingerprint);
                }
            }
            if (fingerprinting && uncovered.isEmpty()) {
                suppressed++;
                continue;
            }
            if (baseline.contains(testId, e.getKey())) {
                // An entry from before findings had fingerprints: it still accepts the whole
                // detector, but the user is told it is now hiding something it was not recorded for.
                suppressed++;
                log.info("baseline.detector-wide.suppressed test={} detector={} uncovered={} "
                        + "hint=\"a detector-wide baseline entry hides findings it was not recorded for; "
                        + "delete that line and rerun with -D{}=true to accept only the current ones\"",
                    testId, e.getKey(), uncovered.size(), Baseline.UPDATE_PROPERTY);
                continue;
            }
            // Gate only on what the baseline does not already accept: a covered verdict must not
            // fail the run because a new prompt beside it made the block print.
            List<GradedFindings.Grade> gated = grades.isEmpty() || !fingerprinting ? grades
                    : grades.stream().filter(g -> uncovered.contains(Baseline.fingerprint(g.summary()))).toList();
            IssueSeverity structuredSeverity = structured.get(e.getKey());
            boolean trips = gate && trips(config, e.getKey(), e.getValue(), structuredSeverity, gated);
            // A block that fails the run always prints in full: the assertion says "full reports above".
            if (!fullReports && !trips && foldsOnConsole(e.getKey(), grades)) {
                System.err.println(foldedLine(e.getKey(), e.getValue(), grades,
                        findingMessages.getOrDefault(e.getKey(), List.of())));
                folded++;
            } else {
                System.err.println(trustBanner(e.getKey(), grades));
                System.err.println(e.getValue());
            }
            AsyncTestListenerRegistry.fireDetectorReport(e.getKey(), e.getValue(), structuredSeverity, grades);
            if (trips) {
                failing.add(e.getKey());
                failingFingerprints.put(e.getKey(), fingerprints);
            }
        }
        if (folded > 0) {
            System.err.println("[AsyncTest] " + folded + " PROMPT/ADVISORY report(s) shown as one line each;"
                    + " rerun with -D" + FULL_REPORT_PROPERTY + "=true to print them in full");
        }
        if (suppressed > 0) {
            log.info("[AsyncTest] {} baselined finding(s) suppressed for {}", suppressed, testId);
        }
        if (failing.isEmpty()) {
            return;
        }

        if (Baseline.updateMode()) {
            int added = Baseline.record(testId, failingFingerprints);
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
     * {@link #execute} for a caller that will call {@link #afterTeardown} once the test instance's
     * {@code @AfterEach} methods have run, as the JUnit interceptor does: the "never shut down"
     * verdict on an executor the run declared then waits until teardown (#996).
     *
     * @param invocationContext the JUnit invocation being run
     * @param config            the resolved configuration
     * @throws Throwable whatever {@link #execute} throws
     * @since 1.13.2
     */
    public static void executeBeforeTeardown(ReflectiveInvocationContext<Method> invocationContext,
                                             AsyncTestConfig config) throws Throwable {
        TEARDOWN_FOLLOWS.set(Boolean.TRUE);
        try {
            execute(invocationContext, config);
        } finally {
            TEARDOWN_FOLLOWS.remove();
        }
    }

    /**
     * {@link #execute} for a caller whose invocation context does not name the code under test, as
     * {@code AsyncTestRunner} does: its context points at the runner's own holder class, in a
     * package the agent never weaves. The default agent attach infers the package to weave from
     * {@code codeUnderTest} instead (#1042); nothing else reads it.
     *
     * @param invocationContext the invocation being run
     * @param config            the resolved configuration
     * @param codeUnderTest     a class in the caller's package, such as the body's own class
     * @param displayName       caller-chosen display name for reports, logs and baseline identity
     * @throws Throwable whatever {@link #execute} throws
     * @since 1.14.0
     */
    public static void executeForCodeIn(ReflectiveInvocationContext<Method> invocationContext,
                                        AsyncTestConfig config, Class<?> codeUnderTest,
                                        @Nullable String displayName) throws Throwable {
        CODE_UNDER_TEST.set(codeUnderTest);
        DISPLAY_NAME.set(displayName);
        try {
            execute(invocationContext, config);
        } finally {
            DISPLAY_NAME.remove();
            CODE_UNDER_TEST.remove();
        }
    }

    /**
     * {@link #executeForCodeIn(ReflectiveInvocationContext, AsyncTestConfig, Class, String)} with
     * the executable's own name as the display name.
     *
     * @param invocationContext the invocation being run
     * @param config            the resolved configuration
     * @param codeUnderTest     a class in the caller's package, such as the body's own class
     * @throws Throwable whatever {@link #execute} throws
     * @since 1.14.0
     */
    public static void executeForCodeIn(ReflectiveInvocationContext<Method> invocationContext,
                                        AsyncTestConfig config, Class<?> codeUnderTest) throws Throwable {
        CODE_UNDER_TEST.set(codeUnderTest);
        try {
            execute(invocationContext, config);
        } finally {
            CODE_UNDER_TEST.remove();
        }
    }

    /**
     * Reports the executors the last {@link #executeBeforeTeardown} on this thread declared and the
     * test's {@code @AfterEach} methods did not shut down either, and applies the failOn gate to
     * them when the run itself passed it (#996). Does nothing when there was no such run.
     *
     * @since 1.13.2
     */
    public static void afterTeardown() {
        PendingTeardown pending = PENDING_TEARDOWN.get();
        PENDING_TEARDOWN.remove();
        if (pending == null) {
            return;
        }
        ExecutorShutdownDetector.ExecutorShutdownReport report = pending.detector().analyzeAfterTeardown();
        if (!report.hasIssues()) {
            return;
        }
        String name = ExecutorShutdownDetector.class.getSimpleName();
        // The most severe finding, as DetectorRegistry.ifIssue gates a run's (#1419).
        IssueSeverity severity = report.structuredViolations.stream()
                .map(se.deversity.asynctest.report.Violation::severity)
                .min(java.util.Comparator.naturalOrder())
                .orElse(IssueSeverity.MEDIUM);
        List<String> messages = report.structuredViolations.stream()
                .map(se.deversity.asynctest.report.Violation::message).toList();
        reportAndGate(pending.config(), pending.testMethod(), Map.of(name, "\n" + report),
                Map.of(), Map.of(name, severity), Map.of(name, messages), pending.gate());
    }

    /**
     * Abstraction over CyclicBarrier and SpinContentionBarrier.
     * Lets {@link RoundRunner} stay barrier-implementation-agnostic.
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
     * {@code Future.cancel(true)} calls {@link RoundRunner} makes for
     * the same scenario, so stranded spin-barrier workers are still reclaimed at the
     * round-timeout boundary rather than only at the final {@code shutdownNow()}.
     *
     * <p><strong>Virtual threads never spin:</strong> when {@code useVirtualThreads} is
     * {@code true} the spin-barrier property is ignored and the {@link CyclicBarrier}
     * path is used regardless. Neither {@link Thread#onSpinWait()} nor
     * {@link Thread#interrupted()} is a virtual-thread scheduling point, so a spinning
     * virtual thread occupies its carrier until it exits the loop on its own. With more
     * participants than carrier threads (the default carrier count is the core count,
     * and stress mode configures hundreds of participants), the first {@code carriers}
     * arrivals spin on every carrier while the remaining participants can never mount to
     * arrive — a livelock that burns the whole round timeout and reports a spurious
     * "Invocation round timed out" with zero detector activity, on every round.
     */
    private static ContentionBarrier createBarrier(int threads, long timeoutMs,
                                                   boolean useVirtualThreads) {
        if (!useVirtualThreads && Boolean.getBoolean("async-test.spin-barrier.enabled")) {
            SpinContentionBarrier spin = new SpinContentionBarrier(threads);
            return spin::await;
        }
        CyclicBarrier cyclic = new CyclicBarrier(threads);
        return () -> cyclic.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Runs one test's rounds. Each round submits {@code threads} workers that meet at a start
     * barrier, run the body once under the run's {@link AsyncTestContext}, and count down a latch
     * the runner thread waits on for the round's budget.
     *
     * <p>Holds what stays the same for every round of one {@link #execute} call; what one round's
     * workers share lives in a {@link Round}. Every field is final and set before the first
     * submission, which publishes it to the workers.
     */
    private static final class RoundRunner {
        private final ReflectiveInvocationContext<Method> context;
        private final Method method;
        private final int threads;
        private final ExecutorService executor;
        private final Phase1DetectorSet phase1;
        private final AsyncTestContext phase2Context;
        private final boolean useVirtualThreads;
        private final @Nullable Set<Long> workerThreadIds;

        RoundRunner(ReflectiveInvocationContext<Method> context, Method method, int threads,
                    ExecutorService executor, Phase1DetectorSet phase1, AsyncTestContext phase2Context,
                    boolean useVirtualThreads, @Nullable Set<Long> workerThreadIds) {
            this.context = context;
            this.method = method;
            this.threads = threads;
            this.executor = executor;
            this.phase1 = phase1;
            this.phase2Context = phase2Context;
            this.useVirtualThreads = useVirtualThreads;
            this.workerThreadIds = workerThreadIds;
        }

        /**
         * Runs one round and returns once every worker has finished without failing.
         *
         * @param roundTimeoutMs what is left of the run's budget for this round
         * @param schedule       the round's controlled schedule, or {@code null} for the OS's
         * @throws RoundTimeoutError when a worker is still running at the deadline, with the
         *                           failures of the workers that did finish attached as suppressed
         * @throws AssertionError    the workers' failures combined, when any failed
         */
        void run(long roundTimeoutMs, @Nullable ControlledSchedule schedule) throws InterruptedException {
            Round round = new Round(createBarrier(threads, roundTimeoutMs, useVirtualThreads), threads,
                    roundTimeoutMs, schedule, context.getTarget().orElse(null), context.getArguments().toArray());
            // Futures are retained (not ignored) so a round timeout can cancel(true) any worker
            // still stuck, instead of leaving it to linger until execute()'s outer
            // executor.shutdownNow(). Worker completion itself is tracked by the round's latch;
            // exceptions are caught inside the worker into the round's failures.
            List<Future<?>> workers = new ArrayList<>(threads);
            for (int t = 0; t < threads; t++) {
                // The one identity a worker keeps across rounds when its thread does not (#693).
                final int workerSlot = t;
                workers.add(executor.submit(() -> runWorker(workerSlot, round)));
            }
            awaitWorkers(round, workers);
        }

        /**
         * One worker's turn in a round, on the worker's own thread.
         *
         * <p>{@code latch.countDown()} must always run, whatever fails in install, the body or
         * uninstall. Without that guarantee the runner blocks on {@code latch.await()} until the
         * round times out, turning any bug in worker cleanup into a fake "deadlock detected" report.
         */
        private void runWorker(int workerSlot, Round round) {
            // Join the set the telemetry bridge filters on, so accesses this worker makes are
            // attributed to this run. Done first: a failure below still leaves the id registered,
            // which only ever means one extra id in a set that dies with the run.
            if (workerThreadIds != null) {
                workerThreadIds.add(Thread.currentThread().threadId());
            }
            boolean installed = false;
            try {
                AsyncTestContext.install(phase2Context, workerSlot);
                installed = true;
                runBodyOnce(workerSlot, round);
            } catch (Throwable notRun) { // install failed, or recording a body failure did
                round.failures.add(notRun);
            } finally {
                // Symmetry rule (CLAUDE.md): only uninstall if install succeeded. Each cleanup step
                // is independently guarded so one failure can't suppress the next.
                if (installed) {
                    try {
                        AsyncTestContext.uninstall();
                    } catch (Throwable uninstallErr) {
                        round.failures.add(uninstallErr);
                    }
                }
                round.latch.countDown();
            }
        }

        /**
         * Meets the round's other workers at the start barrier, then runs the body once.
         *
         * <p>A failure is recorded, not thrown, and releases the peers waiting at the round's
         * rendezvous, and any still on their way: a worker that failed will never reach it, and they
         * would otherwise wait out the round and report a timeout that hides this failure.
         */
        private void runBodyOnce(int workerSlot, Round round) {
            try {
                round.barrier.arrive();
                round.pastBarrier.incrementAndGet();
                // After the barrier, so every worker joins the schedule; left in a finally, so a
                // body that throws hands the round on and clears the thread's slot before a pooled
                // thread runs the next round.
                if (round.schedule != null) {
                    round.schedule.enter(workerSlot);
                }
                if (phase1.livelock != null) {
                    phase1.livelock.workerEnteredBody(Thread.currentThread());
                }
                round.inBody.add(Thread.currentThread());
                try {
                    invokeBody(round);
                } finally {
                    round.inBody.remove(Thread.currentThread());
                    if (phase1.livelock != null) {
                        phase1.livelock.workerLeftBody(Thread.currentThread());
                    }
                    if (round.schedule != null) {
                        round.schedule.leave();
                    }
                }
            } catch (Throwable ex) {
                round.failures.add(unwrap(ex));
                phase2Context.breakRendezvous();
            }
        }

        /**
         * Invokes the body, and when it returns a {@link CompletionStage}, waits for the stage
         * too.
         *
         * <p>Without the wait an async body would "succeed" the instant {@code invoke()} returned,
         * long before the async work finished, which defeats the point of stress-testing async
         * code.
         */
        private void invokeBody(Round round)
                throws ReflectiveOperationException, InterruptedException, ExecutionException, TimeoutException {
            Object result = method.invoke(round.target, round.args);
            if (result instanceof CompletionStage<?> stage) {
                stage.toCompletableFuture()
                     .get(round.timeoutMs, TimeUnit.MILLISECONDS);
            }
        }

        /** Waits for the round's workers, and throws what the round ends with when it failed. */
        private void awaitWorkers(Round round, List<Future<?>> workers) throws InterruptedException {
            boolean completed = round.latch.await(round.timeoutMs, TimeUnit.MILLISECONDS);
            // An assertion that failed on a thread a worker started is this round's failure (#1253): a
            // body that joined the thread has seen it die, and the death was recorded before that.
            round.failures.addAll(phase2Context.takeBodyThreadAssertions());
            if (!completed) {
                throw timedOut(round, workers);
            }
            if (!round.failures.isEmpty()) {
                throw buildMultiFailureError(round.failures);
            }
        }

        /**
         * Stops a round that outlived its budget and {@return the error that says how far it got}
         *
         * <p>Interrupts any worker still stuck (blocked on a now-broken barrier, or never reached
         * it) instead of leaving it to run until {@code execute()}'s outer
         * {@code executor.shutdownNow()}. {@code cancel(true)} on a finished future is a documented
         * no-op, so this is safe however many workers are still outstanding.
         */
        private AssertionError timedOut(Round round, List<Future<?>> workers) {
            // Read before the cancels below: a cancelled worker counts the latch down too, and
            // read after them a hung round reported "2/2 threads completed" (#1386).
            int finished = threads - (int) round.latch.getCount();
            int passedBarrier = round.pastBarrier.get();
            // Say so before interrupting: a body that catches this interrupt and records it has
            // not left its work on its own, and a detector must not read it as though it had (#598).
            phase2Context.markRoundTimedOut();
            // Before the interrupts, so what is sampled is what the bodies were doing.
            boolean workersParked = everyWorkerParked(round.inBody);
            if (phase1.livelock != null) {
                phase1.livelock.roundTimedOut();
            }
            for (Future<?> worker : workers) {
                worker.cancel(true);
            }
            AssertionError roundTimeout = new RoundTimeoutError(
                    roundTimeoutMessage(threads, finished, passedBarrier, round.timeoutMs)
                            + attachedFailures(round.failures.size()), workersParked);
            // The workers that DID finish often carry the diagnosis: a worker that threw before
            // the barrier is the most common reason its peers never arrived, and throwing without
            // them reported only a thread count. The failures list is copy-on-write, so cancelled
            // workers appending concurrently race harmlessly against this snapshot iteration.
            for (Throwable failure : round.failures) {
                roundTimeout.addSuppressed(failure);
            }
            return roundTimeout;
        }
    }

    /**
     * What one round's workers share: the barrier that releases them together, the latch the
     * runner thread waits on, the failures they record, and what the runner reads if the round
     * times out.
     *
     * <p>Built on the runner thread before the first submission, which publishes it to the
     * workers; they touch only its concurrent members.
     */
    private static final class Round {
        final ContentionBarrier barrier;
        final long timeoutMs;
        final @Nullable ControlledSchedule schedule;
        final @Nullable Object target;
        final Object[] args;
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final CountDownLatch latch;
        /**
         * Workers that got past the start barrier, so a timed-out round can say whether any never
         * reached the body rather than guessing (#1386).
         */
        final AtomicInteger pastBarrier = new AtomicInteger();
        /** The workers inside the body right now, sampled if the round runs out (#1393). */
        final Set<Thread> inBody = ConcurrentHashMap.newKeySet();

        Round(ContentionBarrier barrier, int threads, long timeoutMs, @Nullable ControlledSchedule schedule,
              @Nullable Object target, Object[] args) {
            this.barrier = barrier;
            this.latch = new CountDownLatch(threads);
            this.timeoutMs = timeoutMs;
            this.schedule = schedule;
            this.target = target;
            this.args = args;
        }
    }

    /**
     * The longest a run waits, in total, for the threads its body started and left running before
     * its last check (#1379). A body that leaves a thread running on purpose pays this once per
     * run; one that joins its threads, or starts none, pays nothing.
     */
    static final long STARTED_THREAD_GRACE_MS = 100;

    /**
     * Joins {@code started} until each has ended or {@link #STARTED_THREAD_GRACE_MS} has passed
     * across all of them. A thread's uncaught-exception handler runs before it ends, so a death
     * inside the window has been recorded when this returns.
     */
    static void joinBriefly(List<Thread> started) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STARTED_THREAD_GRACE_MS);
        for (Thread thread : started) {
            long leftMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (leftMs <= 0) {
                return;
            }
            try {
                thread.join(leftMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** How many times, and how far apart, a timed-out round's workers are sampled (#1393). */
    private static final int PARKED_SAMPLES = 5;
    private static final long PARKED_SAMPLE_GAP_MS = 10;

    /**
     * {@return whether every worker still in the body was BLOCKED or WAITING, with no timeout, in
     * each of a few samples} That is a round that will not finish however much budget it gets,
     * which the arithmetic on the budget cannot tell from one that simply ran out (#1393). A
     * worker sleeping or in a timed wait is TIMED_WAITING and still going somewhere, so it keeps
     * the verdict to the budget, and so does a round with no worker left in the body.
     */
    static boolean everyWorkerParked(Set<Thread> inBody) {
        if (inBody.isEmpty()) {
            return false;
        }
        for (int sample = 0; sample < PARKED_SAMPLES; sample++) {
            for (Thread worker : inBody) {
                Thread.State state = worker.getState();
                if (state != Thread.State.BLOCKED && state != Thread.State.WAITING
                        && state != Thread.State.TERMINATED) {
                    return false;
                }
            }
            try {
                Thread.sleep(PARKED_SAMPLE_GAP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !inBody.isEmpty();
    }

    /**
     * {@return what a timed-out round says about itself (#1386)}
     *
     * <p>The broken-barrier hint is given only when a worker never got past the barrier: every
     * hang the hint used to accompany had its workers inside the body. A round whose workers all
     * finished in the moment between its deadline and the count being read says that, instead of
     * "2/2 threads completed" beside a hint that one may be stuck.
     *
     * @param threads        the round's workers
     * @param finished       how many had finished when the deadline passed
     * @param passedBarrier  how many had got past the start barrier
     * @param roundTimeoutMs the time the round was given
     */
    static String roundTimeoutMessage(int threads, int finished, int passedBarrier, long roundTimeoutMs) {
        if (finished >= threads) {
            return "Invocation round timed out after " + roundTimeoutMs + "ms, though all " + threads
                    + " workers finished just after it ran out.";
        }
        int heldAtBarrier = threads - passedBarrier;
        return "Invocation round timed out: " + finished + "/" + threads + " threads completed within "
                + roundTimeoutMs + "ms."
                + (heldAtBarrier > 0 ? " " + heldAtBarrier + " worker(s) never got past the start barrier "
                        + "(a worker that failed before it, or a broken barrier)." : "");
    }

    /** {@return the note that a timed-out round's worker failures ride along as suppressed, or ""} */
    static String attachedFailures(int failureCount) {
        return failureCount == 0 ? ""
                : " " + failureCount + " worker failure(s) from this round are attached as suppressed.";
    }

    /**
     * How far a run got before its budget ran out, which decides what its timeout says (#1386).
     *
     * <p>{@code timeoutMs} is the budget for the whole run. A correct body that makes steady
     * progress and outgrows it is not a deadlock, and saying so sent readers looking for a hang
     * that was not there. A round that had well over the time earlier rounds took, and still did
     * not finish, is.
     *
     * @param roundsCompleted   rounds that finished before the one that ran out
     * @param invocations       rounds the run asked for
     * @param lastRoundBudgetMs what was left of the budget when the last round started, zero or
     *                          less when it ran out between rounds
     */
    record RunProgress(int roundsCompleted, int invocations, long lastRoundBudgetMs,
                       boolean workersParked) {

        /** A round with less than this many average rounds' worth of budget left ran out, not hung. */
        private static final int ROOMY_ROUNDS = 4;

        RunProgress(int roundsCompleted, int invocations, long lastRoundBudgetMs) {
            this(roundsCompleted, invocations, lastRoundBudgetMs, false);
        }

        /** {@return this progress, for a round whose unfinished workers were all parked (#1393)} */
        RunProgress withWorkersParked() {
            return new RunProgress(roundsCompleted, invocations, lastRoundBudgetMs, true);
        }

        /** {@return the first sentence of the run's timeout} */
        String headline(long timeoutMs) {
            String done = roundsCompleted + " of " + invocations + " rounds completed";
            if (workersParked) {
                // Evidence beats the arithmetic: the round would not have finished with more budget.
                return "Test timed out after " + timeoutMs + "ms. Possible deadlock, starvation, or visibility issue. "
                        + done + "; every worker still in the body was blocked or waiting with no timeout.";
            }
            if (budgetRanOut(timeoutMs)) {
                return ranOutOfBudget(timeoutMs, done);
            }
            return "Test timed out after " + timeoutMs + "ms. Possible deadlock, starvation, or visibility issue. "
                    + done + ".";
        }

        private String ranOutOfBudget(long timeoutMs, String done) {
            long spentMs = timeoutMs - Math.max(lastRoundBudgetMs, 0);
            return "Test timed out after " + timeoutMs + "ms: the budget ran out while the run was making progress, "
                    + done + " in " + spentMs + " ms"
                    + (lastRoundBudgetMs > 0 ? " and the next had " + lastRoundBudgetMs + " ms left" : "")
                    + ". timeoutMs covers the whole run, not one round: raise it or lower invocations. A round"
                    + " that hangs looks the same when little budget is left, so if raising it does not help,"
                    + " look for a hang.";
        }

        private boolean budgetRanOut(long timeoutMs) {
            if (lastRoundBudgetMs <= 0) {
                return true;
            }
            if (roundsCompleted == 0) {
                return false;
            }
            long averageRoundMs = (timeoutMs - lastRoundBudgetMs) / roundsCompleted;
            return lastRoundBudgetMs < ROOMY_ROUNDS * averageRoundMs;
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
        // N threads hitting one defect produce N identical failures. Listing each one buries
        // the failures that differ — and those are the ones worth reading — so identical
        // failures are collapsed to one line with a count, in first-seen order.
        Map<String, List<Throwable>> distinct = new LinkedHashMap<>();
        for (Throwable t : failures) {
            distinct.computeIfAbsent(t.getClass().getName() + ": " + t.getMessage(),
                    k -> new ArrayList<>()).add(t);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(failures.size()).append(" concurrent thread(s) failed");
        if (distinct.size() < failures.size()) {
            sb.append(" (").append(distinct.size()).append(" distinct)");
        }
        sb.append(":\n");
        int index = 0;
        for (List<Throwable> group : distinct.values()) {
            Throwable t = group.get(0);
            index++;
            sb.append("  [").append(index).append("] ")
              .append(t.getClass().getSimpleName()).append(": ")
              .append(t.getMessage());
            if (group.size() > 1) {
                sb.append(" (x").append(group.size()).append(')');
            }
            sb.append('\n');
        }

        AssertionError combined = new AssertionError(sb.toString().trim());
        // One representative per distinct failure: the first is the cause, the rest are
        // suppressed. Attaching all N would repeat the same stack trace N times.
        Iterator<List<Throwable>> groups = distinct.values().iterator();
        combined.initCause(groups.next().get(0));
        while (groups.hasNext()) {
            combined.addSuppressed(groups.next().get(0));
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

    /** {@return {@code progress}, marked parked when the round that ran out found its workers so (#1393)} */
    private static RunProgress progressAfter(AssertionError timeout, RunProgress progress) {
        return timeout instanceof RoundTimeoutError round && round.workersParked
                ? progress.withWorkersParked() : progress;
    }

    /**
     * True only for the runner's own timeout marker, {@link RoundTimeoutError}.
     *
     * <p>Deliberately a type check, not message sniffing: the previous implementation
     * matched any {@link AssertionError} whose message contained "timed out", which
     * misclassified a <em>user's</em> assertion failure that happened to mention those
     * words — the failure was rewrapped as "Test timed out after …", the replay-seed
     * line was never printed, and listeners received {@code onTimeout} instead of
     * {@code onTestFailed}.
     */
    private static boolean isTimeoutLike(AssertionError e) {
        return e instanceof RoundTimeoutError;
    }

    /**
     * Marker type for timeouts raised by the runner itself (a round that did not finish
     * within its budget, or the overall deadline expiring between rounds). The
     * {@code catch (AssertionError)} block in {@link #execute} routes on this type — see
     * {@link #isTimeoutLike} — so user assertion failures can never be mistaken for
     * harness timeouts, whatever their message says. Extends {@link AssertionError}, so
     * user-facing behavior ({@code assertThrows(AssertionError.class, …)}, JUnit
     * reporting) is unchanged.
     */
    // The PMD suppression is deliberate: this type MUST be an AssertionError so JUnit
    // reports it as a test failure and existing assertThrows(AssertionError.class, ...)
    // callers keep passing — the runner threw bare AssertionError for timeouts before this
    // type existed. It is a marker for routing, not a new error category.
    @SuppressWarnings("PMD.DoNotExtendJavaLangError")
    static final class RoundTimeoutError extends AssertionError {
        private static final long serialVersionUID = 1L;

        /** Whether the round's unfinished workers were all parked when it ran out (#1393). */
        final boolean workersParked;

        RoundTimeoutError(String message) {
            this(message, false);
        }

        RoundTimeoutError(String message, boolean workersParked) {
            super(message);
            this.workersParked = workersParked;
        }
    }

    /**
     * Reports a failed round that was not a timeout: the seed to replay it with, the
     * {@code onTestFailed} event, then the detector reports.
     *
     * <p>Report-only. The {@code failOn} gate ({@link #analyzeAndGate}) intentionally runs only
     * on the success path: a test that already failed does not need a second, synthetic
     * failure from detector findings on top of its own. The caller quiesces the workers first.
     */
    private static void reportFailure(Throwable failure, long seed,
                                      ControlledSchedule.@Nullable Summary schedule,
                                      Phase2Analysis phase2Analysis) {
        // Surface the seed of the failing round, and say what pasting it into
        // @AsyncTest(replaySeed=N) replays: on its own only the seed's random choices, under a
        // controlled schedule the interleaving too, unless a worker escaped the schedule.
        System.err.println(replayHint(seed, schedule));
        AsyncTestListenerRegistry.fireTestFailed(failure);
        printPhase2Reports(phase2Analysis);
    }

    /**
     * The line a failing run prints about its seed. It used to say the seed reproduced the
     * failure, which only a controlled schedule with no escapes makes true.
     */
    static String replayHint(long seed, ControlledSchedule.@Nullable Summary schedule) {
        String head = "[AsyncTest] Failure with replaySeed=" + seed + "L — ";
        if (schedule == null) {
            return head + "paste into @AsyncTest(replaySeed=...) to replay the seed's random choices;"
                    + " add controlledSchedule = true to replay the interleaving too.";
        }
        if (schedule.escapes() == 0) {
            return head + "paste into @AsyncTest(replaySeed=..., controlledSchedule = true) to replay"
                    + " this interleaving.";
        }
        return head + "under controlledSchedule = true, but " + schedule.escapes() + " time(s) a worker"
                + " stopped without reaching a scheduling point (blocked, slept or waited) and left the"
                + " schedule, so a replay may interleave differently.";
    }

    /**
     * Reports a timeout exactly once — listeners notified, thread dump printed, detector
     * reports flushed — and returns the {@link RoundTimeoutError} to throw.
     *
     * <p>Callers that throw the returned error from inside {@link #execute}'s try block must
     * set {@code timeoutAlreadyReported} first: the returned type satisfies
     * {@link #isTimeoutLike}, so the enclosing {@code catch (AssertionError)} would
     * otherwise route it through here a second time.
     *
     * <p>The message carries {@link #findingSummary}, which is the only place a timed-out run
     * says whether any detector had an answer: the {@code failOn} gate is success-path-only,
     * so the reports flushed above are all a reader gets, and in a parallel build they are
     * hundreds of interleaved lines from the failure. See {@link #findingSummary}.
     */
    private static AssertionError timeoutError(long timeoutMs,
                                               @Nullable Throwable cause,
                                               Phase2Analysis phase2Analysis,
                                               boolean detectDeadlocks,
                                               RunProgress progress) {
        AsyncTestListenerRegistry.fireTimeout(timeoutMs);
        if (detectDeadlocks) {
            DeadlockDetector.printThreadDump();
            DeadlockDetector.printLearningAndFix();
        }
        // Report-only: this is a failure/timeout path, so the failOn gate in
        // analyzeAndGate (success-path-only) is never reached for this run.
        printPhase2Reports(phase2Analysis);
        AssertionError error = new RoundTimeoutError(progress.headline(timeoutMs) + findingSummary(phase2Analysis));
        if (cause != null) {
            error.initCause(cause);
        }
        return error;
    }

    /**
     * One sentence naming the detectors that had something to say before the timeout.
     *
     * <p><strong>Why the failure needs this.</strong> {@link #analyzeAndGate} runs on the
     * success path only, so a run that timed out never reaches the {@code failOn} gate and
     * its failure is the timeout, not the finding. The findings are printed to stderr by the
     * caller, but a reader looking at a failing build sees the assertion message first and
     * often only that, and under {@code -T 1C} the reports belong to whichever module happened
     * to be writing at the time. Naming the detectors in the message is what makes "the round
     * timed out" and "here is what was detected" the same sentence.
     *
     * <p>The empty case is reported explicitly rather than omitted. "No enabled detector
     * produced a finding" is a different diagnosis from "a detector fired and you missed it",
     * and silence cannot distinguish them: it is exactly the ambiguity that let 21 example
     * demonstrations name a detector in their {@code @Disabled} reason while failing on a
     * timeout, with nothing to say which of them had a finding waiting (issue #363).
     *
     * <p>Names only, not reports. The reports are already on stderr in full, and an assertion
     * message that inlined them would push the timeout itself off the top of the console.
     */
    private static String findingSummary(Phase2Analysis phase2Analysis) {
        Set<String> named = phase2Analysis.get().keySet();
        if (named.isEmpty()) {
            return " No enabled detector produced a finding before the timeout.";
        }
        return " " + named.size() + " detector finding(s) recorded before the timeout: "
                + String.join(", ", named) + ". Full reports above.";
    }

    /**
     * Brings the round's workers to rest before detector state is read for reporting.
     *
     * <p>{@code shutdownNow()} interrupts anything still running (idempotent — the
     * {@code finally} block in {@link #execute} calls it again harmlessly), and the
     * bounded wait gives cancelled workers time to unwind out of the test body, the
     * {@code AsyncTestContext.uninstall()} call before
     * analysis reads the detectors they were writing to. The bound defaults to 2s and is
     * tunable via {@code -Dasync-test.quiesce.grace.ms} for suites whose code unwinds
     * slowly after cancellation. A worker stuck in uninterruptible user code can outlive
     * any grace period — threads cannot be killed — in which case analysis proceeds
     * anyway (the detectors' own snapshotting plus {@code ifIssue} containment degrade
     * that to a partial report rather than a crash; {@code DetectorAnalysisUnderConcurrentWritesTest}
     * pins that no built detector's analysis throws while two threads still record into it,
     * #1150), and the WARNs below say so, with
     * the surviving workers' stacks, instead of letting the report silently lie.
     *
     * <p>Such a worker stays inside its own run when it finally unwinds: {@link #execute} builds
     * a fresh executor per run, so no thread serves two runs, and the context state is per
     * thread, so its late detector writes reach only its own finished run and its
     * {@code uninstall()} clears only its own thread (#1145,
     * {@code ConcurrencyRunnerTest.orphanedWorker_cannotReachTheNextRunsContext}). Sharing an
     * executor across runs would break that.
     */
    private static void quiesceWorkers(ExecutorService executor, Method testMethod) {
        executor.shutdownNow();
        long graceMs = resolveQuiesceGraceMillis();
        try {
            if (!executor.awaitTermination(graceMs, TimeUnit.MILLISECONDS)) {
                log.warn("runner.quiesce.incomplete test={} graceMs={} "
                        + "hint=\"a worker is still running (likely uninterruptible user "
                        + "code); detector reports below may be missing its last accesses; "
                        + "raise -D{} if the code just unwinds slowly\"",
                    testMethod.getName(), graceMs, QUIESCE_GRACE_PROPERTY);
                logSurvivingWorkerStacks();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Resolves the quiesce grace from {@code -Dasync-test.quiesce.grace.ms}, defaulting to
     * {@value #DEFAULT_QUIESCE_GRACE_MS}ms. Parsing is defensive — a missing, blank,
     * non-numeric or negative value falls back to the default rather than throwing.
     */
    private static long resolveQuiesceGraceMillis() {
        String raw = System.getProperty(QUIESCE_GRACE_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_QUIESCE_GRACE_MS;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed >= 0 ? parsed : DEFAULT_QUIESCE_GRACE_MS;
        } catch (NumberFormatException e) {
            return DEFAULT_QUIESCE_GRACE_MS;
        }
    }

    /**
     * WARN-logs the stack of every still-live worker thread (named
     * {@value #WORKER_THREAD_PREFIX}…) so the user sees <em>where</em> the stuck worker
     * is, not just that one exists. Only platform workers appear in
     * {@link Thread#getAllStackTraces()}; a stuck virtual worker is invisible here, and
     * the {@code runner.quiesce.incomplete} event still reports the situation.
     */
    private static void logSurvivingWorkerStacks() {
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            Thread thread = entry.getKey();
            if (!thread.isAlive() || !thread.getName().startsWith(WORKER_THREAD_PREFIX)) {
                continue;
            }
            StackTraceElement[] stack = entry.getValue();
            StringBuilder frames = new StringBuilder();
            for (int i = 0; i < Math.min(stack.length, 12); i++) {
                frames.append("\n    at ").append(stack[i]);
            }
            log.warn("runner.quiesce.stuck-worker thread={} state={}{}",
                thread.getName(), thread.getState(), frames);
        }
    }

    /**
     * Names worker threads {@code async-test-worker-N}, N from {@link #WORKER_SEQUENCE} so a name
     * never repeats across runs, and makes platform workers daemon (virtual ones always are).
     *
     * <p><strong>Daemon is load-bearing.</strong> A worker blocked on a monitor cannot be
     * interrupted out of it - not by {@code shutdownNow}, not by anything - so a test body that
     * deadlocks leaves its workers alive for the life of the process. While these were the
     * non-daemon threads {@code Executors.defaultThreadFactory()} produces, that deadlock did not
     * merely fail one test: it held the whole JVM open, and this library must never be the reason
     * a host cannot exit.
     *
     * <p>Surefire hid it, because {@code reuseForks=false} gives every class its own fork and
     * kills it. PIT did not: it runs every class in one JVM and waits for that JVM to exit, so two
     * deadlocked workers stopped a mutation run for three hours at zero CPU (#479). Virtual
     * threads, which are the default, have always been daemon by construction - this makes the
     * platform path agree with the path almost every run already takes.
     *
     * <p>Nothing is lost by it. The runner quiesces its own workers with a bounded grace and
     * reports what is still stuck, so results are gathered before the test method returns; a
     * daemon worker that outlives the JVM is by definition one that was never going to finish.
     */
    private static java.util.concurrent.ThreadFactory namedWorkerFactory(
            java.util.concurrent.ThreadFactory base) {
        return runnable -> {
            Thread thread = base.newThread(runnable);
            thread.setName(WORKER_THREAD_PREFIX + WORKER_SEQUENCE.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * The one line a reader sees before a finding's own report: which detector, and how far to
     * trust it.
     *
     * <p>A detectAll run enables every detector, and without this line a recorded deadlock and a
     * pattern the library cannot fully model print identically. A reader who cannot rank findings
     * treats the whole report as noise, so the rank goes first, above the detail.
     *
     * <p>Written to {@code System.err} beside the report rather than prepended to it: the report
     * string is what listeners receive and what tests assert on, and it stays untouched.
     */
    static String trustBanner(String detectorName, TrustTier tier) {
        return "[AsyncTest] " + detectorName + " trust=" + tier + " " + trustHint(tier);
    }

    private static String trustHint(TrustTier tier) {
        return switch (tier) {
            case VERDICT -> "(a finding means the code is wrong; decided from observed state or visible"
                    + " synchronization, and measured on the bug and on its correct twin)";
            case FACT -> "(the report states what was observed or recorded; whether it is a bug is your call)";
            case PROMPT -> "(a prompt to verify; synchronization the library cannot see may make this correct)";
            case ADVISORY -> "(a performance or hygiene note, not a correctness claim)";
        };
    }

    /**
     * Whether this detector's output should fail the run.
     *
     * <p>A detector that graded its findings is judged finding by finding: the run fails when
     * <em>any one</em> of them clears both thresholds. Judging the block as a whole is what made a
     * verdict-grade finding inherit the weakest tier its detector can produce, so a verdict-only
     * gate missed real bugs. Ungraded detectors keep the per-detector answer, which is still right
     * for a detector whose findings are all the same kind.
     */
    private static boolean trips(AsyncTestConfig config, String detectorName, String report,
                                 @Nullable IssueSeverity structuredSeverity,
                                 List<GradedFindings.Grade> grades) {
        if (grades.isEmpty()) {
            return config.failOn.triggeredBy(DetectorDefaultSeverity.of(detectorName, report, structuredSeverity))
                    && DetectorTrust.tierOfDetector(detectorName).atLeast(config.minTrust);
        }
        return grades.stream().anyMatch(grade ->
                config.failOn.triggeredBy(grade.severity()) && grade.tier().atLeast(config.minTrust));
    }

    /**
     * The banner for a detector whose report may grade its findings one by one.
     *
     * <p>The head line claims no more than the weakest finding under it supports: it names the
     * lowest tier, and the span when the grades differ ({@code trust=PROMPT..VERDICT}). It used to
     * name the best tier, so a block holding one observed mutation and one structural note was
     * headed "a finding means the code is wrong", and the note inherited that sentence. Each
     * graded finding then gets a line of its own with its tier and severity, so the reader can
     * tell which line is the verdict. The {@code failOn} gate is unaffected: {@link #trips} still
     * judges every finding on its own grade.
     *
     * <p>An ungraded detector gets the one-line banner of its detector-wide tier.
     */
    static String trustBanner(String detectorName, List<GradedFindings.Grade> grades) {
        if (grades.isEmpty()) {
            return trustBanner(detectorName, DetectorTrust.tierOfDetector(detectorName));
        }
        TrustTier lowest = grades.get(0).tier();
        TrustTier highest = lowest;
        for (GradedFindings.Grade grade : grades) {
            lowest = grade.tier().compareTo(lowest) < 0 ? grade.tier() : lowest;
            highest = grade.tier().compareTo(highest) > 0 ? grade.tier() : highest;
        }
        String span = lowest == highest ? lowest.toString() : lowest + ".." + highest;
        StringBuilder banner = new StringBuilder("[AsyncTest] ").append(detectorName)
                .append(" trust=").append(span).append(' ').append(trustHint(lowest));
        for (GradedFindings.Grade grade : grades) {
            banner.append(System.lineSeparator()).append("[AsyncTest]   finding trust=")
                    .append(grade.tier()).append(" severity=").append(grade.severity())
                    .append(": ").append(grade.summary());
        }
        return banner.toString();
    }

    /**
     * Whether a passing run prints this block as one line: when none of its findings is FACT or
     * VERDICT grade. A block holding a verdict among prompts prints in full, so folding never
     * hides a finding the library stands behind.
     */
    static boolean foldsOnConsole(String detectorName, List<GradedFindings.Grade> grades) {
        TrustTier best = grades.stream()
                .map(GradedFindings.Grade::tier)
                .max(java.util.Comparator.naturalOrder())
                .orElseGet(() -> DetectorTrust.tierOfDetector(detectorName));
        return !best.atLeast(TrustTier.FACT);
    }

    /**
     * One line for a folded block: detector, tier, finding count and the first finding's headline.
     *
     * <p>A graded report names its findings in its grades, and a report that keeps structured
     * findings names them in their messages. Only a report with neither, which since #801 is a
     * third-party detector's, is read by its text: one {@code "  - "} bullet per finding, or a
     * single finding headed by its first line. The bullets were the rule for every report until
     * #773, and they also list context and advice, so a folded line could count "Fix" text as
     * findings and lead with it.
     */
    static String foldedLine(String detectorName, String report, List<GradedFindings.Grade> grades,
                             List<String> findingMessages) {
        int count;
        String headline;
        TrustTier tier;
        if (grades.isEmpty() && !findingMessages.isEmpty()) {
            count = findingMessages.size();
            headline = findingMessages.get(0).strip();
            tier = DetectorTrust.tierOfDetector(detectorName);
        } else if (grades.isEmpty()) {
            List<String> bullets = report.lines().filter(l -> l.startsWith("  - ")).toList();
            count = Math.max(1, bullets.size());
            headline = bullets.isEmpty()
                    ? report.lines().map(String::strip).filter(l -> !l.isEmpty()).findFirst().orElse("")
                    : bullets.get(0).substring(4).strip();
            tier = DetectorTrust.tierOfDetector(detectorName);
        } else {
            count = grades.size();
            headline = grades.get(0).summary();
            tier = grades.stream().map(GradedFindings.Grade::tier)
                    .min(java.util.Comparator.naturalOrder()).orElseThrow();
        }
        if (headline.length() > FOLDED_HEADLINE_LIMIT) {
            headline = headline.substring(0, FOLDED_HEADLINE_LIMIT) + "...";
        }
        return "[AsyncTest] " + detectorName + " trust=" + tier + " findings=" + count + ": " + headline;
    }

    private static final int FOLDED_HEADLINE_LIMIT = 160;

    /**
     * Prints every finding in full on the failure and timeout paths.
     *
     * <p>Deliberately not folded the way {@link #analyzeAndGate} folds PROMPT and ADVISORY blocks:
     * here the test has already failed on its own evidence, so a prompt-grade finding is no longer
     * noise but a candidate cause, and only failing tests reach this path, which bounds the output.
     */
    private static void printPhase2Reports(Phase2Analysis phase2Analysis) {
        Map<String, List<GradedFindings.Grade>> graded = phase2Analysis.grades();
        Map<String, IssueSeverity> structured = phase2Analysis.severities();
        for (Map.Entry<String, String> finding : phase2Analysis.get().entrySet()) {
            List<GradedFindings.Grade> grades = graded.getOrDefault(finding.getKey(), List.of());
            System.err.println("\n" + trustBanner(finding.getKey(), grades));
            System.err.println(finding.getValue());
            AsyncTestListenerRegistry.fireDetectorReport(finding.getKey(), finding.getValue(),
                    structured.get(finding.getKey()), grades);
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
        private final String testName;
        private @Nullable Map<String, String> reports;
        private @Nullable JfrPinningStream pinningStream;

        Phase2Analysis(AsyncTestContext ctx, String testName) {
            this.ctx = ctx;
            this.testName = testName;
        }

        /** Sets the stream {@link #get()} stops before analyzing; {@code null} when there is none. */
        void drainBeforeAnalysis(@Nullable JfrPinningStream stream) {
            this.pinningStream = stream;
        }

        /**
         * {@return the per-finding grades of this run, keyed by detector}
         *
         * <p>Runs {@link #get()} first: the grades are a by-product of the analysis pass, so
         * reading them before it has run would report the previous pass's, or nothing at all.
         */
        Map<String, List<GradedFindings.Grade>> grades() {
            get();
            return ctx.findingGrades();
        }

        /** {@return the structured severities of this run, keyed by detector; runs {@link #get()} first} */
        Map<String, IssueSeverity> severities() {
            get();
            return ctx.findingSeverities();
        }

        /** {@return the structured finding messages of this run, keyed by detector; runs {@link #get()} first} */
        Map<String, List<String>> messages() {
            get();
            return ctx.findingMessages();
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
                // JFR delivers pins in batches and had delivered none of them before stop() in
                // the 2026-10-10 probe, so the analysis sees them only after this. Every report
                // path comes through here, the failure and timeout reports included.
                JfrPinningStream pins = pinningStream;
                if (pins != null) {
                    pins.close();
                }
                memo = ctx.analyzeAllNamed();
                reports = memo;
                logNotes(testName, ctx.detectorNotes());
            }
            return memo;
        }
    }

    /** At most this many {@code runner.detector.note} events per detector per run. */
    static final int NOTE_EVENTS_PER_DETECTOR = 3;

    /**
     * Logs the notes of detectors whose report had no finding, one INFO event per distinct note.
     *
     * <p>Such a report is never printed, so a note in it (a hint that asks the caller to change a
     * recording, such as an undecided {@code SynchronizedNonFinalDetector} slot) reached nobody
     * (#816). A note is not a finding, so it goes to the diagnostic channel at INFO rather than
     * into the report or at WARN. Here, in the memoized analysis, it is said once per run on
     * every path, and at most {@value #NOTE_EVENTS_PER_DETECTOR} times per detector; the
     * {@code notes=} field keeps the total so a reader knows when lines were left out.
     */
    private static void logNotes(String testName, Map<String, List<String>> notes) {
        for (Map.Entry<String, List<String>> e : notes.entrySet()) {
            List<String> lines = e.getValue();
            for (int i = 0; i < Math.min(lines.size(), NOTE_EVENTS_PER_DETECTOR); i++) {
                log.info("runner.detector.note test={} detector={} notes={} note=\"{}\"",
                        testName, e.getKey(), lines.size(), lines.get(i).replace("\"", "\\\""));
            }
        }
    }

    // ---- Per-invocation lifecycle helpers ----

    /** Stops the round's workers, waiting up to a second for them to end. */
    private static void shutDownWorkers(ExecutorService executor) {
        executor.shutdownNow();
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Drains the telemetry ring into the run, then detaches the bridge, then says how many events
     * it could not attribute to a worker.
     *
     * <p>Drain, then detach, in that order: {@code close()} clears the callback, so anything still
     * sitting in the ring buffer at that point is discarded on the next drain.
     * {@code analyzeAndGate()} runs after the cleanup, so detaching before flushing threw away the
     * accesses from the last round of every passing test. Each step is guarded on its own, so a
     * failed flush still detaches.
     *
     * <p>The unattributed events are accesses from threads the body handed work to in a way the
     * agent does not see: a thread started in unwoven code, a task given to a
     * {@code ThreadPoolExecutor}'s {@code execute}. A woven start, submit, stage or other execute is
     * attributed (#745, #741). The bridge cannot attribute the rest to a round, so it drops them,
     * and a clean atomicity report then covers the workers and their woven hand-offs and nothing
     * else. Said at INFO, per test rather than once per JVM, because it is an observation about
     * this test's body and not a property of the configuration: the reader has to know which test
     * it applies to (#500).
     */
    private static void detachTelemetry(TelemetryBridge telemetryBridge, String testName) {
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
        long unattributed = telemetryBridge.droppedNonWorkerEvents();
        if (unattributed > 0) {
            log.info("runner.telemetry.unattributed test={} events={} "
                    + "reason=\"the agent captured field accesses on threads that no "
                    + "worker started or handed a task through a woven call, so they "
                    + "belong to no round and carry no atomicity evidence\" "
                    + "hint=\"start threads with Thread.start or submit tasks to a JDK "
                    + "executor from the @AsyncTest workers, or read a clean atomicity "
                    + "report as covering the workers only\"",
                testName, unattributed);
        }
    }

    /**
     * Says, per test at INFO, how many array element accesses {@code arrays=true} did not record.
     *
     * <p>{@code arrays=true} records the first {@value TelemetryRegistry#MAX_TRACKED_ARRAY_INDEX}
     * elements of an array; an access past them is dropped, which can only hide a race (#1262).
     * Per test, like {@code runner.telemetry.unattributed}, because it is about this body; the
     * atomicity report carries the same count when it has findings to print.
     */
    private static void announceUntrackedArrayAccesses(long untrackedArrayAccesses, String testName) {
        if (untrackedArrayAccesses > 0) {
            log.info("runner.telemetry.arrays-untracked test={} accesses={} tracked={} "
                    + "reason=\"arrays=true records the first {} elements of an array, and "
                    + "these accesses were past them, so no race on those elements is reported\" "
                    + "hint=\"read a clean atomicity report as covering the first {} elements "
                    + "of each array only\"",
                testName, untrackedArrayAccesses, TelemetryRegistry.MAX_TRACKED_ARRAY_INDEX,
                TelemetryRegistry.MAX_TRACKED_ARRAY_INDEX, TelemetryRegistry.MAX_TRACKED_ARRAY_INDEX);
        }
    }

    /**
     * Ends the run's JFR pinning recording and says what it saw that is not this run's finding.
     *
     * <p>Usually already closed by the analysis on a reporting path; {@code close()} is idempotent,
     * and this call is the one that guarantees the recording ends with the run. The unattributed
     * count is said per test at INFO because it is about this body: JFR reports every virtual
     * thread in the JVM, so the count also holds pins of another test running in parallel, which
     * is why they are not attributed. The rest are DEBUG diagnostics of the feed itself.
     */
    private static void closePinningStream(JfrPinningStream pinningStream, String testName) {
        pinningStream.close();
        long unattributedPins = pinningStream.unattributedEvents();
        if (unattributedPins > 0) {
            log.info("runner.pinning.unattributed test={} events={} "
                    + "reason=\"the JVM reported virtual-thread pins on threads that are not "
                    + "this run's workers or threads they started through woven code: "
                    + "threads started in code the agent does not weave, or a test running in "
                    + "parallel, so they are not this run's findings\" "
                    + "hint=\"attach the agent with collections=true so threads the body "
                    + "starts are attributed, or pin in the @AsyncTest workers themselves\"",
                testName, unattributedPins);
        }
        // A pin inside the agent's transformer, on a class the body defined (#984).
        if (pinningStream.instrumentationEvents() > 0) {
            log.debug("runner.pinning.instrumentation test={} events={}",
                testName, pinningStream.instrumentationEvents());
        }
        // A JDK lock the JVM contended on while it loaded or initialized a class (#1028).
        if (pinningStream.criticalSectionEvents() > 0) {
            log.debug("runner.pinning.critical-section test={} events={}",
                testName, pinningStream.criticalSectionEvents());
        }
        // A JDK lock contended under the JVM's own upcall, call-site linkage (#1248).
        if (pinningStream.vmUpcallEvents() > 0) {
            log.debug("runner.pinning.vm-upcall test={} events={}",
                testName, pinningStream.vmUpcallEvents());
        }
        // DEBUG, not INFO: nearly every run has some, from class loading on first use.
        if (pinningStream.singleRoundSites() > 0) {
            log.debug("runner.pinning.single-round test={} sites={}",
                testName, pinningStream.singleRoundSites());
        }
    }

    /**
     * Completes the benchmark and {@return the regression {@code failOnBenchmarkRegression}
     * found, or {@code null}}
     *
     * <p>A regression is a verdict, not a cleanup failure: {@link #execute} throws it only on the
     * success path, after the gate, so a body failure or a {@code failOn} finding still outranks
     * it. Any other failure to complete is logged and does not fail the run.
     */
    private static @Nullable BenchmarkRegressionException completeBenchmark(BenchmarkRecorder benchmarkRecorder) {
        try {
            benchmarkRecorder.complete();
        } catch (BenchmarkRegressionException e) {
            return e;
        } catch (Exception e) {
            log.warn("Benchmark completion failed: {}", e.toString(), e);
        }
        return null;
    }


    /**
     * Tells every round-scoped detector that a round begins, before its workers exist.
     *
     * <p>Round-epoch happens-before: the previous round's workers all finished before
     * {@code latch.await} returned, and the runner thread's submissions start the next round, so
     * rounds are totally ordered through the runner thread. Flushing telemetry first attributes
     * agent-captured accesses still in the ring to the round that produced them (a no-op without
     * the agent); the epoch bumps then let the record-based detectors refuse to pair accesses from
     * different rounds, which cannot race. {@code RoundStartFanOutIsCompleteTest} reads the calls
     * below by their receivers, so they keep these parameter names.
     */
    private static void markRoundStart(Phase1DetectorSet phase1, AsyncTestContext phase2Context,
                                       @Nullable JfrPinningStream pinningStream) {
        TelemetryRegistry.flush();
        if (phase1.visibility != null) {
            phase1.visibility.markInvocationStart();
        }
        if (phase1.race != null) {
            phase1.race.markInvocationStart();
        }
        if (phase1.atomicity != null) {
            phase1.atomicity.markInvocationStart();
        }
        phase2Context.markInvocationStart();
        // Before this round's workers start: the stream reports only a site that pinned
        // in two rounds, and the workers already started are the earlier rounds'.
        if (pinningStream != null) {
            pinningStream.markRound();
        }
    }

    /**
     * Closes a round's controlled schedule, releasing any worker still waiting for a turn, and
     * {@return its summary, which the failure report reads to say whether the seed replays}
     */
    private static ControlledSchedule.Summary closeSchedule(ControlledSchedule schedule, String testName,
                                                            int round, long seed) {
        schedule.close();
        ControlledSchedule.Summary summary = schedule.summary();
        log.debug("runner.schedule.round test={} round={} seed={} steps={} switches={} "
                + "escapes={} fallbacks={}", testName, round, seed,
            summary.steps(), summary.switches(), summary.escapes(),
            summary.fallbacks());
        return summary;
    }

    /** Says once per run, at INFO, that a controlled schedule had no point to interleave at. */
    private static void announceUnpointedSchedule(String testName) {
        log.info("runner.schedule.unpointed test={} reason=\"no worker reached a "
                + "scheduling point, so the controlled schedule ran each body whole "
                + "and interleaved nothing\" hint=\"run with the agent's fields=true or "
                + "collections=true, or call AsyncTestContext.schedulePoint() "
                + "where a worker may be interrupted\"", testName);
    }

    /**
     * Runs the round's {@code @AfterEachInvocation} hooks and {@return the failure the round
     * ends with: {@code roundFailure} with a hook failure attached as suppressed, the hook
     * failure when the round itself passed, or {@code null}}
     *
     * <p>The after-hooks deliberately do not run in a bare {@code finally}. Java only
     * auto-suppresses in try-with-resources: when a {@code finally} block throws, the in-flight
     * exception is discarded outright. So a teardown hook that failed used to replace the round's
     * {@code RoundTimeoutError}, taking its thread dump and its suppressed per-worker causes with
     * it and leaving the user a message about a teardown method instead. That fired precisely when
     * the diagnosis mattered most, because after-hooks typically reset the state a timed-out round
     * corrupted, so a corrupt round and a throwing hook are correlated, not independent.
     */
    private static @Nullable Throwable runAfterHooks(@Nullable Object testInstance, List<Method> hooks,
                                                     @Nullable Throwable roundFailure) {
        try {
            invokeLifecycleMethods(testInstance, hooks);
        } catch (Throwable hookFailure) { // NOPMD - broad: any hook failure, same policy
            if (roundFailure == null) {
                return hookFailure;
            }
            roundFailure.addSuppressed(hookFailure);
        }
        return roundFailure;
    }


    /**
     * Discovers the {@code @BeforeEachInvocation} / {@code @AfterEachInvocation} hooks of
     * {@code target}, with the inheritance rules of {@code @BeforeEach} / {@code @AfterEach}.
     *
     * <p>A hook is inherited from a superclass unless a subclass declares a method of the same
     * signature, annotated or not; the subclass method then supersedes it, and is itself a hook
     * only if it carries the annotation. Superseding is decided on the signature alone, as JUnit
     * does, so a private superclass hook is replaced too. Without this rule an overridden hook was
     * collected at both levels, and since {@code Method.invoke} on the superclass method dispatches
     * virtually to the override, the override ran twice per round; an un-annotated override was
     * invoked through its annotated parent although it is not a hook at all.
     *
     * <p>Before-hooks run superclass first and after-hooks subclass first, again as JUnit orders
     * them, so a subclass can rely on its parent's state being set up before its own hook runs and
     * torn down after.
     *
     * @param superclassFirst {@code true} for before-hooks, {@code false} for after-hooks
     */
    private static <A extends java.lang.annotation.Annotation> List<Method> findLifecycleMethods(
            @Nullable Object target, Class<A> annotationType, boolean superclassFirst) {
        if (target == null) return List.of();
        List<List<Method>> levels = new ArrayList<>();
        Set<String> superseded = new java.util.HashSet<>();
        Class<?> klass = target.getClass();
        while (klass != null && klass != Object.class) {
            List<Method> level = new ArrayList<>();
            for (Method m : klass.getDeclaredMethods()) {
                if (m.getParameterCount() != 0 || m.getReturnType() != void.class) {
                    continue;
                }
                // Zero parameters, so the name is the whole signature.
                if (!superseded.add(m.getName())) {
                    continue;
                }
                if (m.isAnnotationPresent(annotationType)) {
                    m.setAccessible(true);
                    level.add(m);
                }
            }
            levels.add(level);
            klass = klass.getSuperclass();
        }
        if (superclassFirst) {
            java.util.Collections.reverse(levels);
        }
        List<Method> found = new ArrayList<>();
        for (List<Method> level : levels) {
            found.addAll(level);
        }
        return found;
    }

    private static void invokeLifecycleMethods(@Nullable Object target, List<Method> methods) {
        if (target == null || methods.isEmpty()) return;
        for (Method m : methods) {
            try {
                m.invoke(target);   // made accessible once, by findLifecycleMethods
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
