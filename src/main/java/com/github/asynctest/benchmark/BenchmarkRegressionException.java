package com.github.asynctest.benchmark;

/**
 * Exception thrown when a benchmark regression is detected.
 * Contains the comparison result for detailed analysis.
 */
public class BenchmarkRegressionException extends RuntimeException {

    private final BenchmarkComparisonResult comparisonResult;

    public BenchmarkRegressionException(String message, BenchmarkComparisonResult comparisonResult) {
        super(message);
        this.comparisonResult = comparisonResult;
    }

    public BenchmarkComparisonResult getComparisonResult() {
        return comparisonResult;
    }

    @Override
    public String toString() {
        return "BenchmarkRegressionException: " + getMessage();
    }
}
