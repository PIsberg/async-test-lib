package com.example.corpus;

import org.jspecify.annotations.Nullable;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.diagnostics.DetectorDefaultSeverity;
import se.deversity.asynctest.diagnostics.IssueSeverity;

/**
 * One recording-lane entry: a third-party class, a detector, and what the body did to it.
 *
 * <p>The ground truth here is not the same as the other lanes'. There, the class's own javadoc
 * decides whether a finding is a true positive, because the body does nothing but share the
 * instance. Here the body cooperates, and a documented-thread-safe class used with a
 * check-then-act genuinely deserves a finding - the defect is in the usage, not the class. So
 * every row states what the body does and what must therefore happen, and carries the class's
 * contract only as context.
 *
 * <p>That is what makes the expectation assertable. A detector's verdict in this lane is a
 * function of the {@code record*} calls the body made, not of how the scheduler interleaved
 * them, so {@link Expectation#MUST_FIRE} and {@link Expectation#MUST_STAY_SILENT} are structural
 * claims rather than a bet on observing a race. The unmodified lanes cannot assert either, which
 * is why they gate only at the group level.
 *
 * @param testMethod       the {@code @AsyncTest} method in {@link CorpusRecordingLaneTest}
 * @param library          the artifact the class ships in, at the version this module resolves
 * @param className        the fully qualified class under test
 * @param detector         the detector the body records to, and the only one this row speaks for
 * @param contract         the class's own documented contract, for context
 * @param expectation      what must happen, given what the body records
 * @param rationale        why that outcome follows from the recorded calls
 * @param expectedSeverity the severity the finding is expected to carry, or {@code null} to default from model
 */
record RecordingSubject(
        String testMethod,
        String library,
        String className,
        DetectorType detector,
        Contract contract,
        Expectation expectation,
        String rationale,
        @Nullable IssueSeverity expectedSeverity) {

    RecordingSubject(
            String testMethod,
            String library,
            String className,
            DetectorType detector,
            Contract contract,
            Expectation expectation,
            String rationale) {
        this(testMethod, library, className, detector, contract, expectation, rationale, null);
    }

    /** {@return the expected severity if declared, or derived from detector default severity for MUST_FIRE} */
    public @Nullable IssueSeverity resolvedSeverity() {
        if (expectedSeverity != null) {
            return expectedSeverity;
        }
        if (expectation == Expectation.MUST_FIRE) {
            return DetectorDefaultSeverity.of(detector).orElse(IssueSeverity.HIGH);
        }
        return null;
    }

    /** What the recorded calls oblige the detector to do. */
    enum Expectation {

        /**
         * The body records a sequence that meets the detector's stated precondition, so a finding
         * is owed. A silent run means the detector stopped reading its own input.
         */
        MUST_FIRE,

        /**
         * The body records correct usage, so a finding would be noise. This is the direction that
         * catches a detector whose model is too coarse to tell the two apart.
         */
        MUST_STAY_SILENT
    }
}
