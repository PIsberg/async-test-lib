package se.deversity.asynctest.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.agentfixture.LockGuardedCounters;
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
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unmodified code that holds a lock across a scheduling point replays with no escape (#1022).
 *
 * <p>The woven half of {@code ControlledScheduleLockTest}: here the monitor enter and exit calls
 * come from the field weaver and the {@code lock()}/{@code unlock()} substitutions from the
 * collection weaver, not from the test. Each increment reads and writes its counter inside the
 * critical section, two woven field accesses, so the schedule may pause the worker while it holds
 * the lock. Before the schedule knew lock ownership it handed the round to a peer that then
 * blocked on the lock, and every such round counted an escape.
 *
 * <p>Its own class because {@code selfAttach} is once per JVM and {@code forkEvery=1} gives each
 * test class its own.
 */
@Tag("e2e")
class ControlledScheduleLockWeavingTest {

    private static final int THREADS = 3;

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

    private void assertEveryRoundReplays(Consumer<LockGuardedCounters> increment,
                                         ToIntFunction<LockGuardedCounters> count) throws Throwable {
        for (long seed = 1; seed <= 20; seed++) {
            LockGuardedCounters counters = new LockGuardedCounters();
            AsyncTestRunner.run(AsyncTestConfig.builder().threads(THREADS).invocations(1)
                    .replaySeed(seed).controlledSchedule(true).timeoutMs(60_000)
                    .licenseMockMode(true).build(), () -> increment.accept(counters));
            assertEquals(THREADS, count.applyAsInt(counters), "seed " + seed + ": the lock guards the count");
        }
        List<String> rounds = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("runner.schedule.round")).toList();
        assertEquals(20, rounds.size(), rounds.toString());
        assertTrue(rounds.stream().allMatch(m -> m.contains("escapes=0")),
                "a worker paused inside a woven critical section cost an escape, so the schedule handed"
                        + " the round to a peer that blocked on the lock: " + rounds);
        assertTrue(rounds.stream().anyMatch(m -> !m.contains("switches=0")),
                "no round changed hands, so nothing was exercised: " + rounds);
    }

    @Test
    void aSynchronizedIncrementReplaysWithNoEscape() throws Throwable {
        assertEveryRoundReplays(LockGuardedCounters::incrementUnderMonitor, LockGuardedCounters::monitorCount);
    }

    @Test
    void aReentrantLockIncrementReplaysWithNoEscape() throws Throwable {
        assertEveryRoundReplays(LockGuardedCounters::incrementUnderLock, LockGuardedCounters::lockCount);
    }
}
