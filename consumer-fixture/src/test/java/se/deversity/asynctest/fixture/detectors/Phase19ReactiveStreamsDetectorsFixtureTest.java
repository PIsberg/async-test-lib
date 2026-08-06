package se.deversity.asynctest.fixture.detectors;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.spin;

/**
 * Phase 19, reactive-streams (Flow API) group — {@code FLOW_PUBLISHER_CONCURRENCY}.
 *
 * <p>No dedicated example module yet; the catalog entry in
 * {@code docs/DETECTOR_CATALOG.md} carries the buggy-vs-fixed pair.
 */
class Phase19ReactiveStreamsDetectorsFixtureTest {

    /** One subscriber shared by every worker — the sharing is the hazard. */
    private static final Object SHARED_SUBSCRIBER = new Object();

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.FLOW_PUBLISHER_CONCURRENCY})
    void flowPublisherConcurrency() {
        reachable("flowPublisherConcurrencyDetector()",
                AsyncTestContext::flowPublisherConcurrencyDetector);

        // The hazard: a hand-rolled publisher delivering onNext to one subscriber from
        // several threads at once (reactive-streams rule 1.3). Each worker brackets one
        // delivery to the shared subscriber; workers colliding on the barrier are the
        // overlapping delivery the detector observes.
        var detector = AsyncTestContext.flowPublisherConcurrencyDetector();
        detector.recordNextStart(SHARED_SUBSCRIBER, Thread.currentThread());
        spin(8);
        detector.recordNextEnd(SHARED_SUBSCRIBER);
        detector.recordComplete(SHARED_SUBSCRIBER, Thread.currentThread());
    }
}
