package se.deversity.asynctest;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link AsyncTestConfig.Builder#build()} detector-resolution logic across all
 * paths — {@code detectAll} true/false, full {@code includes}, full {@code excludes}, and
 * their interaction — plus the non-detector setters. The detector-flag count is derived
 * reflectively (every public {@code boolean} field named {@code detect*}/{@code validate*}/
 * {@code monitor*}) so the assertions stay correct as detectors are added.
 */
class AsyncTestConfigBuildResolutionTest {

    private static boolean isDetectorFlag(Field f) {
        if (f.getType() != boolean.class) return false;
        String n = f.getName();
        if (n.equals("detectAll")) return false; // umbrella toggle, not a detector flag
        return n.startsWith("detect") || n.startsWith("validate") || n.startsWith("monitor");
    }

    private static int totalDetectorFlags() {
        int n = 0;
        for (Field f : AsyncTestConfig.class.getFields()) if (isDetectorFlag(f)) n++;
        return n;
    }

    private static int enabledDetectorFlags(AsyncTestConfig cfg) {
        int n = 0;
        try {
            for (Field f : AsyncTestConfig.class.getFields()) {
                if (isDetectorFlag(f) && f.getBoolean(cfg)) n++;
            }
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
        return n;
    }

    private static boolean flag(AsyncTestConfig cfg, String name) {
        try {
            return AsyncTestConfig.class.getField(name).getBoolean(cfg);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void detectAll_enablesEveryDetector() {
        AsyncTestConfig cfg = AsyncTestConfig.builder().detectAll(true).build();
        assertEquals(totalDetectorFlags(), enabledDetectorFlags(cfg));
    }

    @Test
    void detectAll_withEverythingExcluded_disablesEveryDetector() {
        // Exercises the `else detectX = false` side of every if in the detectAll block.
        AsyncTestConfig cfg = AsyncTestConfig.builder()
                .detectAll(true)
                .excludes(DetectorType.values())
                .build();
        assertEquals(0, enabledDetectorFlags(cfg));
    }

    @Test
    void detectAllFalse_withEverythingExcluded_staysAllDisabled() {
        // Exercises the detectAll=false else-block: every `if (excludes.contains(X))` taken.
        AsyncTestConfig cfg = AsyncTestConfig.builder()
                .detectAll(false)
                .excludes(DetectorType.values())
                .build();
        assertEquals(0, enabledDetectorFlags(cfg));
    }

    @Test
    void includes_all_enablesEveryDetector() {
        // includes forces detectAll and excludes nothing (everything is included).
        AsyncTestConfig cfg = AsyncTestConfig.builder()
                .includes(DetectorType.values())
                .build();
        assertEquals(totalDetectorFlags(), enabledDetectorFlags(cfg));
    }

    @Test
    void includes_single_enablesOnlyThatDetector() {
        AsyncTestConfig cfg = AsyncTestConfig.builder()
                .includes(new DetectorType[]{DetectorType.DEADLOCKS})
                .build();
        assertEquals(1, enabledDetectorFlags(cfg));
        assertTrue(flag(cfg, "detectDeadlocks"));
        assertFalse(flag(cfg, "detectVisibility"));
    }

    @Test
    void excludes_winOverIncludes() {
        // DEADLOCKS is both included and excluded → excluded wins; only VISIBILITY remains.
        AsyncTestConfig cfg = AsyncTestConfig.builder()
                .includes(new DetectorType[]{DetectorType.DEADLOCKS, DetectorType.VISIBILITY})
                .excludes(new DetectorType[]{DetectorType.DEADLOCKS})
                .build();
        assertEquals(1, enabledDetectorFlags(cfg));
        assertFalse(flag(cfg, "detectDeadlocks"));
        assertTrue(flag(cfg, "detectVisibility"));
    }

    @Test
    void detectAll_withSingleExclude_leavesEverythingElseEnabled() {
        AsyncTestConfig cfg = AsyncTestConfig.builder()
                .detectAll(true)
                .excludes(new DetectorType[]{DetectorType.DEADLOCKS})
                .build();
        assertEquals(totalDetectorFlags() - 1, enabledDetectorFlags(cfg));
        assertFalse(flag(cfg, "detectDeadlocks"));
    }

    @Test
    void nonDetectorSetters_arePreservedThroughBuild() {
        AsyncTestConfig cfg = AsyncTestConfig.builder()
                .threads(7)
                .invocations(42)
                .timeoutMs(1234L)
                .useVirtualThreads(false)
                .virtualThreadStressMode("HIGH")
                .replaySeed(99L)
                .failOn(FailOn.HIGH)
                .enableBenchmarking(true)
                .build();
        assertEquals(7, cfg.threads);
        assertEquals(42, cfg.invocations);
        assertEquals(1234L, cfg.timeoutMs);
        assertFalse(cfg.useVirtualThreads);
        assertEquals("HIGH", cfg.virtualThreadStressMode);
        assertEquals(99L, cfg.replaySeed);
        assertSame(FailOn.HIGH, cfg.failOn);
        assertTrue(cfg.enableBenchmarking);
    }

    @Test
    void failOn_null_defaultsToNone() {
        AsyncTestConfig cfg = AsyncTestConfig.builder().failOn(null).build();
        assertSame(FailOn.NONE, cfg.failOn);
    }

    // ---- Per-type exclude resolution (detectAll=false path) ----
    //
    // The mapping DetectorType -> config field is derived empirically through the
    // detectAll path: excluding exactly one type must disable exactly one flag.
    // That derivation doubles as a wiring check (no type may flip zero or two flags),
    // and the mapping then drives per-type assertions against the detectAll=false
    // exclude block — each `if (excludes.contains(X)) detectX = false` line is pinned
    // by an explicit enable + exclude of exactly that type.

    private static Map<DetectorType, String> detectorFieldByType() {
        Map<DetectorType, String> map = new EnumMap<>(DetectorType.class);
        try {
            for (DetectorType type : DetectorType.values()) {
                AsyncTestConfig cfg = AsyncTestConfig.builder()
                        .detectAll(true)
                        .excludes(new DetectorType[]{type})
                        .build();
                String disabled = null;
                for (Field f : AsyncTestConfig.class.getFields()) {
                    if (isDetectorFlag(f) && !f.getBoolean(cfg)) {
                        assertNull(disabled, "excluding " + type + " disabled both "
                                + disabled + " and " + f.getName());
                        disabled = f.getName();
                    }
                }
                assertNotNull(disabled, "excluding " + type + " under detectAll disabled no flag");
                map.put(type, disabled);
            }
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
        return map;
    }

    private static AsyncTestConfig.Builder enable(String flagName) {
        try {
            AsyncTestConfig.Builder b = AsyncTestConfig.builder();
            AsyncTestConfig.Builder.class.getMethod(flagName, boolean.class).invoke(b, true);
            return b;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Builder has no boolean setter named " + flagName, e);
        }
    }

    @Test
    void detectorTypes_mapBijectivelyOntoDetectorFlags() {
        Map<DetectorType, String> map = detectorFieldByType();
        Set<String> mappedFields = new HashSet<>(map.values());
        assertEquals(map.size(), mappedFields.size(), "two DetectorTypes control the same flag");
        assertEquals(totalDetectorFlags(), mappedFields.size(),
                "every detector flag must be controlled by exactly one DetectorType");
    }

    @Test
    void explicitEnable_withExcludeOfSameType_isDisabled() {
        for (Map.Entry<DetectorType, String> e : detectorFieldByType().entrySet()) {
            AsyncTestConfig cfg = enable(e.getValue())
                    .excludes(new DetectorType[]{e.getKey()})
                    .build();
            assertFalse(flag(cfg, e.getValue()),
                    e.getValue() + " enabled explicitly but excluded via " + e.getKey()
                            + " must resolve to disabled");
        }
    }

    @Test
    void explicitEnable_withoutExclude_staysEnabled() {
        for (Map.Entry<DetectorType, String> e : detectorFieldByType().entrySet()) {
            AsyncTestConfig cfg = enable(e.getValue()).build();
            assertTrue(flag(cfg, e.getValue()),
                    e.getValue() + " enabled explicitly with no excludes must stay enabled");
        }
    }
}
