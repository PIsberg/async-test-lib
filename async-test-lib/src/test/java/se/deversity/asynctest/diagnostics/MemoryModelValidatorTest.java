package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryModelValidatorTest {

    private MemoryModelValidator validator;

    @BeforeEach
    void setUp() {
        validator = new MemoryModelValidator();
    }

    @Test
    void validateRunsWithoutException() {
        assertDoesNotThrow(() -> validator.validate());
    }

    @Test
    void validationResultIsValidByDefault() {
        MemoryModelValidator.ValidationResult result = validator.validate();
        assertTrue(result.isValid(), "JMM should be correctly implemented on any conformant JVM");
    }

    @Test
    void validationResultHasObservations() {
        MemoryModelValidator.ValidationResult result = validator.validate();
        assertNotNull(result.observations);
        assertFalse(result.observations.isEmpty());
    }

    @Test
    void validationResultTestsRunGreaterThanZero() {
        MemoryModelValidator.ValidationResult result = validator.validate();
        assertTrue(result.testsRun > 0);
    }

    @Test
    void validationResultToStringNotNull() {
        MemoryModelValidator.ValidationResult result = validator.validate();
        assertNotNull(result.toString());
    }

    @Test
    void validationResultPassRateWithAllPassed() {
        MemoryModelValidator.ValidationResult result = validator.validate();
        // Override by constructing a known result state: all passed means 100%
        MemoryModelValidator.ValidationResult knownResult = new MemoryModelValidator.ValidationResult();
        knownResult.testsRun = 4;
        knownResult.testsPassed = 4;
        knownResult.observations.addAll(result.observations);
        assertEquals(100.0, knownResult.getPassRate(), 0.001);
        assertTrue(knownResult.isValid());
    }

    @Test
    void validationResultIsInvalidWithFailures() {
        MemoryModelValidator.ValidationResult result = validator.validate();
        MemoryModelValidator.ValidationResult failingResult = new MemoryModelValidator.ValidationResult();
        failingResult.testsRun = 4;
        failingResult.testsPassed = 2;
        failingResult.observations.addAll(result.observations);
        assertFalse(failingResult.isValid());
        assertEquals(50.0, failingResult.getPassRate(), 0.001);
    }
}
