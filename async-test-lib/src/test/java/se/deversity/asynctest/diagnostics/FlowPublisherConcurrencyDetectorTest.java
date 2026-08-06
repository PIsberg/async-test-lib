package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FlowPublisherConcurrencyDetector}.
 *
 * <p>Threads passed to the record methods are identity carriers only (the detector reads
 * {@code threadId()} / {@code getName()}), so unstarted {@code Thread} instances make the
 * overlap scenarios deterministic instead of schedule-dependent.
 */
class FlowPublisherConcurrencyDetectorTest {

    /** Stand-in for a Flow.Subscriber; the detector never calls it. */
    private static final class FakeSubscriber { }

    private FlowPublisherConcurrencyDetector detector;

    @BeforeEach
    void setUp() {
        detector = new FlowPublisherConcurrencyDetector();
    }

    @Test
    void sequentialDeliveryWithinDemandIsClean() {
        FakeSubscriber sub = new FakeSubscriber();
        Thread t = Thread.currentThread();
        detector.recordSubscribe(sub, "clean", t);
        detector.recordRequest(sub, 2);
        detector.recordNextStart(sub, t);
        detector.recordNextEnd(sub);
        detector.recordNextStart(sub, t);
        detector.recordNextEnd(sub);
        detector.recordComplete(sub, t);

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "Serialized, in-demand delivery must be clean: " + report);
    }

    @Test
    void overlappingOnNextIsFlagged() {
        FakeSubscriber sub = new FakeSubscriber();
        Thread other = new Thread(() -> { }, "flow-worker-2");
        detector.recordNextStart(sub, Thread.currentThread());
        detector.recordNextStart(sub, other);

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "Two threads inside onNext at once must be flagged");
        assertTrue(report.toString().contains("overlapping onNext"),
                "Report must name the overlap: " + report);
        assertFalse(report.structuredViolations.isEmpty(), "A structured Violation must be emitted");
    }

    @Test
    void signalAfterTerminalIsFlagged() {
        FakeSubscriber sub = new FakeSubscriber();
        Thread t = Thread.currentThread();
        detector.recordComplete(sub, t);
        detector.recordNextStart(sub, t);
        detector.recordNextEnd(sub);

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "onNext after onComplete must be flagged");
        assertTrue(report.toString().contains("after a terminal"),
                "Report must name the late signal: " + report);
    }

    @Test
    void secondTerminalSignalIsFlagged() {
        FakeSubscriber sub = new FakeSubscriber();
        Thread t = Thread.currentThread();
        detector.recordComplete(sub, t);
        detector.recordError(sub, t);

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "A second terminal signal must be flagged");
    }

    @Test
    void deliveryBeyondRecordedDemandIsFlaggedAsMedium() {
        FakeSubscriber sub = new FakeSubscriber();
        Thread t = Thread.currentThread();
        detector.recordRequest(sub, 1);
        detector.recordNextStart(sub, t);
        detector.recordNextEnd(sub);
        detector.recordNextStart(sub, t);
        detector.recordNextEnd(sub);

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "Delivering 2 with 1 requested must be flagged");
        assertTrue(report.toString().contains("MEDIUM"),
                "Demand findings are conditional and must carry MEDIUM severity: " + report);
    }

    @Test
    void noDemandFindingWhenNoRequestWasRecorded() {
        FakeSubscriber sub = new FakeSubscriber();
        Thread t = Thread.currentThread();
        detector.recordNextStart(sub, t);
        detector.recordNextEnd(sub);
        detector.recordNextStart(sub, t);
        detector.recordNextEnd(sub);

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                "Partial instrumentation (no recordRequest) must not fake a demand overrun: " + report);
    }

    @Test
    void unbalancedNextEndCannotManufactureOverlap() {
        FakeSubscriber sub = new FakeSubscriber();
        Thread t = Thread.currentThread();
        detector.recordNextEnd(sub);
        detector.recordNextEnd(sub);
        detector.recordNextStart(sub, t);
        detector.recordNextEnd(sub);
        detector.recordNextStart(sub, t);

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                "Sequential delivery with unbalanced brackets must stay clean: " + report);
    }

    @Test
    void nullSubscriberAndNullThreadAreIgnored() {
        detector.recordSubscribe(null, "x", Thread.currentThread());
        detector.recordRequest(null, 1);
        detector.recordNextStart(null, Thread.currentThread());
        detector.recordNextEnd(null);
        detector.recordComplete(null, Thread.currentThread());
        detector.recordNextStart(new FakeSubscriber(), null);
        assertFalse(detector.analyze().hasIssues());
    }

    @Test
    void analyzeIsIdempotent() {
        FakeSubscriber sub = new FakeSubscriber();
        detector.recordNextStart(sub, Thread.currentThread());
        detector.recordNextStart(sub, new Thread(() -> { }, "flow-worker-2"));

        String first = detector.analyze().toString();
        String second = detector.analyze().toString();
        assertEquals(first, second, "analyze() must be idempotent on quiescent state");
    }
}
