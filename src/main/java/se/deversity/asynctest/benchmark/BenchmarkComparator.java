package se.deversity.asynctest.benchmark;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.deversity.vibetags.annotations.AIStrictClasspath;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputFilter;
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

    private static final Logger log = LoggerFactory.getLogger(BenchmarkComparator.class);

    /**
     * Allow-list of every class that legitimately appears in a serialized baseline store
     * ({@code HashMap<String, BenchmarkResult>}), with {@code !*} rejecting all others.
     * Without this filter, {@code readObject()} would instantiate any {@code Serializable}
     * class named in the stream, so a store file written by another party could drive a
     * gadget chain (CWE-502) before the result is ever cast to a Map.
     *
     * <p>Three entries are non-obvious. {@code java.time.Ser} is the serialization proxy
     * {@link java.time.LocalDateTime} writes itself as. {@code java.util.Map$Entry} and
     * {@code java.lang.Object} are the array component types that {@code HashMap.readObject}
     * and {@code ArrayList.readObject} pass to {@code checkArray}; omit either and a valid
     * store fails to load. Patterns match exact class names, so allowing {@code Object}
     * does not admit its subclasses.
     */
    private static final ObjectInputFilter BASELINE_FILTER = ObjectInputFilter.Config.createFilter(
        "java.util.HashMap;"
            + "java.util.ArrayList;"
            + "java.util.Map$Entry;"
            + "java.lang.Object;"
            + "java.lang.String;"
            + "java.lang.Number;"
            + "java.lang.Long;"
            + "java.time.Ser;"
            + "java.time.LocalDateTime;"
            + "java.time.LocalDate;"
            + "java.time.LocalTime;"
            + "se.deversity.asynctest.benchmark.BenchmarkResult;"
            + "!*"
    );

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
            percentChange = (double) (currentAvg - baselineAvg) / baselineAvg * 100.0;
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

        log.warn("\n{}\n⚠️  BENCHMARK REGRESSION DETECTED ⚠️\n{}\n{}\n{}",
            "=".repeat(80), "=".repeat(80), message, "=".repeat(80));

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
    public Optional<BenchmarkResult> loadBaseline(String benchmarkKey) {
        File storeFile = benchmarkStorePath.toFile();
        if (!storeFile.exists()) {
            return Optional.empty();
        }

        try {
            return Optional.ofNullable(readStore(storeFile).get(benchmarkKey));
        } catch (IOException | ClassNotFoundException e) {
            // If we can't read the baseline, treat as if it doesn't exist
            log.warn("Could not load benchmark baseline: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Deserialize the baseline store under {@link #BASELINE_FILTER} and verify the
     * resulting graph really is a {@code Map<String, BenchmarkResult>}. The filter blocks
     * disallowed classes at stream-resolution time; the checks below reject a
     * well-typed-but-wrong graph (e.g. a bare BenchmarkResult at the top level) that would
     * otherwise surface as an unchecked ClassCastException.
     *
     * @throws InvalidObjectException if the store does not hold a baseline map
     */
    @AIStrictClasspath(reason = "Java native deserialization sink. The BASELINE_FILTER allow-list "
            + "(ending in !*) must resolve every class in the stream and reject all others, preventing "
            + "arbitrary class loading (CWE-502 RCE). Never widen the filter or remove setObjectInputFilter.")
    private Map<String, BenchmarkResult> readStore(File storeFile) throws IOException, ClassNotFoundException {
        try (FileInputStream fis = new FileInputStream(storeFile);
             ObjectInputStream ois = new ObjectInputStream(fis)) {

            ois.setObjectInputFilter(BASELINE_FILTER);

            if (!(ois.readObject() instanceof Map<?, ?> raw)) {
                throw new InvalidObjectException("Benchmark baseline store does not contain a baseline map");
            }

            Map<String, BenchmarkResult> store = new HashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof BenchmarkResult value)) {
                    throw new InvalidObjectException("Benchmark baseline store contains an unexpected entry type");
                }
                store.put(key, value);
            }
            return store;
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
    private Map<String, BenchmarkResult> loadAllBaselines() {
        File storeFile = benchmarkStorePath.toFile();
        if (!storeFile.exists()) {
            return new HashMap<>();
        }

        try {
            return readStore(storeFile);
        } catch (IOException | ClassNotFoundException e) {
            log.warn("Could not load benchmark baselines, starting fresh: {}", e.getMessage(), e);
            return new HashMap<>();
        }
    }

    /**
     * Save all baselines to storage.
     */
    private void saveAllBaselines(Map<String, BenchmarkResult> store) {
        // Ensure parent directory exists
        Path parent = benchmarkStorePath.getParent();
        if (parent != null) {
            File parentDir = parent.toFile();
            if (!parentDir.exists() && !parentDir.mkdirs()) {
                log.warn("Could not create benchmark baseline directory: {}", parentDir);
                return;
            }
        }

        try (FileOutputStream fos = new FileOutputStream(benchmarkStorePath.toFile());
             ObjectOutputStream oos = new ObjectOutputStream(fos)) {
            oos.writeObject(store);
        } catch (IOException e) {
            log.warn("Could not save benchmark baselines: {}", e.getMessage(), e);
        }
    }

    /**
     * Clear all stored baselines.
     */
    public void clearAllBaselines() {
        File storeFile = benchmarkStorePath.toFile();
        if (storeFile.exists() && !storeFile.delete()) {
            log.warn("Could not delete benchmark baseline file: {}", storeFile);
        }
    }
}
