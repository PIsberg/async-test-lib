package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ParallelStreamDetector.
 */
public class ParallelStreamDetectorTest {

    @Test
    void testStatelessStreamUsage() {
        ParallelStreamDetector detector = new ParallelStreamDetector();
        
        detector.recordParallelStream("stateless-stream");
        detector.recordStatelessOperation("stateless-stream", "map");
        detector.recordStatelessOperation("stateless-stream", "filter");
        detector.recordStatelessOperation("stateless-stream", "collect");
        
        ParallelStreamDetector.ParallelStreamReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.hasIssues(), "Stateless operations should not report issues");
    }

    @Test
    void testStatefulLambdaDetection() {
        ParallelStreamDetector detector = new ParallelStreamDetector();
        
        detector.recordParallelStream("stateful-stream");
        detector.recordStatefulOperation("stateful-stream", "forEach");
        
        ParallelStreamDetector.ParallelStreamReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect stateful lambda");
        assertFalse(report.statefulLambdas.isEmpty(), "Should report stateful lambdas");
    }

    @Test
    void testNonThreadSafeCollectorDetection() {
        ParallelStreamDetector detector = new ParallelStreamDetector();
        
        detector.recordParallelStream("unsafe-collector-stream");
        detector.recordNonThreadSafeCollector("unsafe-collector-stream", "ArrayList");
        
        ParallelStreamDetector.ParallelStreamReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect non-thread-safe collector");
        assertFalse(report.nonThreadSafeCollectors.isEmpty(), "Should report unsafe collectors");
    }

    @Test
    void testSideEffectDetection() {
        ParallelStreamDetector detector = new ParallelStreamDetector();
        
        detector.recordParallelStream("side-effect-stream");
        detector.recordSideEffect("side-effect-stream", "shared-state-modification");
        
        ParallelStreamDetector.ParallelStreamReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect side effects");
        assertFalse(report.sideEffects.isEmpty(), "Should report side effects");
    }

    @Test
    void testMultiThreadTracking() {
        ParallelStreamDetector detector = new ParallelStreamDetector();
        
        detector.recordParallelStream("multi-thread-stream");
        
        Thread t1 = new Thread(() -> {
            detector.recordStatelessOperation("multi-thread-stream", "map");
        });
        
        Thread t2 = new Thread(() -> {
            detector.recordStatelessOperation("multi-thread-stream", "filter");
        });
        
        Thread t3 = new Thread(() -> {
            detector.recordStatelessOperation("multi-thread-stream", "collect");
        });
        
        t1.start();
        t2.start();
        t3.start();
        
        try {
            t1.join();
            t2.join();
            t3.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        ParallelStreamDetector.ParallelStreamReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.parallelExecution.isEmpty(), "Should track parallel execution");
        assertTrue(report.parallelExecution.get(0).contains("3 threads"),
                   "Should report 3 threads");
    }

    @Test
    void testOperationTypeTracking() {
        ParallelStreamDetector detector = new ParallelStreamDetector();
        
        detector.recordParallelStream("operation-tracking-stream");
        detector.recordStatelessOperation("operation-tracking-stream", "map");
        detector.recordStatelessOperation("operation-tracking-stream", "map");
        detector.recordStatelessOperation("operation-tracking-stream", "filter");
        detector.recordStatelessOperation("operation-tracking-stream", "collect");
        
        ParallelStreamDetector.ParallelStreamReport report = detector.analyze();
        
        assertNotNull(report);
        String activity = report.streamActivity.get("operation-tracking-stream");
        assertNotNull(activity);
        assertTrue(activity.contains("map:2"), "Should track map count");
        assertTrue(activity.contains("filter:1"), "Should track filter count");
    }

    @Test
    void testNullSafety() {
        ParallelStreamDetector detector = new ParallelStreamDetector();
        
        // Should not throw on null inputs
        detector.recordParallelStream(null);
        detector.recordStatefulOperation(null, "forEach");
        detector.recordStatelessOperation(null, "map");
        detector.recordNonThreadSafeCollector(null, "ArrayList");
        detector.recordSideEffect(null, "effect");
        
        ParallelStreamDetector.ParallelStreamReport report = detector.analyze();
        assertNotNull(report);
    }

    @Test
    void testReportToString() {
        ParallelStreamDetector detector = new ParallelStreamDetector();
        
        detector.recordParallelStream("test-stream");
        detector.recordStatefulOperation("test-stream", "forEach");
        
        ParallelStreamDetector.ParallelStreamReport report = detector.analyze();
        
        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("PARALLEL STREAM ISSUES DETECTED"), "Report should have header");
        assertTrue(reportStr.contains("Stateful Lambdas"), "Report should mention stateful lambdas");
    }

    @Test
    void testMultipleIssuesDetection() {
        ParallelStreamDetector detector = new ParallelStreamDetector();
        
        detector.recordParallelStream("multi-issue-stream");
        detector.recordStatefulOperation("multi-issue-stream", "forEach");
        detector.recordNonThreadSafeCollector("multi-issue-stream", "HashMap");
        detector.recordSideEffect("multi-issue-stream", "counter-increment");
        
        ParallelStreamDetector.ParallelStreamReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect multiple issues");
        assertFalse(report.statefulLambdas.isEmpty(), "Should report stateful lambdas");
        assertFalse(report.nonThreadSafeCollectors.isEmpty(), "Should report unsafe collectors");
        assertFalse(report.sideEffects.isEmpty(), "Should report side effects");
    }
}
