package se.deversity.asynctest.telemetry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.FailOn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * End-to-end check that field-access telemetry captured during a run reaches a detector.
 *
 * <p><strong>The failure this pins.</strong> The agent is the library's only automatic
 * detection path: it weaves accessors, and every intercepted access is published to
 * {@link TelemetryEventBuffer} through {@link TelemetryRegistry#recordAccess}. A background
 * drain thread flushes that buffer to whatever {@code DrainCallback} is registered.
 * {@link TelemetryBridge} is that callback, and it forwards the events into the live
 * {@code AtomicityValidator} for the run.
 *
 * <p>Nothing in the library used to register it. {@code AsyncTestAgent.premain} calls the
 * no-argument {@link TelemetryRegistry#start()}, which leaves the callback null, and
 * {@code TelemetryRegistry}'s drain then hands every event to a discard lambda. The result
 * was a complete, documented, unit-tested pipeline whose last hop was missing: with the
 * agent attached, every captured access was thrown away and no detector ever saw one.
 * The unit tests could not catch it because each half worked — the agent published, the
 * bridge forwarded, the validator analysed — and only the wiring between the runner and
 * the bridge was absent.
 *
 * <p>This test stands in for the agent by publishing the same events the advice would, so
 * it needs no {@code -javaagent} and no byte-buddy on the classpath (which the architecture
 * rules forbid here anyway). What it exercises is the part that was missing: telemetry
 * published from the worker threads of a live {@code @AsyncTest} run has to be drained into
 * that run's detector and be visible to the {@code failOn} gate.
 */
class AgentTelemetryReachesDetectorsTest {

    @AfterEach
    void stopTelemetry() {
        TelemetryRegistry.stop();
    }

    @Test
    @DisplayName("telemetry published during a run reaches the run's atomicity detector")
    void telemetryPublishedDuringARunReachesTheDetector() {
        // What AsyncTestAgent.premain does when -javaagent is present.
        TelemetryRegistry.start();

        Events testEvents = EngineTestKit
                .engine("junit-jupiter")
                .selectors(selectClass(TelemetryPublishingDummy.class))
                .execute()
                .testEvents();

        assertEquals(1, testEvents.failed().count(),
                "The run publishes reads and writes of one identifier from several worker "
                        + "threads, which is exactly what AtomicityValidator flags, and it runs "
                        + "with failOn=LOW. If nothing failed, the telemetry never reached the "
                        + "detector: check that ConcurrencyRunner still activates TelemetryBridge "
                        + "for the run and flushes the buffer before analysis.");
    }

    /**
     * Runs on several threads, each publishing one read and one write of the same
     * identifier. {@code AtomicityValidator} reports a field seen by more than one thread
     * with both a read and a write, so a working pipeline turns this into a finding, and
     * {@code failOn = LOW} turns that finding into a test failure.
     *
     * <p>Only the atomicity detector is enabled, so the failure cannot come from another
     * detector reacting to the same code.
     */
    public static class TelemetryPublishingDummy {

        @AsyncTest(threads = 4,
                   invocations = 3,
                   includes = DetectorType.ATOMICITY_VIOLATIONS,
                   failOn = FailOn.LOW)
        void publishesAccessesFromEveryWorker() {
            long threadId = Thread.currentThread().threadId();
            TelemetryRegistry.recordAccess(threadId, "SharedCounter.value", false);
            TelemetryRegistry.recordAccess(threadId, "SharedCounter.value", true);
        }
    }
}
