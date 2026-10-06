package se.deversity.asynctest;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import se.deversity.asynctest.diagnostics.TrustTier;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AIPublicAPI;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an asynchronous stress test.
 * The test will be executed concurrently across multiple threads
 * using a CyclicBarrier to maximize the chance of race conditions.
 * 
 * Supports detection of:
 * - Deadlocks (with thread dump analysis)
 * - Visibility issues (missing volatile keywords)
 * - Livelocks and thread starvation
 * - Virtual thread pinning issues (Java 21+)
 *
 * <h2>Placement</h2>
 * <ul>
 *   <li><b>Method</b> — the standard usage; the method becomes an async stress test.</li>
 *   <li><b>Class</b> — provides shared configuration for every method in the class
 *       annotated with {@link org.junit.jupiter.api.TestTemplate}. A method-level
 *       {@code @AsyncTest} always takes precedence over the class-level one.</li>
 *   <li><b>Annotation</b> — compose your own reusable annotation, e.g.
 *       <pre>{@code
 *       @Retention(RetentionPolicy.RUNTIME)
 *       @Target(ElementType.METHOD)
 *       @AsyncTest(preset = Preset.ESSENTIALS, threads = 8)
 *       public @interface EssentialsAsyncTest {}
 *       }</pre>
 *       Composed annotations carry a fixed configuration; their attributes cannot be
 *       overridden at the use site.</li>
 * </ul>
 *
 * <h2>Selecting detectors</h2>
 * Detectors are named by {@link DetectorType}: {@link #includes()} selects exactly the listed ones,
 * {@link #preset()} a curated bundle, {@link #detectAll()} every one, and {@link #excludes()}
 * removes from any of these. A bare {@code @AsyncTest} runs {@link Preset#ESSENTIALS}; every
 * detector is the explicit {@code detectAll = true} (#923). The 146 per-detector boolean
 * attributes of 1.12 ({@code detectRaceConditions = true} and the rest) were removed in 1.13.0 (#920).
 */
@AIContract(reason = "Public annotation API used directly in user test methods. Attribute names, types, and defaults are part of the stable public API — any change is a breaking change for all consumers. Detector selection is by DetectorType through includes/excludes/preset/detectAll; never reintroduce a per-detector boolean attribute (removed in 1.13.0, #920). The bare-annotation selection is Preset.ESSENTIALS with detectAll = false (1.13.0, #923): a different default changes what every unchanged test in every consumer detects.")
@AIPublicAPI
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@TestTemplate
@ExtendWith(se.deversity.asynctest.extension.AsyncTestExtension.class)
@API(status = Status.STABLE)
public @interface AsyncTest {

    /**
     * Number of threads to run concurrently per invocation.
     * Each thread will execute the test method once per invocation round.
     *
     * @return the number of threads that run the test body concurrently in each invocation round
     */
    int threads() default 10;

    /**
     * Optional schedule matrix: when non-empty, the test runs once per entry,
     * each run using that entry as the thread count. The {@link #threads()}
     * value is ignored for runs that use this matrix.
     *
     * <p>Bug-finding sensitivity is often thread-count-dependent — a race that
     * misses at 4 threads can surface reliably at 32, and vice versa. Use this
     * to sweep a range cheaply.
     *
     * <p>Example:
     * <pre>{@code
     * @AsyncTest(threadCounts = {1, 2, 4, 8, 16, 32, 64})
     * void racy_under_contention() { ... }
     * }</pre>
     *
     * <p>Default empty array means "use {@link #threads()}" (legacy behavior).
     *
     * @since 1.6.0
     *
     * @return the thread counts to run the test at, one matrix entry each; empty to use {@link #threads()}
     */
    int[] threadCounts() default {};

    /**
     * Number of times the entire concurrent execution is repeated.
     *
     * @return the number of invocation rounds, each releasing every thread from the barrier at once
     */
    int invocations() default 100;

    /**
     * Whether to use Virtual Threads (Project Loom) instead of standard platform threads.
     * Requires Java 21+.
     *
     * @return {@code true} to run the test body on virtual threads instead of a fixed platform-thread pool
     */
    boolean useVirtualThreads() default true;

    /**
     * Maximum time to wait for the entire test (all threads and invocations) to complete.
     * If exceeded, a deadlock is assumed and a JVM Thread dump will be triggered.
     * Default is 5000ms.
     *
     * @return the per-test budget in milliseconds, before {@code async-test.timeout.multiplier} scaling
     */
    long timeoutMs() default 5000;

    /**
     * Virtual thread stress test mode. When enabled, uses aggressive thread counts
     * to detect thread-pinning issues (e.g., synchronized blocks pinning virtual threads).
     * Only applicable when useVirtualThreads=true.
     * 
     * Values:
     * - "OFF" (default): normal testing
     * - "LOW": 100 threads
     * - "MEDIUM": 1,000 threads
     * - "HIGH": 10,000 threads
     * - "EXTREME": 100,000+ threads (may require heap size adjustment)
     *
     * @return the stress level name, or {@code "OFF"} to leave the configured thread count alone
     */
    String virtualThreadStressMode() default "OFF";

    /**
     * Enable every detector, whatever {@link #preset()} says. {@link #includes()} takes precedence
     * over it, and {@link #excludes()} removes detectors from the selection.
     * <p><strong>Default is {@code false}</strong> since 1.13.0 (#923): {@code @AsyncTest} alone runs
     * {@link Preset#ESSENTIALS}, and {@code detectAll = true} is the explicit opt-in to every
     * detector. {@code false} leaves {@link #preset()} in charge.
     * <p>Example: {@code @AsyncTest(detectAll = true)} — every detector.
     * <p>Example: {@code @AsyncTest(includes = DetectorType.DEADLOCKS)} — only deadlock detection.
     *
     * @return {@code true} to enable every detector, subject to {@link #includes()} and {@link #excludes()}
     */
    boolean detectAll() default false;

    /**
     * Curated detector bundle, used when neither {@link #includes()} nor {@code detectAll = true}
     * is set.
     *
     * <ul>
     *   <li>{@link Preset#ESSENTIALS} — 12 high-signal detectors for everyday CI (default since 1.13.0, #923).</li>
     *   <li>{@link Preset#ALL} — every detector, the same selection as {@code detectAll = true}.</li>
     *   <li>{@link Preset#STRICT} — same as ALL, named explicitly.</li>
     *   <li>{@link Preset#CI_FAST} — minimal set for pull-request gates.</li>
     *   <li>{@link Preset#NONE} — disable all detectors; concurrent execution only.</li>
     * </ul>
     *
     * <p>{@link #excludes()} still applies on top of the preset, letting you trim
     * one or two detectors from a curated bundle.
     *
     * @since 1.6.0
     *
     * @return the curated detector bundle to enable
     */
    Preset preset() default Preset.ESSENTIALS;

    /**
     * Replay seed for deterministic re-runs.
     *
     * <p>The runner exposes a {@code long} seed per invocation via
     * {@link AsyncTestContext#replaySeed()}. When {@link #replaySeed()} is
     * {@code 0} (default), each invocation gets a fresh random seed and the
     * value is logged on test failure so you can plug it back in. When set
     * explicitly, every invocation uses that exact seed.
     *
     * <p>This does <em>not</em> make thread scheduling deterministic — that
     * would require JVM-level instrumentation — but it gives any RNG-driven
     * input in your test body (sleep jitter, randomised payloads, choice of
     * worker behaviour) a stable starting point so a failure caught once can
     * be reproduced.
     *
     * <p>Usage pattern:
     * <pre>{@code
     * @AsyncTest
     * void flaky_race() {
     *     long seed = AsyncTestContext.replaySeed();
     *     var rng = new Random(seed);
     *     // ... use rng for any randomised choices in the body
     * }
     * }</pre>
     *
     * @since 1.6.0
     *
     * @return the fixed seed to reproduce a previous run, or {@code 0} to draw a fresh seed per round
     */
    long replaySeed() default 0L;

    /**
     * Specific detectors to exclude from whatever {@link #includes()}, {@link #preset()} or
     * {@link #detectAll()} selected. Use {@link DetectorType} to specify which detectors to skip.
     * <p>Example: {@code @AsyncTest(detectAll = true, excludes = {DetectorType.BUSY_WAITING})}
     *
     * @return the detectors to switch off, which win over every other selection
     */
    DetectorType[] excludes() default {};

    /**
     * Detectors to switch off by id: a third-party detector's own
     * {@link se.deversity.asynctest.spi.Detector#id() id}, or a built-in's
     * {@link DetectorType} name, which then excludes that type as {@link #excludes()} would.
     *
     * <p>Example: {@code @AsyncTest(excludeIds = {"com.acme.pool-misuse"})}
     *
     * @since 1.13.0
     *
     * @return the detector ids to switch off; an id no detector declares is ignored
     */
    String[] excludeIds() default {};

    /**
     * Enable exactly the listed detectors and nothing else.
     *
     * <p>When non-empty, this attribute takes precedence over {@link #preset()} and
     * {@link #detectAll()}: only the listed {@link DetectorType}s are active.
     * {@link #excludes()} still applies on top and wins on conflict.
     *
     * <p>Example: {@code @AsyncTest(includes = {DetectorType.DEADLOCKS, DetectorType.RACE_CONDITIONS})}
     * — only deadlock and race-condition detection.
     *
     * <p>Default empty array means "no opinion" — {@link #preset()} /
     * {@link #detectAll()} semantics apply unchanged.
     *
     * @since 1.7.0
     *
     * @return the only detectors to enable; a non-empty value overrides {@link #preset()} and {@link #detectAll()}
     */
    DetectorType[] includes() default {};

    /**
     * Severity threshold at or above which detector findings fail this test.
     *
     * <p>After the N×M run completes, every enabled detector is analyzed.
     * Findings at or above this threshold throw an {@link AssertionError};
     * findings below it are printed and fired to registered
     * {@link AsyncTestListener}s but do not fail the test.
     *
     * <p>The default {@link FailOn#NONE} preserves the legacy report-only
     * behavior. Set {@code failOn = FailOn.HIGH} in CI to gate merges on
     * serious findings while still surfacing lower-severity ones.
     *
     * <p>Known findings can be suppressed via a baseline file:
     * {@code -Dasync-test.baseline=<path>} to apply,
     * {@code -Dasync-test.baseline.update=true} to record current findings
     * instead of failing. Each baseline line is
     * {@code com.example.MyTest#myMethod | DetectorName}.
     *
     * @since 1.7.0
     *
     * @return the lowest finding severity that should fail the test
     */
    FailOn failOn() default FailOn.NONE;

    /**
     * Lowest {@link TrustTier} a finding's detector must carry before {@link #failOn()} may act on
     * it.
     *
     * <p>{@code failOn} asks how bad a finding would be if it were real. This asks whether it is
     * real. The two are independent, and a merge gate needs both: of the 142 detectors, three are
     * backed today by a measured case that fires on the bug and stays silent on its correctly
     * synchronized twin, while most of the rest report a pattern they cannot fully model and mean
     * "go and look" rather than "this is broken".
     *
     * <p>The default {@link TrustTier#ADVISORY} is the weakest tier, so it filters nothing and the
     * gate behaves exactly as it did before this attribute existed. Set
     * {@code minTrust = TrustTier.VERDICT} on a merge gate to fail only on findings the library
     * can stand behind without a human reading the report first. Findings below the floor are
     * still printed and still fired to every {@link AsyncTestListener}; they just do not fail the
     * build.
     *
     * <p>Which detector carries which tier, and the evidence behind it, is in
     * {@code DetectorTrust} and {@code docs/analysis/detector-accuracy-eval.md}.
     *
     * @since 1.9.7
     *
     * @return the lowest trust tier whose findings may fail the test
     */
    TrustTier minTrust() default TrustTier.ADVISORY;

    // ============= Phase 2: Advanced Detectors =============

    // ============= Phase 2: Additional Monitors =============

    // ============= Phase 2: Additional Concurrency Detectors =============

    // ============= Phase 2: Advanced Concurrency Utilities =============

    // ============= Phase 4: Infrastructure & Resource Management =============

    // ============= Phase 5: Thread-Safety of Common Types =============

    // ============= Phase 6: Virtual Thread Concurrency (Java 21+) =============

    // ============= Phase 7: High-Level Concurrency Patterns =============

    // ============= Benchmarking =============

    /**
     * Enable benchmarking for this test method.
     * When true, execution times are recorded and compared against baselines.
     *
     * @return {@code true} to record per-invocation timings and compare them against the stored baseline
     */
    boolean enableBenchmarking() default false;

    /**
     * Regression threshold percentage.
     * If execution time increases by more than this percentage compared to baseline,
     * a regression is detected.
     * Default is 20% (0.2 = 20%).
     *
     * @return the fraction by which a run may slow against its baseline before counting as a regression
     */
    double benchmarkRegressionThreshold() default 0.2;

    /**
     * Fail the test on benchmark regression.
     * If true, a regression exceeding the threshold will cause test failure.
     * If false, only a warning is logged.
     *
     * @return {@code true} to fail the test when a benchmark regression exceeds the threshold
     */
    boolean failOnBenchmarkRegression() default false;

    // ============= Phase 8: Lifecycle & Structural Correctness =============

    // Phase 9 (Repository & Environment State) held detectUncommittedChanges until its
    // removal after 1.7.2 — a git-status environment check, not a concurrency property.

    // ============= Phase 10: API Traps & Subtle Concurrency Bugs =============

    // ============= Phase 11: Thread-Safety of Additional Types & Patterns =============

    // ============= Phase 12: Operational & Hygiene Concurrency Issues =============

    // ============= Phase 13: Additional concurrency-bug categories (1.0.0+) =============

    // ============= License Gating (Integration) =============

    /**
     * Keygen Account ID. Defaults to System property 'keygen.account.id' if empty.
     *
     * @return the Keygen account id, or empty to take it from the environment
     */
    String keygenAccountId() default "";

    /**
     * Keygen API Key. Defaults to System property 'keygen.api.key' if empty.
     *
     * @return the Keygen API key, or empty to take it from the environment
     */
    String keygenApiKey() default "";

    /**
     * Keygen Product Id. Defaults to System property 'keygen.product.id' if empty.
     *
     * @return the Keygen product id, or empty to take it from the environment
     */
    String keygenProductId() default "";

    /**
     * LemonSqueezy store subdomain (e.g. 'acme' for acme.lemonsqueezy.com).
     *
     * @return the Lemon Squeezy store id, or empty to take it from the environment
     */
    String lemonSqueezyStore() default "";

    /**
     * License key for Keygen validation. Defaults to System property 'license.key' if empty.
     *
     * @return the license key, or empty to take it from the environment
     */
    String licenseKey() default "";

    /**
     * When true, use mock mode for LicenseGate (no network calls). Defaults to System property 'license.mock.mode' if empty.
     *
     * @return {@code true} to bypass the license check for this test
     */
    boolean licenseMockMode() default false;
}

