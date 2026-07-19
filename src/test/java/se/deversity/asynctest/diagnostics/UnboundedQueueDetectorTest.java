package se.deversity.asynctest.diagnostics;

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

    @Test
    void recordQueueCreation_capacityBoundaryZero_isNotUnbounded() {
        LinkedBlockingQueue<String> zeroCapacity = new LinkedBlockingQueue<>(1);
        detector.recordQueueCreation(zeroCapacity, "zero-capacity", 0);

        UnboundedQueueDetector.UnboundedQueueReport report = detector.analyze();

        assertFalse(report.hasIssues(), "capacity == 0 must not be treated as unbounded");
        assertTrue(report.toString().contains("Unbounded: 0"),
            "capacity == 0 must not be counted in unboundedCount");
    }

    @Test
    void analyze_unboundedCountAndTotalTracked_reflectMixOfCapacities() {
        LinkedBlockingQueue<String> negativeCapacity = new LinkedBlockingQueue<>(10);
        LinkedBlockingQueue<String> maxValueCapacity = new LinkedBlockingQueue<>(10);
        LinkedBlockingQueue<String> zeroCapacity = new LinkedBlockingQueue<>(10);
        LinkedBlockingQueue<String> boundedCapacity = new LinkedBlockingQueue<>(10);

        detector.recordQueueCreation(negativeCapacity, "negative", -1);
        detector.recordQueueCreation(maxValueCapacity, "max-value", Integer.MAX_VALUE);
        detector.recordQueueCreation(zeroCapacity, "zero", 0);
        detector.recordQueueCreation(boundedCapacity, "bounded", 100);

        String report = detector.analyze().toString();

        assertTrue(report.contains("Total tracked: 4"), "all four queues must be tracked");
        assertTrue(report.contains("Unbounded: 2"),
            "only the negative and Integer.MAX_VALUE capacity queues must count as unbounded");
    }

    @Test
    void recordEnqueueRecordDequeue_exactImbalanceComputation() {
        detector.setWarningThreshold(2);
        LinkedBlockingQueue<String> unbounded = new LinkedBlockingQueue<>();
        detector.recordQueueCreation(unbounded, "leaky-queue", -1);

        for (int i = 0; i < 5; i++) {
            detector.recordEnqueue(unbounded);
        }
        detector.recordDequeue(unbounded);
        detector.recordDequeue(unbounded);

        UnboundedQueueDetector.UnboundedQueueReport report = detector.analyze();

        boolean found = report.getEvents().stream()
            .anyMatch(e -> e.description.equals(
                "Producer/consumer imbalance: 5 enqueued, 2 dequeued (imbalance: 3)"));
        assertTrue(found, "expected exact imbalance description with imbalance == 3 (5 - 2)");
    }

    @Test
    void analyze_imbalanceBoundary_exactlyAtThresholdVsOneOver() {
        detector.setWarningThreshold(3);
        LinkedBlockingQueue<String> unbounded = new LinkedBlockingQueue<>();
        detector.recordQueueCreation(unbounded, "boundary-queue", -1);

        for (int i = 0; i < 3; i++) {
            detector.recordEnqueue(unbounded);
        }
        UnboundedQueueDetector.UnboundedQueueReport atThreshold = detector.analyze();
        boolean hasImbalanceAtThreshold = atThreshold.getEvents().stream()
            .anyMatch(e -> e.description.contains("imbalance"));
        assertFalse(hasImbalanceAtThreshold, "imbalance == warningThreshold must not trigger an event");

        detector.recordEnqueue(unbounded);
        UnboundedQueueDetector.UnboundedQueueReport overThreshold = detector.analyze();
        boolean hasImbalanceOverThreshold = overThreshold.getEvents().stream()
            .anyMatch(e -> e.description.contains("imbalance: 4"));
        assertTrue(hasImbalanceOverThreshold, "imbalance == warningThreshold + 1 must trigger an event");
    }

    @Test
    void recordEnqueue_unboundedQueueAtExactSizeBoundary_warnsExactlyOnce() {
        detector.setWarningThreshold(1);
        LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
        detector.recordQueueCreation(queue, "growing-unbounded", -1);

        queue.add("a");
        detector.recordEnqueue(queue);
        UnboundedQueueDetector.UnboundedQueueReport atThreshold = detector.analyze();
        boolean warnedAtThreshold = atThreshold.getEvents().stream()
            .anyMatch(e -> e.description.contains("exceeded warning threshold"));
        assertFalse(warnedAtThreshold, "size == warningThreshold must not warn");

        queue.add("b");
        detector.recordEnqueue(queue);
        UnboundedQueueDetector.UnboundedQueueReport overThreshold = detector.analyze();
        long warnCount = overThreshold.getEvents().stream()
            .filter(e -> e.description.contains("exceeded warning threshold"))
            .count();
        assertEquals(1, warnCount, "size == warningThreshold + 1 must warn exactly once");

        queue.add("c");
        detector.recordEnqueue(queue);
        UnboundedQueueDetector.UnboundedQueueReport beyondThreshold = detector.analyze();
        long warnCountAfter = beyondThreshold.getEvents().stream()
            .filter(e -> e.description.contains("exceeded warning threshold"))
            .count();
        assertEquals(1, warnCountAfter, "must only warn once per queue even as size keeps growing");
    }

    @Test
    void recordEnqueue_capacityBoundaryZero_neverWarnsRegardlessOfSize() {
        detector.setWarningThreshold(1);
        LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(5);
        detector.recordQueueCreation(queue, "zero-capacity-boundary", 0);

        queue.add("a");
        detector.recordEnqueue(queue);
        queue.add("b");
        detector.recordEnqueue(queue);

        UnboundedQueueDetector.UnboundedQueueReport report = detector.analyze();
        boolean warned = report.getEvents().stream()
            .anyMatch(e -> e.description.contains("exceeded warning threshold"));
        assertFalse(warned, "capacity == 0 must never warn since it is not unbounded");
    }

    @Test
    void clear_removesAllTrackedQueueState() {
        LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
        detector.recordQueueCreation(queue, "to-be-cleared", -1);

        UnboundedQueueDetector.UnboundedQueueReport before = detector.analyze();
        assertTrue(before.toString().contains("Total tracked: 1"));

        detector.clear();

        UnboundedQueueDetector.UnboundedQueueReport after = detector.analyze();
        assertTrue(after.toString().contains("Total tracked: 0"), "clear() must remove tracked queue state");
        assertFalse(after.hasIssues(), "clear() must remove pending events too");
    }

    @Test
    void toString_reflectsEmptyEventsMessage_whenNoIssues() {
        LinkedBlockingQueue<String> bounded = new LinkedBlockingQueue<>(10);
        detector.recordQueueCreation(bounded, "clean-queue", 10);

        String output = detector.analyze().toString();
        assertTrue(output.contains("No unbounded queue issues detected"));
        assertFalse(output.contains("UNBOUNDED QUEUE ISSUES DETECTED"));
    }

    @Test
    void toString_listsIssues_whenEventsPresent() {
        LinkedBlockingQueue<String> unbounded = new LinkedBlockingQueue<>();
        detector.recordQueueCreation(unbounded, "leaky-queue", -1);

        String output = detector.analyze().toString();
        assertTrue(output.contains("UNBOUNDED QUEUE ISSUES DETECTED"));
        assertFalse(output.contains("No unbounded queue issues detected"));
    }
}
