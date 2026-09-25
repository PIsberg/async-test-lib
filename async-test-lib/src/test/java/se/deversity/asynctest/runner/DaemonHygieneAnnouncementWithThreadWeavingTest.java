package se.deversity.asynctest.runner;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.slf4j.LoggerFactory;
import se.deversity.asynctest.AgentThreadHooks;
import se.deversity.asynctest.E2E;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Once the agent has woven {@code Thread.setDaemon}, the daemon-hygiene detector judges the
 * decision rather than the inherited flag (#731), so {@code runner.detector.inert} for it would be
 * false. {@link ConcurrencyRunnerLogContractTest} pins the announcement without the agent.
 *
 * <p>A class of its own because {@link AgentThreadHooks#threadWeavingInstalled()} is JVM-wide and
 * one-way; Maven and Gradle both give every test class its own JVM, so no other test sees it.
 */
@DisplayName("runner.detector.inert with thread weaving installed")
@E2E
class DaemonHygieneAnnouncementWithThreadWeavingTest {

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

    @Test
    @DisplayName("the daemon-hygiene detector is not called inert once setDaemon is woven")
    void theDaemonHygieneDetectorIsNotAnnouncedInert() {
        AgentThreadHooks.threadWeavingInstalled();
        ConcurrencyRunner.DAEMON_HYGIENE_INERT_LOGGED.set(false);

        EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(ConcurrencyRunnerLogContractTest.NarratedDummy.class))
            .execute()
            .testEvents()
            .assertStatistics(stats -> stats.succeeded(1));

        List<String> announcements = appender.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .filter(m -> m.startsWith("runner.detector.inert"))
            .filter(m -> m.contains("detector=DaemonThreadHygieneDetector"))
            .toList();
        assertTrue(announcements.isEmpty(),
            "with the thread table woven a bare new Thread(...).start() in a body is judged, so "
                + "telling the user to read a clean report as 'not observed' is wrong: "
                + announcements);
    }
}
