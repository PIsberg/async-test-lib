package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

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
}
