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

    /**
     * Each check must establish its own ordering instead of assuming a freshly started thread
     * runs within a fixed number of milliseconds. Two of them slept (50ms and 10ms) and then
     * read a value another thread was expected to have written by then, so on a loaded runner
     * they recorded a happens-before violation that was really a scheduling miss:
     * {@code AdvancedAsyncTestsTest.testMemoryModelValidation} failed once in a 1945-test
     * Gradle CI run while all six Maven cells passed the same test on the same commit.
     *
     * <p>Oversubscribing the CPU does not reproduce that reliably enough to gate on, so this
     * injects the condition directly: the writing threads start 250ms late, which is longer
     * than either sleep ever was. A conformant JVM has no memory-model excuse here, so a
     * failure is the validator's own timing assumption and nothing else.
     */
    @Test
    void validationHoldsWhenTheWritingThreadStartsLate() {
        MemoryModelValidator slowToSchedule = new MemoryModelValidator(() -> {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        MemoryModelValidator.ValidationResult result = slowToSchedule.validate();

        assertTrue(result.isValid(), () -> "a late writer is a scheduling delay, not a JMM "
                + "violation, so every check must still hold:\n" + result);
    }
}
