package se.deversity.asynctest.diagnostics;

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
        assertFalse(report.emptyPolls.isEmpty(), "Should report empty polls");
        // Recorded, but not a finding. Polling an empty queue is what poll() is for, and the
        // standard drain loop `while ((x = q.poll()) != null)` produces exactly one of these
        // per drain — so gating on it flagged the textbook implementation. This assertion
        // used to be assertTrue(hasIssues()), which pinned that false positive in place.
        assertFalse(report.hasIssues(),
            "A null poll() on an empty queue is normal and must not count as an issue");
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
        assertFalse(report.producerConsumerImbalance.isEmpty(), "Should report imbalance");
        // Recorded, but not a finding. Any bursty-but-correct workload crosses the 0.5/2.0
        // ratio thresholds, so gating on it turned a description of the traffic into an
        // accusation. This assertion used to be assertTrue(hasIssues()).
        assertFalse(report.hasIssues(),
            "A lopsided producer/consumer ratio describes the workload, not a defect");
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

    @Test
    void testZeroCapacityNeverSaturates() {
        BlockingQueueDetector detector = new BlockingQueueDetector();
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(10);

        // capacity <= 0 must disable saturation checking regardless of observed size
        detector.registerQueue(queue, "zero-capacity-queue", 0);

        for (int i = 0; i < 9; i++) {
            queue.offer("item" + i);
            detector.recordOffer(queue, "zero-capacity-queue", true);
        }

        BlockingQueueDetector.BlockingQueueReport report = detector.analyze();

        assertTrue(report.saturation.isEmpty(),
            "capacity <= 0 must never report saturation, even at high observed size");
    }

    @Test
    void testNoImbalanceWhenNoProduces() {
        BlockingQueueDetector detector = new BlockingQueueDetector();
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(10);

        detector.registerQueue(queue, "no-produce-queue", 10);
        queue.offer("seed");

        queue.poll();
        detector.recordPoll(queue, "no-produce-queue", true);
        detector.recordTake(queue, "no-produce-queue");

        BlockingQueueDetector.BlockingQueueReport report = detector.analyze();

        assertTrue(report.producerConsumerImbalance.isEmpty(),
            "totalProduces == 0 must suppress imbalance reporting even though consumes > 0");
    }

    @Test
    void testNoImbalanceWhenNoConsumes() {
        BlockingQueueDetector detector = new BlockingQueueDetector();
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(10);

        detector.registerQueue(queue, "no-consume-queue", 10);

        queue.offer("item");
        detector.recordOffer(queue, "no-consume-queue", true);
        detector.recordPut(queue, "no-consume-queue");

        BlockingQueueDetector.BlockingQueueReport report = detector.analyze();

        assertTrue(report.producerConsumerImbalance.isEmpty(),
            "totalConsumes == 0 must suppress imbalance reporting (avoids ratio computed against zero)");
    }

    @Test
    void testRatioExactlyTwoIsNotImbalanced() {
        BlockingQueueDetector detector = new BlockingQueueDetector();
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(20);

        detector.registerQueue(queue, "ratio-boundary-high-queue", 20);

        for (int i = 0; i < 4; i++) {
            queue.offer("item" + i);
            detector.recordOffer(queue, "ratio-boundary-high-queue", true);
        }
        for (int i = 0; i < 2; i++) {
            queue.poll();
            detector.recordPoll(queue, "ratio-boundary-high-queue", true);
        }

        BlockingQueueDetector.BlockingQueueReport report = detector.analyze();

        assertTrue(report.producerConsumerImbalance.isEmpty(),
            "ratio == 2.0 exactly must NOT be flagged; threshold is strictly greater than 2.0");
    }

    @Test
    void testRatioExactlyHalfIsNotImbalanced() {
        BlockingQueueDetector detector = new BlockingQueueDetector();
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(20);

        detector.registerQueue(queue, "ratio-boundary-low-queue", 20);

        for (int i = 0; i < 2; i++) {
            queue.offer("item" + i);
            detector.recordOffer(queue, "ratio-boundary-low-queue", true);
        }
        for (int i = 0; i < 4; i++) {
            queue.poll();
            detector.recordPoll(queue, "ratio-boundary-low-queue", true);
        }

        BlockingQueueDetector.BlockingQueueReport report = detector.analyze();

        assertTrue(report.producerConsumerImbalance.isEmpty(),
            "ratio == 0.5 exactly must NOT be flagged; threshold is strictly less than 0.5");
    }

    @Test
    void testConsumersOutpacingProducersIsImbalanced() {
        BlockingQueueDetector detector = new BlockingQueueDetector();
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(20);

        detector.registerQueue(queue, "ratio-low-queue", 20);

        queue.offer("item");
        detector.recordOffer(queue, "ratio-low-queue", true);

        for (int i = 0; i < 4; i++) {
            queue.poll();
            detector.recordPoll(queue, "ratio-low-queue", true);
        }

        BlockingQueueDetector.BlockingQueueReport report = detector.analyze();

        assertFalse(report.producerConsumerImbalance.isEmpty(), "ratio 0.25 must be flagged as imbalance");
        assertTrue(report.producerConsumerImbalance.get(0).contains("consumers outpacing producers"),
            "ratio below 0.5 must be reported as consumers outpacing producers");
    }

    @Test
    void testProducerConsumerRatioMathIsExact() {
        BlockingQueueDetector detector = new BlockingQueueDetector();
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(100);

        detector.registerQueue(queue, "ratio-math-queue", 100);

        // totalProduces = offerCount(3) + putCount(2) = 5
        for (int i = 0; i < 3; i++) {
            queue.offer("item" + i);
            detector.recordOffer(queue, "ratio-math-queue", true);
        }
        detector.recordPut(queue, "ratio-math-queue");
        detector.recordPut(queue, "ratio-math-queue");

        // totalConsumes = pollCount(1) + takeCount(1) = 2
        queue.poll();
        detector.recordPoll(queue, "ratio-math-queue", true);
        detector.recordTake(queue, "ratio-math-queue");

        BlockingQueueDetector.BlockingQueueReport report = detector.analyze();

        assertFalse(report.producerConsumerImbalance.isEmpty());
        assertTrue(
            report.producerConsumerImbalance.get(0).contains("ratio 2.5 (producers outpacing consumers)"),
            "ratio must be exactly (offers+puts)/(polls+takes) = 5/2 = 2.5, using addition and division "
                + "(not subtraction or multiplication); got: " + report.producerConsumerImbalance.get(0));
    }

    @Test
    void testRecordPollUpdatesMaxObservedSize() {
        BlockingQueueDetector detector = new BlockingQueueDetector();
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(10);

        detector.registerQueue(queue, "poll-size-queue", 10);

        queue.offer("a");
        queue.offer("b");
        queue.offer("c");

        queue.poll();
        detector.recordPoll(queue, "poll-size-queue", true);

        BlockingQueueDetector.BlockingQueueReport report = detector.analyze();

        assertTrue(report.queueActivity.get("poll-size-queue").contains("max size: 2"),
            "recordPoll must call updateSizeState so max observed size reflects size at call time");
    }

    @Test
    void testRecordPutUpdatesMaxObservedSize() {
        BlockingQueueDetector detector = new BlockingQueueDetector();
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(10);

        detector.registerQueue(queue, "put-size-queue", 10);

        queue.offer("a");
        queue.offer("b");

        detector.recordPut(queue, "put-size-queue");

        BlockingQueueDetector.BlockingQueueReport report = detector.analyze();

        assertTrue(report.queueActivity.get("put-size-queue").contains("max size: 2"),
            "recordPut must call updateSizeState so max observed size reflects size at call time");
    }

    @Test
    void testRecordTakeUpdatesMaxObservedSize() {
        BlockingQueueDetector detector = new BlockingQueueDetector();
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(10);

        detector.registerQueue(queue, "take-size-queue", 10);

        queue.offer("a");
        queue.offer("b");
        queue.offer("c");

        queue.poll();
        detector.recordTake(queue, "take-size-queue");

        BlockingQueueDetector.BlockingQueueReport report = detector.analyze();

        assertTrue(report.queueActivity.get("take-size-queue").contains("max size: 2"),
            "recordTake must call updateSizeState so max observed size reflects size at call time");
    }

    @Test
    void testReportToStringIncludesAllPopulatedSections() {
        BlockingQueueDetector detector = new BlockingQueueDetector();
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(2);

        detector.registerQueue(queue, "full-report-queue", 2);

        // Trigger silentFailures + saturation (queue fills to capacity 2)
        queue.offer("item1");
        detector.recordOffer(queue, "full-report-queue", true);
        queue.offer("item2");
        detector.recordOffer(queue, "full-report-queue", true);
        boolean failedOffer = queue.offer("item3");
        detector.recordOffer(queue, "full-report-queue", failedOffer);

        // Drain the queue, then trigger emptyPolls
        queue.poll();
        queue.poll();
        String extra = queue.poll();
        detector.recordPoll(queue, "full-report-queue", extra != null);

        // Trigger producerConsumerImbalance: produces = 3 + 5 = 8, consumes = 1 + 1 = 2, ratio = 4.0
        for (int i = 0; i < 5; i++) {
            detector.recordPut(queue, "full-report-queue");
        }
        detector.recordTake(queue, "full-report-queue");

        BlockingQueueDetector.BlockingQueueReport report = detector.analyze();
        String reportStr = report.toString();

        assertTrue(report.hasIssues());
        assertTrue(reportStr.contains("Empty Polls"), "must print Empty Polls section when list non-empty");
        assertTrue(reportStr.contains("Queue Saturation"), "must print Queue Saturation section when list non-empty");
        assertTrue(reportStr.contains("Producer/Consumer Imbalance"),
            "must print Producer/Consumer Imbalance section when list non-empty");
        assertTrue(reportStr.contains("Queue Activity"), "must print Queue Activity section when non-empty");
        assertFalse(reportStr.contains("No issues detected"),
            "must not print 'No issues detected' when issues are actually present");
    }

    /**
     * Correct bounded-queue code must not be reported as having issues.
     *
     * <p>Both patterns below are textbook. {@code if (!q.offer(x)) retryLater(x)} is how you
     * apply backpressure to a bounded queue, and {@code while ((x = q.poll()) != null)} is how
     * you drain one — it ends with exactly one null per drain, every time, by construction.
     *
     * <p>Both used to make {@code hasIssues()} true, so a correct implementation printed
     * "BLOCKING QUEUE ISSUES DETECTED" in the consumer's CI on every run, and under
     * {@code failOn = HIGH} would have failed their build. The recording API is what gives the
     * game away: the {@code success} flag can only have come from the caller reading the return
     * value, which is precisely the thing the old report text accused them of ignoring.
     */
    @Test
    void correctBackpressureAndDrainLoopsAreNotReportedAsIssues() {
        BlockingQueueDetector detector = new BlockingQueueDetector();


        BlockingQueue<String> queue = new ArrayBlockingQueue<>(2);
        detector.registerQueue(queue, "orders", 2);

        // Backpressure: the queue fills, offer() reports it, the caller handles the rejection.
        detector.recordOffer(queue, "orders", true);
        detector.recordOffer(queue, "orders", true);
        detector.recordOffer(queue, "orders", false);

        // Drain loop: two items come out, then the terminating null.
        detector.recordPoll(queue, "orders", true);
        detector.recordPoll(queue, "orders", true);
        detector.recordPoll(queue, "orders", false);

        BlockingQueueDetector.BlockingQueueReport report = detector.analyze();

        assertFalse(report.hasIssues(),
            "A rejected offer() and a null poll() are what a bounded queue returns when it is "
                + "working. Gating on them means every correct producer/consumer implementation "
                + "reports an issue on every run, which trains users to ignore this detector and "
                + "breaks builds that set failOn. Only saturation should count. Report was:\n"
                + report);

        // The counts must survive: they are useful, they are just not findings.
        String rendered = report.toString();
        assertTrue(rendered.contains("orders"),
            "The activity counts must still be reported — the fix is to stop calling them "
                + "issues, not to stop recording them. Report was:\n" + rendered);
    }
}
