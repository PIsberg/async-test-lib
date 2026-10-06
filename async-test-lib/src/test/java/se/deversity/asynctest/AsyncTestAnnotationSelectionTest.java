package se.deversity.asynctest;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What an {@code @AsyncTest} annotation selects, read through {@link AsyncTestConfig#from(AsyncTest)}.
 *
 * <p>2.0.0 removed the per-detector boolean attributes (#920). Under 1.x {@code detectAll = false}
 * did not mean "none": it left every attribute at its default, and 144 of the 146 defaulted to
 * {@code true}. A selection is now said with {@code includes}, {@code excludes} and {@code preset},
 * and {@code detectAll = false} on its own selects nothing.
 */
class AsyncTestAnnotationSelectionTest {

    private static Set<DetectorType> selectionOf(String method) throws NoSuchMethodException {
        AsyncTest ann = Fixtures.class.getDeclaredMethod(method).getAnnotation(AsyncTest.class);
        return AsyncTestConfig.from(ann).enabledDetectors();
    }

    @Test
    void detectAllFalseOnItsOwnSelectsNothing() throws Exception {
        assertEquals(EnumSet.noneOf(DetectorType.class), selectionOf("detectAllFalse"));
    }

    @Test
    void includesSelectExactlyTheListedTypes() throws Exception {
        assertEquals(EnumSet.of(DetectorType.FALSE_SHARING, DetectorType.TIMER), selectionOf("includesTwo"));
    }

    @Test
    void presetNoneSelectsNothing_andAPresetSelectsItsSet() throws Exception {
        assertEquals(EnumSet.noneOf(DetectorType.class), selectionOf("presetNone"));
        assertEquals(Preset.CI_FAST.enabled(), selectionOf("presetCiFast"));
    }

    @Test
    void excludesWinOverAnyOtherSelection() throws Exception {
        assertEquals(EnumSet.of(DetectorType.TIMER), selectionOf("includesMinusExclude"));
        Set<DetectorType> allButOne = EnumSet.allOf(DetectorType.class);
        allButOne.remove(DetectorType.DEADLOCKS);
        assertEquals(allButOne, selectionOf("detectAllMinusExclude"));
    }

    static class Fixtures {
        @AsyncTest(detectAll = false)
        void detectAllFalse() { }

        @AsyncTest(includes = {DetectorType.FALSE_SHARING, DetectorType.TIMER})
        void includesTwo() { }

        @AsyncTest(preset = Preset.NONE)
        void presetNone() { }

        @AsyncTest(preset = Preset.CI_FAST)
        void presetCiFast() { }

        @AsyncTest(includes = {DetectorType.FALSE_SHARING, DetectorType.TIMER}, excludes = DetectorType.FALSE_SHARING)
        void includesMinusExclude() { }

        @AsyncTest(detectAll = true, excludes = DetectorType.DEADLOCKS)
        void detectAllMinusExclude() { }
    }
}
