package com.github.asynctest.benchmark;

import com.github.asynctest.AsyncTestConfig;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Records benchmark execution times and manages comparison with baselines.
 * Integrated into the AsyncTest execution flow.
 */
public class BenchmarkRecorder {

    private static final String DEFAULT_BENCHMARK_STORE = "target/benchmark-data/baseline-store.dat";

    private final AsyncTestConfig config;
    private final String testClass;
    private final String testMethod;
    private final List<Long> invocationTimesNanos;
    private final long startTimeNanos;
    private BenchmarkComparator comparator;
    private boolean benchmarkingEnabled;

    public BenchmarkRecorder(AsyncTestConfig config, String testClass, String testMethod) {
        this.config = config;
        this.testClass = testClass;
        this.testMethod = testMethod;
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
    public BenchmarkComparisonResult complete() {
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

        // Calculate statistics
        long minTime = timesCopy.stream().mapToLong(Long::longValue).min().orElse(0);
        long maxTime = timesCopy.stream().mapToLong(Long::longValue).max().orElse(0);
        long avgTime = totalExecutionTimeNanos / timesCopy.size();

        BenchmarkResult currentResult = BenchmarkResult.builder()
            .testClass(testClass)
            .testMethod(testMethod)
            .timestamp(LocalDateTime.now())
            .threads(config.threads)
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
                System.out.println("[BENCHMARK] Baseline created for " + testClass + "#" + testMethod +
                    ": avg=" + BenchmarkResult.formatTime(avgTime));
            } else {
                System.out.println("[BENCHMARK] Baseline updated for " + testClass + "#" + testMethod +
                    ": avg=" + BenchmarkResult.formatTime(avgTime));
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

        String changeStr = String.format("%+.2f%%", result.getPercentChange());
        System.out.println("[BENCHMARK] " + status + " for " + testClass + "#" + testMethod +
            " (change: " + changeStr + ")");
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
