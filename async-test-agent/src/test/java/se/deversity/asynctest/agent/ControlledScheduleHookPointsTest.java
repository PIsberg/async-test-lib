package se.deversity.asynctest.agent;

import com.example.agentfixture.HookOnlyRaces;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestRunner;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A substituted collection or atomic call is a scheduling point of a controlled schedule (#1023).
 *
 * <p>{@link ControlledScheduleWeavingTest} covers woven field accesses. Here the fixture has none:
 * {@link HookOnlyRaces} takes its map and its atomic as parameters, so a {@code containsKey} then
 * {@code put} on a shared {@code HashMap}, and a {@code get} then {@code set} on an
 * {@code AtomicInteger}, contain no woven instruction but the substituted calls. Before those hooks
 * were points, the whole body ran in one turn and no seed could lose either update; a check-then-act
 * in unmodified code was the one race the schedule could not force.
 *
 * <p>Its own class because {@code selfAttach} is once per JVM and {@code forkEvery=1} gives each
 * test class its own.
 */
@Tag("e2e")
class ControlledScheduleHookPointsTest {

    @BeforeAll
    static void attachWithCollectionAndFieldWeaving() {
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            supported = false;
        }
        assumeTrue(supported, "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");
        AsyncTestAgent.selfAttach("includes=com.example.agentfixture,fields=true,collections=true");
    }

    @AfterAll
    static void stopRegistry() {
        TelemetryRegistry.stop();
    }

    private static AsyncTestConfig controlled(long seed) {
        return AsyncTestConfig.builder().threads(2).invocations(1).replaySeed(seed)
                .controlledSchedule(true).timeoutMs(60_000).licenseMockMode(true).build();
    }

    /** Two workers claim one key in a fresh map; returns how many believed they put it. */
    private static int winners(long seed) throws Throwable {
        Map<String, Integer> map = new HashMap<>();
        AtomicInteger winners = new AtomicInteger();
        AsyncTestRunner.run(controlled(seed), () -> {
            if (HookOnlyRaces.claim(map, "key", 1)) {
                winners.incrementAndGet();
            }
        });
        return winners.get();
    }

    /** Two workers bump one fresh atomic once each; returns the final count. */
    private static int finalCount(long seed) throws Throwable {
        AtomicInteger count = new AtomicInteger();
        AsyncTestRunner.run(controlled(seed), () -> HookOnlyRaces.bump(count));
        return count.get();
    }

    @Test
    void aCheckThenPutOnAPlainMapIsSplitBySomeSeed_andThatSeedReplays() throws Throwable {
        assertRaceFoundAndReplayed(ControlledScheduleHookPointsTest::winners, 2, 1,
                "containsKey then put on a shared HashMap");
    }

    @Test
    void aGetThenSetOnAnAtomicIsSplitBySomeSeed_andThatSeedReplays() throws Throwable {
        assertRaceFoundAndReplayed(ControlledScheduleHookPointsTest::finalCount, 1, 2,
                "get then set on a shared AtomicInteger");
    }

    private interface Round {
        int outcome(long seed) throws Throwable;
    }

    private static void assertRaceFoundAndReplayed(Round round, int lost, int kept, String shape)
            throws Throwable {
        long losing = 0L;
        long keeping = 0L;
        for (long seed = 1; seed <= 64 && (losing == 0L || keeping == 0L); seed++) {
            int outcome = round.outcome(seed);
            if (outcome == lost && losing == 0L) {
                losing = seed;
            } else if (outcome == kept && keeping == 0L) {
                keeping = seed;
            }
        }
        assertTrue(losing != 0L, "no seed in 1..64 switched workers inside " + shape
                + ", so the substituted calls are not scheduling points");
        assertTrue(keeping != 0L, "every seed in 1..64 lost the race in " + shape);
        for (int run = 0; run < 10; run++) {
            assertEquals(lost, round.outcome(losing), "seed " + losing + " must lose the race on every replay");
            assertEquals(kept, round.outcome(keeping), "seed " + keeping + " must keep it on every replay");
        }
    }
}
