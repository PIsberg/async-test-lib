package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ThreadStarvationDetectorTest {

    private ThreadStarvationDetector detector;

    @BeforeEach
    void setUp() {
        detector = new ThreadStarvationDetector();
    }

    @Test
    void anUnrecordedSubmissionIsNotAWaitOfMillionsOfMilliseconds() {
        ThreadStarvationDetector detector = new ThreadStarvationDetector();

        // recordTaskSubmission returns 0 when it recorded nothing - a null executor, or the
        // detector disabled. Callers pass that straight on, and 0 used to be subtracted from
        // System.nanoTime() as if it were a timestamp, producing the JVM's nanoTime origin as a
        // wait: millions of milliseconds, and starvation for a task that never queued (#501).
        long noSubmission = detector.recordTaskSubmission(null);
        assertEquals(0L, noSubmission, "the sentinel this test is about");

        detector.registerExecutor(java.util.concurrent.Executors.newSingleThreadExecutor(), "pool", 1);
        detector.recordTaskStart("pool", noSubmission);

        assertFalse(detector.analyze().hasIssues(),
            "no submission was recorded, so there is no wait to measure: " + detector.analyze());
    }

    @Test
    void noStarvation_whenTasksExecuteQuickly() {
        detector.setStarvationThresholdMs(5000);
        
        ExecutorService executor = Executors.newFixedThreadPool(2);
        detector.registerExecutor(executor, "test-pool", 2);
        
        long submitTime = detector.recordTaskSubmission(executor);
        detector.recordTaskStart("test-pool", submitTime);
        detector.recordTaskEnd("test-pool");

        ThreadStarvationDetector.ThreadStarvationReport report = detector.analyze();
        
        assertFalse(report.hasIssues());
        executor.shutdownNow();
    }

    @Test
    void disabledDetector_returnsNoIssues() {
        detector.disable();
        
        ExecutorService executor = Executors.newFixedThreadPool(1);
        detector.registerExecutor(executor, "test-pool", 1);

        ThreadStarvationDetector.ThreadStarvationReport report = detector.analyze();
        assertFalse(report.hasIssues());
        executor.shutdownNow();
    }

    @Test
    void clear_removesAllData() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        detector.registerExecutor(executor, "test-pool", 2);
        
        detector.clear();

        ThreadStarvationDetector.ThreadStarvationReport report = detector.analyze();
        assertFalse(report.hasIssues());
        executor.shutdownNow();
    }

    @Test
    void report_containsSummary() {
        ThreadStarvationDetector.ThreadStarvationReport report = detector.analyze();
        String reportStr = report.toString();
        assertTrue(reportStr.contains("ThreadStarvationReport"));
        assertTrue(reportStr.contains("Total tasks tracked"));
    }

    @Test
    void report_showsNoStarvation_whenClean() {
        ThreadStarvationDetector.ThreadStarvationReport report = detector.analyze();
        String reportStr = report.toString();
        assertTrue(reportStr.contains("No thread starvation detected"));
    }

    @Test
    void starvationThreshold_configurable() {
        detector.setStarvationThresholdMs(1);
        detector.setStarvationThresholdMs(10000);
        
        ExecutorService executor = Executors.newFixedThreadPool(1);
        detector.registerExecutor(executor, "test-pool", 1);
        
        ThreadStarvationDetector.ThreadStarvationReport report = detector.analyze();
        assertNotNull(report);
        executor.shutdownNow();
    }

    /** The detector releases a subject the code under test dropped (#918). */
    @Test
    void doesNotKeepARecordedSubjectAlive() throws InterruptedException {
        ThreadStarvationDetector d = new ThreadStarvationDetector();
        AbstractInstanceDetectorTest.assertNotRetained(() -> new java.util.concurrent.ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<Runnable>()), e -> d.registerExecutor(e, null, 1));
    }

    /**
     * An executor registered without a name is still matched by name later: the null name it
     * used to carry made recordTaskStart's equals() throw for every executor (#918).
     */
    @Test
    void anUnnamedExecutorDoesNotBreakTaskStartForTheOthers() {
        ThreadStarvationDetector d = new ThreadStarvationDetector();
        ExecutorService unnamed = Executors.newSingleThreadExecutor();
        ExecutorService named = Executors.newSingleThreadExecutor();
        try {
            d.registerExecutor(unnamed, null, 1);
            d.registerExecutor(named, "named", 1);
            long submitted = d.recordTaskSubmission(named);
            assertDoesNotThrow(() -> d.recordTaskStart("named", submitted));
            assertDoesNotThrow(() -> d.recordTaskEnd("named"));
        } finally {
            unnamed.shutdownNow();
            named.shutdownNow();
        }
    }

    @org.junit.jupiter.api.Test
    void aStarvedTaskReportsNoNegativeExecutionTime() throws Exception {
        // The event is recorded as the task starts, when its run time is not known; it was
        // computed as 0 - start, the negated nanoTime origin (#1419).
        ThreadStarvationDetector detector = new ThreadStarvationDetector();
        detector.setStarvationThresholdMs(0);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            detector.registerExecutor(pool, "pool", 1);
            long submitted = detector.recordTaskSubmission(pool);
            detector.recordTaskStart("pool", submitted);

            java.util.List<ThreadStarvationDetector.StarvationEventSnapshot> events = detector.analyze().getEvents();
            assertFalse(events.isEmpty(), "a threshold of 0 reports every task");
            for (ThreadStarvationDetector.StarvationEventSnapshot event : events) {
                assertTrue(event.executionTimeMs >= 0, "execution time " + event.executionTimeMs);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
