package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AtomicityValidator.
 */
public class AtomicityValidatorTest {

    @Test
    void noRecordingsReturnNoIssues() {
        AtomicityValidator validator = new AtomicityValidator();

        AtomicityValidator.AtomicityReport report = validator.analyzeAtomicity();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "No recordings — should report no issues");
        assertTrue(report.checkThenActViolations.isEmpty());
        assertTrue(report.unsafeFieldAccesses.isEmpty());
        assertTrue(report.totcouRaces.isEmpty());
    }

    @Test
    void singleThreadNoViolations() {
        AtomicityValidator validator = new AtomicityValidator();

        validator.recordCompoundOperationStart("increment");
        validator.recordFieldAccess("counter", 0, false);  // read 0
        validator.recordFieldAccess("counter", 1, true);   // write 1
        validator.recordCompoundOperationEnd("increment");

        AtomicityValidator.AtomicityReport report = validator.analyzeAtomicity();

        // Single thread — no cross-thread unsafeFieldAccesses
        assertFalse(report.unsafeFieldAccesses.stream()
                .anyMatch(s -> s.contains("2 threads")),
                "Single-thread compound operation must not be flagged for cross-thread race");
    }

    @Test
    void checkThenActViolationDetected() {
        AtomicityValidator validator = new AtomicityValidator();

        // checkValue and expectedValue differ while wouldAct == true → violation
        boolean result = validator.detectCheckThenActViolation("flag", "A", "B", true);

        assertTrue(result, "Mismatched check/expected values should return true (violation detected)");

        AtomicityValidator.AtomicityReport report = validator.analyzeAtomicity();
        assertTrue(report.hasIssues(), "A recorded check-then-act violation must be present in the report");
        assertFalse(report.checkThenActViolations.isEmpty(),
                "checkThenActViolations must be non-empty after a detected violation");
    }

    @Test
    void compoundOperationStartEnd() {
        AtomicityValidator validator = new AtomicityValidator();

        // Start and end with no accesses in between — must not throw
        assertDoesNotThrow(() -> {
            validator.recordCompoundOperationStart("op");
            validator.recordCompoundOperationEnd("op");
        });

        AtomicityValidator.AtomicityReport report = validator.analyzeAtomicity();
        assertNotNull(report);
    }

    @Test
    void nullOperationNameHandled() {
        AtomicityValidator validator = new AtomicityValidator();

        // null or blank operation names must be silently ignored
        assertDoesNotThrow(() -> validator.recordCompoundOperationStart(null));
        assertDoesNotThrow(() -> validator.recordCompoundOperationEnd(null));
        assertDoesNotThrow(() -> validator.recordCompoundOperationStart(""));
        assertDoesNotThrow(() -> validator.recordCompoundOperationEnd(""));
    }

    @Test
    void disabledDetectorSkipsRecording() {
        AtomicityValidator validator = new AtomicityValidator();
        validator.disable();

        validator.recordCompoundOperationStart("op");
        validator.recordFieldAccess("x", 1, false);
        validator.recordFieldAccess("x", 2, true);
        boolean violated = validator.detectCheckThenActViolation("y", "a", "b", true);

        assertFalse(violated, "Disabled validator should not detect violations");

        AtomicityValidator.AtomicityReport report = validator.analyzeAtomicity();
        assertFalse(report.hasIssues(), "Disabled validator must record nothing");
    }

    @Test
    void reportToStringContainsViolationInfo() {
        AtomicityValidator validator = new AtomicityValidator();
        validator.detectCheckThenActViolation("balance", 100, 0, true);

        AtomicityValidator.AtomicityReport report = validator.analyzeAtomicity();
        String text = report.toString();

        assertNotNull(text);
        assertTrue(text.contains("ATOMICITY VIOLATIONS"), "toString() should contain violation header");
        assertTrue(text.contains("balance"), "toString() should name the violated field");
    }

    @Test
    void resetClearsState() {
        AtomicityValidator validator = new AtomicityValidator();
        validator.detectCheckThenActViolation("x", 1, 2, true);

        validator.reset();

        AtomicityValidator.AtomicityReport report = validator.analyzeAtomicity();
        assertFalse(report.hasIssues(), "After reset() all recorded violations must be cleared");
    }

    @Test
    void analyze_delegatesToAnalyzeAtomicity() {
        AtomicityValidator validator = new AtomicityValidator();
        validator.detectCheckThenActViolation("balance", 100, 0, true);

        AtomicityValidator.AtomicityReport viaAnalyze = validator.analyze();
        AtomicityValidator.AtomicityReport viaAnalyzeAtomicity = validator.analyzeAtomicity();

        assertEquals(viaAnalyzeAtomicity.hasIssues(), viaAnalyze.hasIssues());
        assertEquals(viaAnalyzeAtomicity.toString(), viaAnalyze.toString());
    }
}
