package se.deversity.asynctest;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AsyncTestConfig.Builder#preset(Preset)} selects a preset the way {@code @AsyncTest} does
 * (#931).
 *
 * <p>Since 1.13.0 a bare {@code @AsyncTest} runs {@link Preset#ESSENTIALS}, but the builder had no
 * preset at all: a programmatic run ({@code AsyncTestRunner}, the JVM-languages docs) could ask
 * for the annotation's default only by listing its 12 types in {@code includes}.
 */
class AsyncTestConfigBuilderPresetTest {

    private static Set<DetectorType> bareAnnotation() throws NoSuchMethodException {
        return AsyncTestConfig.from(Fixtures.class.getDeclaredMethod("bare").getAnnotation(AsyncTest.class))
                .enabledDetectors();
    }

    @Test
    void aPresetSelectsItsSet_andEssentialsIsWhatABareAnnotationRuns() throws Exception {
        Set<DetectorType> essentials = AsyncTestConfig.builder().preset(Preset.ESSENTIALS).build().enabledDetectors();
        assertEquals(Preset.ESSENTIALS.enabled(), essentials);
        assertEquals(bareAnnotation(), essentials);
        assertEquals(Preset.CI_FAST.enabled(), AsyncTestConfig.builder().preset(Preset.CI_FAST).build().enabledDetectors());
        assertEquals(EnumSet.noneOf(DetectorType.class), AsyncTestConfig.builder().preset(Preset.NONE).build().enabledDetectors());
    }

    @Test
    void anAllPresetSelectsEveryDetector_andReadsAsDetectAll() {
        for (Preset all : new Preset[] {Preset.ALL, Preset.STRICT}) {
            AsyncTestConfig cfg = AsyncTestConfig.builder().preset(all).build();
            assertEquals(EnumSet.allOf(DetectorType.class), cfg.enabledDetectors(), all.name());
            assertTrue(cfg.detectAll, all + " must read as detectAll, as it does through the annotation");
        }
    }

    @Test
    void includesThenDetectAllWinOverThePreset_andExcludesApplyOnTop() {
        assertEquals(EnumSet.of(DetectorType.TIMER), AsyncTestConfig.builder()
                .preset(Preset.ESSENTIALS).includes(new DetectorType[] {DetectorType.TIMER}).build().enabledDetectors());
        assertEquals(EnumSet.allOf(DetectorType.class), AsyncTestConfig.builder()
                .preset(Preset.CI_FAST).detectAll(true).build().enabledDetectors());
        Set<DetectorType> trimmed = EnumSet.copyOf(Preset.ESSENTIALS.enabled());
        trimmed.remove(DetectorType.LIVELOCKS);
        assertEquals(trimmed, AsyncTestConfig.builder().preset(Preset.ESSENTIALS)
                .excludes(new DetectorType[] {DetectorType.LIVELOCKS}).build().enabledDetectors());
    }

    @Test
    void aPresetReplacesThePerDetectorSetters_asDetectAllDoes() {
        assertEquals(Preset.CI_FAST.enabled(), AsyncTestConfig.builder()
                .detectTimerIssues(true).preset(Preset.CI_FAST).build().enabledDetectors());
    }

    @Test
    void aNullPresetIsRefused() {
        assertThrows(NullPointerException.class, () -> AsyncTestConfig.builder().preset(null));
    }

    static class Fixtures {
        @AsyncTest
        void bare() { }
    }
}
