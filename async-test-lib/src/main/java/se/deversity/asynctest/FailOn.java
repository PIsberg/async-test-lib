package se.deversity.asynctest;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import se.deversity.asynctest.diagnostics.IssueSeverity;

/**
 * Severity threshold at or above which detector findings fail an {@code @AsyncTest}.
 *
 * <p>Used by {@link AsyncTest#failOn()}. After a test run completes, every enabled
 * detector is analyzed; findings whose {@link IssueSeverity} meets this threshold
 * cause the test to fail with an {@link AssertionError}. Findings below the
 * threshold are still printed and fired to registered
 * {@link AsyncTestListener}s, but do not fail the test.
 *
 * <p>{@link #NONE} (the default) preserves the legacy report-only behavior:
 * findings never fail the test by themselves.
 *
 * @since 1.7.0
 */
@API(status = Status.STABLE)
public enum FailOn {

    /**
     * Never fail on detector findings (report-only mode, legacy default).
     *
     * <p>Also what makes findings assertable rather than fatal: at any other threshold the run
     * fails before a test can inspect them. See {@link AsyncFindings}.
     */
    NONE,

    /** Fail on any finding ({@code LOW} and above). */
    LOW,

    /** Fail on {@code MEDIUM}, {@code HIGH}, and {@code CRITICAL} findings. */
    MEDIUM,

    /** Fail on {@code HIGH} and {@code CRITICAL} findings. */
    HIGH,

    /** Fail only on {@code CRITICAL} findings. */
    CRITICAL;

    /**
     * Returns {@code true} when a finding of the given severity meets this threshold.
     *
     * @param severity the severity of a detector finding; {@code null} returns {@code false}
     * @return whether the finding should fail the test
     */
    // IssueSeverity declaration order IS the intended severity rank (CRITICAL(0)..LOW(3)).
    @SuppressWarnings("EnumOrdinal")
    public boolean triggeredBy(IssueSeverity severity) {
        if (this == NONE || severity == null) {
            return false;
        }
        // IssueSeverity declares CRITICAL(0) .. LOW(3); lower ordinal = more severe.
        int worstAccepted = switch (this) {
            case LOW      -> 3;
            case MEDIUM   -> 2;
            case HIGH     -> 1;
            case CRITICAL -> 0;
            case NONE     -> -1; // unreachable
        };
        return severity.ordinal() <= worstAccepted;
    }
}
