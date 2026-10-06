package se.deversity.asynctest;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AsyncTestConfig#enabledDetectors()} is the one answer to "which detectors run", and every
 * public detector flag agrees with it.
 *
 * <p>Before #917 each flag was resolved on its own line in {@code build()}, 146 independent
 * expressions that could each be wrong, and nothing exposed the resolved selection as a whole: the
 * registry and every report had to re-ask 146 booleans. Derived from one set, a flag cannot
 * disagree with it, which is what this test checks across every way a selection is made.
 */
class AsyncTestConfigEnabledSetTest {

    @Test
    void theBuilderDefaultEnablesOnlyDeadlockDetection() {
        assertEquals(EnumSet.of(DetectorType.DEADLOCKS),
                AsyncTestConfig.builder().build().enabledDetectors());
    }

    @Test
    void detectAllEnablesEveryType_andExcludesRemoveFromIt() {
        assertEquals(EnumSet.allOf(DetectorType.class),
                AsyncTestConfig.builder().detectAll(true).build().enabledDetectors());
        Set<DetectorType> expected = EnumSet.allOf(DetectorType.class);
        expected.remove(DetectorType.VISIBILITY);
        assertEquals(expected, AsyncTestConfig.builder().detectAll(true)
                .excludes(new DetectorType[]{DetectorType.VISIBILITY}).build().enabledDetectors());
    }

    @Test
    void includesSelectExactlyTheirTypes_andExcludesStillWin() {
        AsyncTestConfig cfg = AsyncTestConfig.builder()
                .includes(new DetectorType[]{DetectorType.VISIBILITY, DetectorType.LIVELOCKS, DetectorType.TIMER})
                .excludes(new DetectorType[]{DetectorType.TIMER})
                .build();
        assertEquals(EnumSet.of(DetectorType.VISIBILITY, DetectorType.LIVELOCKS), cfg.enabledDetectors());
    }

    @Test
    void aPerDetectorSetterAddsAndRemovesItsType() {
        AsyncTestConfig cfg = AsyncTestConfig.builder()
                .detectDeadlocks(false)
                .detectSharedRandom(true)
                .monitorSemaphore(true)
                .build();
        assertEquals(EnumSet.of(DetectorType.SHARED_RANDOM, DetectorType.SEMAPHORE), cfg.enabledDetectors());
    }

    @Test
    void everyFlagEqualsMembershipInTheSet_acrossRandomSelections() throws IllegalAccessException {
        DetectorType[] all = DetectorType.values();
        Random random = new Random(917);
        for (int round = 0; round < 200; round++) {
            AsyncTestConfig.Builder b = AsyncTestConfig.builder().detectAll(random.nextBoolean());
            List<DetectorType> includes = new java.util.ArrayList<>();
            List<DetectorType> excludes = new java.util.ArrayList<>();
            for (DetectorType t : all) {
                int roll = random.nextInt(20);
                if (roll == 0) includes.add(t);
                if (roll == 1) excludes.add(t);
            }
            if (random.nextBoolean()) b.includes(includes.toArray(new DetectorType[0]));
            b.excludes(excludes.toArray(new DetectorType[0]));
            AsyncTestConfig cfg = b.build();
            for (DetectorType t : all) {
                assertEquals(cfg.enabledDetectors().contains(t), cfg.isEnabled(t), t.name());
            }
            int flagsOn = 0;
            for (Field f : AsyncTestConfig.class.getFields()) {
                if (isDetectorFlag(f) && f.getBoolean(cfg)) flagsOn++;
            }
            assertEquals(cfg.enabledDetectors().size(), flagsOn,
                    "the public flags that read true must be exactly the enabled set, round " + round);
        }
    }

    @Test
    void theSetCannotBeChangedThroughTheConfig() {
        Set<DetectorType> enabled = AsyncTestConfig.builder().detectAll(true).build().enabledDetectors();
        assertThrows(UnsupportedOperationException.class, () -> enabled.remove(DetectorType.DEADLOCKS));
        assertTrue(enabled.contains(DetectorType.DEADLOCKS));
    }

    private static boolean isDetectorFlag(Field f) {
        String n = f.getName();
        return f.getType() == boolean.class && !n.equals("detectAll")
                && (n.startsWith("detect") || n.startsWith("validate") || n.startsWith("monitor"));
    }
}
