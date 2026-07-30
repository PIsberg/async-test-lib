package se.deversity.asynctest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code @AsyncTest(includes = {...})} and {@code Builder.includes(...)}:
 * only the listed detectors are enabled, excludes win on conflict, and a
 * non-empty includes overrides preset/detectAll.
 */
class IncludesConfigTest {

    private static AsyncTest annotationOf(String methodName) throws NoSuchMethodException {
        return Holder.class.getDeclaredMethod(methodName).getAnnotation(AsyncTest.class);
    }

    @Test
    void includesEnablesExactlyTheListedDetectors() throws Exception {
        AsyncTestConfig cfg = AsyncTestConfig.from(annotationOf("includesTwo"));

        assertTrue(cfg.detectDeadlocks, "included detector must be enabled");
        assertTrue(cfg.detectSharedMessageDigest, "included detector must be enabled");
        assertFalse(cfg.detectRaceConditions, "non-included detector must be disabled");
        assertFalse(cfg.detectSharedCollections, "non-included detector must be disabled");
        assertFalse(cfg.detectVisibility, "non-included detector must be disabled");
    }

    @Test
    void includesOverridesDetectAllFalse() throws Exception {
        AsyncTestConfig cfg = AsyncTestConfig.from(annotationOf("includesDespiteDetectAllFalse"));

        assertTrue(cfg.detectSharedMessageDigest,
            "includes must win over detectAll=false");
        assertFalse(cfg.detectRaceConditions);
    }

    @Test
    void includesOverridesPreset() throws Exception {
        AsyncTestConfig cfg = AsyncTestConfig.from(annotationOf("includesDespitePresetNone"));

        assertTrue(cfg.detectDeadlocks, "includes must win over preset=NONE");
        assertFalse(cfg.detectSharedMessageDigest);
    }

    @Test
    void excludesWinsOverIncludes() throws Exception {
        AsyncTestConfig cfg = AsyncTestConfig.from(annotationOf("includeAndExcludeSame"));

        assertFalse(cfg.detectDeadlocks, "excludes must win on conflict with includes");
        assertTrue(cfg.detectSharedMessageDigest, "non-conflicting include stays enabled");
    }

    @Test
    void emptyIncludesKeepsLegacyDetectAllSemantics() throws Exception {
        AsyncTestConfig cfg = AsyncTestConfig.from(annotationOf("plainDetectAll"));

        assertTrue(cfg.detectDeadlocks);
        assertTrue(cfg.detectRaceConditions);
        assertTrue(cfg.detectSharedMessageDigest);
    }

    @Test
    void builderIncludesMirrorsAnnotationSemantics() {
        AsyncTestConfig cfg = AsyncTestConfig.builder()
            .includes(new DetectorType[] {DetectorType.DEADLOCKS, DetectorType.SHARED_MESSAGE_DIGEST})
            .excludes(new DetectorType[] {DetectorType.SHARED_MESSAGE_DIGEST})
            .build();

        assertTrue(cfg.detectDeadlocks);
        assertFalse(cfg.detectSharedMessageDigest, "builder excludes must win over includes");
        assertFalse(cfg.detectRaceConditions);
    }

    @SuppressWarnings("unused")
    static class Holder {

        @AsyncTest(includes = {DetectorType.DEADLOCKS, DetectorType.SHARED_MESSAGE_DIGEST})
        void includesTwo() {}

        @AsyncTest(detectAll = false, includes = {DetectorType.SHARED_MESSAGE_DIGEST})
        void includesDespiteDetectAllFalse() {}

        @AsyncTest(preset = Preset.NONE, includes = {DetectorType.DEADLOCKS})
        void includesDespitePresetNone() {}

        @AsyncTest(includes = {DetectorType.DEADLOCKS, DetectorType.SHARED_MESSAGE_DIGEST},
                   excludes = {DetectorType.DEADLOCKS})
        void includeAndExcludeSame() {}

        @AsyncTest(detectAll = true)
        void plainDetectAll() {}
    }
}
