package se.deversity.asynctest.benchmark;

import org.jspecify.annotations.Nullable;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.vibetags.annotations.AIFeatureFlag;
import se.deversity.vibetags.annotations.AIMemoryBudget;
import se.deversity.vibetags.annotations.AIObservability;
import se.deversity.vibetags.annotations.AIPerformance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Records benchmark execution times and manages comparison with baselines.
 * Integrated into the AsyncTest execution flow.
 */
@AIPerformance(constraint = "recordInvocationStart() and recordInvocationEnd() are called on the hot path inside every invocation round. Keep them allocation-free and avoid acquiring locks in the common case.")
@AIObservability(
    metrics = {"benchmark.invocation.times"},
    logs = {"[BENCHMARK] Baseline created", "[BENCHMARK] Baseline updated", "[BENCHMARK] STABLE", "[BENCHMARK] REGRESSION", "[BENCHMARK] IMPROVEMENT"},
    note = "Hot path telemetry used by JUnit benchmark metrics and baseline regression checks."
)
@AIFeatureFlag(flag = "async-test.benchmarking.enabled", defaultValue = false)
public class BenchmarkRecorder {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkRecorder.class);
    private static final String DEFAULT_BENCHMARK_STORE = "target/benchmark-data/baseline-store.dat";

    private final AsyncTestConfig config;
    private final String testClass;
    private final String testMethod;
    private final int actualThreads;
    private final List<Long> invocationTimesNanos;
    private final long startTimeNanos;
    private @Nullable BenchmarkComparator comparator;
    private boolean benchmarkingEnabled;

    /**
     * @see #BenchmarkRecorder(AsyncTestConfig, String, String, int) — prefer that
     * overload when the actual thread count may differ from {@code config.threads}
     * (e.g. {@code virtualThreadStressMode} overrides it). This overload records
     * {@code config.threads} as-is and exists for direct/unit-test construction.
     */
    public BenchmarkRecorder(AsyncTestConfig config, String testClass, String testMethod) {
        this(config, testClass, testMethod, config.threads);
    }

    /**
     * @param actualThreads the thread count actually used for this run, which may
     *                      differ from {@code config.threads} when
     *                      {@code virtualThreadStressMode} overrides it; recorded on
     *                      the baseline so comparisons are labeled correctly.
     * @since 1.9.0
     */
    public BenchmarkRecorder(AsyncTestConfig config, String testClass, String testMethod, int actualThreads) {
        this.config = config;
        this.testClass = testClass;
        this.testMethod = testMethod;
        this.actualThreads = actualThreads;
        this.invocationTimesNanos = new ArrayList<>();
        this.startTimeNanos = System.nanoTime();
        this.benchmarkingEnabled = config.enableBenchmarking;

        if (benchmarkingEnabled) {
            // Support system property override for benchmark store location
            String storePath = System.getProperty("benchmark.store.path", DEFAULT_BENCHMARK_STORE);
            Path benchmarkStorePath = Paths.get(storePath);

            // Convert percentage from decimal (e.g., 0.2 -> 20.0)
            double thresholdPercent = config.benchmarkRegressionThreshold * 100.0;

            this.comparator = new BenchmarkComparator(
                benchmarkStorePath,
                thresholdPercent,
                config.failOnBenchmarkRegression
            );
        }
    }

    /**
     * Check if benchmarking is enabled.
     */
    public boolean isBenchmarkingEnabled() {
        return benchmarkingEnabled;
    }

    /**
     * Record the start of an invocation round.
     * @return start time in nanoseconds
     */
    @AIMemoryBudget(AIMemoryBudget.AllocationPolicy.NO_AUTOBOXING)
    public long recordInvocationStart() {
        if (!benchmarkingEnabled) {
            return 0;
        }
        return System.nanoTime();
    }

    /**
     * Record the end of an invocation round.
     * @param startTimeNanos the start time returned by recordInvocationStart()
     */
    @AIMemoryBudget(AIMemoryBudget.AllocationPolicy.NO_AUTOBOXING)
    public void recordInvocationEnd(long startTimeNanos) {
        if (!benchmarkingEnabled) {
            return;
        }
        long elapsedNanos = System.nanoTime() - startTimeNanos;
        synchronized (invocationTimesNanos) {
            invocationTimesNanos.add(elapsedNanos);
        }
    }

    /**
     * Complete benchmarking and compare with baseline.
     * This should be called after all invocations are complete.
     *
     * @return the comparison result, or null if benchmarking is not enabled
     */
    public @Nullable BenchmarkComparisonResult complete() {
        if (!benchmarkingEnabled || comparator == null) {
            return null;
        }

        long totalEndTimeNanos = System.nanoTime();
        long totalExecutionTimeNanos = totalEndTimeNanos - startTimeNanos;

        List<Long> timesCopy;
        synchronized (invocationTimesNanos) {
            timesCopy = new ArrayList<>(invocationTimesNanos);
        }

        if (timesCopy.isEmpty()) {
            return null;
        }

        // Calculate statistics. avgTime is derived from the sum of the recorded
        // per-round samples (same source as min/max) rather than
        // totalExecutionTimeNanos / count — the latter is wall-clock time for the whole
        // run, which also includes barrier waits, lifecycle methods, and other overhead
        // between rounds, so it isn't comparable to the true per-round min/max.
        long minTime = timesCopy.stream().mapToLong(Long::longValue).min().orElse(0);
        long maxTime = timesCopy.stream().mapToLong(Long::longValue).max().orElse(0);
        long sumTime = timesCopy.stream().mapToLong(Long::longValue).sum();
        long avgTime = sumTime / timesCopy.size();

        BenchmarkResult currentResult = BenchmarkResult.builder()
            .testClass(testClass)
            .testMethod(testMethod)
            .timestamp(LocalDateTime.now(ZoneId.systemDefault()))
            .threads(actualThreads)
            .invocations(config.invocations)
            .totalExecutionTimeNanos(totalExecutionTimeNanos)
            .avgTimePerInvocationNanos(avgTime)
            .minTimePerInvocationNanos(minTime)
            .maxTimePerInvocationNanos(maxTime)
            .invocationTimesNanos(timesCopy)
            .build();

        // Compare with baseline
        BenchmarkComparisonResult comparison = comparator.compare(currentResult);

        // Check if we should update the baseline
        boolean updateBaseline = Boolean.getBoolean("benchmark.update");
        if (updateBaseline || comparison.isFirstRun()) {
            comparator.saveBaseline(currentResult);
            if (comparison.isFirstRun()) {
                log.info("[BENCHMARK] Baseline created for {}#{}: avg={}",
                    testClass, testMethod, BenchmarkResult.formatTime(avgTime));
            } else {
                log.info("[BENCHMARK] Baseline updated for {}#{}: avg={}",
                    testClass, testMethod, BenchmarkResult.formatTime(avgTime));
            }
        } else {
            // Print comparison result
            printComparisonResult(comparison);
        }

        return comparison;
    }

    /**
     * Print the benchmark comparison result.
     */
    private void printComparisonResult(BenchmarkComparisonResult result) {
        if (result.isFirstRun()) {
            return; // Already handled above
        }

        String status;
        if (result.isRegression()) {
            status = "⚠️  REGRESSION";
        } else if (result.isImprovement()) {
            status = "✓ IMPROVEMENT";
        } else {
            status = "✓ STABLE";
        }

        String changeStr = String.format(Locale.ROOT, "%+.2f%%", result.getPercentChange());
        log.info("[BENCHMARK] {} for {}#{} (change: {})", status, testClass, testMethod, changeStr);
    }

    /**
     * Get the total execution time in nanoseconds.
     */
    public long getTotalExecutionTimeNanos() {
        return System.nanoTime() - startTimeNanos;
    }

    /**
     * Get the number of recorded invocations.
     */
    public int getInvocationCount() {
        synchronized (invocationTimesNanos) {
            return invocationTimesNanos.size();
        }
    }
}
