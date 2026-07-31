package se.deversity.asynctest.fixture.detectors;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import java.util.concurrent.atomic.AtomicInteger;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.PINNED_SEED;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.insideRound;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.spin;

/**
 * Phase 3, runtime-analysis group — {@code RACE_CONDITIONS} through
 * {@code INTERRUPT_MISHANDLING}.
 *
 * <p>Four of the five have no public per-detector accessor and use
 * {@link DetectorFixtureSupport#insideRound(long)}; {@code ATOMICITY_VIOLATIONS} does have
 * one ({@code atomicityValidator()}) and asserts the stronger claim.
 *
 * <p>Corresponding examples: {@code examples/08-race-condition},
 * {@code examples/23-thread-local-leak}, {@code examples/21-busy-wait},
 * {@code examples/22-atomicity-violation}, {@code examples/24-interrupt-mishandling}.
 */
class Phase03RuntimeAnalysisDetectorsFixtureTest {

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               replaySeed = PINNED_SEED, includes = {DetectorType.RACE_CONDITIONS})
    void raceConditions() {
        insideRound(PINNED_SEED);

        // Unsynchronised read-modify-write on shared state: the classic race.
        UNGUARDED.value++;
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               replaySeed = PINNED_SEED, includes = {DetectorType.THREAD_LOCAL_LEAKS})
    void threadLocalLeaks() {
        insideRound(PINNED_SEED);

        // Set on a pooled/virtual carrier and removed again — the leak is forgetting remove().
        LEAKY.set("per-worker state");
        try {
            spin(32);
        } finally {
            LEAKY.remove();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               replaySeed = PINNED_SEED, includes = {DetectorType.BUSY_WAITING})
    void busyWaiting() {
        insideRound(PINNED_SEED);

        // A bounded spin loop — the shape of a busy-wait, without the unbounded part.
        AtomicInteger gate = new AtomicInteger();
        for (int i = 0; i < 128 && gate.get() == 0; i++) {
            Thread.onSpinWait();
            if (i == 127) {
                gate.set(1);
            }
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.ATOMICITY_VIOLATIONS})
    void atomicityViolations() {
        reachable("atomicityValidator()", AsyncTestContext::atomicityValidator);

        // Two atomic operations that are not atomic together: check-then-act on an atomic.
        AtomicInteger counter = new AtomicInteger();
        if (counter.get() < 10) {
            counter.incrementAndGet();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               replaySeed = PINNED_SEED, includes = {DetectorType.INTERRUPT_MISHANDLING})
    void interruptMishandling() {
        insideRound(PINNED_SEED);

        // Catch and restore, which is the correct handling the detector contrasts against
        // the swallowing form covered by INTERRUPT_SWALLOWING.
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Shared, unguarded, mutated by every worker — deliberately. */
    private static final Counter UNGUARDED = new Counter();

    private static final ThreadLocal<String> LEAKY = new ThreadLocal<>();

    private static final class Counter {
        private int value;
    }
}
