package se.deversity.asynctest.diagnostics;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

/**
 * What has to happen before a detector can produce a finding.
 *
 * <p>The corpus eval ran 34 unmodified third-party classes under {@code detectAll = true} and
 * exactly two detectors of 142 spoke. That is not a defect in the other 140: it is a property
 * nobody had named. A detector is fed by one of three things, and a user deciding whether to
 * attach the agent, or wondering why a detector never fires, is really asking which one.
 *
 * <p>The classification lives in {@link DetectorFeeds}, one row per {@code DetectorType}, and is
 * printed for readers in {@code docs/DETECTOR_CATALOG.md}; {@code DetectorFeedCoverageTest} keeps
 * the three in step.
 *
 * @since 1.10.0
 */
@API(status = Status.EXPERIMENTAL)
public enum DetectorFeed {

    /**
     * Fed by the agent's woven streams: field accesses, collection call sites and lock
     * acquisitions in code the agent instruments. Fires on unmodified code, including
     * third-party code, whenever the agent is attached; also accepts explicit recording.
     */
    AGENT,

    /**
     * Fed by the JVM and the harness themselves: thread and lock introspection via
     * {@code ThreadMXBean}, thread dumps, memory and GC beans, and the runner's own rounds,
     * timings and thread lifecycle. Fires with an empty test body, no agent and no recording
     * call; attaching nothing and recording nothing still measures something.
     */
    ZERO_CONFIG,

    /**
     * Fed only by explicit recording: the test body (or code under test cooperating with it)
     * calls a {@code record*}/{@code register*} API on the detector, usually through
     * {@code AsyncTestContext}. Without those calls the detector has nothing to say, however
     * buggy the code under test is.
     */
    RECORDING
}
