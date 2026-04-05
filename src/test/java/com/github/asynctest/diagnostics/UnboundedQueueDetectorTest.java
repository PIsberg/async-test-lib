package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

class UnboundedQueueDetectorTest {

    private UnboundedQueueDetector detector;

    @BeforeEach
    void setUp() {
        detector = new UnboundedQueueDetector();
    }

    @Test
    void detectsUnboundedQueue() {
        LinkedBlockingQueue<String> unbounded = new LinkedBlockingQueue<>();
        detector.recordQueueCreation(unbounded, "test-queue", -1);

        UnboundedQueueDetector.UnboundedQueueReport report = detector.analyze();
        
        assertTrue(report.hasIssues());
        assertEquals(1, report.getEvents().size());
    }

    @Test
    void boundedQueue_noIssue() {
        LinkedBlockingQueue<String> bounded = new LinkedBlockingQueue<>(100);
        detector.recordQueueCreation(bounded, "bounded-queue", 100);

        UnboundedQueueDetector.UnboundedQueueReport report = detector.analyze();
        
        assertFalse(report.hasIssues());
    }

    @Test
    void disabledDetector_returnsNoIssues() {
        detector.disable();
        
        LinkedBlockingQueue<String> unbounded = new LinkedBlockingQueue<>();
        detector.recordQueueCreation(unbounded, "test-queue", -1);

        UnboundedQueueDetector.UnboundedQueueReport report = detector.analyze();
        assertFalse(report.hasIssues());
    }

    @Test
    void clear_removesAllTrackedQueues() {
        LinkedBlockingQueue<String> unbounded = new LinkedBlockingQueue<>();
        detector.recordQueueCreation(unbounded, "to-clear", -1);
        
        detector.clear();
        
        UnboundedQueueDetector.UnboundedQueueReport report = detector.analyze();
        assertFalse(report.hasIssues());
    }

    @Test
    void report_containsSummary() {
        UnboundedQueueDetector.UnboundedQueueReport report = detector.analyze();
        String reportStr = report.toString();
        assertTrue(reportStr.contains("UnboundedQueueReport"));
    }

    @Test
    void enqueueDequeue_trackingWorks() {
        LinkedBlockingQueue<String> bounded = new LinkedBlockingQueue<>(100);
        detector.recordQueueCreation(bounded, "tracked", 100);
        
        detector.recordEnqueue(bounded);
        detector.recordEnqueue(bounded);
        detector.recordDequeue(bounded);

        UnboundedQueueDetector.UnboundedQueueReport report = detector.analyze();
        // Bounded queue with no issues should have empty events
        assertTrue(report.getEvents().isEmpty(), "Bounded queue should have no events");
    }
}
