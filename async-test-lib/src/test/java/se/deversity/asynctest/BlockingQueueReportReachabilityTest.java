package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.BlockingQueueDetector;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the three counts {@code hasIssues()} does not gate on actually end up.
 *
 * <p>{@code BlockingQueueReport.hasIssues()} gates on saturation and, since #454, on a dropped
 * element; nothing else. The rejected
 * offers, the empty polls and the producer/consumer ratio are counted and printed but never make
 * the report a finding, and #447 asked whether that meant they were computed for nobody.
 *
 * <p>They are not, and the question was worth asking rather than answering from the source. Both
 * paths that surface a legacy detector - {@code DetectorRegistry.analyzeAllNamed}, which is what a
 * user reads, and {@code LegacyDetectorAdapter}, which is what the SPI reports - render the report
 * only when {@code hasIssues()} is true, and then render all of it. So the three counts reach the
 * reader exactly when there is a saturation finding to attach them to, and never on their own.
 *
 * <p>That is a deliberate arrangement and this pins both halves, because each is a different
 * failure if it drifts. Losing the first would print "BLOCKING QUEUE ISSUES DETECTED" over a body
 * that says no issues were detected. Losing the second would strip context from a real finding: a
 * rejected offer is exactly what a saturated queue does next, and the count is how a reader sizes
 * it.
 */
class BlockingQueueReportReachabilityTest {

    /** A queue whose peak reaches its bound, with a rejected offer and an empty poll on the way. */
    private static BlockingQueueDetector saturatedWithRejectedOfferAndEmptyPoll() {
        BlockingQueueDetector detector = new BlockingQueueDetector();
        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(2);
        detector.registerQueue(queue, "work-queue", 2);

        detector.recordOffer(queue, "work-queue", queue.offer("a"));
        detector.recordOffer(queue, "work-queue", queue.offer("b"));
        // Full now, so the third offer is rejected: the count that never gates.
        detector.recordOffer(queue, "work-queue", queue.offer("c"));

        detector.recordPoll(queue, "work-queue", queue.poll() != null);
        detector.recordPoll(queue, "work-queue", queue.poll() != null);
        // Empty now, so the third poll returns null: how a drain loop ends.
        detector.recordPoll(queue, "work-queue", queue.poll() != null);
        return detector;
    }

    @Test
    @DisplayName("a rejected offer and an empty poll are printed under the saturation finding")
    void theUngatedCountsRideAlongWithARealFinding() {
        String report = saturatedWithRejectedOfferAndEmptyPoll().analyze().toString();

        assertTrue(report.contains("queue reached 2/2 capacity"),
                "the saturation finding is what makes the report reachable at all; got:\n" + report);
        assertTrue(report.contains("offer() returned false 1 times"),
                "the rejected offer is context for the saturation rather than noise: it is what a "
                        + "full queue does next. Got:\n" + report);
        assertTrue(report.contains("poll() returned null 1 times"),
                "so is the empty poll, which is how a drain loop ends. Got:\n" + report);
        assertTrue(report.contains("max size: 2"),
                "and the activity line carries the raw counts a reader sizes it by. Got:\n"
                        + report);
    }

    @Test
    @DisplayName("a dropped element reaches the reader on its own, with no saturation to ride on")
    void aDroppedElementIsAFindingOnItsOwn() {
        DetectorRegistry registry = new DetectorRegistry(
                AsyncTestConfig.builder().detectAll(true).build());
        // No capacity, so no saturation line can ever carry this: what reaches the reader is
        // the dropped element or nothing.
        BlockingQueue<Object> handoff = new SynchronousQueue<>();
        registry.blockingQueueDetector.observeQueue(handoff);

        boolean added = handoff.offer("dropped");
        registry.blockingQueueDetector.recordOffer(handoff, "handoff", added);
        registry.blockingQueueDetector.recordOfferResultDiscarded(added);

        Map<String, String> named = registry.analyzeAllNamed();
        assertTrue(named.containsKey("BlockingQueueDetector"),
                "a rejected offer whose result was popped is the one rejected offer that is a "
                        + "finding, and it must reach the path a user reads (#454). Got: " + named);
        assertTrue(named.get("BlockingQueueDetector").contains("discarded the result"),
                "and say what it is: " + named.get("BlockingQueueDetector"));
    }

    @Test
    @DisplayName("the same counts reach nobody when there is no saturation to attach them to")
    void theUngatedCountsAreNotAFindingOnTheirOwn() {
        DetectorRegistry registry = new DetectorRegistry(
                AsyncTestConfig.builder().detectAll(true).build());
        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(100);
        registry.blockingQueueDetector.registerQueue(queue, "roomy", 100);

        registry.blockingQueueDetector.recordOffer(queue, "roomy", queue.offer("only"));
        registry.blockingQueueDetector.recordPoll(queue, "roomy", queue.poll() != null);
        // A drain loop's last poll: the textbook implementation, and an emptyPolls count.
        registry.blockingQueueDetector.recordPoll(queue, "roomy", queue.poll() != null);

        assertFalse(registry.blockingQueueDetector.analyze().hasIssues(),
                "one element through a queue of a hundred is not saturation, and an empty poll "
                        + "ending a drain loop is correct code");

        Map<String, String> named = registry.analyzeAllNamed();
        assertFalse(named.containsKey("BlockingQueueDetector"),
                "the path a user reads gates on hasIssues(), so these counts must surface nothing "
                        + "at all on their own. It printed: " + named.get("BlockingQueueDetector"));
    }
}
