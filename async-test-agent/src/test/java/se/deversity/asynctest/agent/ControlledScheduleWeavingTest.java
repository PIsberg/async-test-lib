package se.deversity.asynctest.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.agentfixture.DirectFieldMutationBean;
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

    private Logger runnerLog;
    private ListAppender<ILoggingEvent> appender;
    private Level previousLevel;

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

    private List<String> rounds() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("runner.schedule.round")).toList();
    }

    /** What one round left in the counter, and whether the schedule kept it replayable. */
    private record Outcome(int count, boolean exact) {}

    /** Two workers each run {@link DirectFieldMutationBean#increment()} once. */
    private Outcome finalCount(long seed) throws Throwable {
        DirectFieldMutationBean bean = new DirectFieldMutationBean();
        AsyncTestRunner.run(AsyncTestConfig.builder().threads(2).invocations(1).replaySeed(seed)
                .controlledSchedule(true).timeoutMs(60_000).licenseMockMode(true).build(), bean::increment);
        List<String> rounds = rounds();
        return new Outcome(bean.observedCount(), rounds.get(rounds.size() - 1).contains("escapes=0"));
    }

    @Test
    void aWovenLostUpdateFoundOnceIsReplayedEveryTime() throws Throwable {
        // Exact replay is promised for a round no worker escaped. An escape, a worker the watcher
        // saw blocked or stalled, is counted precisely because it voids the promise: on
        // 2026-10-26 (Test Suite (21), job 121916226372) the tenth replay of seed 1 counted
        // escapes=1 in a 15 ms round and kept the update the seed had lost. So a seed is chosen,
        // and a replay compared, only from escape-free rounds, as ControlledScheduleTest does
        // (#1063); a real nondeterminism with no escape still fails here.
        long losing = 0L;
        long keeping = 0L;
        for (long seed = 1; seed <= 64 && (losing == 0L || keeping == 0L); seed++) {
            Outcome outcome = finalCount(seed);
            if (!outcome.exact()) {
                continue;
            }
            if (outcome.count() == 1 && losing == 0L) {
                losing = seed;
            } else if (outcome.count() == 2 && keeping == 0L) {
                keeping = seed;
            }
        }
        assertTrue(losing != 0L, "no seed in 1..64 switched workers between the woven read and write"
                + " of count, so the woven accesses are not reaching the schedule");
        assertTrue(keeping != 0L, "every seed in 1..64 lost the update");
        int compared = 0;
        for (int run = 0; run < 10; run++) {
            Outcome lost = finalCount(losing);
            if (lost.exact()) {
                assertEquals(1, lost.count(), "seed " + losing + " must lose the update on every replay, and"
                        + " the round did not escape: " + rounds());
                compared++;
            }
            Outcome kept = finalCount(keeping);
            if (kept.exact()) {
                assertEquals(2, kept.count(), "seed " + keeping + " must keep both updates on every replay,"
                        + " and the round did not escape: " + rounds());
                compared++;
            }
        }
        assertTrue(compared >= 16, "too few escape-free replays to test replay (" + compared + " of 20): "
                + rounds());
    }
}
