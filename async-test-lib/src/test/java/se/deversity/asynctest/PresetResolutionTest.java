package se.deversity.asynctest;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies Preset → AsyncTestConfig resolution. The matrix:
 *
 * <ul>
 *   <li>{@link Preset#ALL} / {@link Preset#STRICT} — every detector flag stays at the
 *       annotation default (current behavior, detectAll honored).</li>
 *   <li>{@link Preset#ESSENTIALS} — exactly the listed detectors enabled, rest off.</li>
 *   <li>{@link Preset#CI_FAST} — minimal high-signal subset; visibility / livelocks
 *       / heavyweight ones must be off.</li>
 *   <li>{@link Preset#NONE} — every detector flag is off.</li>
 *   <li>user-supplied {@code excludes()} prune even from a curated preset.</li>
 * </ul>
 */
class PresetResolutionTest {

    @Test
    void presetAll_enablesEverything() {
        AsyncTestConfig cfg = AsyncTestConfig.from(annotation(Preset.ALL));
        assertTrue(cfg.detectAll, "ALL preset must keep detectAll");
        assertTrue(cfg.detectDeadlocks);
        assertTrue(cfg.detectRaceConditions);
        assertTrue(cfg.detectVisibility, "ALL must enable VISIBILITY (heavy but in scope)");
    }

    @Test
    void presetStrict_isSameAsAll() {
        AsyncTestConfig cfg = AsyncTestConfig.from(annotation(Preset.STRICT));
        assertTrue(cfg.detectDeadlocks);
        assertTrue(cfg.detectRaceConditions);
        assertTrue(cfg.detectVisibility);
    }

    @Test
    void presetNone_disablesEveryDetector() {
        AsyncTestConfig cfg = AsyncTestConfig.from(annotation(Preset.NONE));
        // Spot-check across phases — every detector flag should be false.
        assertFalse(cfg.detectDeadlocks);
        assertFalse(cfg.detectRaceConditions);
        assertFalse(cfg.detectVisibility);
        assertFalse(cfg.detectLockLeaks);
        assertFalse(cfg.detectSharedMessageDigest);
        assertFalse(cfg.detectUncaughtExceptionHandler);
    }

    @Test
    void presetEssentials_enablesOnlyTheCuratedSet() {
        AsyncTestConfig cfg = AsyncTestConfig.from(annotation(Preset.ESSENTIALS));
        // In:
        assertTrue(cfg.detectDeadlocks);
        assertTrue(cfg.detectRaceConditions);
        assertTrue(cfg.detectAtomicityViolations);
        assertTrue(cfg.detectLockLeaks);
        assertTrue(cfg.detectInterruptMishandling);
        assertTrue(cfg.detectConcurrentModifications);
        assertTrue(cfg.detectCompletableFutureExceptions);
        assertTrue(cfg.detectResourceLeaks);
        // Out (representative samples):
        assertFalse(cfg.detectVisibility, "ESSENTIALS must skip the heavyweight visibility detector");
        assertFalse(cfg.detectFalseSharing);
        assertFalse(cfg.detectSharedMessageDigest);
    }

    @Test
    void presetCiFast_isSmallerThanEssentials() {
        AsyncTestConfig cfg = AsyncTestConfig.from(annotation(Preset.CI_FAST));
        assertTrue(cfg.detectDeadlocks);
        assertTrue(cfg.detectRaceConditions);
        assertTrue(cfg.detectAtomicityViolations);
        assertTrue(cfg.detectLockLeaks);
        assertTrue(cfg.detectConcurrentModifications);
        assertTrue(cfg.detectCompletableFutureExceptions);
        // CI_FAST omits these but ESSENTIALS includes them.
        assertFalse(cfg.detectLivelocks);
        assertFalse(cfg.detectInterruptMishandling);
        assertFalse(cfg.detectVisibility);
    }

    @Test
    void userExcludesPruneFromPreset() {
        AsyncTestConfig cfg = AsyncTestConfig.from(
                annotation(Preset.ESSENTIALS, DetectorType.DEADLOCKS, DetectorType.RACE_CONDITIONS));
        assertFalse(cfg.detectDeadlocks, "Explicit excludes must remove a detector even from a preset");
        assertFalse(cfg.detectRaceConditions);
        // Other preset members still on.
        assertTrue(cfg.detectAtomicityViolations);
    }

    // ---- helper: build a stand-in AsyncTest annotation via reflection proxy ----

    private static AsyncTest annotation(Preset preset, DetectorType... excludes) {
        // We don't need a real annotation — AsyncTestConfig.from reads via accessor
        // methods only, so a JDK proxy backed by the annotation's defaults plus our
        // overrides is the cleanest stand-in.
        return new AsyncTestStub(preset, excludes);
    }

    /**
     * Minimal AsyncTest implementation used to drive AsyncTestConfig.from in unit
     * tests. Every method defers to the annotation's default value except preset()
     * and excludes(), which the tests vary.
     */
    @SuppressWarnings("ClassExplicitlyAnnotation")
    private static final class AsyncTestStub implements AsyncTest {
        private final Preset preset;
        private final DetectorType[] excludes;
        AsyncTestStub(Preset preset, DetectorType[] excludes) {
            this.preset = preset;
            this.excludes = excludes;
        }
        private static <T> T def(String name) {
            try {
                @SuppressWarnings("unchecked")
                T v = (T) AsyncTest.class.getDeclaredMethod(name).getDefaultValue();
                return v;
            } catch (NoSuchMethodException e) { throw new AssertionError(e); }
        }
        @Override public int threads() { return def("threads"); }
        @Override public int[] threadCounts() { return def("threadCounts"); }
        @Override public int invocations() { return def("invocations"); }
        @Override public boolean useVirtualThreads() { return def("useVirtualThreads"); }
        @Override public long timeoutMs() { return def("timeoutMs"); }
        @Override public String virtualThreadStressMode() { return def("virtualThreadStressMode"); }
        @Override public boolean detectAll() { return def("detectAll"); }
        @Override public Preset preset() { return preset; }
        @Override public long replaySeed() { return def("replaySeed"); }
        @Override public DetectorType[] excludes() { return excludes; }
        @Override public DetectorType[] includes() { return def("includes"); }
        @Override public String[] excludeIds() { return def("excludeIds"); }
        @Override public FailOn failOn() { return def("failOn"); }
        @Override public se.deversity.asynctest.diagnostics.TrustTier minTrust() { return def("minTrust"); }
        @Override public boolean enableBenchmarking() { return def("enableBenchmarking"); }
        @Override public double benchmarkRegressionThreshold() { return def("benchmarkRegressionThreshold"); }
        @Override public boolean failOnBenchmarkRegression() { return def("failOnBenchmarkRegression"); }
        @Override public String keygenAccountId() { return def("keygenAccountId"); }
        @Override public String keygenApiKey() { return def("keygenApiKey"); }
        @Override public String keygenProductId() { return def("keygenProductId"); }
        @Override public String lemonSqueezyStore() { return def("lemonSqueezyStore"); }
        @Override public String licenseKey() { return def("licenseKey"); }
        @Override public boolean licenseMockMode() { return def("licenseMockMode"); }
        @Override public Class<? extends Annotation> annotationType() { return AsyncTest.class; }
    }
}
