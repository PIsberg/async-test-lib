package se.deversity.asynctest;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AsyncTestContext#rendezvous()} holds every worker of a round until all of them have
 * reached it, as many times per body as the body calls it.
 *
 * <p>Before it, a body that needed its workers to meet mid-body built a {@code CyclicBarrier} of
 * its own and had to get the party count, the timeout and the reuse across rounds right by hand.
 * The failure directions (a peer that throws, a peer that never arrives) need the engine and are in
 * {@code RendezvousFailureE2eTest}.
 */
@ConcurrencyTestFor(AsyncTestContext.class)
class RendezvousTest {

    private static final int THREADS = 4;
    private static final int ROUNDS = 50;

    private static final AtomicInteger BODY_EXECUTIONS = new AtomicInteger();

    /** Arrivals in the current phase; read by every worker after the rendezvous. */
    private final AtomicInteger arrived = new AtomicInteger();

    /** Workers that have read {@link #arrived} this phase; the last one resets both. */
    private final AtomicInteger departed = new AtomicInteger();

    @AsyncTest(threads = THREADS, invocations = ROUNDS, timeoutMs = 60_000)
    void noWorkerPassesTheRendezvousBeforeEveryPeerOfItsRoundArrives() {
        BODY_EXECUTIONS.incrementAndGet();
        for (int phase = 0; phase < 3; phase++) {
            arrived.incrementAndGet();
            AsyncTestContext.rendezvous();
            assertEquals(THREADS, arrived.get(),
                    "released from rendezvous " + phase + " before all " + THREADS + " workers arrived");
            // Every worker must read arrived before it is reset, and the reset must happen before
            // anyone arrives at the next phase: the second rendezvous orders both.
            if (departed.incrementAndGet() == THREADS) {
                departed.set(0);
                arrived.set(0);
            }
            AsyncTestContext.rendezvous(Duration.ofSeconds(30));
        }
    }

    @AfterAll
    static void everyBodyRan() {
        assertEquals(THREADS * ROUNDS, BODY_EXECUTIONS.get(),
                "every worker of every round must have passed every rendezvous");
    }

    @Test
    void rendezvousOutsideAnAsyncTestIsAnError() {
        IllegalStateException e = assertThrows(IllegalStateException.class, AsyncTestContext::rendezvous);
        assertEquals(true, e.getMessage().contains("@AsyncTest"), e.getMessage());
    }

    @AsyncTest(threads = 1, invocations = 3, timeoutMs = 10_000)
    void aSingleWorkerPassesStraightThrough() {
        AsyncTestContext.rendezvous();
    }
}
