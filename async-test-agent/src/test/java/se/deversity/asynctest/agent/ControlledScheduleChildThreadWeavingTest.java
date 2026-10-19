package se.deversity.asynctest.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.agentfixture.ChildRaceBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestRunner;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A worker racing the thread it starts, in unmodified code, is interleaved and replayed by a
 * controlled schedule (#1024).
 *
 * <p>The woven half of {@code ControlledScheduleChildThreadTest}: {@link ChildRaceBean} calls
 * {@code new Thread(...).start()} and {@code join()} itself, and the agent substitutes both. Before
 * the schedule adopted children, the child ran beside whichever worker held the turn, so no seed
 * decided whether its increment landed between the worker's read and write, and the worker's
 * {@code join} blocked outside the schedule and cost an escape.
 *
 * <p>Its own class because {@code selfAttach} is once per JVM and {@code forkEvery=1} gives each
 * test class its own.
 */
@Tag("e2e")
class ControlledScheduleChildThreadWeavingTest {

    private Logger runnerLog;
    private ListAppender<ILoggingEvent> appender;
    private Level previousLevel;

    @BeforeAll
    static void attachWithFieldAndCollectionWeaving() {
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

    @BeforeEach
    void captureRunnerLog() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        runnerLog = context.getLogger("se.deversity.asynctest.runner.ConcurrencyRunner");
        previousLevel = runnerLog.getLevel();
        runnerLog.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        runnerLog.addAppender(appender);
    }

    @AfterEach
    void restore() {
        runnerLog.detachAppender(appender);
        appender.stop();
        runnerLog.setLevel(previousLevel);
    }

    private static int finalCount(long seed) throws Throwable {
        ChildRaceBean bean = new ChildRaceBean();
        AsyncTestRunner.run(AsyncTestConfig.builder().threads(1).invocations(1).replaySeed(seed)
                .controlledSchedule(true).timeoutMs(60_000).licenseMockMode(true).build(), bean::raceWithChild);
        return bean.count();
    }

    @Test
    void aWovenRaceWithAChildThreadIsFoundAndReplayed() throws Throwable {
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
        assertTrue(losing != 0L, "no seed in 1..64 lost the update between the worker and the thread"
                + " it started, so woven starts do not reach the schedule");
        assertTrue(keeping != 0L, "every seed in 1..64 lost the update");
        for (int run = 0; run < 10; run++) {
            assertEquals(1, finalCount(losing), "seed " + losing + " must lose the update on every replay");
            assertEquals(2, finalCount(keeping), "seed " + keeping + " must keep both on every replay");
        }
        List<String> rounds = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("runner.schedule.round")).toList();
        assertTrue(rounds.stream().allMatch(m -> m.contains("escapes=0")),
                "a woven join of a scheduled child must wait inside the schedule: " + rounds);
    }
}
