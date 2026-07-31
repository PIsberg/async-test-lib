package se.deversity.asynctest.fixture.detectors;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.DetectorType;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.TimeUnit;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.PINNED_SEED;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.insideRound;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.pause;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.spin;

/**
 * Phase 1 — the three foundation detectors: {@code DEADLOCKS}, {@code VISIBILITY},
 * {@code LIVELOCKS}.
 *
 * <p>None of the three has a public per-detector accessor on {@code AsyncTestContext}, so
 * these fixtures assert the weaker {@link DetectorFixtureSupport#insideRound(long)} claim.
 * See that class for why the {@code shared*} accessors are deliberately not used here.
 *
 * <p>Corresponding examples: {@code examples/06-deadlock},
 * {@code examples/02-visibility-volatile-flag}, {@code examples/07-livelock}.
 */
class Phase01FoundationDetectorsFixtureTest {

    /** Ordered {@code tryLock} across two locks — the shape a deadlock detector watches, minus the hang. */
    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               replaySeed = PINNED_SEED, includes = {DetectorType.DEADLOCKS})
    void deadlocks() {
        insideRound(PINNED_SEED);

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
    }

    /** A plain (non-volatile) flag written by one worker and read by the other. */
    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               replaySeed = PINNED_SEED, includes = {DetectorType.VISIBILITY})
    void visibility() {
        insideRound(PINNED_SEED);

        UnsafeFlag flag = new UnsafeFlag();
        flag.raise();
        flag.observe();
    }

    /** Two workers backing off each other without either making progress for long. */
    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               replaySeed = PINNED_SEED, includes = {DetectorType.LIVELOCKS})
    void livelocks() {
        insideRound(PINNED_SEED);

        AtomicBoolean yielded = new AtomicBoolean();
        for (int attempt = 0; attempt < 4 && !yielded.get(); attempt++) {
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
