package se.deversity.asynctest;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code -Dasync-test.detectAll=true} turns every detector on for every {@code @AsyncTest} in the
 * run, as {@code detectAll = true} on each annotation would (#946).
 *
 * <p>The 1.13.0 migration guide's audit is "run everything once, fix what it finds, go back to
 * {@code ESSENTIALS}", and without a run-wide switch the first step meant editing every annotation
 * and reverting it. The switch means exactly {@code detectAll = true}: it overrides a preset, the
 * default one included, while {@code includes} still selects exactly its list and
 * {@code excludes} still carve their types out.
 */
class RunWideDetectAllTest {

    private static final String PROPERTY = "async-test.detectAll";

    private String previous;

    @BeforeEach
    void saveProperty() {
        previous = System.getProperty(PROPERTY);
        System.clearProperty(PROPERTY);
    }

    @AfterEach
    void restoreProperty() {
        if (previous == null) {
            System.clearProperty(PROPERTY);
        } else {
            System.setProperty(PROPERTY, previous);
        }
    }

    private static Set<DetectorType> selectionOf(String method) throws NoSuchMethodException {
        AsyncTest ann = Fixtures.class.getDeclaredMethod(method).getAnnotation(AsyncTest.class);
        return AsyncTestConfig.from(ann).enabledDetectors();
    }

    @Test
    @DisplayName("unset, a bare annotation runs ESSENTIALS")
    void unsetLeavesTheDefault() throws Exception {
        assertEquals(Preset.ESSENTIALS.enabled(), selectionOf("bare"));
    }

    @Test
    @DisplayName("set, a bare annotation runs every detector")
    void setTurnsEveryDetectorOnForABareAnnotation() throws Exception {
        System.setProperty(PROPERTY, "true");
        assertEquals(EnumSet.allOf(DetectorType.class), selectionOf("bare"));
        assertEquals(DetectorType.values().length, AsyncTestConfig.from(
                Fixtures.class.getDeclaredMethod("bare").getAnnotation(AsyncTest.class))
                .enabledDetectors().size());
    }

    @Test
    @DisplayName("set, a named preset gives way to every detector")
    void setOverridesANamedPreset() throws Exception {
        System.setProperty(PROPERTY, "true");
        assertEquals(EnumSet.allOf(DetectorType.class), selectionOf("presetCiFast"));
    }

    @Test
    @DisplayName("set, includes still select exactly their list and excludes still apply")
    void includesAndExcludesStillWin() throws Exception {
        System.setProperty(PROPERTY, "true");
        assertEquals(EnumSet.of(DetectorType.FALSE_SHARING, DetectorType.TIMER), selectionOf("includesTwo"));
        Set<DetectorType> allButOne = EnumSet.allOf(DetectorType.class);
        allButOne.remove(DetectorType.DEADLOCKS);
        assertEquals(allButOne, selectionOf("bareMinusDeadlocks"));
    }

    @Test
    @DisplayName("any value but true leaves the annotation's own selection")
    void onlyTrueSwitchesItOn() throws Exception {
        System.setProperty(PROPERTY, "false");
        assertEquals(Preset.ESSENTIALS.enabled(), selectionOf("bare"));
        System.setProperty(PROPERTY, "yes");
        assertEquals(Preset.CI_FAST.enabled(), selectionOf("presetCiFast"));
    }

    static class Fixtures {
        @AsyncTest
        void bare() { }

        @AsyncTest(preset = Preset.CI_FAST)
        void presetCiFast() { }

        @AsyncTest(includes = {DetectorType.FALSE_SHARING, DetectorType.TIMER})
        void includesTwo() { }

        @AsyncTest(excludes = DetectorType.DEADLOCKS)
        void bareMinusDeadlocks() { }
    }
}
