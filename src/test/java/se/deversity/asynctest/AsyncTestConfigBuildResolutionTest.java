package se.deversity.asynctest;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
