package se.deversity.asynctest.benchmark;

import org.jspecify.annotations.Nullable;

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

    /**
     * {@return the comparison result}
     */
    public @Nullable BenchmarkComparisonResult getComparisonResult() {
        return comparisonResult;
    }

    @Override
    public @Nullable String getMessage() { // NOPMD UselessOverridingMethod — see comment below
        // Returns the stored message unchanged. This override exists only so the custom
        // toString() below (which keeps the simple-class-name prefix) does not trip Error
        // Prone's OverrideThrowableToString, which flags a toString() override on a Throwable
        // unless getMessage() is also overridden. PMD's UselessOverridingMethod is therefore
        // suppressed: the override is deliberate, not accidental boilerplate.
        return super.getMessage();
    }

    // Overrides toString() to use the simple class name prefix (not the FQN that
    // Throwable.toString() would emit); the human-readable detail is the super message.
    @Override
    public String toString() {
        return "BenchmarkRegressionException: " + getMessage();
    }
}
