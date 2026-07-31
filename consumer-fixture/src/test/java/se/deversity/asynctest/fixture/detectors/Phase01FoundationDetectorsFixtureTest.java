package se.deversity.asynctest.fixture.detectors;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.TimeUnit;

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

        UnsafeFlag flag = new UnsafeFlag();
        flag.raise();
        // Recording the access is what lets the monitor see the value diverge between
        // workers — the accessor that makes this line possible is the point of the fixture.
        AsyncTestContext.visibilityMonitor().recordFieldAccess("UnsafeFlag.raised", flag.observe());
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
