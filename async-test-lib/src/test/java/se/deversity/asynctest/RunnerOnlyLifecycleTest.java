package se.deversity.asynctest;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * The round lifecycle methods on {@link AsyncTestContext} are the runner's, and a test body cannot
 * call them (#947).
 *
 * <p>They are public only because {@code ConcurrencyRunner} lives in another package, and a body
 * reaches the instance through {@link AsyncTestContext#get()}. From a body,
 * {@code openRendezvousForRound} replaced the round's rendezvous while its workers were in it,
 * {@code markInvocationStart} reset every round-scoped detector mid-round, and
 * {@code setReplaySeedForRound} rewrote the seed the report prints. The runner calls them on its
 * own thread, which never has a run's context installed; a body's thread always has.
 */
@E2E
class RunnerOnlyLifecycleTest {

    static final AtomicInteger REFUSED = new AtomicInteger();
    static final AtomicInteger ALLOWED = new AtomicInteger();

    @BeforeEach
    void reset() {
        REFUSED.set(0);
        ALLOWED.set(0);
    }

    static void attempt(Consumer<AsyncTestContext> call) {
        try {
            call.accept(AsyncTestContext.get());
            ALLOWED.incrementAndGet();
        } catch (IllegalStateException refused) {
            REFUSED.incrementAndGet();
        }
    }

    @Test
    @DisplayName("a body calling a round lifecycle method is refused, and the run itself is unaffected")
    void aBodyCannotDriveTheRound() {
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(BodyDrivesTheRound.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.succeeded(1));

        assertEquals(0, ALLOWED.get(), "a body was allowed to drive the round");
        assertEquals(BodyDrivesTheRound.THREADS * BodyDrivesTheRound.ROUNDS * 4, REFUSED.get(),
                "every call from every body execution is refused");
    }

    @Test
    @DisplayName("outside a run, as the runner calls them, the lifecycle methods work")
    void theRunnerThreadMayDriveTheRound() {
        AsyncTestContext context = new AsyncTestContext(AsyncTestConfig.builder().threads(2).build());
        for (Consumer<AsyncTestContext> call : List.<Consumer<AsyncTestContext>>of(
                c -> c.setReplaySeedForRound(42L),
                AsyncTestContext::markInvocationStart,
                c -> c.openRendezvousForRound(2, 1_000L),
                AsyncTestContext::markRoundTimedOut)) {
            assertDoesNotThrow(() -> call.accept(context));
        }
    }

    static class BodyDrivesTheRound {
        static final int THREADS = 2;
        static final int ROUNDS = 3;

        @AsyncTest(threads = THREADS, invocations = ROUNDS)
        void body() {
            attempt(c -> c.openRendezvousForRound(THREADS, 1_000L));
            attempt(AsyncTestContext::markInvocationStart);
            attempt(c -> c.setReplaySeedForRound(42L));
            attempt(AsyncTestContext::markRoundTimedOut);
        }
    }
}
