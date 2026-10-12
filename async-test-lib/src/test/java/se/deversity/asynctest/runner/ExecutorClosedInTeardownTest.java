package se.deversity.asynctest.runner;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.AsyncTestListener;
import se.deversity.asynctest.AsyncTestListenerRegistry;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.E2E;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.diagnostics.ExecutorShutdownDetector;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * An executor the run created and the test class shuts down in {@code @AfterEach} is not leaked
 * (#996, item 3).
 *
 * <p>Analysis runs inside the test method, before JUnit calls {@code @AfterEach}, so a pool closed in
 * teardown used to read as never shut down. The "never shut down" verdict now waits until the test
 * instance's {@code @AfterEach} methods have run, and is reported then if the pool is still open.
 */
@E2E
class ExecutorClosedInTeardownTest {

    private static final String DETECTOR = "ExecutorShutdownDetector";
    private static final Map<String, String> REPORTS = new ConcurrentHashMap<>();

    /** Creates and declares a pool, submits one task, and leaves the pool open. */
    static ExecutorService openAPool() {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        ExecutorShutdownDetector detector = AsyncTestContext.executorShutdownDetector();
        detector.recordExecutorCreated(pool, "teardown-pool");
        pool.submit(() -> { });
        detector.recordTaskSubmitted(pool);
        return pool;
    }

    /** Opens a pool in the body and shuts it down in {@code @AfterEach}. */
    public static class ClosedInAfterEach {
        static volatile ExecutorService pool;

        @AsyncTest(threads = 1, invocations = 1, includes = DetectorType.EXECUTOR_SHUTDOWN)
        void body() {
            pool = openAPool();
        }

        @AfterEach
        void closeThePool() throws InterruptedException {
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Opens a pool in the body and never shuts it down, until the class is done. Its
     * {@code @AfterEach} closes nothing, so the verdict waits for teardown and is reported then.
     */
    public static class NeverClosed {
        static volatile ExecutorService pool;

        @AsyncTest(threads = 1, invocations = 1, includes = DetectorType.EXECUTOR_SHUTDOWN)
        void body() {
            pool = openAPool();
        }

        @AfterEach
        void closeNothing() {
            // A teardown that forgets the pool.
        }

        @AfterAll
        static void releaseTheThreads() {
            pool.shutdownNow();
        }
    }

    /** {@link NeverClosed} with a gate the finding's MEDIUM severity reaches. */
    public static class NeverClosedUnderAGate {
        static volatile ExecutorService pool;

        @AsyncTest(threads = 1, invocations = 1, includes = DetectorType.EXECUTOR_SHUTDOWN,
                   failOn = FailOn.MEDIUM)
        void body() {
            pool = openAPool();
        }

        @AfterEach
        void closeNothing() {
            // A teardown that forgets the pool, so the gate applies after it.
        }

        @AfterAll
        static void releaseTheThreads() {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a held verdict still fails the test at failOn once teardown has not closed the pool")
    void theHeldVerdictIsGated() {
        REPORTS.clear();
        var results = EngineTestKit.engine("junit-jupiter").selectors(selectClass(NeverClosedUnderAGate.class))
                .execute().testEvents();
        assertTrue(results.failed().count() == 1,
                "the run passed its own gate before teardown, so the verdict held past @AfterEach "
                        + "must apply failOn = MEDIUM itself: " + results.list());
        Throwable failure = results.failed().stream().findFirst()
                .flatMap(e -> e.getPayload(org.junit.platform.engine.TestExecutionResult.class))
                .flatMap(org.junit.platform.engine.TestExecutionResult::getThrowable).orElseThrow();
        assertTrue(failure.getMessage().contains("at or above failOn")
                        && failure.getMessage().contains(DETECTOR), failure.getMessage());
    }

    @Test
    @DisplayName("a pool shut down in @AfterEach is not reported as leaked")
    void closedInAfterEachIsSilent() {
        run(ClosedInAfterEach.class);
        assertFalse(REPORTS.containsKey(DETECTOR),
                "the test class shut the pool down in @AfterEach, which JUnit runs after the test "
                        + "method and so after analysis. Report: " + REPORTS.get(DETECTOR));
    }

    @Test
    @DisplayName("a pool still open after @AfterEach is reported")
    void neverClosedIsReported() {
        run(NeverClosed.class);
        String report = REPORTS.get(DETECTOR);
        assertTrue(report != null && report.contains("teardown-pool"),
                "nothing shut the pool down, so it leaks past the test. Reports: " + REPORTS);
    }

    private static void run(Class<?> fixture) {
        REPORTS.clear();
        AsyncTestListener capture = new AsyncTestListener() {
            @Override
            public void onDetectorReport(String detectorName, String report) {
                REPORTS.put(detectorName, report);
            }
        };
        try (AsyncTestListenerRegistry.Registration r = AsyncTestListenerRegistry.registerScoped(capture)) {
            EngineTestKit.engine("junit-jupiter").selectors(selectClass(fixture)).execute();
        }
    }
}
