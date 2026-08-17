package se.deversity.asynctest.fixture.detectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertAllReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.registerOnce;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.spin;

/**
 * Phase 22, CompletableFuture publication and lambda capture group —
 * {@code COMPLETABLE_FUTURE_COMPLETION_RACE},
 * {@code COMPLETABLE_FUTURE_CANCELLATION_PROPAGATION},
 * {@code COMPLETABLE_FUTURE_COMBINATOR_MISUSE} and {@code LAMBDA_LOST_UPDATE}.
 *
 * <p>Each fixture proves its detector is reachable from the published artifact, then runs the
 * hazard through the detector's public recording API and asserts in {@code @AfterAll} that the
 * finding came back out through {@link AsyncFindings} — the same channel the printed report and
 * the {@code failOn} gate read.
 *
 * <p>All four hazards here are ordering-sensitive, so none of them is left to luck. The
 * completion race needs two threads to call {@code complete} on one future; the cancellation
 * fixture needs work recorded strictly after the cancel; the lost-update fixture needs both
 * threads to read the counter before either writes. The first two are ordered with
 * {@code registerOnce}'s gate and the last with an explicit {@link CyclicBarrier}, rather than
 * with sleeps that pass on a fast machine and fail on a loaded CI leg.
 *
 * <p>See {@code docs/DETECTOR_CATALOG.md} for the buggy-vs-fixed pair behind each one.
 */
class Phase22CompletableFutureAndLambdaCaptureFixtureTest {

    private static AsyncFindings findings;

    @BeforeAll
    static void collectFindings() {
        findings = AsyncFindings.collect();
    }

    @AfterAll
    static void everyFedDetectorReported() {
        try {
            assertAllReported(findings,
                    "CompletableFutureCompletionRaceDetector",
                    "CompletableFutureCancellationPropagationDetector",
                    "CompletableFutureCombinatorMisuseDetector",
                    "LambdaLostUpdateDetector");
        } finally {
            findings.close();
        }
    }

    /** One completion slot both workers race for — the whole point of the first fixture. */
    private static final CompletableFuture<String> SHARED_RESULT = new CompletableFuture<>();

    /** The dependent stage both workers watch being cancelled. */
    private static final CompletableFuture<String> SHARED_VIEW = new CompletableFuture<>();

    /** Captured by {@link #SHARED_TASK}; mutated without synchronization, which is the hazard. */
    private static final int[] SHARED_COUNTER = {0};

    /** One lambda instance for both workers, so the detector sees one captured variable. */
    private static final Runnable SHARED_TASK = () -> { };

    /** Holds both workers between reading the counter and writing it back. */
    private static final CyclicBarrier READ_BARRIER = new CyclicBarrier(2);

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.COMPLETABLE_FUTURE_COMPLETION_RACE})
    void completableFutureCompletionRace() {
        reachable("cfCompletionRaceDetector()", AsyncTestContext::cfCompletionRaceDetector);

        // The hazard: both workers publish into one future. complete() is first-writer-wins, so
        // exactly one of these calls returns false and its value is discarded. Completing through
        // the detector is what lets it read that boolean - the return value nobody reads in
        // production is the entire evidence here.
        var detector = AsyncTestContext.cfCompletionRaceDetector();
        detector.complete(SHARED_RESULT, "fixtureLookup", "from-" + Thread.currentThread().getName());
        spin(8);
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.COMPLETABLE_FUTURE_CANCELLATION_PROPAGATION})
    void completableFutureCancellationPropagation() {
        reachable("cfCancellationPropagationDetector()",
                AsyncTestContext::cfCancellationPropagationDetector);

        // The hazard: cancelling the dependent stage stops nothing upstream, and cancel(true)
        // never interrupts a CompletableFuture whatever the flag says.
        var detector = AsyncTestContext.cfCancellationPropagationDetector();
        String stage = "fetch-" + Thread.currentThread().getName();
        detector.recordWorkStarted("fixtureReport", stage, Thread.currentThread());

        // registerOnce cancels on one worker and holds the other until it has, so the work below
        // is recorded strictly after the cancel on both.
        registerOnce("phase22-cancel-view",
                () -> detector.cancel(SHARED_VIEW, "fixtureReport", "view", true));

        spin(8);
        // The upstream stage runs to the end regardless - which is exactly the surprise.
        detector.recordWorkCompleted("fixtureReport", stage, Thread.currentThread());
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.COMPLETABLE_FUTURE_COMBINATOR_MISUSE})
    void completableFutureCombinatorMisuse() {
        reachable("cfCombinatorMisuseDetector()", AsyncTestContext::cfCombinatorMisuseDetector);

        // The hazard: allOf() hands back a future and waits for nothing. This worker builds one
        // over two constituents, lets a single constituent finish, and drops the combined future
        // without joining - so the code moves on while the other write is still in flight. The
        // combinator is per-worker, so no cross-thread ordering is needed to make it fire.
        var detector = AsyncTestContext.cfCombinatorMisuseDetector();
        CompletableFuture<Void> first = new CompletableFuture<>();
        CompletableFuture<Void> second = new CompletableFuture<>();
        CompletableFuture<Void> all = CompletableFuture.allOf(first, second);

        detector.recordCombinator(all, "fixtureWrites-" + Thread.currentThread().getName(),
                "allOf", 2, Thread.currentThread());
        first.complete(null);
        detector.recordConstituentCompleted(all, "first", false, Thread.currentThread());
        spin(8);
        // 'second' never completes and nobody joins 'all'.
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.LAMBDA_LOST_UPDATE})
    void lambdaLostUpdate() {
        reachable("lambdaLostUpdateDetector()", AsyncTestContext::lambdaLostUpdateDetector);

        // The hazard: a captured int[] updated with read-modify-write from two threads. The
        // barrier makes both workers read before either writes, so both observe the same
        // pre-value and one increment is provably lost - which is the finding, rather than the
        // weaker "this lambda ran on two threads and mutated something".
        var detector = AsyncTestContext.lambdaLostUpdateDetector();
        int before = SHARED_COUNTER[0];
        try {
            READ_BARRIER.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (BrokenBarrierException | TimeoutException e) {
            return;   // the assertion in @AfterAll reports the missing finding
        }
        int after = before + 1;
        SHARED_COUNTER[0] = after;
        detector.recordReadModifyWrite(SHARED_TASK, "counter", before, after, Thread.currentThread());
    }
}
