package se.deversity.asynctest.fixture.detectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.TimeUnit;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertAllReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertNoneReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.pause;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.spin;

/**
 * Phase 1 — the three foundation detectors: {@code DEADLOCKS}, {@code VISIBILITY},
 * {@code LIVELOCKS}.
 *
 * <p>These three, and the four in {@link Phase03RuntimeAnalysisDetectorsFixtureTest}, had no
 * public per-detector accessor until 1.7.0 — they were reachable only through
 * {@code AsyncTestContext}'s internal {@code shared*} methods, so these fixtures could
 * assert nothing stronger than "the round is live". They now assert what every other fixture
 * in this package asserts, and drive the detectors' record APIs the way a consumer would.
 *
 * <p>Corresponding examples: {@code examples/06-deadlock},
 * {@code examples/02-visibility-volatile-flag}, {@code examples/07-livelock}.
 */
class Phase01FoundationDetectorsFixtureTest {

    private static AsyncFindings findings;

    @BeforeAll
    static void collectFindings() {
        findings = AsyncFindings.collect();
    }

    @AfterAll
    static void everyFedDetectorReported() {
        try {
            // VisibilityMonitor is the only hazard fixture here. The other two demonstrate
            // the correct pattern - locks taken with tryLock and released, a retry loop that
            // makes progress - so silence is the behaviour worth pinning. DeadlockDetector's
            // firing direction is covered by DetectionCoverageTest, which can afford a real
            // deadlock; a consumer fixture cannot, because the round would hang to its timeout.
            assertAllReported(findings, "VisibilityMonitor");
            assertNoneReported(findings, "DeadlockDetector", "LivelockDetector");
        } finally {
            findings.close();
        }
    }


    /** Ordered {@code tryLock} across two locks — the shape a deadlock detector watches, minus the hang. */
    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.DEADLOCKS})
    void deadlocks() {
        reachable("deadlockDetector()", AsyncTestContext::deadlockDetector);

        ReentrantLock first = new ReentrantLock();
        ReentrantLock second = new ReentrantLock();
        try {
            if (first.tryLock(100, TimeUnit.MILLISECONDS)) {
                try {
                    if (second.tryLock(100, TimeUnit.MILLISECONDS)) {
                        second.unlock();
                    }
                } finally {
                    first.unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // The instance analyze() is the per-round half of the API; hasDeadlock() is static
        // and needs no context.
        AsyncTestContext.deadlockDetector().analyze();
    }

    /** A plain (non-volatile) flag written by one worker and read by the other. */
    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.VISIBILITY})
    void visibility() {
        reachable("visibilityMonitor()", AsyncTestContext::visibilityMonitor);

        // Shared across the round on purpose. A per-invocation instance is observed by one
        // thread only, so its value cannot diverge and the monitor has nothing to compare.
        //
        // The two workers take different roles rather than racing for them. A non-volatile
        // boolean genuinely raced would let both workers observe the same value on a run where
        // the write happened to land first, and the fixture would pass or fail with the
        // scheduler. Roles make the divergence the monitor is meant to catch - one worker
        // seeing the pre-write value, one the post-write value - happen every time.
        UnsafeFlag flag = SHARED_FLAG;
        var monitor = AsyncTestContext.visibilityMonitor();
        if (ROLE.getAndIncrement() % 2 == 0) {
            monitor.recordFieldAccess("UnsafeFlag.raised", flag.observe());   // stale: false
            flag.raise();
        } else {
            flag.raise();
            monitor.recordFieldAccess("UnsafeFlag.raised", flag.observe());   // fresh: true
        }
    }

    /** Two workers backing off each other without either making progress for long. */
    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.LIVELOCKS})
    void livelocks() {
        reachable("livelockDetector()", AsyncTestContext::livelockDetector);

        AtomicBoolean yielded = new AtomicBoolean();
        for (int attempt = 0; attempt < 4 && !yielded.get(); attempt++) {
            // A snapshot per retry is how the detector spots threads that stay runnable
            // without progressing.
            AsyncTestContext.livelockDetector().captureSnapshot();
            Thread.onSpinWait();
            spin(64);
            if (attempt == 3) {
                yielded.set(true);
            }
        }
        pause(1);
    }

    /** Deliberately unsynchronised: the field a VISIBILITY detector would want volatile. */
    /** Assigns the reader and writer roles, so the divergence does not depend on timing. */
    private static final java.util.concurrent.atomic.AtomicInteger ROLE =
            new java.util.concurrent.atomic.AtomicInteger();

    /** One flag for the whole round: divergence needs two workers looking at one field. */
    private static final UnsafeFlag SHARED_FLAG = new UnsafeFlag();

    private static final class UnsafeFlag {
        private boolean raised;

        void raise() {
            raised = true;
        }

        boolean observe() {
            return raised;
        }
    }
}
