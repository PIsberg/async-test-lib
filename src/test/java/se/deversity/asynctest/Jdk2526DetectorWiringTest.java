package se.deversity.asynctest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the three JDK 25/26 preview-era detectors are wired into the legacy
 * {@link DetectorRegistry} — the path {@code ConcurrencyRunner} uses to produce the
 * {@code @AsyncTest} report. The SPI registry is covered separately by
 * {@code AllDetectorsSpiCoverageTest}; this test guards the fan-out wiring
 * (config flag → registry field → analyzeAll) that the SPI test does not exercise.
 */
class Jdk2526DetectorWiringTest {

    @Test
    void detectAll_instantiatesAllThreeJdk2526Detectors() {
        AsyncTestConfig cfg = AsyncTestConfig.builder().detectAll(true).build();

        assertTrue(cfg.detectStableValueMisuse);
        assertTrue(cfg.detectStructuredTaskScopeMisuse);
        assertTrue(cfg.detectGathererConcurrencyMisuse);

        DetectorRegistry reg = new DetectorRegistry(cfg);
        assertNotNull(reg.stableValueMisuseDetector);
        assertNotNull(reg.structuredTaskScopeMisuseDetector);
        assertNotNull(reg.gathererConcurrencyMisuseDetector);
    }

    @Test
    void excludes_disablesEachJdk2526Detector() {
        AsyncTestConfig cfg = AsyncTestConfig.builder()
                .detectAll(true)
                .excludes(new DetectorType[]{
                        DetectorType.STABLE_VALUE_MISUSE,
                        DetectorType.STRUCTURED_TASK_SCOPE_MISUSE,
                        DetectorType.GATHERER_CONCURRENCY_MISUSE})
                .build();

        assertNull(new DetectorRegistry(cfg).stableValueMisuseDetector);
        assertNull(new DetectorRegistry(cfg).structuredTaskScopeMisuseDetector);
        assertNull(new DetectorRegistry(cfg).gathererConcurrencyMisuseDetector);
    }

    @Test
    void analyzeAll_isCleanWhenNoEventsRecorded() {
        AsyncTestConfig cfg = AsyncTestConfig.builder().detectAll(true).build();
        DetectorRegistry reg = new DetectorRegistry(cfg);
        // No record* calls made → the three detectors must contribute no findings.
        assertTrue(reg.analyzeAll().stream()
                .noneMatch(s -> s.contains("StableValue")
                        || s.contains("StructuredTaskScope")
                        || s.contains("Gatherer")));
    }
}
