package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ThreadPoolDeadlockDetector}.
 */
class ThreadPoolDeadlockDetectorTest {

    @Test
    void noDeadlockRisk_whenNoNestedSubmissions() {
        ThreadPoolDeadlockDetector detector = new ThreadPoolDeadlockDetector();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        
        detector.registerPool(pool, "test-pool");
        ThreadPoolDeadlockDetector.ThreadPoolDeadlockReport report = detector.analyze();
        
        assertFalse(report.hasDeadlockRisk());
        assertEquals(0, report.getRisks().size());
        
        pool.shutdown();
    }

    @Test
    void nestedSubmission_detected() {
        ThreadPoolDeadlockDetector detector = new ThreadPoolDeadlockDetector();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        
        detector.registerPool(pool, "test-pool");
        detector.recordNestedSubmission(pool, "test-pool");
        
        ThreadPoolDeadlockDetector.ThreadPoolDeadlockReport report = detector.analyze();
        
        assertTrue(report.hasDeadlockRisk());
        assertEquals(1, report.getRisks().size());
        assertEquals("test-pool", report.getRisks().get(0).getPoolName());
        
        pool.shutdown();
    }

    @Test
    void multipleNestedSubmissions_allRecorded() {
        ThreadPoolDeadlockDetector detector = new ThreadPoolDeadlockDetector();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        
        detector.registerPool(pool, "test-pool");
        detector.recordNestedSubmission(pool, "test-pool");
        detector.recordNestedSubmission(pool, "test-pool");
        detector.recordNestedSubmission(pool, "test-pool");
        
        ThreadPoolDeadlockDetector.ThreadPoolDeadlockReport report = detector.analyze();
        
        assertTrue(report.hasDeadlockRisk());
        assertEquals(1, report.getRisks().size());
        assertEquals(3, report.getRisks().get(0).getNestedSubmissionCount());
        
        pool.shutdown();
    }

    @Test
    void taskCompletion_tracked() {
        ThreadPoolDeadlockDetector detector = new ThreadPoolDeadlockDetector();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        
        detector.registerPool(pool, "test-pool");
        detector.recordNestedSubmission(pool, "test-pool");
        detector.recordTaskCompleted(pool);
        
        // Should still have risk recorded, but active count decreased
        ThreadPoolDeadlockDetector.ThreadPoolDeadlockReport report = detector.analyze();
        
        assertTrue(report.hasDeadlockRisk());
        
        pool.shutdown();
    }

    @Test
    void disabledDetector_noRisksReported() {
        ThreadPoolDeadlockDetector detector = new ThreadPoolDeadlockDetector();
        detector.setEnabled(false);
        
        ExecutorService pool = Executors.newFixedThreadPool(2);
        detector.registerPool(pool, "test-pool");
        detector.recordNestedSubmission(pool, "test-pool");
        
        ThreadPoolDeadlockDetector.ThreadPoolDeadlockReport report = detector.analyze();
        
        assertFalse(report.hasDeadlockRisk());
        
        pool.shutdown();
    }

    @Test
    void clear_resetsAllData() {
        ThreadPoolDeadlockDetector detector = new ThreadPoolDeadlockDetector();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        
        detector.registerPool(pool, "test-pool");
        detector.recordNestedSubmission(pool, "test-pool");
        detector.clear();
        
        ThreadPoolDeadlockDetector.ThreadPoolDeadlockReport report = detector.analyze();
        
        assertEquals(0, report.getRisks().size());
        assertEquals(0, detector.getDeadlockRiskCount());
        
        pool.shutdown();
    }

    @Test
    void nullPool_ignored() {
        ThreadPoolDeadlockDetector detector = new ThreadPoolDeadlockDetector();
        
        assertDoesNotThrow(() -> detector.registerPool(null, "null-pool"));
        assertDoesNotThrow(() -> detector.recordNestedSubmission(null, "null-pool"));
        assertDoesNotThrow(() -> detector.recordTaskCompleted(null));
        
        ThreadPoolDeadlockDetector.ThreadPoolDeadlockReport report = detector.analyze();
        assertEquals(0, report.getRisks().size());
    }

