package se.deversity.asynctest;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.DetectorTrust;
import se.deversity.asynctest.diagnostics.TrustTier;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bare {@code @AsyncTest} runs {@link Preset#ESSENTIALS}, and every detector is one explicit
 * {@code detectAll = true} away (#923).
 *
 * <p>Until 1.13.0 the bare annotation enabled every detector: each test paid for every detector's
 * setup and report, and read findings from detectors at every trust tier, while most suites want a
 * curated subset. The lean default is settled against {@link DetectorTrust}: no detector in it
 * may sit at {@link TrustTier#ADVISORY}, the tier whose findings are hints rather than defects.
 */
class LeanDefaultSelectionTest {

    private static Set<DetectorType> selectionOf(String method) throws NoSuchMethodException {
        return AsyncTestConfig.from(Fixtures.class.getDeclaredMethod(method).getAnnotation(AsyncTest.class))
                .enabledDetectors();
    }

    @Test
    void aBareAnnotationRunsTheEssentials() throws Exception {
        assertEquals(Preset.ESSENTIALS.enabled(), selectionOf("bare"));
    }

    @Test
    void detectAllIsTheExplicitOptInToEveryDetector() throws Exception {
        assertEquals(EnumSet.allOf(DetectorType.class), selectionOf("detectAllTrue"));
        assertEquals(EnumSet.allOf(DetectorType.class), selectionOf("presetAll"));
    }

    @Test
    void detectAllFalseLeavesThePresetInCharge() throws Exception {
        assertEquals(Preset.ESSENTIALS.enabled(), selectionOf("detectAllFalse"));
        assertEquals(Preset.CI_FAST.enabled(), selectionOf("presetCiFast"));
    }

    @Test
    void includesStillOverrideEverything() throws Exception {
        assertEquals(EnumSet.of(DetectorType.TIMER), selectionOf("includesTimer"));
    }

    @Test
    void theLeanDefaultHoldsNoAdvisoryDetector() {
        List<DetectorType> advisory = Preset.ESSENTIALS.enabled().stream()
                .filter(t -> DetectorTrust.tierOf(t) == TrustTier.ADVISORY)
                .toList();
        assertTrue(advisory.isEmpty(), "the default must not run hint-grade detectors: " + advisory);
        assertTrue(Preset.ESSENTIALS.enabled().containsAll(Preset.CI_FAST.enabled()),
                "CI_FAST is the lean default trimmed for speed, so it must sit inside it");
    }

    static class Fixtures {
        @AsyncTest
        void bare() { }

        @AsyncTest(detectAll = true)
        void detectAllTrue() { }

        @AsyncTest(preset = Preset.ALL)
        void presetAll() { }

        @AsyncTest(detectAll = false)
        void detectAllFalse() { }

        @AsyncTest(preset = Preset.CI_FAST)
        void presetCiFast() { }

        @AsyncTest(includes = DetectorType.TIMER)
        void includesTimer() { }
    }
}
