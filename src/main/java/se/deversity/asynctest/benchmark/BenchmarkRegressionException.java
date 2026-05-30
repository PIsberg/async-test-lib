package se.deversity.asynctest.benchmark;

/**
 * Exception thrown when a benchmark regression is detected.
 * Contains the comparison result for detailed analysis.
 */
public class BenchmarkRegressionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    // Not serialized: BenchmarkComparisonResult does not implement Serializable.
    // The message string (from super) carries the human-readable detail.
    private final transient BenchmarkComparisonResult comparisonResult;

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