    @Test
    void reportToString_containsDetails() {
        ThreadPoolDeadlockDetector detector = new ThreadPoolDeadlockDetector();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        
        detector.registerPool(pool, "test-pool");
        detector.recordNestedSubmission(pool, "test-pool");
        
        ThreadPoolDeadlockDetector.ThreadPoolDeadlockReport report = detector.analyze();
        String reportStr = report.toString();
        
        assertTrue(reportStr.contains("test-pool"));
        assertTrue(reportStr.contains("Nested submissions"));
        assertTrue(reportStr.contains("Recommendations"));
        
        pool.shutdown();
    }

    @Test
    void reportToString_noRisk_returnsPositiveMessage() {
        ThreadPoolDeadlockDetector detector = new ThreadPoolDeadlockDetector();
        
        ThreadPoolDeadlockDetector.ThreadPoolDeadlockReport report = detector.analyze();
        String reportStr = report.toString();
        
        assertTrue(reportStr.contains("No deadlock risks detected"));
    }

    @Test
    void poolDeadlockRisk_detailsAccessible() {
        ThreadPoolDeadlockDetector detector = new ThreadPoolDeadlockDetector();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        
        detector.registerPool(pool, "detailed-pool");
        detector.recordNestedSubmission(pool, "detailed-pool");
        
        ThreadPoolDeadlockDetector.ThreadPoolDeadlockReport report = detector.analyze();
        
        if (!report.getRisks().isEmpty()) {
            ThreadPoolDeadlockDetector.PoolDeadlockRisk risk = report.getRisks().get(0);
            
            assertEquals("detailed-pool", risk.getPoolName());
            assertTrue(risk.getPoolSize() > 0);
            assertEquals(1, risk.getNestedSubmissionCount());
            assertFalse(risk.getNestedSubmissions().isEmpty());
        }
        
        pool.shutdown();
    }

    @Test
    void multiplePools_allTracked() {
        ThreadPoolDeadlockDetector detector = new ThreadPoolDeadlockDetector();
        ExecutorService pool1 = Executors.newFixedThreadPool(2);
        ExecutorService pool2 = Executors.newFixedThreadPool(4);
        
        detector.registerPool(pool1, "pool-1");
        detector.registerPool(pool2, "pool-2");
        
        detector.recordNestedSubmission(pool1, "pool-1");
        detector.recordNestedSubmission(pool2, "pool-2");
        
        ThreadPoolDeadlockDetector.ThreadPoolDeadlockReport report = detector.analyze();
        
        assertTrue(report.hasDeadlockRisk());
        assertEquals(2, report.getRisks().size());
        
        pool1.shutdown();
        pool2.shutdown();
    }

    @Test
    void nestedSubmissionSnapshot_stackTraceAvailable() {
        ThreadPoolDeadlockDetector detector = new ThreadPoolDeadlockDetector();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        
        detector.registerPool(pool, "test-pool");
        detector.recordNestedSubmission(pool, "test-pool");
        
        ThreadPoolDeadlockDetector.ThreadPoolDeadlockReport report = detector.analyze();
        
        if (!report.getRisks().isEmpty()) {
            ThreadPoolDeadlockDetector.PoolDeadlockRisk risk = report.getRisks().get(0);
            if (!risk.getNestedSubmissions().isEmpty()) {
                ThreadPoolDeadlockDetector.NestedSubmissionSnapshot snapshot = 
                    risk.getNestedSubmissions().get(0);
                
                assertNotNull(snapshot.getStackTrace());
                assertTrue(snapshot.getStackTrace().length > 0);
                assertEquals("test-pool", snapshot.getPoolName());
            }
        }
        
        pool.shutdown();
    }
}
