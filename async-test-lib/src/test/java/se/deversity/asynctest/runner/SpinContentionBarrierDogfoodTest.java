package se.deversity.asynctest.runner;

import org.junit.jupiter.api.AfterAll;
import se.deversity.asynctest.AsyncTest;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Dogfoods {@link SpinContentionBarrier}, the runner's own lock-free rendezvous, with
 * {@code @AsyncTest}.
 *
 * <p>Why this exists: the barrier is only reached in production when
 * {@code async-test.spin-barrier.enabled} is set and virtual threads are off, so on a default CI
 * run nothing executes it except {@code SpinContentionBarrierTest}, which drives it once through a
 * hand-rolled fixed thread pool. Once is the wrong number for a phase counter that overflows, is
 * reused every round, and publishes with a release fence. Here the library's own harness supplies
 * the repetition and the collision: {@link #THREADS} workers are released together by the runner's
 * barrier and immediately collide on a nested {@code SpinContentionBarrier},
 * {@link #ROUNDS} times over.
 *
 * <p>The barrier instance is shared across every round because the runner drives all rounds
 * against a single test-class instance, which is what puts the cyclic reuse under test rather
 * than a fresh barrier each time.
 */
class SpinContentionBarrierDogfoodTest {

    private static final int THREADS = 4;
    private static final int ROUNDS = 200;

    /** Reused every round: cyclic reuse is the part a one-shot test never reaches. */
    private final SpinContentionBarrier barrier = new SpinContentionBarrier(THREADS);

    /** Arrivals in the current cycle, reset by the last thread out. */
    private final AtomicInteger arrived = new AtomicInteger();

    /** Departures in the current cycle; reaching THREADS means every peer has read {@link #arrived}. */
    private final AtomicInteger departed = new AtomicInteger();

    private static final AtomicInteger BODY_EXECUTIONS = new AtomicInteger();

    @AsyncTest(threads = THREADS, invocations = ROUNDS, useVirtualThreads = false, timeoutMs = 20_000)
    void noThreadIsReleasedBeforeEveryPeerHasArrived() throws InterruptedException {
        BODY_EXECUTIONS.incrementAndGet();
        arrived.incrementAndGet();

        barrier.await();

        // The barrier's entire contract. A thread released early sees a short count here.
        assertEquals(THREADS, arrived.get(),
                "released from the barrier before all " + THREADS + " threads had arrived");

        // Reset only once every peer has read arrived: a thread increments departed strictly
        // after its assertion, so departed == THREADS implies all reads are done.
        if (departed.incrementAndGet() == THREADS) {
            departed.set(0);
            arrived.set(0);
        }
    }

    @AfterAll
    static void everyRoundCompletedOnEveryThread() {
        assertEquals(THREADS * ROUNDS, BODY_EXECUTIONS.get(),
                "a cycle was skipped or a thread never made it through the barrier");
    }
}
