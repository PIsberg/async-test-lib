package se.deversity.asynctest.spi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.report.Violation;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A third-party detector can declare an identity of its own, outside the closed
 * {@link DetectorType} enum, and be built, addressed, reported and switched off by that id (#919).
 *
 * <p>Before, {@link Detector#type()} was the only identity, so a genuinely new detector had to
 * borrow a built-in constant (the test stand-in {@link ExternalTestDetector} borrows
 * {@code EXPLICIT_GC}), and two such detectors borrowing one constant replaced each other in the
 * type-keyed registry.
 */
class OpenDetectorIdentityTest {

    @AfterEach
    void disarm() {
        NovelIdTestDetector.disarm();
        ExternalTestDetector.disarm();
    }

    @Test
    void aDetectorWithItsOwnIdIsBuiltAndAddressableById() {
        NovelIdTestDetector.arm();
        DetectorRegistry registry = DetectorRegistry.buildExternal(AsyncTestConfig.builder().build());

        Detector detector = registry.get(NovelIdTestDetector.ID);
        assertNotNull(detector, "a factory with its own id must be built and found by that id");
        assertEquals(NovelIdTestDetector.ID, detector.id());
        assertNull(detector.type(), "it declares no DetectorType");
    }

    @Test
    void aBuiltInTypeStillGivesTheId_andTheTypedLookupStillWorks() {
        ExternalTestDetector.arm();
        DetectorRegistry registry = DetectorRegistry.buildExternal(AsyncTestConfig.builder().build());

        assertEquals("EXPLICIT_GC", new ExternalTestDetectorFactory().id());
        assertNotNull(registry.get(DetectorType.EXPLICIT_GC));
        assertNotNull(registry.get("EXPLICIT_GC"));
    }

    @Test
    void itsFindingsReachTheReportsUnderItsName() {
        NovelIdTestDetector.arm();
        Map<String, String> reports = new AsyncTestContext(AsyncTestConfig.builder().build()).analyzeAllNamed();

        assertTrue(reports.getOrDefault(NovelIdTestDetector.NAME, "").contains(NovelIdTestDetector.MESSAGE),
                "the finding must be keyed by its Violation's detector name: " + reports.keySet());
    }

    @Test
    void excludeIdsSwitchesItOff() {
        NovelIdTestDetector.arm();
        AsyncTestConfig cfg = AsyncTestConfig.builder().excludeIds(NovelIdTestDetector.ID).build();

        assertFalse(cfg.isEnabled(NovelIdTestDetector.ID));
        assertNull(DetectorRegistry.buildExternal(cfg).get(NovelIdTestDetector.ID),
                "an id the config excludes must not be built");
        assertTrue(AsyncTestConfig.builder().build().isEnabled(NovelIdTestDetector.ID),
                "an id nobody excluded is enabled: the detector is on the classpath because the user put it there");
    }

    @Test
    void excludeIdsWinsOverAFactoryThatIgnoresTheConfig() {
        ExternalTestDetector.arm();
        // ExternalTestDetectorFactory.isEnabledFor answers from its own switch, not the config.
        AsyncTestConfig cfg = AsyncTestConfig.builder().excludeIds("EXPLICIT_GC").build();

        assertNull(DetectorRegistry.buildExternal(cfg).get("EXPLICIT_GC"),
                "the registry, not each factory, must enforce excludeIds");
    }

    @Test
    void aBuiltInIdIsItsTypeName_inBothDirections() {
        AsyncTestConfig cfg = AsyncTestConfig.builder().detectAll(true)
                .excludeIds("DEADLOCKS").build();

        assertFalse(cfg.isEnabled(DetectorType.DEADLOCKS), "excluding a built-in by its id excludes the type");
        assertFalse(cfg.isEnabled("DEADLOCKS"));
        assertTrue(cfg.isEnabled("VISIBILITY"));
        assertEquals(cfg.isEnabled(DetectorType.VISIBILITY), cfg.isEnabled("VISIBILITY"));
    }

    @Test
    void aDetectorWithNeitherIdNorTypeFailsLoudly() {
        Detector anonymous = List::of;
        IllegalStateException e = assertThrows(IllegalStateException.class, anonymous::id);
        assertTrue(e.getMessage().contains("id()"), e.getMessage());
    }

    @Test
    void twoDetectorsWithDifferentIdsCoexist() {
        NovelIdTestDetector.arm();
        ExternalTestDetector.arm();
        DetectorRegistry registry = DetectorRegistry.buildExternal(AsyncTestConfig.builder().build());

        assertEquals(2, registry.all().size());
        List<Violation> violations = registry.analyzeAll();
        assertEquals(2, violations.size(), "both detectors' findings must survive: " + violations);
    }
}
