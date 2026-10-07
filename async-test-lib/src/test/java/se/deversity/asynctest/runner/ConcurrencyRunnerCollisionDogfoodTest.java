package se.deversity.asynctest.runner;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.ConcurrencyTestFor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dogfoods {@link ConcurrencyRunner}'s own claim with {@code @AsyncTest}: every worker of a round
 * is inside the test body at the same time, and no round starts before the previous one has left.
 *
 * <p>That is the contention the library sells. A runner that dispatched workers one after another,
 * ran them on a pool smaller than {@code threads}, or started a round before the last one finished
 * would still execute the body {@code threads * invocations} times and report green; every detector
 * would then see far less overlap than the user asked for, and nothing would say so. Here each body
 * waits, with a bound, for all {@link #THREADS} peers on a {@code CyclicBarrier} of its own, so a
 * round that is short of a peer fails with a timeout instead of passing. What it cannot see is the
 * runner's own release barrier: workers that all start eventually meet here even without it.
 */
@ConcurrencyTestFor(ConcurrencyRunner.class)
class ConcurrencyRunnerCollisionDogfoodTest {

    private static final int THREADS = 4;
    private static final int ROUNDS = 100;

    /** Bounded well under the round timeout, so a missing peer fails here and says why. */
    private static final long PEER_WAIT_SECONDS = 10;

    private static final AtomicInteger BODY_EXECUTIONS = new AtomicInteger();

    /** Reused every round, because the runner drives all rounds against one test instance. */
    private final CyclicBarrier peers = new CyclicBarrier(THREADS);

    /** Workers currently inside the body; more than THREADS means two rounds overlapped. */
    private final AtomicInteger inBody = new AtomicInteger();

    @AsyncTest(threads = THREADS, invocations = ROUNDS, timeoutMs = 60_000, detectAll = true)
    void everyWorkerOfARoundIsInTheBodyAtOnce()
            throws InterruptedException, BrokenBarrierException {
        BODY_EXECUTIONS.incrementAndGet();
        int present = inBody.incrementAndGet();
        try {
            assertTrue(present <= THREADS,
                    present + " workers in the body at once: a round started before the last one left");
            try {
                peers.await(PEER_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new AssertionError("not all " + THREADS + " workers of a round were in the"
                        + " body within " + PEER_WAIT_SECONDS + "s: the runner did not run them"
                        + " concurrently", e);
            }
        } finally {
            inBody.decrementAndGet();
        }
    }

    @AfterAll
    static void everyWorkerRanEveryRound() {
        assertEquals(THREADS * ROUNDS, BODY_EXECUTIONS.get(),
                "the runner must execute the body threads * invocations times");
    }
}
