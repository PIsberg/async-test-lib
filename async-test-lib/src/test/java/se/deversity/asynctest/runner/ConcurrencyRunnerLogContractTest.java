package se.deversity.asynctest.runner;
import se.deversity.asynctest.E2E;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import se.deversity.asynctest.AsyncTest;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import org.junit.platform.testkit.engine.EngineTestKit;

/**
 * The runner's DEBUG events are a contract.
 *
 * <p>Every timing decision in a run is derived from values resolved once inside
 * {@code execute()}: the timeout multiplier, the effective timeout, and the thread count, which
 * stress mode can override so it is not the number on the annotation. None of that is
 * observable from a test's pass or fail, which is exactly why {@code runner.config} exists and
 * why it is asserted here: it is the line that explains a run that behaved differently on CI.
 *
 * <p>Renaming an event or a field asserted here is a breaking change. See CLAUDE.md, "Logging".
 */
@DisplayName("ConcurrencyRunner DEBUG events")
@E2E
class ConcurrencyRunnerLogContractTest {

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

    private List<String> events() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private String eventStartingWith(String prefix) {
        return events().stream()
            .filter(m -> m.startsWith(prefix))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "no '" + prefix + "' event was logged; got " + events()));
    }

    @Test
    @DisplayName("one run emits the resolved configuration and one event per round")
    void theRunNarratesItsOwnConfiguration() {
        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(NarratedDummy.class))
            .execute()
            .testEvents()
            .assertStatistics(stats -> stats.succeeded(1));

        String config = eventStartingWith("runner.config");
        assertTrue(config.contains("test=narrated"), "the event names the test: " + config);
        assertTrue(config.contains("threads=3"), "the thread count actually used: " + config);
        assertTrue(config.contains("invocations=2"), "the invocation count: " + config);
        assertTrue(config.contains("effectiveTimeoutMs="),
            "the effective budget every downstream timeout derives from: " + config);
        assertTrue(config.contains("multiplier="),
            "the multiplier that explains a CI-only difference: " + config);

        assertEquals(2, events().stream().filter(m -> m.startsWith("runner.round.start")).count(),
            "one start event per invocation: " + events());
        assertEquals(2, events().stream().filter(m -> m.startsWith("runner.round.done")).count(),
            "one done event per invocation: " + events());
        assertTrue(eventStartingWith("runner.round.start").contains("seed="),
            "every round carries the replay seed, which is the reproduction handle");
        assertTrue(eventStartingWith("runner.round.done").contains("durationMs="),
            "the round reports what it cost");
    }

    @Test
    @DisplayName("the first run without the agent announces it once, at INFO")
    void agentAbsenceIsAnnouncedOnceAtInfo() {
        ConcurrencyRunner.AGENT_ABSENCE_LOGGED.set(false);

        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(NarratedDummy.class))
            .execute()
            .testEvents()
            .assertStatistics(stats -> stats.succeeded(1));
        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(NarratedDummy.class))
            .execute()
            .testEvents()
            .assertStatistics(stats -> stats.succeeded(1));

        List<ILoggingEvent> announcements = appender.list.stream()
            .filter(e -> e.getFormattedMessage().startsWith("runner.agent.absent"))
            .toList();
        assertEquals(1, announcements.size(),
            "the agent's absence is a JVM-global fact: exactly one announcement across "
                + "any number of runs, or a large suite drowns in repetition. Got: " + events());
        ILoggingEvent announcement = announcements.get(0);
        assertSame(Level.INFO, announcement.getLevel(),
            "INFO, not DEBUG: the user who never attached the agent is exactly the user "
                + "who will not have DEBUG enabled");
        String message = announcement.getFormattedMessage();
        assertTrue(message.contains("test="),
            "the event names the test that triggered it: " + message);
        assertTrue(message.contains("async-test-agent"),
            "the hint names the artifact that closes the gap: " + message);
    }

    @Test
    @DisplayName("a daemon-hygiene run on virtual threads says the detector cannot see anything")
    void daemonHygieneInertnessIsAnnouncedOnceAtInfo() {
        ConcurrencyRunner.DAEMON_HYGIENE_INERT_LOGGED.set(false);

        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(NarratedDummy.class))
            .execute()
            .testEvents()
            .assertStatistics(stats -> stats.succeeded(1));
        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(NarratedDummy.class))
            .execute()
            .testEvents()
            .assertStatistics(stats -> stats.succeeded(1));

        List<ILoggingEvent> announcements = appender.list.stream()
            .filter(e -> e.getFormattedMessage().startsWith("runner.detector.inert"))
            .filter(e -> e.getFormattedMessage().contains("detector=DaemonThreadHygieneDetector"))
            .toList();
        assertEquals(1, announcements.size(),
            "once per JVM, like runner.agent.absent: a suite of a thousand @AsyncTest methods "
                + "must not repeat it. Filtered by detector because more than one detector can "
                + "be inert in the same run. Got: " + events());
        ILoggingEvent announcement = announcements.get(0);
        assertSame(Level.INFO, announcement.getLevel(),
            "INFO, not DEBUG: the user reading a clean daemon-hygiene report is exactly the "
                + "user who will not have DEBUG enabled");
        String message = announcement.getFormattedMessage();
        assertTrue(message.contains("detector=DaemonThreadHygieneDetector"),
            "the event names the detector that cannot observe anything: " + message);
        assertTrue(message.contains("test="),
            "the event names the test that triggered it: " + message);
        assertTrue(message.contains("useVirtualThreads"),
            "the reason names the setting that makes it inert: " + message);
    }

    @Test
    @DisplayName("the same run on platform threads says nothing: the detector can see")
    void platformThreadRunsDoNotClaimTheDetectorIsInert() {
        ConcurrencyRunner.DAEMON_HYGIENE_INERT_LOGGED.set(false);

        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(PlatformThreadDummy.class))
            .execute()
            .testEvents()
            .assertStatistics(stats -> stats.succeeded(1));

        assertTrue(events().stream().noneMatch(m -> m.contains("detector=DaemonThreadHygieneDetector")),
            "on platform threads a thread created in the body inherits the worker's non-daemon "
                + "flag, so the detector can report; claiming otherwise would be false. Got: "
                + events());
    }

    @Test
    @DisplayName("a deadlock-detecting run on virtual threads says the detector cannot see the workers")
    void deadlockInertnessIsAnnouncedOnceAtInfo() {
        ConcurrencyRunner.DEADLOCK_INERT_LOGGED.set(false);

        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(NarratedDummy.class))
            .execute()
            .testEvents()
            .assertStatistics(stats -> stats.succeeded(1));
        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(NarratedDummy.class))
            .execute()
            .testEvents()
            .assertStatistics(stats -> stats.succeeded(1));

        List<ILoggingEvent> announcements = appender.list.stream()
            .filter(e -> e.getFormattedMessage().startsWith("runner.detector.inert"))
            .filter(e -> e.getFormattedMessage().contains("detector=DeadlockDetector"))
            .toList();
        assertEquals(1, announcements.size(),
            "once per JVM: a suite of a thousand @AsyncTest methods must not repeat it. Got: "
                + events());
        ILoggingEvent announcement = announcements.get(0);
        assertSame(Level.INFO, announcement.getLevel(),
            "INFO, not DEBUG: a clean deadlock report is the most reassuring output this "
                + "library produces, and the user reading one will not have DEBUG enabled");
        String message = announcement.getFormattedMessage();
        assertTrue(message.contains("test="),
            "the event names the test that triggered it: " + message);
        assertTrue(message.contains("findDeadlockedThreads"),
            "the reason names the JMX call that cannot see virtual threads: " + message);
        assertTrue(message.contains("useVirtualThreads"),
            "the hint names the setting that makes the detector able to see: " + message);
    }

    @Test
    @DisplayName("the same run on platform threads makes no claim about the deadlock detector")
    void platformThreadRunsDoNotClaimTheDeadlockDetectorIsInert() {
        ConcurrencyRunner.DEADLOCK_INERT_LOGGED.set(false);

        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(PlatformThreadDummy.class))
            .execute()
            .testEvents()
            .assertStatistics(stats -> stats.succeeded(1));

        assertTrue(events().stream().noneMatch(m -> m.contains("detector=DeadlockDetector")),
            "on platform threads findDeadlockedThreads() can close a cycle between the "
                + "workers, so claiming the detector is inert would be false. Got: " + events());
    }

    @Test
    @DisplayName("a livelock-detecting run on virtual threads says the detector cannot see the workers")
    void livelockInertnessIsAnnouncedOnceAtInfo() {
        ConcurrencyRunner.LIVELOCK_INERT_LOGGED.set(false);

        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(NarratedDummy.class))
            .execute()
            .testEvents()
            .assertStatistics(stats -> stats.succeeded(1));
        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(NarratedDummy.class))
            .execute()
            .testEvents()
            .assertStatistics(stats -> stats.succeeded(1));

        List<ILoggingEvent> announcements = appender.list.stream()
            .filter(e -> e.getFormattedMessage().startsWith("runner.detector.inert"))
            .filter(e -> e.getFormattedMessage().contains("detector=LivelockDetector"))
            .toList();
        assertEquals(1, announcements.size(),
            "once per JVM, like the other two. Got: " + events());
        ILoggingEvent announcement = announcements.get(0);
        assertSame(Level.INFO, announcement.getLevel(),
            "INFO, not DEBUG: detectAll turns this detector on, so the user affected is anyone "
                + "who has not opted out, and they will not have DEBUG enabled");
        String message = announcement.getFormattedMessage();
        assertTrue(message.contains("dumpAllThreads"),
            "the reason names the JMX call that cannot see virtual threads: " + message);
    }

    @Test
    @DisplayName("the same run on platform threads makes no claim about the livelock detector")
    void platformThreadRunsDoNotClaimTheLivelockDetectorIsInert() {
        ConcurrencyRunner.LIVELOCK_INERT_LOGGED.set(false);

        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(PlatformThreadDummy.class))
            .execute()
            .testEvents()
            .assertStatistics(stats -> stats.succeeded(1));

        assertTrue(events().stream().noneMatch(m -> m.contains("detector=LivelockDetector")),
            "on platform threads the workers are in the dump, so the detector can see them and "
                + "claiming otherwise would be false. Got: " + events());
    }

    /** Runs under the extension so the narrative is produced by the real code path. */
    static class NarratedDummy {
        private final AtomicInteger counter = new AtomicInteger();

        @AsyncTest(threads = 3, invocations = 2)
        void narrated() {
            counter.incrementAndGet();
        }
    }

    /** The same run with the virtual-thread executor turned off. */
    static class PlatformThreadDummy {
        private final AtomicInteger counter = new AtomicInteger();

        @AsyncTest(threads = 2, invocations = 1, useVirtualThreads = false)
        void onPlatformThreads() {
            counter.incrementAndGet();
        }
    }
}
