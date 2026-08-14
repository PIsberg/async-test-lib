package se.deversity.asynctest.fixture.detectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertAllReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertNoneReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.spin;

/**
 * The executor / future / latch detectors that shipped implemented and tested but for a
 * while had no {@code DetectorType} constant: {@code LATCH_MISUSE},
 * {@code EXECUTOR_DEADLOCK}, {@code FUTURE_BLOCKING}.
 *
 * <p>These three are the reason this package is worth having. They were reachable only by
 * instantiating the detector class directly; now they are wired, and a consumer can select
 * them through {@code includes}. That is precisely what these fixtures assert — and what
 * would have failed before the wiring landed.
 *
 * <p>Corresponding examples: {@code examples/27-latch-misuse},
 * {@code examples/25-executor-deadlock}, {@code examples/26-future-blocking}.
 */
class UnwiredExecutorDetectorsFixtureTest {

    private static AsyncFindings findings;

    @BeforeAll
    static void collectFindings() {
        findings = AsyncFindings.collect();
    }

    @AfterAll
    static void everyFedDetectorReported() {
        try {
            assertAllReported(findings,
                    "LatchMisuseDetector",
                    "ExecutorDeadlockDetector",
                    "FutureBlockingDetector");
        } finally {
            findings.close();
        }
    }


    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.LATCH_MISUSE})
    void latchMisuse() {
        reachable("latchMisuseDetector()", AsyncTestContext::latchMisuseDetector);

        // The misuse is awaiting a latch nobody counts down, or counting down more times
        // than the latch was sized for. Both are contained here by a timed await.
        CountDownLatch latch = new CountDownLatch(1);
        latch.countDown();
        latch.countDown();               // extra countDown on an already-open latch
        try {
            latch.await(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.EXECUTOR_DEADLOCK})
    void executorDeadlock() {
        reachable("executorDeadlockDetector()", AsyncTestContext::executorDeadlockDetector);

        // A task on a single-thread executor that waits for another task on the same
        // executor deadlocks. The fixture submits independent work and bounds the wait.
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> { spin(32); }).get(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            // Not the subject of this fixture.
        } finally {
            pool.shutdownNow();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.FUTURE_BLOCKING})
    void futureBlocking() {
        reachable("futureBlockingDetector()", AsyncTestContext::futureBlockingDetector);

        // get() with no timeout blocks forever if the task never finishes; the timed form
        // is the fix, and the untimed call is what the detector reports.
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = pool.submit(() -> { spin(32); });
            future.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            // Not the subject of this fixture.
        } finally {
            pool.shutdownNow();
        }
    }
}
