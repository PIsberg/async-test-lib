package com.github.asynctest.benchmark;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Compares current benchmark results against stored baselines.
 * Detects performance regressions and triggers alerts when thresholds are exceeded.
 */
public class BenchmarkComparator {

    private final Path benchmarkStorePath;
    private final double regressionThresholdPercent;
    private final boolean failOnRegression;

    /**
     * Create a benchmark comparator.
     *
     * @param benchmarkStorePath path to store benchmark data
     * @param regressionThresholdPercent percentage increase that triggers a regression alert (e.g., 20.0 for 20%)
     * @param failOnRegression if true, throw exception on regression; if false, just log warning
     */
    public BenchmarkComparator(Path benchmarkStorePath, double regressionThresholdPercent, boolean failOnRegression) {
        this.benchmarkStorePath = benchmarkStorePath;
        this.regressionThresholdPercent = regressionThresholdPercent;
        this.failOnRegression = failOnRegression;
    }

    /**
     * Compare current results with baseline and report any regressions.
     *
     * @param currentResult the current benchmark result
     * @return comparison result with details
     */
    public BenchmarkComparisonResult compare(BenchmarkResult currentResult) {
        Optional<BenchmarkResult> baselineOpt = loadBaseline(currentResult.getBenchmarkKey());

        if (baselineOpt.isEmpty()) {
            // No baseline exists - this is the first run
            return BenchmarkComparisonResult.firstRun(currentResult);
        }

        BenchmarkResult baseline = baselineOpt.get();
        return compareWithBaseline(currentResult, baseline);
    }

    /**
     * Compare current result with a specific baseline.
     */
    private BenchmarkComparisonResult compareWithBaseline(BenchmarkResult current, BenchmarkResult baseline) {
        long baselineAvg = baseline.getAvgTimePerInvocationNanos();
        long currentAvg = current.getAvgTimePerInvocationNanos();

        double percentChange;
        if (baselineAvg == 0) {
            percentChange = currentAvg > 0 ? 100.0 : 0.0;
        } else {
            percentChange = ((double) (currentAvg - baselineAvg) / baselineAvg) * 100.0;
        }

        boolean isRegression = percentChange > regressionThresholdPercent;
        boolean isImprovement = percentChange < -regressionThresholdPercent;

        BenchmarkComparisonResult result = BenchmarkComparisonResult.builder()
            .currentResult(current)
            .baselineResult(baseline)
            .percentChange(percentChange)
            .isRegression(isRegression)
            .isImprovement(isImprovement)
            .thresholdPercent(regressionThresholdPercent)
            .build();

        if (isRegression) {
            handleRegression(result);
        }

        return result;
    }

    /**
     * Handle a detected regression - either throw exception or log warning.
     */
    private void handleRegression(BenchmarkComparisonResult result) {
        String message = buildRegressionMessage(result);

        System.err.println("\n" + "=".repeat(80));
        System.err.println("⚠️  BENCHMARK REGRESSION DETECTED ⚠️");
        System.err.println("=".repeat(80));
        System.err.println(message);
        System.err.println("=".repeat(80) + "\n");

        if (failOnRegression) {
            throw new BenchmarkRegressionException(message, result);
        }
    }

    /**
     * Build a detailed regression message.
     */
    private String buildRegressionMessage(BenchmarkComparisonResult result) {
        BenchmarkResult current = result.getCurrentResult();
        BenchmarkResult baseline = result.getBaselineResult();

        StringBuilder sb = new StringBuilder();
        sb.append("Performance regression detected in ").append(current.getBenchmarkKey()).append("\n");
        sb.append("  Baseline: ").append(BenchmarkResult.formatTime(baseline.getAvgTimePerInvocationNanos()))
          .append(" (").append(baseline.getTimestamp()).append(")\n");
        sb.append("  Current:  ").append(BenchmarkResult.formatTime(current.getAvgTimePerInvocationNanos()))
          .append(" (").append(current.getTimestamp()).append(")\n");
        sb.append("  Change:   +").append(String.format("%.2f", result.getPercentChange()))
          .append("% (threshold: ").append(regressionThresholdPercent).append("%)\n");
        sb.append("  Difference: ").append(BenchmarkResult.formatTime(
            current.getAvgTimePerInvocationNanos() - baseline.getAvgTimePerInvocationNanos()
        )).append(" slower\n");
        sb.append("\nSuggested actions:\n");
        sb.append("  - Review recent code changes for performance impact\n");
        sb.append("  - Check for increased contention or resource constraints\n");
        sb.append("  - If this is expected, update the baseline by running with -Dbenchmark.update=true\n");

        return sb.toString();
    }

    /**
     * Load baseline for a specific benchmark key.
     */
    @SuppressWarnings("unchecked")
    public Optional<BenchmarkResult> loadBaseline(String benchmarkKey) {
        File storeFile = benchmarkStorePath.toFile();
        if (!storeFile.exists()) {
            return Optional.empty();
        }

        try (FileInputStream fis = new FileInputStream(storeFile);
             ObjectInputStream ois = new ObjectInputStream(fis)) {

            Map<String, BenchmarkResult> store = (Map<String, BenchmarkResult>) ois.readObject();
            return Optional.ofNullable(store.get(benchmarkKey));

        } catch (IOException | ClassNotFoundException e) {
            // If we can't read the baseline, treat as if it doesn't exist
            System.err.println("Warning: Could not load benchmark baseline: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Save a benchmark result as the new baseline.
     */
    public void saveBaseline(BenchmarkResult result) {
        Map<String, BenchmarkResult> store = loadAllBaselines();
        store.put(result.getBenchmarkKey(), result);
        saveAllBaselines(store);
    }

    /**
     * Load all baselines from storage.
     */
    @SuppressWarnings("unchecked")
    private Map<String, BenchmarkResult> loadAllBaselines() {
        File storeFile = benchmarkStorePath.toFile();
        if (!storeFile.exists()) {
            return new HashMap<>();
        }

        try (FileInputStream fis = new FileInputStream(storeFile);
             ObjectInputStream ois = new ObjectInputStream(fis)) {
            return (Map<String, BenchmarkResult>) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("Warning: Could not load benchmark baselines, starting fresh: " + e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Save all baselines to storage.
     */
    private void saveAllBaselines(Map<String, BenchmarkResult> store) {
        // Ensure parent directory exists
        File parentDir = benchmarkStorePath.getParent().toFile();
        if (!parentDir.exists()) {
            parentDir.mkdirs();
        }

        try (FileOutputStream fos = new FileOutputStream(benchmarkStorePath.toFile());
             ObjectOutputStream oos = new ObjectOutputStream(fos)) {
            oos.writeObject(store);
        } catch (IOException e) {
            System.err.println("Warning: Could not save benchmark baselines: " + e.getMessage());
        }
    }

    /**
     * Clear all stored baselines.
     */
    public void clearAllBaselines() {
        File storeFile = benchmarkStorePath.toFile();
        if (storeFile.exists()) {
            storeFile.delete();
        }
    }
}
