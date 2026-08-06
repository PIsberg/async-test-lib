package se.deversity.asynctest.runner;
import se.deversity.asynctest.E2E;

import org.junit.jupiter.api.Test;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the replay-seed contract:
 *
 * <ul>
 *   <li>{@code AsyncTestContext.replaySeed()} returns 0L outside an @AsyncTest round.</li>
 *   <li>Inside a round, all worker threads see the same per-round seed.</li>
 *   <li>An explicit {@code @AsyncTest(replaySeed = N)} pins every round to N.</li>
 *   <li>Default ({@code replaySeed = 0}) produces a fresh seed per round.</li>
 * </ul>
 */
@E2E
class ReplaySeedTest {

    static final ConcurrentLinkedQueue<Long> SEEDS_OBSERVED = new ConcurrentLinkedQueue<>();
    static final AtomicReference<Long> SEED_FROM_BODY = new AtomicReference<>();

    @Test
    void replaySeed_outsideTestReturnsZero() {
        assertEquals(0L, AsyncTestContext.replaySeed(),
                "Outside any @AsyncTest round, replaySeed must default to 0L");
    }

    @Test
    void explicitSeed_isStableAcrossRoundsAndWorkers() {
        SEEDS_OBSERVED.clear();

        Events tests = EngineTestKit.engine("junit-jupiter")
                .selectors(DiscoverySelectors.selectClass(ExplicitSeedFixture.class))
                .execute()
                .testEvents();

        tests.assertStatistics(s -> s.started(1).succeeded(1));

        // 3 invocations × 4 workers = 12 observations; every one must equal 42424242L.
        assertEquals(12, SEEDS_OBSERVED.size());
        for (Long s : SEEDS_OBSERVED) {
            assertEquals(42424242L, s,
                    "Explicit @AsyncTest(replaySeed=42424242L) must be seen identically by every worker in every round");
        }
    }

    @Test
    void defaultSeed_variesAcrossRounds() {
        SEEDS_OBSERVED.clear();

        Events tests = EngineTestKit.engine("junit-jupiter")
                .selectors(DiscoverySelectors.selectClass(DefaultSeedFixture.class))
                .execute()
                .testEvents();

        tests.assertStatistics(s -> s.started(1).succeeded(1));

        // 5 invocations × 2 workers = 10 observations. With a random seed per round
        // we should see ≤ 5 distinct values (within-round equality) but > 1
        // distinct value across rounds. Probability of all 5 rounds drawing the same
        // long is ~ 1/2^192 — safe to assert.
        java.util.Set<Long> distinct = new java.util.HashSet<>(SEEDS_OBSERVED);
        assertTrue(distinct.size() > 1,
                "Default seed must vary across rounds; observed: " + distinct);
        assertTrue(distinct.size() <= 5,
                "Within a round all workers must see the same seed; observed > 5 distinct: " + distinct);
        assertFalse(distinct.contains(0L),
                "Default path must generate non-zero seeds; observed contained 0L");
    }

    // ---- Fixtures ----

    static class ExplicitSeedFixture {
        @AsyncTest(threads = 4, invocations = 3, timeoutMs = 10_000, replaySeed = 42424242L,
                detectAll = false, licenseMockMode = true)
        void recordSeedExplicit() {
            SEEDS_OBSERVED.add(AsyncTestContext.replaySeed());
        }
    }

    static class DefaultSeedFixture {
        @AsyncTest(threads = 2, invocations = 5, timeoutMs = 10_000,
                detectAll = false, licenseMockMode = true)
        void recordSeedDefault() {
            SEEDS_OBSERVED.add(AsyncTestContext.replaySeed());
        }
    }
}
