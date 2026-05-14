package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConstructorSafetyValidatorTest {

    @Test
    void noRecordingsReturnNoIssues() {
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        ConstructorSafetyValidator.ConstructorSafetyReport report = validator.validateConstructorSafety();
        assertFalse(report.hasIssues());
    }

    @Test
    void completeConstructionNoIssues() {
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        Object obj = new Object();
        long now = System.nanoTime();
        validator.recordConstructionStart(obj);
        validator.recordFieldAccess(obj, "field1", now + 100);
        validator.recordConstructionEnd(obj);
        ConstructorSafetyValidator.ConstructorSafetyReport report = validator.validateConstructorSafety();
        assertFalse(report.hasIssues());
    }

    @Test
    void fieldAccessBeforeEndDetected() {
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        Object obj = new Object();
        long now = System.nanoTime();
        validator.recordConstructionStart(obj);
        validator.recordFieldAccess(obj, "sharedField", now + 50);
        ConstructorSafetyValidator.ConstructorSafetyReport report = validator.validateConstructorSafety();
        // Object never had recordConstructionEnd called — construction is incomplete
        assertTrue(report.hasIssues() ||
                !report.fieldsAccessedDuringConstruction.isEmpty() ||
                !report.possiblyIncompleteConstructions.isEmpty());
    }

    @Test
    void nullObjectHandled() {
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        assertDoesNotThrow(() -> validator.recordConstructionStart(null));
    }

    @Test
    void reportHasIssuesFalseByDefault() {
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        ConstructorSafetyValidator.ConstructorSafetyReport report = validator.validateConstructorSafety();
        assertFalse(report.hasIssues());
        assertTrue(report.unsafeObjects.isEmpty());
        assertTrue(report.possiblyIncompleteConstructions.isEmpty());
        assertTrue(report.fieldsAccessedDuringConstruction.isEmpty());
    }

    @Test
    void reportToStringNoIssues() {
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        ConstructorSafetyValidator.ConstructorSafetyReport report = validator.validateConstructorSafety();
        String text = report.toString();
        assertNotNull(text);
        assertFalse(text.isBlank());
    }

    @Test
    void resetClearsState() {
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        Object obj = new Object();
        validator.recordConstructionStart(obj);
        validator.reset();
        ConstructorSafetyValidator.ConstructorSafetyReport report = validator.validateConstructorSafety();
        assertFalse(report.hasIssues());
    }
}
