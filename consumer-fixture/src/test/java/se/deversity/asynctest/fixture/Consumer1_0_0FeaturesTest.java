package se.deversity.asynctest.fixture;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncAssert;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.AsyncTestListenerRegistry;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.NoopAsyncTestListener;
import se.deversity.asynctest.Preset;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.report.JsonFormatter;
import se.deversity.asynctest.report.MarkdownFormatter;
import se.deversity.asynctest.report.Violation;
import se.deversity.asynctest.spi.DetectorRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Consumer-side smoke tests exercising every public-API addition in 1.0.0.
 *
 * <p>These compile against the published artifact (consumer-fixture depends
 * on the deployed JAR, not the source) — so a passing run proves these
 * symbols are part of the stable public API and a consumer can reach them
 * without depending on internal classes.
 *
 * <p>The existing {@code ConsumerAsyncTestUsageTest} covers the pre-1.0.0
 * surface. This file is additive — keeping the new feature tour isolated
 * makes regressions easy to bisect.
 */
class Consumer1_0_0FeaturesTest {

    // ---- 1) Preset enum is reachable + values match what's documented ----

    @Test
    void preset_enum_public_values_present() {
        assertEquals(5, Preset.values().length,
            "Preset enum is part of the public API and must expose ALL/STRICT/ESSENTIALS/CI_FAST/NONE");
        // Spot-check each by name to catch accidental renames.
        assertNotNull(Preset.ALL);
        assertNotNull(Preset.STRICT);
        assertNotNull(Preset.ESSENTIALS);
        assertNotNull(Preset.CI_FAST);
        assertNotNull(Preset.NONE);
        assertTrue(Preset.ALL.isAll());
        assertTrue(Preset.STRICT.isAll());
        assertFalse(Preset.ESSENTIALS.isAll());
    }

    @AsyncTest(threads = 4, invocations = 2, preset = Preset.CI_FAST,
               licenseMockMode = true)
    void preset_attribute_compiles_and_runs() {
        // Smoke: the @AsyncTest preset attribute compiles against the public
        // annotation and runs without throwing.
    }

    // ---- 2) threadCounts schedule matrix ----

    // 3 invocations × 1 round each ⇒ 3 JUnit test events.
    @AsyncTest(threadCounts = {2, 4, 8}, invocations = 1,
               preset = Preset.CI_FAST, licenseMockMode = true)
    void threadCounts_matrix_smoke() {
        // Just exists to prove the attribute compiles against the public annotation.
    }

    // ---- 3) replaySeed + AsyncTestContext.replaySeed() ----

    @AsyncTest(threads = 2, invocations = 1, replaySeed = 4242L,
               preset = Preset.NONE, licenseMockMode = true)
    void replaySeed_accessor_returns_pinned_value() {
        assertEquals(4242L, AsyncTestContext.replaySeed(),
            "Inside an @AsyncTest round with replaySeed=N, AsyncTestContext.replaySeed() must return N");
    }

    @Test
    void replaySeed_outside_test_returns_zero() {
        assertEquals(0L, AsyncTestContext.replaySeed(),
            "Outside any @AsyncTest round, AsyncTestContext.replaySeed() must default to 0L");
    }

    // ---- 4) AsyncAssert.awaitAsync ----

    @Test
    void awaitAsync_resolves_value() {
        CompletableFuture<String> ok = CompletableFuture.completedFuture("hello");
        assertEquals("hello", AsyncAssert.awaitAsync(ok, Duration.ofSeconds(1)));
    }

