package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.FlowPublisherConcurrencyDetector;
import se.deversity.asynctest.example.service.TickPublisher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for TickPublisher.
 *
 * ========================================================================
 * DETECTOR: FlowPublisherConcurrencyDetector
 *           (DetectorType.FLOW_PUBLISHER_CONCURRENCY)
 * ========================================================================
 *
 * Reactive Streams rule 1.3, which java.util.concurrent.Flow adopts:
 * "onSubscribe, onNext, onError and onComplete signalled to a Subscriber
 * MUST be signalled serially."
 *
 * Serially does not mean "from a single thread" — a publisher may hop
 * threads freely. It means no two signals overlap in time. That is the
 * guarantee that lets a subscriber keep a running total, a buffer or a
 * parser position without a lock, and nearly every subscriber does.
 *
 * THE BUG:
 *   - the publisher fans out to a pool, so two onNext calls are inside the
 *     subscriber at the same moment and its unsynchronised state corrupts
 *
 * THE FIX:
 *   - serialise in the publisher (a lock, a drain loop, or one thread).
 *     Not by making subscribers thread-safe: the contract belongs to the
 *     publisher, and every downstream operator is written assuming it.
 *
 * TWO RELATED VIOLATIONS THE DETECTOR ALSO CATCHES:
 *   - a signal after a terminal event (onNext after onComplete)
 *   - more items delivered than were requested, which is rule 1.1 and
 *     overflows a bounded subscriber
 *
 * The detector is driven through its recording API here rather than by
 * racing real threads, because a race that reproduces on demand is not a
 * race. The event sequences below are exactly the ones a violating
 * publisher generates.
 */
class TickPublisherTest {

    private FlowPublisherConcurrencyDetector detector;
    private TickPublisher.TotallingSubscriber subscriber;

    @BeforeEach
    void setUp() {
        detector = new FlowPublisherConcurrencyDetector();
        subscriber = new TickPublisher.TotallingSubscriber();
    }

    // -----------------------------------------------------------------------
    // Part 1: serial delivery within demand. The contract is kept.
    // -----------------------------------------------------------------------

    @Test
    void serialDeliveryWithinDemand_isClean() {
        Thread t = Thread.currentThread();

        detector.recordSubscribe(subscriber, "tickStream", t);
        detector.recordRequest(subscriber, 2);
        detector.recordNextStart(subscriber, t);
        detector.recordNextEnd(subscriber);
        detector.recordNextStart(subscriber, t);
        detector.recordNextEnd(subscriber);
        detector.recordComplete(subscriber, t);

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                () -> "Serialized, in-demand delivery must be clean:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 2: two threads inside onNext at once. Rule 1.3 broken.
    // -----------------------------------------------------------------------

    @Test
    void overlappingOnNext_isDetected() {
        Thread poolThread = new Thread(() -> { }, "tick-pool-2");

        detector.recordSubscribe(subscriber, "tickStream", Thread.currentThread());
        detector.recordRequest(subscriber, Long.MAX_VALUE);
        detector.recordNextStart(subscriber, Thread.currentThread());
        detector.recordNextStart(subscriber, poolThread);        // no matching end between them

        var report = detector.analyze();
        assertTrue(report.hasIssues(),
                () -> "Two threads inside onNext at once must be flagged:\n" + report);
        assertTrue(report.toString().contains("overlapping onNext"),
                () -> "The report must name the overlap:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 3: a signal after a terminal event. The subscriber has already
    // been told the stream ended.
    // -----------------------------------------------------------------------

    @Test
    void signalAfterTerminal_isDetected() {
        Thread t = Thread.currentThread();

        detector.recordSubscribe(subscriber, "tickStream", t);
        detector.recordRequest(subscriber, Long.MAX_VALUE);
        detector.recordComplete(subscriber, t);
        detector.recordNextStart(subscriber, t);                 // after onComplete
        detector.recordNextEnd(subscriber);

        var report = detector.analyze();
        assertTrue(report.hasIssues(),
                () -> "onNext after onComplete must be flagged:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 4: the serialised publisher keeps an unsynchronised subscriber
    // correct, which is the whole point of the rule.
    // -----------------------------------------------------------------------

    @Test
    void serialisedDelivery_keepsTheSubscribersTotalCorrect() {
        Thread t = Thread.currentThread();
        detector.recordSubscribe(subscriber, "tickStream", t);
        detector.recordRequest(subscriber, 3);

        for (int i = 0; i < 3; i++) {
            detector.recordNextStart(subscriber, t);
            subscriber.onNext(100L + i);
            detector.recordNextEnd(subscriber);
        }
        detector.recordComplete(subscriber, t);

        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "Delivery stayed within the contract:\n" + report);
        // 100 + 101 + 102, with no lock anywhere in the subscriber.
        assertTrue(subscriber.total() == 303L, "total was " + subscriber.total());
        assertTrue(subscriber.received() == 3, "received " + subscriber.received());
    }
}
