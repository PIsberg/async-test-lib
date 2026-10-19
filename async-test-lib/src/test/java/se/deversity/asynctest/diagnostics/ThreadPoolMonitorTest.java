package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.report.Violation;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ThreadPoolMonitorTest {

    @Test
    void anUnregisteredPoolIsNotReportedAsFullOrSaturated() {
        ThreadPoolMonitor monitor = new ThreadPoolMonitor();

        // A rejection reported for a pool nobody registered. The monitor invents a state for it,
        // and its bounds are 0 because nobody said - not because the pool has none. 0 >= 0 used
        // to make "All 0 threads busy" fire on every such pool (#501).
        monitor.recordTaskRejected(new Object(), "queue full");

        ThreadPoolMonitor.ThreadPoolReport report = monitor.analyzePoolHealth();
        assertTrue(report.threadStarvation.isEmpty(),
            "nothing here knows the pool's size, so it cannot be full: " + report.threadStarvation);
        assertTrue(report.saturatedQueues.isEmpty(),
            "nor its queue capacity: " + report.saturatedQueues);
        assertFalse(report.poolsWithRejections.isEmpty(),
            "the rejection itself was observed and still stands");
    }

    @Test
    void noPoolsReturnNoIssues() {
        ThreadPoolMonitor monitor = new ThreadPoolMonitor();
        ThreadPoolMonitor.ThreadPoolReport report = monitor.analyzePoolHealth();
        assertFalse(report.hasIssues());
    }

    @Test
    void normalTaskCycleNoIssues() {
        ThreadPoolMonitor monitor = new ThreadPoolMonitor();
        Object executor = new Object();
        monitor.registerPool(executor, "pool1", 4, 8, 100);
        monitor.recordTaskSubmitted(executor);
        monitor.recordTaskStarted(executor);
        monitor.recordTaskCompleted(executor, 10L);
        ThreadPoolMonitor.ThreadPoolReport report = monitor.analyzePoolHealth();
        assertFalse(report.hasIssues());
    }

    @Test
    void taskRejectionDetected() {
        ThreadPoolMonitor monitor = new ThreadPoolMonitor();
        Object executor = new Object();
        monitor.registerPool(executor, "pool2", 2, 4, 10);
        monitor.recordTaskRejected(executor, "queue full");
        ThreadPoolMonitor.ThreadPoolReport report = monitor.analyzePoolHealth();
        assertFalse(report.poolsWithRejections.isEmpty());
    }

    @Test
    void reportToStringWithRejection() {
        ThreadPoolMonitor monitor = new ThreadPoolMonitor();
        Object executor = new Object();
        monitor.registerPool(executor, "pool3", 1, 2, 5);
        monitor.recordTaskRejected(executor, "rejected");
        ThreadPoolMonitor.ThreadPoolReport report = monitor.analyzePoolHealth();
        String text = report.toString();
        assertNotNull(text);
        assertFalse(text.isBlank());
    }

    @Test
    void reportHasIssuesFalseWithNoIssues() {
        ThreadPoolMonitor monitor = new ThreadPoolMonitor();
        ThreadPoolMonitor.ThreadPoolReport report = monitor.analyzePoolHealth();
        assertFalse(report.hasIssues());
        assertTrue(report.poolsWithRejections.isEmpty());
        assertTrue(report.saturatedQueues.isEmpty());
        assertTrue(report.longRunningTasks.isEmpty());
        assertTrue(report.threadStarvation.isEmpty());
    }

    @Test
    void resetClearsState() {
        ThreadPoolMonitor monitor = new ThreadPoolMonitor();
        Object executor = new Object();
        monitor.registerPool(executor, "pool4", 1, 1, 1);
        monitor.recordTaskRejected(executor, "overloaded");
        assertTrue(monitor.analyzePoolHealth().hasIssues());
        monitor.reset();
        assertFalse(monitor.analyzePoolHealth().hasIssues());
    }

    @Test
    void nullExecutorHandled() {
        ThreadPoolMonitor monitor = new ThreadPoolMonitor();
        assertDoesNotThrow(() -> monitor.recordTaskSubmitted(null));
        assertDoesNotThrow(() -> monitor.recordTaskStarted(null));
        assertDoesNotThrow(() -> monitor.recordTaskCompleted(null, 0L));
    }

    @Test
    void multipleTasksTracked() {
        ThreadPoolMonitor monitor = new ThreadPoolMonitor();
        Object executor = new Object();
        monitor.registerPool(executor, "pool5", 4, 8, 200);
        for (int i = 0; i < 5; i++) {
            monitor.recordTaskSubmitted(executor);
            monitor.recordTaskStarted(executor);
            monitor.recordTaskCompleted(executor, (long) (i * 10));
        }
        ThreadPoolMonitor.ThreadPoolReport report = monitor.analyzePoolHealth();
        assertNotNull(report);
        assertFalse(report.hasIssues());
    }

    @Test
    void analyze_delegatesToAnalyzePoolHealth() {
        ThreadPoolMonitor monitor = new ThreadPoolMonitor();
        Object executor = new Object();
        monitor.registerPool(executor, "pool1", 4, 4, 10);
        monitor.recordTaskRejected(executor, "queue full");

        ThreadPoolMonitor.ThreadPoolReport viaAnalyze = monitor.analyze();
        ThreadPoolMonitor.ThreadPoolReport viaAnalyzePoolHealth = monitor.analyzePoolHealth();

        assertEquals(viaAnalyzePoolHealth.hasIssues(), viaAnalyze.hasIssues());
        assertEquals(viaAnalyzePoolHealth.toString(), viaAnalyze.toString());
    }

    /** {@return the grade of the one finding whose line starts with {@code pool}} */
    private static GradedFindings.Grade gradeOf(ThreadPoolMonitor.ThreadPoolReport report, String pool) {
        List<GradedFindings.Grade> grades = assertInstanceOf(GradedFindings.class, report,
                "the report must grade each finding by the path behind it: " + report).grades();
        List<GradedFindings.Grade> matching = grades.stream()
                .filter(g -> g.summary().startsWith(pool + ":")).toList();
        assertEquals(1, matching.size(), pool + " in " + grades);
        return matching.get(0);
    }

    @Test
    void aRejectionTheExecutorThrewIsObservedAndARecordedOneAsserted() {
        ThreadPoolMonitor monitor = new ThreadPoolMonitor();
        Object recorded = new Object();
        monitor.registerPool(recorded, "recorded", 1, 4, 10);
        monitor.recordTaskRejected(recorded, "queue full");
        // What the woven submit and execute hand over: the executor and the exception it threw.
        ThreadPoolExecutor woven = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>());
        try {
            monitor.recordRejectedExecution(woven, new RejectedExecutionException("saturated"));
            ThreadPoolMonitor.ThreadPoolReport report = monitor.analyze();

            GradedFindings.Grade thrown = gradeOf(report, "ThreadPoolExecutor@1");
            assertEquals(DetectorTrust.Evidence.OBSERVED, thrown.evidence(), thrown.toString());
            // The executor threw, but a caller may handle a rejection on purpose (a retry, a
            // fallback), which the executor cannot tell: a fact, not a verdict.
            assertEquals(TrustTier.FACT, thrown.tier(), thrown.toString());

            GradedFindings.Grade asserted = gradeOf(report, "recorded");
            assertEquals(DetectorTrust.Evidence.ASSERTED, asserted.evidence(), asserted.toString());
            assertEquals(TrustTier.FACT, asserted.tier(), asserted.toString());

            assertTrue(report.threadStarvation.isEmpty() && report.saturatedQueues.isEmpty(),
                    "a pool known only through what it threw has no declared bounds: " + report);
            for (Violation v : report.structuredViolations) {
                assertEquals(v.severity(), gradeOf(report, v.message().substring(0, v.message().indexOf(':')))
                        .severity(), "a grade keeps the severity the gate read before (#1061): " + v);
            }
        } finally {
            woven.shutdownNow();
        }
    }

    @Test
    void theThresholdRulesArePrompts() {
        ThreadPoolMonitor monitor = new ThreadPoolMonitor();
        Object full = new Object();
        monitor.registerPool(full, "full", 1, 1, 1);
        monitor.recordTaskSubmitted(full);
        monitor.recordTaskSubmitted(full);
        monitor.recordTaskStarted(full);
        Object slow = new Object();
        monitor.registerPool(slow, "slow", 1, 4, 100);
        monitor.recordTaskSubmitted(slow);
        monitor.recordTaskStarted(slow);
        monitor.recordTaskCompleted(slow, 60_000L);

        List<GradedFindings.Grade> grades = ((GradedFindings) monitor.analyze()).grades();
        assertEquals(3, grades.size(), "saturated queue and starvation on full, a long task on slow: " + grades);
        for (GradedFindings.Grade grade : grades) {
            assertEquals(TrustTier.PROMPT, grade.tier(), grade.toString());
            assertEquals(DetectorTrust.Evidence.HEURISTIC, grade.evidence(), grade.toString());
        }
    }

    @Test
    void aPoolWithBothKindsOfRejectionIsGradedByTheObservedOne() {
        ThreadPoolMonitor monitor = new ThreadPoolMonitor();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>());
        try {
            monitor.registerPool(pool, "both", 1, 1, 0);
            monitor.recordTaskRejected(pool, "recorded by hand");
            monitor.recordRejectedExecution(pool, new RejectedExecutionException("saturated"));
            ThreadPoolMonitor.ThreadPoolReport report = monitor.analyze();
            assertTrue(report.poolsWithRejections.contains("both: 2 tasks rejected"), report.toString());
            assertEquals(DetectorTrust.Evidence.OBSERVED, gradeOf(report, "both").evidence());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aCleanReportHasNoGrades() {
        assertEquals(List.of(), ((GradedFindings) new ThreadPoolMonitor().analyze()).grades());
    }

    /** The detector releases a subject the code under test dropped (#918). */
    @Test
    void doesNotKeepARecordedSubjectAlive() throws InterruptedException {
        ThreadPoolMonitor d = new ThreadPoolMonitor();
        AbstractInstanceDetectorTest.assertNotRetained(Object::new, e -> d.registerPool(e, null, 1, 1, 1));
    }
}
