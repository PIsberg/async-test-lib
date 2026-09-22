package se.deversity.asynctest.runner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.EngineTestKit;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.AsyncTestListener;
import se.deversity.asynctest.AsyncTestListenerRegistry;
import se.deversity.asynctest.E2E;
import se.deversity.asynctest.FailOn;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * What {@code DaemonThreadHygieneDetector} can and cannot see from inside a test body, pinned
 * through the real runner because neither half is visible from a unit test of the detector.
 *
 * <p>A platform thread inherits the daemon flag of the thread that created it. The runner's
 * workers are daemon threads in both thread modes - virtual threads always are, and the platform
 * workers were made daemon so that a deadlocked worker could not hold the JVM open (#479) - so
 * every {@code new Thread(...)} a body constructs is daemon before the body can get it wrong, and
 * the detector's rule ("report a non-daemon thread that is still alive") has nothing to judge.
 * That is the first test, and it is the reason {@code examples/46-daemon-thread} stopped
 * demonstrating anything on 2026-09-03.
 *
 * <p>The second test is what is left, and it is not nothing: a {@code ThreadFactory} that sets
 * the flag itself decides it regardless of who calls it, and
 * {@code Executors.defaultThreadFactory()} - the factory behind every JDK thread pool - sets it
 * to false explicitly. A body that leaks a pool thread is still reported, all the way to the
 * {@code failOn} gate. The pair is what keeps the first test from being read as "the detector is
 * dead": it is the announcement's hint, executed.
 */
@DisplayName("DaemonThreadHygieneDetector observability from a test body")
@E2E
class DaemonThreadHygieneObservabilityTest {

    private static final Map<String, String> REPORTS = new ConcurrentHashMap<>();

    /**
     * Released before any assertion runs. The factory fixture's threads are non-daemon by
     * construction, which is the whole point of it, so a gate that fails must not be able to
     * leave the JVM unable to exit. Re-armed per test rather than shared: released once, it
     * would let the next fixture's threads terminate before analysis, and a detector that only
     * reports live threads would go silent for a reason that has nothing to do with the
     * property under test.
     */
    private static volatile CountDownLatch release = new CountDownLatch(1);

    private static Runnable parkUntilReleased() {
        CountDownLatch armed = release;
        return () -> {
            try {
                armed.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    @BeforeEach
    void arm() {
        release = new CountDownLatch(1);
    }

    @AfterEach
    void releaseParkedThreads() {
        release.countDown();
    }

    /** A bare {@code new Thread(...)}, the shape every "forgot setDaemon(true)" example uses. */
    static class BareThreadFixture {
        @AsyncTest(threads = 2, invocations = 1, detectAll = false, useVirtualThreads = false,
                detectDaemonThreadHygiene = true, failOn = FailOn.LOW)
        void body() {
            Thread leaked = new Thread(parkUntilReleased(), "bare-" + Thread.currentThread().threadId());
            leaked.start();
            AsyncTestContext.daemonThreadHygieneDetector().recordThread(leaked, "bare-thread");
        }
    }

    /** The same leak through the factory every JDK thread pool uses, which sets the flag itself. */
    static class FactoryThreadFixture {
        @AsyncTest(threads = 2, invocations = 1, detectAll = false, useVirtualThreads = false,
                detectDaemonThreadHygiene = true, failOn = FailOn.LOW)
        void body() {
            Thread leaked = Executors.defaultThreadFactory().newThread(parkUntilReleased());
            leaked.start();
            AsyncTestContext.daemonThreadHygieneDetector().recordThread(leaked, "pool-thread");
        }
    }

    @Test
    @DisplayName("a thread the body constructs is daemon by inheritance, so nothing is reported")
    void aThreadCreatedInTheBodyCannotBeJudged() {
        EngineExecutionResults results = run(BareThreadFixture.class);

        assertFalse(REPORTS.containsKey("DaemonThreadHygieneDetector"),
                "a report here means the runner's workers are no longer daemon threads. That is "
                        + "the state #479 was about: a worker that deadlocks then keeps the JVM "
                        + "alive after the build has given up. Check that before celebrating the "
                        + "detector. Report: " + REPORTS.get("DaemonThreadHygieneDetector"));
        results.testEvents().assertStatistics(stats -> stats.succeeded(1).failed(0));
    }

    @Test
    @DisplayName("a thread from Executors.defaultThreadFactory() is non-daemon, and is reported")
    void aThreadFromTheJdkFactoryIsStillJudged() {
        EngineExecutionResults results = run(FactoryThreadFixture.class);

        assertTrue(REPORTS.containsKey("DaemonThreadHygieneDetector"),
                "Executors.defaultThreadFactory() calls setDaemon(false) on every thread it "
                        + "hands back, whoever creates it, so this leak is observable and the "
                        + "detector must report it. Without this direction the test above reads "
                        + "as 'the detector never fires'. Reports: " + REPORTS.keySet());
        results.testEvents().assertStatistics(stats -> stats.failed(1));
        String failure = results.testEvents().failed().stream()
                .map(event -> event.getPayload(TestExecutionResult.class)
                        .flatMap(TestExecutionResult::getThrowable)
                        .map(Throwable::getMessage)
                        .orElse(""))
                .findFirst()
                .orElse("");
        assertTrue(failure.contains("DaemonThreadHygieneDetector"),
                "the finding has to reach the failOn gate, which is the path a user reads; a "
                        + "detector that reports only to the listener fails nothing: " + failure);
    }

    private static EngineExecutionResults run(Class<?> fixture) {
        REPORTS.clear();
        AsyncTestListener capture = new AsyncTestListener() {
            @Override
            public void onDetectorReport(String detectorName, String report) {
                REPORTS.put(detectorName, report);
            }
        };
        try (AsyncTestListenerRegistry.Registration r = AsyncTestListenerRegistry.registerScoped(capture)) {
            return EngineTestKit.engine("junit-jupiter").selectors(selectClass(fixture)).execute();
        }
    }
}