    @Test
    void awaitAsync_unwraps_runtime_exception() {
        CompletableFuture<Void> bad = CompletableFuture.failedFuture(
            new IllegalStateException("boom"));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> AsyncAssert.awaitAsync(bad, Duration.ofSeconds(1)));
        assertEquals("boom", ex.getMessage());
    }

    // ---- 5) Scoped listener registration ----

    @Test
    void registerScoped_is_autocloseable() {
        int countBefore = AsyncTestListenerRegistry.getListenerCount();
        try (AsyncTestListenerRegistry.Registration r =
                 AsyncTestListenerRegistry.registerScoped(new NoopAsyncTestListener())) {
            assertNotNull(r);
            assertEquals(countBefore + 1, AsyncTestListenerRegistry.getListenerCount());
        }
        assertEquals(countBefore, AsyncTestListenerRegistry.getListenerCount(),
            "Closing Registration must unregister the listener (no JVM-wide leak)");
    }

    @Test
    void snapshot_restoreSnapshot_revert_adds_and_removes() {
        AsyncTestListenerRegistry.Snapshot snap = AsyncTestListenerRegistry.snapshot();
        AsyncTestListenerRegistry.register(new NoopAsyncTestListener());
        int withTransient = AsyncTestListenerRegistry.getListenerCount();
        AsyncTestListenerRegistry.restoreSnapshot(snap);
        assertTrue(AsyncTestListenerRegistry.getListenerCount() < withTransient,
            "restoreSnapshot must remove listeners added since the snapshot was taken");
    }

    // ---- 6) Structured Violations + formatters ----

    @Test
    void violation_record_and_formatters_are_public() {
        Violation v = new Violation(
            "Smoke",
            IssueSeverity.HIGH,
            "demo message",
            List.of(),
            Map.of("threads", 4),
            Instant.now());
        assertEquals("Smoke", v.detector());
        assertEquals(IssueSeverity.HIGH, v.severity());

        String md = new MarkdownFormatter().format(List.of(v));
        assertTrue(md.contains("Smoke"));
        assertTrue(md.contains("HIGH"));

        String json = new JsonFormatter().format(List.of(v));
        assertTrue(json.contains("\"detector\":\"Smoke\""));
        assertTrue(json.contains("\"severity\":\"HIGH\""));
    }

    // ---- 7) SPI: DetectorRegistry.build + ServiceLoader discovery ----

    @Test
    void spi_registry_instantiates_every_detector_type_with_detectAll() {
        AsyncTestConfig cfg = AsyncTestConfig.builder().detectAll(true).build();
        DetectorRegistry reg = DetectorRegistry.build(cfg);
        assertEquals(DetectorType.values().length, reg.all().size(),
            "Public SPI must instantiate exactly one detector per DetectorType when detectAll=true");
    }

    @Test
    void spi_registry_lookup_by_type_works_for_a_phase13_detector() {
        AsyncTestConfig cfg = AsyncTestConfig.builder()
            .detectAll(true)
            .build();
        DetectorRegistry reg = DetectorRegistry.build(cfg);
        assertNotNull(reg.get(DetectorType.JDBC_CONNECTION_SHARED),
            "Phase 13 detector must be addressable through the public SPI");
    }

    // ---- 8) Phase 13 detectors: AsyncTestContext accessors compile + return ----

    // Default Preset.ALL + detectAll=true enables every detector. Per-flag
    // overrides under Preset.NONE don't work — the preset's effective excludes
    // pin every type off before per-flag values are read.
    @AsyncTest(threads = 2, invocations = 1, licenseMockMode = true)
    void phase13_accessors_are_reachable() {
        assertNotNull(AsyncTestContext.daemonThreadHygieneDetector());
        assertNotNull(AsyncTestContext.notifyWithoutMonitorDetector());
        assertNotNull(AsyncTestContext.sharedSecureRandomDetector());
        assertNotNull(AsyncTestContext.weakHashMapSharedDetector());
        assertNotNull(AsyncTestContext.jdbcConnectionSharedDetector());
    }

    @AsyncTest(threads = 2, invocations = 1, licenseMockMode = true)
    void phase14_accessors_are_reachable() {
        assertNotNull(AsyncTestContext.sharedStatefulCryptoDetector());
        assertNotNull(AsyncTestContext.nonAtomicConcurrentMapUpdateDetector());
        assertNotNull(AsyncTestContext.sharedDeflaterDetector());
        assertNotNull(AsyncTestContext.thisEscapeDetector());
        assertNotNull(AsyncTestContext.threadLocalRandomMisuseDetector());
    }

    @AsyncTest(threads = 2, invocations = 1, licenseMockMode = true)
    void phase15_accessors_are_reachable() {
        assertNotNull(AsyncTestContext.completableFutureObtrudeDetector());
        assertNotNull(AsyncTestContext.spuriousWakeupHazardDetector());
        assertNotNull(AsyncTestContext.lockUpgradeDeadlockDetector());
        assertNotNull(AsyncTestContext.tryLockMisuseDetector());
        assertNotNull(AsyncTestContext.cfBlockingCallbackDetector());
    }
}
