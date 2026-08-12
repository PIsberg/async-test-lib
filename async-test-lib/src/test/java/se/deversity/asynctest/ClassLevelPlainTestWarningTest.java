package se.deversity.asynctest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Pins the warning for the quietest way to get no testing at all out of this library.
 *
 * <p>Annotate a class with {@code @AsyncTest}, write the methods as {@code @Test} — the reflex
 * spelling — and every one of them runs once, on one thread, with no barrier, no detectors and no
 * licence check, and passes. Class-level {@code @AsyncTest} is a real documented feature, but it
 * only feeds {@code @TestTemplate} methods: JUnit never consults a template provider for a
 * {@code @Test}. The suite had no test for that combination, and the run produces nothing to
 * distinguish it from a genuine concurrent pass except a display name that isn't there.
 *
 * <p>The warning cannot be an error. A class holding async templates alongside ordinary unit tests
 * is legitimate and looks identical at runtime, so failing would break correct suites to catch an
 * ambiguous one. What it can do is say so, once, in the build log.
 */
@E2E
class ClassLevelPlainTestWarningTest {

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger extensionLogger;

    @BeforeEach
    void captureLogs() {
        extensionLogger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger("se.deversity.asynctest.extension.AsyncTestExtension");
        appender = new ListAppender<>();
        appender.start();
        extensionLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        extensionLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("a plain @Test in an @AsyncTest class warns that it did not run concurrently")
    void plainTestMethodInAnAsyncTestClassIsWarnedAbout() {
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(AsyncTestAnnotatedClass.class))
                .execute();

        List<ILoggingEvent> warnings = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("runner.asynctest.not-applied"))
                .toList();

        assertEquals(1, warnings.size(),
                "Exactly one warning was expected — once per class, not once per method, so a "
                        + "class with many plain @Test methods does not bury the build log. "
                        + "Captured: " + appender.list);

        String message = warnings.get(0).getFormattedMessage();
        assertTrue(message.contains("plainTestMethod"),
                "The warning must name the method that silently ran single-threaded, otherwise "
                        + "the reader cannot find it. Was: " + message);
        assertTrue(message.contains("@TestTemplate"),
                "The warning must name the fix. Telling somebody their test did not run without "
                        + "telling them the spelling that would have worked wastes the warning. "
                        + "Was: " + message);
    }

    @Test
    @DisplayName("a genuine @AsyncTest method produces no warning")
    void methodLevelAsyncTestIsNotWarnedAbout() {
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(MethodLevelAsyncTest.class))
                .execute();

        assertTrue(appender.list.stream()
                        .noneMatch(e -> e.getFormattedMessage().contains("runner.asynctest.not-applied")),
                "A correctly annotated method must not warn. A warning that fires on correct "
                        + "usage is noise, and noise is how a real warning gets ignored. "
                        + "Captured: " + appender.list);
    }

    /** Fixture: the mistake. */
    @AsyncTest(threads = 2, invocations = 2, detectDeadlocks = false)
    static class AsyncTestAnnotatedClass {
        @Test
        void plainTestMethod() {
            assertTrue(true);
        }
    }

    /** Fixture: the correct spelling, which must stay silent. */
    static class MethodLevelAsyncTest {
        @AsyncTest(threads = 2, invocations = 2, detectDeadlocks = false)
        void properlyAnnotated() {
            assertTrue(true);
        }
    }
}
