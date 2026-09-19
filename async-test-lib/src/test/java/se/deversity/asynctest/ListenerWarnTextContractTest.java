package se.deversity.asynctest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.report.Violation;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins what a user reads when their listener throws: one WARN per hook, naming the hook, carrying
 * the exception, and never silencing the listener registered after it. The text is somebody
 * else's build log (invariants 8 and 9), and the seven guarded calls share one helper (#702), so
 * this is what keeps a change to that helper from rewording all seven at once.
 */
@DisplayName("a throwing listener: the WARN text and peer delivery")
class ListenerWarnTextContractTest {

    private static final RuntimeException BOOM = new IllegalStateException("boom");

    private ch.qos.logback.classic.Logger registryLog;
    private ListAppender<ILoggingEvent> appender;
    private Level previousLevel;
    private AsyncTestListenerRegistry.Snapshot snapshot;

    @BeforeEach
    void captureRegistryLog() {
        snapshot = AsyncTestListenerRegistry.snapshot();
        AsyncTestListenerRegistry.clearAll();
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        registryLog = context.getLogger(AsyncTestListenerRegistry.class);
        previousLevel = registryLog.getLevel();
        registryLog.setLevel(Level.WARN);
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        registryLog.addAppender(appender);
    }

    @AfterEach
    void restore() {
        registryLog.detachAppender(appender);
        appender.stop();
        registryLog.setLevel(previousLevel);
        AsyncTestListenerRegistry.restoreSnapshot(snapshot);
    }

    @Test
    @DisplayName("each of the seven hooks logs its own name, and the next listener still hears it")
    void everyHookIsContainedAndNamed() {
        List<String> peerHeard = new ArrayList<>();
        AsyncTestListenerRegistry.register(new ThrowingListener());
        AsyncTestListenerRegistry.register(new RecordingListener(peerHeard));

        AsyncTestListenerRegistry.fireInvocationStarted(0, 2);
        AsyncTestListenerRegistry.fireInvocationCompleted(0, 5L);
        AsyncTestListenerRegistry.fireTestFailed(new AssertionError("body"));
        AsyncTestListenerRegistry.fireDetectorReport("RaceConditionDetector", "a finding");
        AsyncTestListenerRegistry.fireTimeout(100L);

        String suffix = " threw: java.lang.IllegalStateException: boom";
        assertEquals(List.of(
                "AsyncTestListener.onInvocationStarted" + suffix,
                "AsyncTestListener.onInvocationCompleted" + suffix,
                "AsyncTestListener.onTestFailed" + suffix,
                "AsyncTestListener.onDetectorReport" + suffix,
                "AsyncTestListener.onStructuredReport" + suffix,
                "AsyncTestListener.onViolation" + suffix,
                "AsyncTestListener.onTimeout" + suffix),
            appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        for (ILoggingEvent event : appender.list) {
            assertEquals(Level.WARN, event.getLevel());
            assertEquals(BOOM.getClass().getName(), event.getThrowableProxy().getClassName(),
                    "the exception travels with the event, so the stack trace is printed");
        }
        assertEquals(List.of("onInvocationStarted", "onInvocationCompleted", "onTestFailed",
                "onDetectorReport", "onStructuredReport", "onViolation", "onTimeout"), peerHeard);
    }

    private static final class ThrowingListener implements AsyncTestListener {
        @Override public void onInvocationStarted(int round, int threads) { throw BOOM; }
        @Override public void onInvocationCompleted(int round, long durationMs) { throw BOOM; }
        @Override public void onTestFailed(Throwable cause) { throw BOOM; }
        @Override public void onDetectorReport(String detectorName, String report) { throw BOOM; }
        @Override public void onStructuredReport(String detectorName, IssueSeverity severity,
                String report) { throw BOOM; }
        @Override public void onViolation(Violation violation) { throw BOOM; }
        @Override public void onTimeout(long timeoutMs) { throw BOOM; }
    }

    private record RecordingListener(List<String> heard) implements AsyncTestListener {
        @Override public void onInvocationStarted(int round, int threads) { heard.add("onInvocationStarted"); }
        @Override public void onInvocationCompleted(int round, long durationMs) { heard.add("onInvocationCompleted"); }
        @Override public void onTestFailed(Throwable cause) { heard.add("onTestFailed"); }
        @Override public void onDetectorReport(String detectorName, String report) { heard.add("onDetectorReport"); }
        @Override public void onStructuredReport(String detectorName, IssueSeverity severity,
                String report) { heard.add("onStructuredReport"); }
        @Override public void onViolation(Violation violation) { heard.add("onViolation"); }
        @Override public void onTimeout(long timeoutMs) { heard.add("onTimeout"); }
    }
}
