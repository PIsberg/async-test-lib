package se.deversity.asynctest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the JDK 25/26 detectors (Phases 16 and 18) are wired into the legacy
 * {@link DetectorRegistry} — the path {@code ConcurrencyRunner} uses to produce the
 * {@code @AsyncTest} report. The SPI registry is covered separately by
 * {@code AllDetectorsSpiCoverageTest}; this test guards the fan-out wiring
 * (config flag → registry field → analyzeAll) that the SPI test does not exercise.
 */
class Jdk2526DetectorWiringTest {

    @Test
    void detectAll_instantiatesAllJdk2526Detectors() {
        AsyncTestConfig cfg = AsyncTestConfig.builder().detectAll(true).build();

        assertTrue(cfg.detectStableValueMisuse);
        assertTrue(cfg.detectStructuredTaskScopeMisuse);
        assertTrue(cfg.detectGathererConcurrencyMisuse);
        assertTrue(cfg.detectLazyConstantMisuse);
        assertTrue(cfg.detectFinalFieldMutation);
        assertTrue(cfg.detectSharedKdf);

        DetectorRegistry reg = new DetectorRegistry(cfg);
        assertNotNull(reg.stableValueMisuseDetector);
        assertNotNull(reg.structuredTaskScopeMisuseDetector);
        assertNotNull(reg.gathererConcurrencyMisuseDetector);
        assertNotNull(reg.lazyConstantMisuseDetector);
        assertNotNull(reg.finalFieldMutationDetector);
        assertNotNull(reg.sharedKdfDetector);
    }

    @Test
    void excludes_disablesEachJdk2526Detector() {
        AsyncTestConfig cfg = AsyncTestConfig.builder()
                .detectAll(true)
                .excludes(new DetectorType[]{
                        DetectorType.STABLE_VALUE_MISUSE,
                        DetectorType.STRUCTURED_TASK_SCOPE_MISUSE,
                        DetectorType.GATHERER_CONCURRENCY_MISUSE,
                        DetectorType.LAZY_CONSTANT_MISUSE,
                        DetectorType.FINAL_FIELD_MUTATION,
                        DetectorType.SHARED_KDF})
                .build();

        assertNull(new DetectorRegistry(cfg).stableValueMisuseDetector);
        assertNull(new DetectorRegistry(cfg).structuredTaskScopeMisuseDetector);
        assertNull(new DetectorRegistry(cfg).gathererConcurrencyMisuseDetector);
        assertNull(new DetectorRegistry(cfg).lazyConstantMisuseDetector);
        assertNull(new DetectorRegistry(cfg).finalFieldMutationDetector);
        assertNull(new DetectorRegistry(cfg).sharedKdfDetector);
    }

    @Test
    void analyzeAll_isCleanWhenNoEventsRecorded() {
        AsyncTestConfig cfg = AsyncTestConfig.builder()
                .detectAll(true)
                .build();
        DetectorRegistry reg = new DetectorRegistry(cfg);
        // No record* calls made → the JDK 25/26 detectors must contribute no findings.
        assertTrue(reg.analyzeAll().stream()
                .noneMatch(s -> s.contains("StableValue")
                        || s.contains("StructuredTaskScope")
                        || s.contains("Gatherer")
                        || s.contains("LazyConstant")
                        || s.contains("final field")
                        || s.contains("SharedKdf")
                        || s.contains("KDF")));
    }
}
