package se.deversity.asynctest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import se.deversity.asynctest.runner.ConcurrencyRunner;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Threads the body starts join the controlled schedule (#1024).
 *
 * <p>A controlled schedule used to run only the runner's workers. A thread the body started ran
 * alongside whichever worker held the turn, so a race between a worker and its own child was neither
 * forced nor replayed, and the worker's {@code join} blocked outside a point and cost an escape.
 *
 * <p>These bodies make the calls the agent weaves around a thread by hand:
 * {@code AgentThreadHooks.threadStart} in place of {@code Thread.start()} and
 * {@code AgentThreadHooks.threadJoin} in place of {@code Thread.join()}. The agent module's
 * {@code ControlledScheduleChildThreadWeavingTest} runs the same shape through the real weaver.
 */
class ControlledScheduleChildThreadTest {

    private ch.qos.logback.classic.Logger runnerLog;
    private ListAppender<ILoggingEvent> appender;
    private Level previousLevel;

    @BeforeEach
    void captureRunnerLog() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        runnerLog = context.getLogger(ConcurrencyRunner.class);
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

    /** One worker and the child it starts each read the count, pause, and write one more. */
    private static int finalCount(long seed) throws Throwable {
        int[] cell = new int[1];
        AsyncTestRunner.run(AsyncTestConfig.builder().threads(1).invocations(1).replaySeed(seed)
                .controlledSchedule(true).timeoutMs(60_000).licenseMockMode(true).build(), () -> {
            Thread child = new Thread(() -> {
                AsyncTestContext.schedulePoint();
                int seen = cell[0];
                AsyncTestContext.schedulePoint();
                cell[0] = seen + 1;
            }, "child");
            AgentThreadHooks.threadStart(child);
            AsyncTestContext.schedulePoint();
            int seen = cell[0];
            AsyncTestContext.schedulePoint();
            cell[0] = seen + 1;
            AgentThreadHooks.threadJoin(child);
        });
        return cell[0];
    }

    @Test
    void aWorkerRacingItsOwnChildIsSplitBySomeSeed_andThatSeedReplays() throws Throwable {
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
        assertTrue(losing != 0L, "no seed in 1..64 lost the update between a worker and its child, so"
                + " the child is not interleaved by the schedule");
        assertTrue(keeping != 0L, "every seed in 1..64 lost the update");
        for (int run = 0; run < 10; run++) {
            assertEquals(1, finalCount(losing), "seed " + losing + " must lose the update on every replay");
            assertEquals(2, finalCount(keeping), "seed " + keeping + " must keep both on every replay");
        }
        List<String> rounds = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("runner.schedule.round")).toList();
        assertTrue(rounds.stream().allMatch(m -> m.contains("escapes=0")),
                "joining a scheduled child must wait inside the schedule, not block outside it: " + rounds);
    }
}
