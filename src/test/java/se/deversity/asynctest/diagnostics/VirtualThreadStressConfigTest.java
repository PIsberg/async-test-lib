package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import se.deversity.asynctest.diagnostics.VirtualThreadStressConfig.StressLevel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link VirtualThreadStressConfig} and its {@link StressLevel} enum.
 *
 * <p>Note: unlike some other diagnostics classes in this package, {@link VirtualThreadStressConfig}
 * does not read any {@code System.getProperty}/system-property-backed configuration — it is a
 * plain immutable value object built via {@link VirtualThreadStressConfig.Builder}. "Default when
 * unset" is therefore covered here as "default when the builder method is not called", rather than
 * via a system property; no {@code @AfterEach} property restoration is needed for this class.
 */
class VirtualThreadStressConfigTest {

    // ============= StressLevel thread counts =============

    @Test
    void lowStressLevel_has100Threads() {
        assertEquals(100, StressLevel.LOW.threadCount);
    }

    @Test
    void mediumStressLevel_has1000Threads() {
        assertEquals(1000, StressLevel.MEDIUM.threadCount);
    }

    @Test
    void highStressLevel_has10000Threads() {
        assertEquals(10000, StressLevel.HIGH.threadCount);
    }

    @Test
    void extremeStressLevel_has100000Threads() {
        assertEquals(100000, StressLevel.EXTREME.threadCount);
    }

    // ============= valueOf() parsing: case sensitivity + invalid input =============

    @Test
    void valueOf_parsesEachDefinedConstantByExactName() {
        assertSame(StressLevel.LOW, StressLevel.valueOf("LOW"));
        assertSame(StressLevel.MEDIUM, StressLevel.valueOf("MEDIUM"));
        assertSame(StressLevel.HIGH, StressLevel.valueOf("HIGH"));
        assertSame(StressLevel.EXTREME, StressLevel.valueOf("EXTREME"));
    }

    @Test
    void valueOf_isCaseSensitive_lowercaseIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> StressLevel.valueOf("low"));
        assertThrows(IllegalArgumentException.class, () -> StressLevel.valueOf("Medium"));
    }

    @Test
    void valueOf_rejectsUnknownLevelName() {
        assertThrows(IllegalArgumentException.class, () -> StressLevel.valueOf("UNKNOWN"));
        assertThrows(IllegalArgumentException.class, () -> StressLevel.valueOf(""));
    }

    @Test
    void valueOf_rejectsNullLevelName() {
        assertThrows(NullPointerException.class, () -> StressLevel.valueOf(null));
    }

    // ============= Builder defaults ("unset" behavior) =============

    @Test
    void builder_defaultsToMediumStressLevel() {
        VirtualThreadStressConfig config = VirtualThreadStressConfig.builder().build();

        assertEquals(StressLevel.MEDIUM, config.getStressLevel());
        assertEquals(1000, config.getThreadCount());
    }

    @Test
    void builder_defaultsToDetectThreadPinningEnabled() {
        VirtualThreadStressConfig config = VirtualThreadStressConfig.builder().build();

        assertTrue(config.isDetectThreadPinning());
    }

    @Test
    void builder_defaultsToVirtualThreadEventsDisabled() {
        VirtualThreadStressConfig config = VirtualThreadStressConfig.builder().build();

        assertFalse(config.isEnableVirtualThreadEvents());
    }

    @Test
    void builder_defaultsTo30SecondTimeout() {
        VirtualThreadStressConfig config = VirtualThreadStressConfig.builder().build();

        assertEquals(30000L, config.getTimeoutMs());
    }

    // ============= Builder overrides =============

    @Test
    void builder_appliesAllOverridesTogether() {
        VirtualThreadStressConfig config = VirtualThreadStressConfig.builder()
            .stressLevel(StressLevel.HIGH)
            .detectThreadPinning(false)
            .enableVirtualThreadEvents(true)
            .timeoutMs(5_000L)
            .build();

        assertEquals(StressLevel.HIGH, config.getStressLevel());
        assertEquals(10000, config.getThreadCount());
        assertFalse(config.isDetectThreadPinning());
        assertTrue(config.isEnableVirtualThreadEvents());
        assertEquals(5_000L, config.getTimeoutMs());
    }

    @Test
    void builder_eachOverrideIsIndependentOfTheOthers() {
        VirtualThreadStressConfig lowOnly = VirtualThreadStressConfig.builder()
            .stressLevel(StressLevel.LOW)
            .build();

        assertEquals(StressLevel.LOW, lowOnly.getStressLevel());
        // Untouched builder fields keep their defaults.
        assertTrue(lowOnly.isDetectThreadPinning());
        assertFalse(lowOnly.isEnableVirtualThreadEvents());
        assertEquals(30000L, lowOnly.getTimeoutMs());
    }

    @Test
    void builder_methodsReturnTheSameBuilderInstanceForChaining() {
        VirtualThreadStressConfig.Builder builder = VirtualThreadStressConfig.builder();

        assertSame(builder, builder.stressLevel(StressLevel.EXTREME));
        assertSame(builder, builder.detectThreadPinning(false));
        assertSame(builder, builder.enableVirtualThreadEvents(true));
        assertSame(builder, builder.timeoutMs(1L));
    }

    // ============= Direct (non-builder) constructor =============

    @Test
    void constructor_wiresAllFieldsThroughGetters() {
        VirtualThreadStressConfig config =
            new VirtualThreadStressConfig(StressLevel.EXTREME, false, true, 12_345L);

        assertEquals(StressLevel.EXTREME, config.getStressLevel());
        assertEquals(100000, config.getThreadCount());
        assertFalse(config.isDetectThreadPinning());
        assertTrue(config.isEnableVirtualThreadEvents());
        assertEquals(12_345L, config.getTimeoutMs());
    }

    // ============= Static helpers =============

    @Test
    void isVirtualThreadSupported_isTrueOnJava21Plus() {
        // This project targets Java 21 (maven.compiler.target), so virtual threads
        // (Thread.ofVirtual) must be available on any JVM this test suite runs on.
        assertTrue(VirtualThreadStressConfig.isVirtualThreadSupported());
    }

    @Test
    void getVirtualThreadExecutorClass_returnsVirtualThreadExecutorNameWhenSupported() {
        assertEquals(
            "java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor",
            VirtualThreadStressConfig.getVirtualThreadExecutorClass());
    }

    // ============= toString() =============

    @Test
    void toString_containsStressLevelThreadCountAndTimeout() {
        VirtualThreadStressConfig config = VirtualThreadStressConfig.builder()
            .stressLevel(StressLevel.HIGH)
            .detectThreadPinning(false)
            .timeoutMs(7_500L)
            .build();

        String text = config.toString();

        assertNotNull(text);
        assertTrue(text.contains("HIGH"));
        assertTrue(text.contains("10000"));
        assertTrue(text.contains("false"));
        assertTrue(text.contains("7500"));
    }
}
