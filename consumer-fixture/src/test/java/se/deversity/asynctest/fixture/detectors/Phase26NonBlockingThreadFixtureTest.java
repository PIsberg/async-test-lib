package se.deversity.asynctest.fixture.detectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import reactor.core.scheduler.NonBlocking;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertAllReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;

/**
 * Phase 26, threads that must not block — {@code BLOCKING_ON_NON_BLOCKING_THREAD}.
 *
 * <p>The fixture proves the detector is reachable from the published artifact, records a blocking
 * call on a thread of a non-blocking scheduler through its public recording API, and asserts in
 * {@code @AfterAll} that the finding came back out through {@link AsyncFindings}.
 *
 * <p>See {@code docs/DETECTOR_CATALOG.md} entry 148 for the buggy-vs-fixed pair.
 */
class Phase26NonBlockingThreadFixtureTest {

    private static AsyncFindings findings;

    /** A thread of a non-blocking scheduler, such as Schedulers.parallel() makes. */
    static final class NonBlockingWorker extends Thread implements NonBlocking {
        NonBlockingWorker(Runnable task) {
            super(task, "parallel-fixture");
        }
    }

    @BeforeAll
    static void collectFindings() {
        findings = AsyncFindings.collect();
    }

    @AfterAll
    static void everyFedDetectorReported() {
        try {
            assertAllReported(findings, "BlockingOnNonBlockingThreadDetector");
        } finally {
            findings.close();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 30_000, licenseMockMode = true,
               includes = {DetectorType.BLOCKING_ON_NON_BLOCKING_THREAD})
    void blockingOnNonBlockingThread() throws InterruptedException {
        reachable("blockingOnNonBlockingThreadDetector()", AsyncTestContext::blockingOnNonBlockingThreadDetector);

        // The hazard: a blocking call on a thread the scheduler made for not blocking.
        var detector = AsyncTestContext.blockingOnNonBlockingThreadDetector();
        Thread loop = new NonBlockingWorker(() -> detector.recordBlockingCall("Thread.sleep", Thread.currentThread()));
        loop.start();
        loop.join();
    }
}
