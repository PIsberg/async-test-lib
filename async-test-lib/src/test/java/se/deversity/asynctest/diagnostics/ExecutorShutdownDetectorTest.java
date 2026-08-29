package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

public class ExecutorShutdownDetectorTest {

    @Test
    void testNoIssuesWhenNotUsed() {
        ExecutorShutdownDetector detector = new ExecutorShutdownDetector();
        ExecutorShutdownDetector.ExecutorShutdownReport report = detector.analyze();
        assertNotNull(report);
        assertFalse(report.hasIssues());
    }

    @Test
    void testNoIssuesWhenShutdownAndAwaitTermination() throws Exception {
        ExecutorShutdownDetector detector = new ExecutorShutdownDetector();
        ExecutorService ex = Executors.newSingleThreadExecutor();
        detector.recordExecutorCreated(ex, "clean-pool");
        ex.submit(() -> {});
        detector.recordTaskSubmitted(ex);
        ex.shutdown();
        ex.awaitTermination(1, TimeUnit.SECONDS);
        detector.recordShutdownCalled(ex, false);
        detector.recordAwaitTerminationCalled(ex);

        ExecutorShutdownDetector.ExecutorShutdownReport report = detector.analyze();
        assertFalse(report.hasIssues(), "Clean shutdown should report no issues");
        ex.shutdownNow();
    }

    @Test
    void testDetectsExecutorNotShutDown() {
        ExecutorShutdownDetector detector = new ExecutorShutdownDetector();
        ExecutorService ex = Executors.newSingleThreadExecutor();
        detector.recordExecutorCreated(ex, "leaking-pool");
        detector.recordTaskSubmitted(ex);
        // shutdown never called

        ExecutorShutdownDetector.ExecutorShutdownReport report = detector.analyze();
        assertTrue(report.hasIssues(), "Should detect missing shutdown");
        assertFalse(report.notShutDown.isEmpty(), "notShutDown list should contain the entry");
        assertTrue(report.notShutDown.get(0).contains("leaking-pool"));
        ex.shutdownNow();
    }

    @Test
    void testDetectsShutdownWithoutAwaitTermination() {
        ExecutorShutdownDetector detector = new ExecutorShutdownDetector();
        ExecutorService ex = Executors.newSingleThreadExecutor();
        detector.recordExecutorCreated(ex, "no-await-pool");
        detector.recordTaskSubmitted(ex);
        detector.recordShutdownCalled(ex, false);
        // awaitTermination never called

        ExecutorShutdownDetector.ExecutorShutdownReport report = detector.analyze();
        assertTrue(report.hasIssues(), "Should detect missing awaitTermination");
        assertFalse(report.noAwaitTermination.isEmpty());
        assertTrue(report.noAwaitTermination.get(0).contains("no-await-pool"));
        ex.shutdownNow();
    }

    @Test
    void testShutdownWithAwaitTerminationFlagInSingleCall() {
        ExecutorShutdownDetector detector = new ExecutorShutdownDetector();
        ExecutorService ex = Executors.newSingleThreadExecutor();
        detector.recordExecutorCreated(ex, "good-pool");
        detector.recordTaskSubmitted(ex);
        detector.recordShutdownCalled(ex, true); // convenience flag

        ExecutorShutdownDetector.ExecutorShutdownReport report = detector.analyze();
        assertFalse(report.hasIssues(), "Should be clean with convenience flag");
        ex.shutdownNow();
    }

