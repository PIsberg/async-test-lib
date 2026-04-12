package com.github.asynctest.benchmark;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkComparisonResultTest {

    private static final LocalDateTime TS = LocalDateTime.of(2026, 1, 1, 0, 0);

    private static BenchmarkResult result(String method, long avgNanos) {
        return BenchmarkResult.builder()
                .testClass("com.example.MyTest")
                .testMethod(method)
                .timestamp(TS)
                .threads(4)
                .invocations(10)
                .avgTimePerInvocationNanos(avgNanos)
                .invocationTimesNanos(List.of(avgNanos))
                .build();
    }

    // ---- builder / getters ----

    @Test
    void builder_populatesAllFields() {
        BenchmarkResult current  = result("m", 200L);
        BenchmarkResult baseline = result("m", 100L);

        BenchmarkComparisonResult r = BenchmarkComparisonResult.builder()
                .currentResult(current)
                .baselineResult(baseline)
                .percentChange(100.0)
                .isRegression(true)
                .isImprovement(false)
                .isFirstRun(false)
                .thresholdPercent(20.0)
                .build();

        assertSame(current,  r.getCurrentResult());
        assertSame(baseline, r.getBaselineResult());
        assertEquals(100.0, r.getPercentChange(), 0.001);
        assertTrue(r.isRegression());
        assertFalse(r.isImprovement());
        assertFalse(r.isFirstRun());
        assertEquals(20.0, r.getThresholdPercent(), 0.001);
    }

    // ---- firstRun factory ----

    @Test
    void firstRun_setsCorrectFlags() {
        BenchmarkResult current = result("m", 150L);
        BenchmarkComparisonResult r = BenchmarkComparisonResult.firstRun(current);

        assertTrue(r.isFirstRun());
        assertFalse(r.isRegression());
        assertFalse(r.isImprovement());
        assertEquals(0.0, r.getPercentChange(), 0.001);
        assertEquals(0.0, r.getThresholdPercent(), 0.001);
        assertSame(current, r.getCurrentResult());
        assertNull(r.getBaselineResult());
    }

    // ---- isWithinThreshold ----

    @Test
    void isWithinThreshold_stable_returnsTrue() {
        BenchmarkComparisonResult r = BenchmarkComparisonResult.builder()
                .currentResult(result("m", 100L))
                .isRegression(false)
                .isImprovement(false)
                .build();

        assertTrue(r.isWithinThreshold());
    }

    @Test
    void isWithinThreshold_regression_returnsFalse() {
        BenchmarkComparisonResult r = BenchmarkComparisonResult.builder()
                .currentResult(result("m", 200L))
                .isRegression(true)
                .isImprovement(false)
                .build();

        assertFalse(r.isWithinThreshold());
    }

    @Test
    void isWithinThreshold_improvement_returnsFalse() {
        BenchmarkComparisonResult r = BenchmarkComparisonResult.builder()
                .currentResult(result("m", 50L))
                .isRegression(false)
                .isImprovement(true)
                .build();

        assertFalse(r.isWithinThreshold());
    }

    // ---- toString ----

    @Test
    void toString_firstRun_containsFirstRunLabel() {
        BenchmarkComparisonResult r = BenchmarkComparisonResult.firstRun(result("myMethod", 1_000_000L));
        String s = r.toString();
        assertTrue(s.contains("FIRST_RUN"), "should contain FIRST_RUN");
        assertTrue(s.contains("myMethod"),  "should contain method name");
    }

    @Test
    void toString_regression_containsRegressionLabel() {
        BenchmarkComparisonResult r = BenchmarkComparisonResult.builder()
                .currentResult(result("m", 200L))
                .baselineResult(result("m", 100L))
                .percentChange(100.0)
                .isRegression(true)
                .isImprovement(false)
                .isFirstRun(false)
                .build();

        assertTrue(r.toString().contains("REGRESSION"));
    }

    @Test
    void toString_improvement_containsImprovementLabel() {
        BenchmarkComparisonResult r = BenchmarkComparisonResult.builder()
                .currentResult(result("m", 50L))
                .baselineResult(result("m", 100L))
                .percentChange(-50.0)
                .isRegression(false)
                .isImprovement(true)
                .isFirstRun(false)
                .build();

        assertTrue(r.toString().contains("IMPROVEMENT"));
    }

    @Test
    void toString_stable_containsStableLabel() {
        BenchmarkComparisonResult r = BenchmarkComparisonResult.builder()
                .currentResult(result("m", 105L))
                .baselineResult(result("m", 100L))
                .percentChange(5.0)
                .isRegression(false)
                .isImprovement(false)
                .isFirstRun(false)
                .build();

        assertTrue(r.toString().contains("STABLE"));
    }

    // ---- equals and hashCode ----

    @Test
    void equals_identicalValues_returnsTrue() {
        BenchmarkResult cur  = result("m", 200L);
        BenchmarkResult base = result("m", 100L);

        BenchmarkComparisonResult a = BenchmarkComparisonResult.builder()
                .currentResult(cur).baselineResult(base)
                .percentChange(100.0).isRegression(true).isImprovement(false).isFirstRun(false)
                .build();
        BenchmarkComparisonResult b = BenchmarkComparisonResult.builder()
                .currentResult(cur).baselineResult(base)
                .percentChange(100.0).isRegression(true).isImprovement(false).isFirstRun(false)
                .build();

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_sameInstance_returnsTrue() {
        BenchmarkComparisonResult r = BenchmarkComparisonResult.firstRun(result("m", 100L));
        assertEquals(r, r);
    }

    @Test
    void equals_null_returnsFalse() {
        BenchmarkComparisonResult r = BenchmarkComparisonResult.firstRun(result("m", 100L));
        assertNotEquals(null, r);
    }

    @Test
    void equals_differentType_returnsFalse() {
        BenchmarkComparisonResult r = BenchmarkComparisonResult.firstRun(result("m", 100L));
        assertNotEquals("other", r);
    }

    @Test
    void equals_differentPercentChange_returnsFalse() {
        BenchmarkResult cur = result("m", 200L);
        BenchmarkComparisonResult a = BenchmarkComparisonResult.builder()
                .currentResult(cur).percentChange(10.0).isRegression(false).isImprovement(false).build();
        BenchmarkComparisonResult b = BenchmarkComparisonResult.builder()
                .currentResult(cur).percentChange(50.0).isRegression(false).isImprovement(false).build();

        assertNotEquals(a, b);
    }
}
