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
    public String getMessage() {
        // Explicit override (returns the stored message unchanged) so the toString() below
        // can keep its simple-class-name prefix without tripping Error Prone's
        // OverrideThrowableToString, which only flags a toString() override when getMessage()
        // is not also overridden.
        return super.getMessage();
    }

    // Overrides toString() to use the simple class name prefix (not the FQN that
    // Throwable.toString() would emit); the human-readable detail is the super message.
    @Override
    public String toString() {
        return "BenchmarkRegressionException: " + getMessage();
    }
}
