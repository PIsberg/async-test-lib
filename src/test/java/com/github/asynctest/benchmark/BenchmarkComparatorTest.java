package com.github.asynctest.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkComparatorTest {

    @TempDir
    Path tempDir;

    private Path storePath() {
        return tempDir.resolve("benchmarks.dat");
    }

    private static BenchmarkResult result(String method, long avgNanos) {
        return BenchmarkResult.builder()
                .testClass("com.example.MyTest")
                .testMethod(method)
                .timestamp(LocalDateTime.of(2026, 1, 1, 0, 0))
                .threads(4)
                .invocations(10)
                .totalExecutionTimeNanos(avgNanos * 10)
                .avgTimePerInvocationNanos(avgNanos)
                .minTimePerInvocationNanos(avgNanos)
                .maxTimePerInvocationNanos(avgNanos)
                .invocationTimesNanos(List.of(avgNanos))
                .build();
    }

    // ---- first run (no baseline) ----

    @Test
    void compare_noBaseline_returnsFirstRun() {
        BenchmarkComparator comparator = new BenchmarkComparator(storePath(), 20.0, false);
        BenchmarkResult current = result("myMethod", 100L);

        BenchmarkComparisonResult r = comparator.compare(current);

        assertTrue(r.isFirstRun());
        assertSame(current, r.getCurrentResult());
        assertNull(r.getBaselineResult());
    }

    // ---- stable result ----

    @Test
    void compare_withinThreshold_returnsStable() {
        BenchmarkComparator comparator = new BenchmarkComparator(storePath(), 20.0, false);
        BenchmarkResult baseline = result("myMethod", 100L);
        comparator.saveBaseline(baseline);

        // 5% increase — within 20% threshold
        BenchmarkResult current = result("myMethod", 105L);
        BenchmarkComparisonResult r = comparator.compare(current);

        assertFalse(r.isFirstRun());
        assertFalse(r.isRegression());
        assertFalse(r.isImprovement());
        assertTrue(r.isWithinThreshold());
        assertEquals(5.0, r.getPercentChange(), 0.5);
    }

    // ---- regression (failOnRegression=false) ----

    @Test
    void compare_regression_failOnRegressionFalse_returnsRegressionResult() {
        BenchmarkComparator comparator = new BenchmarkComparator(storePath(), 20.0, false);
        comparator.saveBaseline(result("myMethod", 100L));

        // 100% increase — well above 20% threshold
        BenchmarkResult current = result("myMethod", 200L);
        BenchmarkComparisonResult r = comparator.compare(current);

        assertTrue(r.isRegression());
        assertFalse(r.isImprovement());
        assertEquals(100.0, r.getPercentChange(), 0.1);
    }

    // ---- regression (failOnRegression=true) ----

    @Test
    void compare_regression_failOnRegressionTrue_throwsException() {
        BenchmarkComparator comparator = new BenchmarkComparator(storePath(), 20.0, true);
        comparator.saveBaseline(result("myMethod", 100L));

        BenchmarkResult current = result("myMethod", 200L);

        BenchmarkRegressionException ex = assertThrows(
                BenchmarkRegressionException.class,
                () -> comparator.compare(current)
        );
        assertNotNull(ex.getComparisonResult());
        assertTrue(ex.getComparisonResult().isRegression());
    }

    // ---- improvement ----

    @Test
    void compare_improvement_flaggedCorrectly() {
        BenchmarkComparator comparator = new BenchmarkComparator(storePath(), 20.0, false);
        comparator.saveBaseline(result("myMethod", 200L));

        // 50% decrease — below -20% threshold
        BenchmarkResult current = result("myMethod", 100L);
        BenchmarkComparisonResult r = comparator.compare(current);

        assertFalse(r.isRegression());
        assertTrue(r.isImprovement());
        assertEquals(-50.0, r.getPercentChange(), 0.1);
    }

    // ---- zero baseline average ----

    @Test
    void compare_zeroBaselineAvg_currentPositive_treatedAs100PercentChange() {
        BenchmarkComparator comparator = new BenchmarkComparator(storePath(), 20.0, false);
        comparator.saveBaseline(result("myMethod", 0L));

        BenchmarkResult current = result("myMethod", 100L);
        BenchmarkComparisonResult r = comparator.compare(current);

        assertEquals(100.0, r.getPercentChange(), 0.001);
        assertTrue(r.isRegression());
    }

    @Test
    void compare_zeroBaselineAvg_currentZero_noChange() {
        BenchmarkComparator comparator = new BenchmarkComparator(storePath(), 20.0, false);
        comparator.saveBaseline(result("myMethod", 0L));

        BenchmarkResult current = result("myMethod", 0L);
        BenchmarkComparisonResult r = comparator.compare(current);

        assertEquals(0.0, r.getPercentChange(), 0.001);
        assertFalse(r.isRegression());
        assertFalse(r.isImprovement());
    }

    // ---- save / load roundtrip ----

    @Test
    void saveBaseline_thenLoadBaseline_returnsStoredResult() {
        BenchmarkComparator comparator = new BenchmarkComparator(storePath(), 20.0, false);
        BenchmarkResult baseline = result("myMethod", 123L);
        comparator.saveBaseline(baseline);

        java.util.Optional<BenchmarkResult> loaded = comparator.loadBaseline("com.example.MyTest#myMethod");

        assertTrue(loaded.isPresent());
        assertEquals(123L, loaded.get().getAvgTimePerInvocationNanos());
    }

    @Test
    void loadBaseline_noFile_returnsEmpty() {
        BenchmarkComparator comparator = new BenchmarkComparator(storePath(), 20.0, false);
        assertTrue(comparator.loadBaseline("nonexistent#key").isEmpty());
    }

    @Test
    void loadBaseline_unknownKey_returnsEmpty() {
        BenchmarkComparator comparator = new BenchmarkComparator(storePath(), 20.0, false);
        comparator.saveBaseline(result("methodA", 100L));

        assertTrue(comparator.loadBaseline("com.example.MyTest#methodB").isEmpty());
    }

    @Test
    void saveBaseline_multipleKeys_eachStoredIndependently() {
        BenchmarkComparator comparator = new BenchmarkComparator(storePath(), 20.0, false);
        comparator.saveBaseline(result("alpha", 100L));
        comparator.saveBaseline(result("beta",  200L));

        assertEquals(100L, comparator.loadBaseline("com.example.MyTest#alpha").orElseThrow().getAvgTimePerInvocationNanos());
        assertEquals(200L, comparator.loadBaseline("com.example.MyTest#beta").orElseThrow().getAvgTimePerInvocationNanos());
    }

    // ---- clearAllBaselines ----

    @Test
    void clearAllBaselines_removesStoredFile() {
        BenchmarkComparator comparator = new BenchmarkComparator(storePath(), 20.0, false);
        comparator.saveBaseline(result("myMethod", 100L));

        comparator.clearAllBaselines();

        assertTrue(comparator.loadBaseline("com.example.MyTest#myMethod").isEmpty());
    }

    @Test
    void clearAllBaselines_noFileExists_doesNotThrow() {
        BenchmarkComparator comparator = new BenchmarkComparator(storePath(), 20.0, false);
        assertDoesNotThrow(comparator::clearAllBaselines);
    }

    // ---- corrupted file ----

    @Test
    void loadBaseline_corruptedFile_returnsEmpty() throws Exception {
        Path store = storePath();
        java.nio.file.Files.write(store, new byte[]{0x00, 0x01, 0x02}); // garbage bytes

        BenchmarkComparator comparator = new BenchmarkComparator(store, 20.0, false);
        assertTrue(comparator.loadBaseline("any#key").isEmpty());
    }
}
