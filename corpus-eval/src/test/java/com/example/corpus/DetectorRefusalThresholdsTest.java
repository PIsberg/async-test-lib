package com.example.corpus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.diagnostics.FalseSharingDetector;
import se.deversity.asynctest.diagnostics.PlatformThreadPerTaskDetector;
import se.deversity.asynctest.diagnostics.ThreadStarvationDetector;
import se.deversity.asynctest.diagnostics.VirtualThreadCarrierExhaustionDetector;
import se.deversity.asynctest.diagnostics.VirtualThreadCpuBoundTaskDetector;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the specific thresholds, properties, and model assumptions cited by each entry in
 * {@link DetectorCoverage#refused()}.
 *
 * <p>Refusals are written down when a detector cannot be paired, either because it has no
 * innocent twin (every recorded event is a defect) or because its outcome depends on runtime
 * clocks, GC pauses, or hardware core counts. Without this check, a detector that changes its
 * internal thresholds or gains a structural seam makes its refusal rationale obsolete without
 * anything failing.
 */
class DetectorRefusalThresholdsTest {

    @Test
    @DisplayName("false sharing refusal pins experimental property and 100-access threshold")
    void falseSharingRefusalPinsExperimentalPropertyAndThreshold() throws Exception {
        String rationale = DetectorCoverage.refused().get(DetectorType.FALSE_SHARING);
        assertNotNull(rationale, "FALSE_SHARING must be refused");
        assertTrue(rationale.contains("experimental flag") && rationale.contains("100-access threshold"),
                "rationale must cite experimental flag and 100-access threshold: " + rationale);

        assertEquals("async-test.experimental.false-sharing", FalseSharingDetector.EXPERIMENTAL_PROPERTY);

        Field thresholdField = FalseSharingDetector.class.getDeclaredField("FIELD_ACCESS_THRESHOLD");
        thresholdField.setAccessible(true);
        assertEquals(100, thresholdField.getInt(null), "FIELD_ACCESS_THRESHOLD must be 100");
    }

    @Test
    @DisplayName("thread starvation refusal pins 1000 ms default threshold")
    void threadStarvationRefusalPinsThreshold() throws Exception {
        String rationale = DetectorCoverage.refused().get(DetectorType.THREAD_STARVATION);
        assertNotNull(rationale, "THREAD_STARVATION must be refused");
        assertTrue(rationale.contains("1000 ms threshold"),
                "rationale must cite 1000 ms threshold: " + rationale);

        ThreadStarvationDetector detector = new ThreadStarvationDetector();
        Field thresholdField = ThreadStarvationDetector.class.getDeclaredField("starvationThresholdMs");
        thresholdField.setAccessible(true);
        assertEquals(1000L, thresholdField.getLong(detector), "default starvation threshold must be 1000ms");
    }

    @Test
    @DisplayName("platform thread per task refusal pins 200 ms probe deadline")
    void platformThreadPerTaskRefusalPinsProbeDeadline() throws Exception {
        String rationale = DetectorCoverage.refused().get(DetectorType.PLATFORM_THREAD_PER_TASK);
        assertNotNull(rationale, "PLATFORM_THREAD_PER_TASK must be refused");
        assertTrue(rationale.contains("200 ms deadline"),
                "rationale must cite 200 ms deadline: " + rationale);

        Field timeoutField = PlatformThreadPerTaskDetector.class.getDeclaredField("PROBE_TIMEOUT_MS");
        timeoutField.setAccessible(true);
        assertEquals(200L, timeoutField.getLong(null), "probe timeout must be 200ms");
    }

    @Test
    @DisplayName("virtual thread CPU-bound refusal pins 50 ms threshold")
    void virtualThreadCpuBoundRefusalPinsThreshold() throws Exception {
        String rationale = DetectorCoverage.refused().get(DetectorType.VIRTUAL_THREAD_CPU_BOUND);
        assertNotNull(rationale, "VIRTUAL_THREAD_CPU_BOUND must be refused");
        assertTrue(rationale.contains("50 ms threshold"),
                "rationale must cite 50 ms threshold: " + rationale);

        Field thresholdField = VirtualThreadCpuBoundTaskDetector.class.getDeclaredField("DEFAULT_CPU_THRESHOLD_MS");
        thresholdField.setAccessible(true);
        assertEquals(50L, thresholdField.getLong(null), "default CPU threshold must be 50ms");
    }

    @Test
    @DisplayName("carrier exhaustion refusal pins availableProcessors carrier count")
    void virtualThreadCarrierExhaustionRefusalPinsAvailableProcessors() throws Exception {
        String rationale = DetectorCoverage.refused().get(DetectorType.VIRTUAL_THREAD_CARRIER_EXHAUSTION);
        assertNotNull(rationale, "VIRTUAL_THREAD_CARRIER_EXHAUSTION must be refused");
        assertTrue(rationale.contains("availableProcessors"),
                "rationale must cite availableProcessors: " + rationale);

        VirtualThreadCarrierExhaustionDetector detector = new VirtualThreadCarrierExhaustionDetector();
        Field carrierField = VirtualThreadCarrierExhaustionDetector.class.getDeclaredField("carrierCount");
        carrierField.setAccessible(true);
        int expectedCarriers = Runtime.getRuntime().availableProcessors();
        assertEquals(expectedCarriers, carrierField.getInt(detector),
                "carrier exhaustion detector must default to availableProcessors");
    }

    @Test
    @DisplayName("lock downgrade refusal pins deferral to LockUpgradeDeadlockDetector")
    void lockDowngradeRefusalPinsDeferral() {
        String rationale = DetectorCoverage.refused().get(DetectorType.LOCK_DOWNGRADE);
        assertNotNull(rationale, "LOCK_DOWNGRADE must be refused");
        assertTrue(rationale.contains("deferred to LockUpgradeDeadlockDetector"),
                "rationale must cite deferral to LockUpgradeDeadlockDetector: " + rationale);
    }

    @Test
    @DisplayName("livelocks refusal pins CPU time bit-identical sampling and virtual thread limitation")
    void livelocksRefusalPinsAssumptions() {
        String rationale = DetectorCoverage.refused().get(DetectorType.LIVELOCKS);
        assertNotNull(rationale, "LIVELOCKS must be refused");
        assertTrue(rationale.contains("bit-identical") && rationale.contains("Inert entirely under the default useVirtualThreads"),
                "rationale must cite bit-identical CPU time and virtual thread limitation: " + rationale);
    }

    @Test
    @DisplayName("memory ordering refusal pins adjacent log observation")
    void memoryOrderingRefusalPinsAdjacentLogObservation() {
        String rationale = DetectorCoverage.refused().get(DetectorType.MEMORY_ORDERING);
        assertNotNull(rationale, "MEMORY_ORDERING must be refused");
        assertTrue(rationale.contains("landing adjacent"),
                "rationale must cite adjacent log entries: " + rationale);
    }

    @Test
    @DisplayName("all seven single-direction detectors have documented no-innocent-twin rationale")
    void allSingleDirectionDetectorsHaveDocumentedRationale() {
        Set<DetectorType> singleDirection = Set.of(
                DetectorType.EXPLICIT_GC,
                DetectorType.VIRTUAL_THREAD_PINNING,
                DetectorType.THREAD_POOL_DEADLOCK,
                DetectorType.THIS_ESCAPE,
                DetectorType.THREAD_LOCAL_RANDOM_MISUSE,
                DetectorType.COMPLETABLE_FUTURE_OBTRUDE_ABUSE,
                DetectorType.DEPRECATED_THREAD_API
        );

        Map<DetectorType, String> refused = DetectorCoverage.refused();
        for (DetectorType type : singleDirection) {
            String rationale = refused.get(type);
            assertNotNull(rationale, type + " must be in refused map");
            assertTrue(rationale.length() > 20, type + " rationale must be descriptive");
        }
    }
}
