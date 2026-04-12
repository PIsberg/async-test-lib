package com.github.asynctest.benchmark;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkRegressionExceptionTest {

    private static BenchmarkComparisonResult comparisonResult() {
        BenchmarkResult current = BenchmarkResult.builder()
                .testClass("com.example.MyTest")
                .testMethod("myMethod")
                .timestamp(LocalDateTime.of(2026, 1, 1, 0, 0))
                .avgTimePerInvocationNanos(200L)
                .invocationTimesNanos(List.of(200L))
                .build();
        BenchmarkResult baseline = BenchmarkResult.builder()
                .testClass("com.example.MyTest")
                .testMethod("myMethod")
                .timestamp(LocalDateTime.of(2026, 1, 1, 0, 0))
                .avgTimePerInvocationNanos(100L)
                .invocationTimesNanos(List.of(100L))
                .build();
        return BenchmarkComparisonResult.builder()
                .currentResult(current)
                .baselineResult(baseline)
                .percentChange(100.0)
                .isRegression(true)
                .isImprovement(false)
                .isFirstRun(false)
                .thresholdPercent(20.0)
                .build();
    }

    @Test
    void constructor_storesMessageAndComparisonResult() {
        BenchmarkComparisonResult cr = comparisonResult();
        BenchmarkRegressionException ex = new BenchmarkRegressionException("regression detected", cr);

        assertEquals("regression detected", ex.getMessage());
        assertSame(cr, ex.getComparisonResult());
    }

    @Test
    void isRuntimeException() {
        BenchmarkRegressionException ex = new BenchmarkRegressionException("msg", comparisonResult());
        assertInstanceOf(RuntimeException.class, ex);
    }

    @Test
    void toString_containsMessage() {
        BenchmarkRegressionException ex = new BenchmarkRegressionException("performance dropped 100%", comparisonResult());
        assertTrue(ex.toString().contains("performance dropped 100%"));
    }

    @Test
    void toString_startsWithClassName() {
        BenchmarkRegressionException ex = new BenchmarkRegressionException("msg", comparisonResult());
        assertTrue(ex.toString().startsWith("BenchmarkRegressionException:"));
    }
}
