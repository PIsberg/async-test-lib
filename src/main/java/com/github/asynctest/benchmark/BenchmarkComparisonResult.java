package com.github.asynctest.benchmark;

import java.util.Objects;

/**
 * Result of comparing current benchmark results against a baseline.
 */
public class BenchmarkComparisonResult {

    private final BenchmarkResult currentResult;
    private final BenchmarkResult baselineResult;
    private final double percentChange;
    private final boolean isRegression;
    private final boolean isImprovement;
    private final boolean isFirstRun;
    private final double thresholdPercent;

    private BenchmarkComparisonResult(Builder builder) {
        this.currentResult = builder.currentResult;
        this.baselineResult = builder.baselineResult;
        this.percentChange = builder.percentChange;
        this.isRegression = builder.isRegression;
        this.isImprovement = builder.isImprovement;
        this.isFirstRun = builder.isFirstRun;
        this.thresholdPercent = builder.thresholdPercent;
    }

    /**
     * Create a result for the first run (no baseline exists).
     */
    public static BenchmarkComparisonResult firstRun(BenchmarkResult currentResult) {
        return builder()
            .currentResult(currentResult)
            .isFirstRun(true)
            .percentChange(0.0)
            .isRegression(false)
            .isImprovement(false)
            .thresholdPercent(0.0)
            .build();
    }

    public BenchmarkResult getCurrentResult() {
        return currentResult;
    }

    public BenchmarkResult getBaselineResult() {
        return baselineResult;
    }

    public double getPercentChange() {
        return percentChange;
    }

    public boolean isRegression() {
        return isRegression;
    }

    public boolean isImprovement() {
        return isImprovement;
    }

    public boolean isFirstRun() {
        return isFirstRun;
    }

    public double getThresholdPercent() {
        return thresholdPercent;
    }

    /**
     * Check if the result is within acceptable bounds (not a regression or improvement).
     */
    public boolean isWithinThreshold() {
        return !isRegression && !isImprovement;
    }

    @Override
    public String toString() {
        if (isFirstRun) {
            return String.format(
                "BenchmarkComparisonResult{FIRST_RUN, %s#%s, avg=%s}",
                currentResult.getTestClass(),
                currentResult.getTestMethod(),
                BenchmarkResult.formatTime(currentResult.getAvgTimePerInvocationNanos())
            );
        }

        String changeStr = String.format("%+.2f%%", percentChange);
        String status = isRegression ? "REGRESSION" : (isImprovement ? "IMPROVEMENT" : "STABLE");

        return String.format(
            "BenchmarkComparisonResult{%s, baseline=%s, current=%s, change=%s}",
            status,
            BenchmarkResult.formatTime(baselineResult.getAvgTimePerInvocationNanos()),
            BenchmarkResult.formatTime(currentResult.getAvgTimePerInvocationNanos()),
            changeStr
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BenchmarkComparisonResult that = (BenchmarkComparisonResult) o;
        return Double.compare(that.percentChange, percentChange) == 0 &&
               isRegression == that.isRegression &&
               isImprovement == that.isImprovement &&
               isFirstRun == that.isFirstRun &&
               Objects.equals(currentResult, that.currentResult) &&
               Objects.equals(baselineResult, that.baselineResult);
    }

    @Override
    public int hashCode() {
        return Objects.hash(currentResult, baselineResult, percentChange, isRegression, isImprovement, isFirstRun);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private BenchmarkResult currentResult;
        private BenchmarkResult baselineResult;
        private double percentChange;
        private boolean isRegression;
        private boolean isImprovement;
        private boolean isFirstRun;
        private double thresholdPercent;

        public Builder currentResult(BenchmarkResult currentResult) {
            this.currentResult = currentResult;
            return this;
        }

        public Builder baselineResult(BenchmarkResult baselineResult) {
            this.baselineResult = baselineResult;
            return this;
        }

        public Builder percentChange(double percentChange) {
            this.percentChange = percentChange;
            return this;
        }

        public Builder isRegression(boolean isRegression) {
            this.isRegression = isRegression;
            return this;
        }

        public Builder isImprovement(boolean isImprovement) {
            this.isImprovement = isImprovement;
            return this;
        }

        public Builder isFirstRun(boolean isFirstRun) {
            this.isFirstRun = isFirstRun;
            return this;
        }

        public Builder thresholdPercent(double thresholdPercent) {
            this.thresholdPercent = thresholdPercent;
            return this;
        }

        public BenchmarkComparisonResult build() {
            return new BenchmarkComparisonResult(this);
        }
    }
}
