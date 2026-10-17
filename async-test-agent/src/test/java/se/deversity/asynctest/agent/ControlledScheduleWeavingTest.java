package se.deversity.asynctest.agent;

import com.example.agentfixture.DirectFieldMutationBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestRunner;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every field access the agent weaves is a scheduling point of a controlled schedule, so the read
 * and the write of an unmodified {@code count = count + 1} are two places the round can change
 * hands, and a seed that lost the update loses it on every replay. {@code ControlledScheduleTest}
 * in the library covers the schedule itself with explicit points; this class is the woven half.
 *
 * <p>Its own class because {@code selfAttach} is once per JVM and {@code forkEvery=1} gives each
 * test class its own.
 */
@Tag("e2e")
class ControlledScheduleWeavingTest {

    @BeforeAll
    static void attachWithFieldWeaving() {
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            supported = false;
        }
        assumeTrue(supported, "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");
        AsyncTestAgent.selfAttach("includes=com.example.agentfixture,fields=true");
    }

    @AfterAll
    static void stopRegistry() {
        TelemetryRegistry.stop();
    }

    /** Two workers each run {@link DirectFieldMutationBean#increment()} once; returns the count. */
    private static int finalCount(long seed) throws Throwable {
        DirectFieldMutationBean bean = new DirectFieldMutationBean();
        AsyncTestRunner.run(AsyncTestConfig.builder().threads(2).invocations(1).replaySeed(seed)
                .controlledSchedule(true).timeoutMs(60_000).licenseMockMode(true).build(), bean::increment);
        return bean.observedCount();
    }

    @Test
    void aWovenLostUpdateFoundOnceIsReplayedEveryTime() throws Throwable {
        long losing = 0L;
        long keeping = 0L;
        for (long seed = 1; seed <= 64 && (losing == 0L || keeping == 0L); seed++) {
            int count = finalCount(seed);
            if (count == 1 && losing == 0L) {
                losing = seed;
            } else if (count == 2 && keeping == 0L) {
                keeping = seed;
            }
        }
        assertTrue(losing != 0L, "no seed in 1..64 switched workers between the woven read and write"
                + " of count, so the woven accesses are not reaching the schedule");
        assertTrue(keeping != 0L, "every seed in 1..64 lost the update");
        for (int run = 0; run < 10; run++) {
            assertEquals(1, finalCount(losing), "seed " + losing + " must lose the update on every replay");
            assertEquals(2, finalCount(keeping), "seed " + keeping + " must keep both updates on every replay");
        }
    }
}
