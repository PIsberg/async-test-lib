package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BlockingQueueDetector.
 */
public class BlockingQueueDetectorTest {

    @Test
    void testNormalQueueUsage() {
        BlockingQueueDetector detector = new BlockingQueueDetector();
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(10);
        
        detector.registerQueue(queue, "normal-queue", 10);
        
        queue.offer("item1");
        detector.recordOffer(queue, "normal-queue", true);
        
        String item = queue.poll();
        detector.recordPoll(queue, "normal-queue", item != null);
        
        BlockingQueueDetector.BlockingQueueReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.hasIssues(), "Normal usage should not report issues");
    }

    @Test
    void testSilentFailureDetection() {
        BlockingQueueDetector detector = new BlockingQueueDetector();
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(2);
        
        detector.registerQueue(queue, "full-queue", 2);
        
        // Fill the queue
        queue.offer("item1");
        detector.recordOffer(queue, "full-queue", true);
        queue.offer("item2");
        detector.recordOffer(queue, "full-queue", true);
        
        // This should fail (queue full)
        boolean added = queue.offer("item3");
        detector.recordOffer(queue, "full-queue", added);
        
        BlockingQueueDetector.BlockingQueueReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect silent failure");
        assertFalse(report.silentFailures.isEmpty(), "Should report silent failures");
    }

    @Test
    void testEmptyPollDetection() {
        BlockingQueueDetector detector = new BlockingQueueDetector();
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(10);
        
        detector.registerQueue(queue, "empty-queue", 10);
        
        // Poll from empty queue
        String item = queue.poll();
        detector.recordPoll(queue, "empty-queue", item != null);
        
        BlockingQueueDetector.BlockingQueueReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect empty poll");
        assertFalse(report.emptyPolls.isEmpty(), "Should report empty polls");
    }

    @Test
    void testSaturationDetection() {
        BlockingQueueDetector detector = new BlockingQueueDetector();
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(10);
        
        detector.registerQueue(queue, "saturated-queue", 10);
        
        // Fill queue to 90% capacity (9 items)
        for (int i = 0; i < 9; i++) {
            assertTrue(queue.offer("item" + i));
            detector.recordOffer(queue, "saturated-queue", true);
        }
        
        // Don't consume - check while queue is still full
        BlockingQueueDetector.BlockingQueueReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect saturation when queue is at 90% capacity");
        assertFalse(report.saturation.isEmpty(), "Should report saturation");
    }

    @Test
    void testProducerConsumerImbalance() {
        BlockingQueueDetector detector = new BlockingQueueDetector();
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(100);
        
        detector.registerQueue(queue, "imbalanced-queue", 100);
        
        // Many more produces than consumes
        for (int i = 0; i < 20; i++) {
            queue.offer("item" + i);
            detector.recordOffer(queue, "imbalanced-queue", true);
        }
        
        for (int i = 0; i < 5; i++) {
            queue.poll();
            detector.recordPoll(queue, "imbalanced-queue", true);
        }
        
        BlockingQueueDetector.BlockingQueueReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect producer/consumer imbalance");
        assertFalse(report.producerConsumerImbalance.isEmpty(), "Should report imbalance");
    }

    @Test
    void testPutTakeTracking() {
        BlockingQueueDetector detector = new BlockingQueueDetector();
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(10);
        
        detector.registerQueue(queue, "put-take-queue", 10);
        
        detector.recordPut(queue, "put-take-queue");
        detector.recordTake(queue, "put-take-queue");
        
        BlockingQueueDetector.BlockingQueueReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.queueActivity.get("put-take-queue").contains("puts: 1"), "Should track puts");
        assertTrue(report.queueActivity.get("put-take-queue").contains("takes: 1"), "Should track takes");
    }

    @Test
    void testNullSafety() {
        BlockingQueueDetector detector = new BlockingQueueDetector();
        
        // Should not throw on null inputs
        detector.registerQueue(null, "null-queue", 10);
        detector.recordOffer(null, "null", true);
        detector.recordPoll(null, "null", true);
        detector.recordPut(null, "null");
        detector.recordTake(null, "null");
        
        BlockingQueueDetector.BlockingQueueReport report = detector.analyze();
        assertNotNull(report);
    }

    @Test
    void testReportToString() {
        BlockingQueueDetector detector = new BlockingQueueDetector();
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(2);
        
        detector.registerQueue(queue, "test-queue", 2);
        
        // Fill and overflow
        queue.offer("item1");
        detector.recordOffer(queue, "test-queue", true);
        queue.offer("item2");
        detector.recordOffer(queue, "test-queue", true);
        boolean failed = queue.offer("item3");
        detector.recordOffer(queue, "test-queue", failed);
        
        BlockingQueueDetector.BlockingQueueReport report = detector.analyze();
        
        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("BLOCKING QUEUE ISSUES DETECTED"), "Report should have header");
        assertTrue(reportStr.contains("Silent Failures"), "Report should mention silent failures");
    }
}
