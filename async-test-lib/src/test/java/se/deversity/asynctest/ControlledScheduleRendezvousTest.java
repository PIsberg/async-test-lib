package se.deversity.asynctest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import se.deversity.asynctest.diagnostics.WorkerSlot;
import se.deversity.asynctest.runner.ConcurrencyRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code rendezvous()} under a controlled schedule replays exactly (#1021).
 *
 * <p>A worker waiting at a rendezvous is parked in a {@code Phaser}, outside any scheduling point,
 * so the waiting workers used to mark it escaped, and every round with a rendezvous was reported as
 * not guaranteed to replay; after the phaser released them, the order they came back in decided
 * who ran next. The rendezvous is the documented way to line workers up between a read and a write,
 * so this made the two features cancel each other.
 */
class ControlledScheduleRendezvousTest {

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

    /** Three workers read, meet, then write in an order the schedule decides; returns that order. */
    private static List<String> trace(long seed) throws Throwable {
        List<String> trace = Collections.synchronizedList(new ArrayList<>());
        int[] cell = new int[1];
        AsyncTestRunner.run(AsyncTestConfig.builder().threads(3).invocations(1).replaySeed(seed)
                .controlledSchedule(true).timeoutMs(60_000).licenseMockMode(true).build(), () -> {
            int slot = WorkerSlot.current();
            int seen = cell[0];
            trace.add(slot + ":read");
            AsyncTestContext.rendezvous();
            trace.add(slot + ":met");
            AsyncTestContext.schedulePoint();
            cell[0] = seen + 1;
            trace.add(slot + ":wrote");
        });
        assertEquals(1, cell[0], "every worker read before any wrote, so the updates collapse to one");
        return new ArrayList<>(trace);
    }

    @Test
    void aReadRendezvousWriteBodyReplaysExactly_withNoEscape() throws Throwable {
        boolean differs = false;
        List<String> first = trace(1);
        for (long seed = 1; seed <= 12; seed++) {
            List<String> once = trace(seed);
            assertEquals(once, trace(seed), "seed " + seed + " must replay the same interleaving");
            differs |= !once.equals(first);
        }
        assertTrue(differs, "every seed gave one interleaving, so the schedule decided nothing");
        List<String> rounds = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("runner.schedule.round")).toList();
        assertTrue(rounds.stream().allMatch(m -> m.contains("escapes=0")),
                "a worker waiting at a rendezvous must not count as escaped: " + rounds);
    }
}