    @Test
    void testAutoNameFromIdentityHash() {
        ExecutorShutdownDetector detector = new ExecutorShutdownDetector();
        ExecutorService ex = Executors.newCachedThreadPool();
        detector.recordExecutorCreated(ex, null); // no name
        detector.recordTaskSubmitted(ex);

        ExecutorShutdownDetector.ExecutorShutdownReport report = detector.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.notShutDown.get(0).startsWith("executor@"));
        ex.shutdownNow();
    }

    @Test
    void testNullSafety() {
        ExecutorShutdownDetector detector = new ExecutorShutdownDetector();
        assertDoesNotThrow(() -> {
            detector.recordExecutorCreated(null, "null-pool");
            detector.recordTaskSubmitted(null);
            detector.recordShutdownCalled(null, false);
            detector.recordAwaitTerminationCalled(null);
        });
        assertFalse(detector.analyze().hasIssues());
    }

    @Test
    void testReportToStringContainsFixHint() {
        ExecutorShutdownDetector detector = new ExecutorShutdownDetector();
        ExecutorService ex = Executors.newSingleThreadExecutor();
        detector.recordExecutorCreated(ex, "hint-pool");
        detector.recordTaskSubmitted(ex);

        String str = detector.analyze().toString();
        assertTrue(str.contains("EXECUTOR SHUTDOWN ISSUES DETECTED"));
        assertTrue(str.contains("Fix"));
        ex.shutdownNow();
    }

    @Test
    void testNoIssueWhenNoTasksSubmitted() {
        ExecutorShutdownDetector detector = new ExecutorShutdownDetector();
        ExecutorService ex = Executors.newSingleThreadExecutor();
        detector.recordExecutorCreated(ex, "idle-pool");
        // no tasks submitted, no shutdown — still no issue since no tasks were submitted

        ExecutorShutdownDetector.ExecutorShutdownReport report = detector.analyze();
        assertFalse(report.hasIssues(), "No tasks submitted means no leak risk");
        ex.shutdownNow();
    }

    /**
     * The case #387 is about: an executor this code was handed, not one it created.
     *
     * <p>A shared static pool, an injected dependency and a framework-managed executor all reach
     * the code under test already built, and none of them is its to close. The detector's only
     * ownership signal is {@link ExecutorShutdownDetector#recordExecutorCreated}, so an executor
     * that was never declared has to stay untracked no matter how much traffic it sees. Without
     * this pinned, narrowing the rule by accident or widening it by agent feeding would both look
     * like a passing build.
     */
    @Test
    @DisplayName("an executor that was never declared is not reported, however many tasks it took")
    void anUndeclaredExecutorIsNotThisScopesToClose() {
        ExecutorShutdownDetector detector = new ExecutorShutdownDetector();
        ExecutorService handedToUs = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 25; i++) {
                detector.recordTaskSubmitted(handedToUs);
            }

            ExecutorShutdownDetector.ExecutorShutdownReport report = detector.analyze();

            assertFalse(report.hasIssues(),
                    "not shutting down an executor you were handed is correct, and this detector "
                            + "has no way to know it was handed one except that nobody declared "
                            + "it. Reporting here would report the common correct case: " + report);
        } finally {
            handedToUs.shutdownNow();
        }
    }

    /**
     * The other side of the same contract, pinned so the cost of misusing the API is visible.
     *
     * <p>Declaring an executor this scope did not create makes the detector report correct code.
     * That is not a defect to fix in the detector - nothing in the event stream distinguishes an
     * owned executor from a borrowed one - it is the reason the javadoc says what
     * {@code recordExecutorCreated} means, and the reason this detector is not agent-fed: weaving
     * would declare every executor in the program and produce exactly this finding everywhere.
     */
    @Test
    @DisplayName("declaring an executor you did not create reports correct code, which is the trap")
    void declaringABorrowedExecutorReportsCorrectCode() {
        ExecutorShutdownDetector detector = new ExecutorShutdownDetector();
        ExecutorService handedToUs = Executors.newFixedThreadPool(2);
        try {
            detector.recordExecutorCreated(handedToUs, "borrowed-pool");
            detector.recordTaskSubmitted(handedToUs);

            ExecutorShutdownDetector.ExecutorShutdownReport report = detector.analyze();

            assertTrue(report.hasIssues(),
                    "the declaration is the ownership signal, so declaring a borrowed executor "
                            + "buys the finding. Pinned because it is the cost of the design, not "
                            + "an accident of it");
        } finally {
            handedToUs.shutdownNow();
        }
    }
}
