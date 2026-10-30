package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * An {@code @AsyncTest} method that returns a value fails the run instead of silently not running
 * (#1119).
 *
 * <p>JUnit runs no {@code @TestTemplate} that returns a value. It reports the method as a
 * discovery warning, {@code must not return a value. It will not be executed}, and the build goes
 * green with nothing run. The natural spelling of an async test, a method returning
 * {@code CompletableFuture<Void>}, hit exactly that: zero tests started, the body ran zero times,
 * and the library's whole output is "your concurrent code is fine". The extension never sees the
 * method, because JUnit drops it before asking any template provider, so the check is a launcher
 * discovery listener the library registers through {@code ServiceLoader}.
 *
 * <p>Run through the real {@link Launcher}, not {@code EngineTestKit}: the launcher is what loads
 * {@code ServiceLoader} listeners, and it is what Surefire and Gradle use.
 */
class NonVoidAsyncTestFailsTest {

    static final AtomicInteger RAN = new AtomicInteger();

    @Test
    @DisplayName("an @AsyncTest returning a CompletableFuture fails the run and names the method")
    void nonVoidAsyncTestFailsTheRun() {
        RAN.set(0);
        TestExecutionSummary summary = run(ReturnsAFuture.class);

        assertEquals(0, RAN.get(), "JUnit ran a non-void template after all; the premise changed");
        assertTrue(summary.getTotalFailureCount() >= 1,
                "a non-void @AsyncTest ran nothing and the run still passed: " + failures(summary));
        String messages = failures(summary);
        assertTrue(messages.contains("body") && messages.contains("ReturnsAFuture"),
                "the failure must name the method that did not run: " + messages);
        assertTrue(messages.contains("void") && messages.contains("awaitAsync"),
                "the failure must name the fix, a void body awaiting with AsyncAssert.awaitAsync: " + messages);
    }

    @Test
    @DisplayName("a non-void method that is not an @AsyncTest is left to JUnit's own warning")
    void nonVoidPlainTemplateIsNotOurs() {
        TestExecutionSummary summary = run(PlainNonVoidTest.class);

        assertEquals(0, summary.getTotalFailureCount(),
                "the listener failed a run over a method that is not an @AsyncTest: " + failures(summary));
    }

    @Test
    @DisplayName("a void @AsyncTest still runs and passes with the listener installed")
    void voidAsyncTestStillRuns() {
        RAN.set(0);
        TestExecutionSummary summary = run(ReturnsVoid.class);

        assertEquals(0, summary.getTotalFailureCount(), failures(summary));
        assertEquals(1, summary.getTestsSucceededCount(), "the void @AsyncTest did not run as one test");
        assertEquals(1, RAN.get(), "threads = 1, invocations = 1 runs the body once");
    }

    private static TestExecutionSummary run(Class<?> fixture) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(fixture))
                .configurationParameter("license.mock.mode", "true")
                .build();
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.execute(request, listener);
        return listener.getSummary();
    }

    private static String failures(TestExecutionSummary summary) {
        StringBuilder text = new StringBuilder();
        summary.getFailures().forEach(f -> text.append(f.getTestIdentifier().getDisplayName())
                .append(": ").append(f.getException()).append('\n'));
        return text.toString();
    }

    static class ReturnsAFuture {
        @AsyncTest(threads = 1, invocations = 1)
        CompletableFuture<Void> body() {
            RAN.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
    }

    static class ReturnsVoid {
        @AsyncTest(threads = 1, invocations = 1)
        void body() {
            RAN.incrementAndGet();
        }
    }

    static class PlainNonVoidTest {
        @Test
        int notATest() {
            return 1;
        }
    }
}
