package se.deversity.asynctest.example;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncAssert;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.AsyncTestListenerRegistry;
import se.deversity.asynctest.NoopAsyncTestListener;
import se.deversity.asynctest.Preset;
import se.deversity.asynctest.example.service.PaymentService;
import se.deversity.asynctest.report.MarkdownFormatter;
import se.deversity.asynctest.report.Violation;
import se.deversity.asynctest.spi.DetectorRegistry;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A runnable tour of the public APIs added in async-test-lib 1.0.0. Each
 * method exercises one of: {@code Preset}, {@code threadCounts} matrix,
 * {@code replaySeed}, {@code AsyncAssert.awaitAsync}, scoped listener
 * registration, structured violations with {@code MarkdownFormatter}, and
 * (via a separate plain {@code @Test}) the SPI registry.
 *
 * <p>The {@link PaymentService} test fixture is intentionally racy so the
 * detectors light up. None of these tests assert correctness of the service —
 * they're demonstrations of the framework features.
 */
class FeatureTourTest {

    // ---- 1) Preset: curated detector bundle ----

    @AsyncTest(
        threads = 8,
        invocations = 5,
        preset = Preset.ESSENTIALS  // ← 12 high-signal detectors instead of all 100
    )
    void preset_essentials() {
        var svc = new PaymentService(/*seed*/ 1L);
        AsyncAssert.awaitAsync(svc.chargeAsync("a-1", 1L), Duration.ofSeconds(1));
    }

    // ---- 2) threadCounts: schedule matrix ----

    // 4 separate JUnit invocations, each running with the matching thread count.
    // The display name is "[AsyncTest] N threads x M invocations" per run.
    @AsyncTest(
        threadCounts = {2, 4, 8, 16},
        invocations = 3,
        preset = Preset.CI_FAST
    )
    void threadCounts_matrix_sweep() {
        var svc = new PaymentService(/*seed*/ 2L);
        AsyncAssert.awaitAsync(svc.chargeAsync("matrix", 1L), Duration.ofSeconds(1));
    }

    // ---- 3) replaySeed: reproducible RNG-driven runs ----

    // Default (replaySeed = 0): runner draws a fresh seed per round and prints
    // it on failure. Pin replaySeed = 42424242L to reproduce a flaky failure.
    @AsyncTest(
        threads = 4,
        invocations = 5,
        preset = Preset.CI_FAST,
        replaySeed = 42424242L
    )
    void replaySeed_pinned() {
        // The runner stamps this round's seed onto AsyncTestContext; the test
        // body seeds its own Random off that value so any jitter we introduce
        // is reproducible across runs.
        long seed = AsyncTestContext.replaySeed();
        Random rng = new Random(seed);

        var svc = new PaymentService(seed);
        // ... use rng for any randomised choices ...
        if (rng.nextBoolean()) {
            AsyncAssert.awaitAsync(svc.chargeAsync("seeded", 1L), Duration.ofSeconds(1));
        }
    }

    // ---- 4) AsyncAssert.awaitAsync: await a CompletionStage inside the body ----

    @AsyncTest(threads = 6, preset = Preset.CI_FAST)
    void awaitAsync_inside_body() {
        var svc = new PaymentService(/*seed*/ 3L);
        CompletableFuture<Long> stage = svc.chargeAsync("await-a", 10L);

        // JUnit Jupiter requires @TestTemplate methods to return void — so the
        // supported way to exercise async APIs inside @AsyncTest is to await
        // the chain explicitly. awaitAsync unwraps ExecutionException so the
        // real cause surfaces.
        Long result = AsyncAssert.awaitAsync(stage, Duration.ofSeconds(5));
        assertTrue(result >= 10L);
    }

    // ---- 5) Scoped listener: try-with-resources binding ----

    @Test
    void scoped_listener_binds_to_a_single_block() {
        var capture = new NoopAsyncTestListener();
        try (var ignored = AsyncTestListenerRegistry.registerScoped(capture)) {
            // capture is registered only inside this try block.
            assertTrue(AsyncTestListenerRegistry.getListenerCount() >= 1);
        }
        // Auto-unregistered on close — no JVM-wide leak into the next test.
    }

    // ---- 6) Structured Violations + MarkdownFormatter ----

    @Test
    void markdown_formatter_renders_violations() {
        // Constructs a Violation directly to show the rendering pipeline; in a
        // real test these come from detector.analyze().structuredViolations.
        Violation v = new Violation(
            "DemoDetector",
            se.deversity.asynctest.diagnostics.IssueSeverity.HIGH,
            "Demo finding for the feature tour.",
            List.of(),
            java.util.Map.of("threads", 4, "type", "demo"),
            java.time.Instant.now());

        String md = new MarkdownFormatter().format(List.of(v));
        assertTrue(md.contains("## AsyncTest Violations"));
        assertTrue(md.contains("### DemoDetector [HIGH]"));
        assertTrue(md.contains("threads: `4`"));
    }

    // ---- 7) SPI registry: programmatic discovery of every detector ----

    @Test
    void spi_registry_instantiates_all_detectors() {
        var cfg = se.deversity.asynctest.AsyncTestConfig.builder()
            .detectAll(true)
            .build();
        DetectorRegistry reg = DetectorRegistry.build(cfg);

        // Every DetectorType value is discoverable via the SPI as of 1.0.0.
        // For a real test, lookups would target specific detector classes:
        // reg.get(MyDetector.class) → typed instance.
        assertTrue(reg.all().size() == se.deversity.asynctest.DetectorType.values().length,
            "SPI registry should instantiate one detector per DetectorType");
    }

    // ---- 8) Bonus: a Phase 13 detector in action — SharedSecureRandom ----

    // Pattern: opt into ONE detector via detectAll=false + per-flag=true.
    // (Preset.NONE plus per-flag overrides does NOT work — the preset's
    // effective excludes force every per-flag back to false; use detectAll=false
    // when you want only the flags you list to be active.)
    @AsyncTest(
        threads = 4,
        invocations = 1,
        detectAll = false,                                  // disable everything else
        detectSharedSecureRandom = true,                    // … then turn this one on
        licenseMockMode = true
    )
    void phase13_shared_secure_random_is_detected() {
        // Get the framework-managed detector instance and record one access from
        // this worker. Across threads this will accumulate into a violation.
        var d = AsyncTestContext.sharedSecureRandomDetector();
        d.recordAccess(SHARED_SECURE_RNG, "shared-rng", Thread.currentThread());

        // Burn one nextInt() so the test "did something"
        SHARED_SECURE_RNG.nextInt();
    }
    private static final java.security.SecureRandom SHARED_SECURE_RNG = new java.security.SecureRandom();

    // Silence unused-import warning for the demo line that does use ThreadLocalRandom.
    @SuppressWarnings("unused")
    private void unusedDemoOfThreadLocalRandom() {
        long x = ThreadLocalRandom.current().nextLong();
    }
}
