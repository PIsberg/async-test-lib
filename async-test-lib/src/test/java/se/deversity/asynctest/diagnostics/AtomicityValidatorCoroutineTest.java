package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A lost update across a suspension (#1007): a coroutine reads a field, suspends, and writes it
 * after another coroutine wrote it in between, all of it possibly on one thread, where no
 * thread-based rule can see two owners.
 *
 * <p>The accesses are recorded as the agent's coroutine-step hook records them: the field's key,
 * its name, the coroutine and the step it runs in.
 */
class AtomicityValidatorCoroutineTest {

    private static final String FIELD = "com.example.Box.n";

    @Test
    @DisplayName("a read, a suspension, another coroutine's write, then a write is a lost update")
    void aWriteAfterAForeignWriteAcrossASuspensionIsReported() {
        AtomicityValidator validator = new AtomicityValidator();
        Object field = new Object();
        Object first = new Object();
        Object second = new Object();

        validator.recordCoroutineAccess(field, FIELD, first, 1, false);
        validator.recordCoroutineAccess(field, FIELD, second, 2, false);
        validator.recordCoroutineAccess(field, FIELD, second, 3, true);
        validator.recordCoroutineAccess(field, FIELD, first, 4, true);

        var report = validator.analyzeAtomicity();
        assertEquals(1, report.checkThenActViolations.size(), report.toString());
        assertTrue(report.checkThenActViolations.iterator().next().startsWith(FIELD + ": "),
                report.toString());
    }

    @Test
    @DisplayName("a coroutine that reads, suspends and writes with nobody writing between is silent")
    void anOwnReadSuspendWriteIsSilent() {
        AtomicityValidator validator = new AtomicityValidator();
        Object field = new Object();
        Object only = new Object();

        validator.recordCoroutineAccess(field, FIELD, only, 1, false);
        validator.recordCoroutineAccess(field, FIELD, only, 2, true);
        validator.recordCoroutineAccess(field, FIELD, only, 3, false);
        validator.recordCoroutineAccess(field, FIELD, only, 4, true);

        assertTrue(validator.analyzeAtomicity().checkThenActViolations.isEmpty());
    }

    @Test
    @DisplayName("another coroutine reading in between, or writing before the read, is no lost update")
    void foreignReadsAndEarlierWritesAreSilent() {
        AtomicityValidator validator = new AtomicityValidator();
        Object field = new Object();
        Object first = new Object();
        Object second = new Object();

        validator.recordCoroutineAccess(field, FIELD, second, 1, true);
        validator.recordCoroutineAccess(field, FIELD, first, 2, false);
        validator.recordCoroutineAccess(field, FIELD, second, 3, false);
        validator.recordCoroutineAccess(field, FIELD, first, 4, true);

        assertTrue(validator.analyzeAtomicity().checkThenActViolations.isEmpty());
    }

    @Test
    @DisplayName("a read and a write in one step are left to the thread-based rules")
    void oneStepIsNotASuspension() {
        AtomicityValidator validator = new AtomicityValidator();
        Object field = new Object();
        Object first = new Object();
        Object second = new Object();

        validator.recordCoroutineAccess(field, FIELD, first, 1, false);
        validator.recordCoroutineAccess(field, FIELD, second, 2, true);
        validator.recordCoroutineAccess(field, FIELD, first, 1, true);

        assertTrue(validator.analyzeAtomicity().checkThenActViolations.isEmpty());
    }

    @Test
    @DisplayName("the same field of another object is other state")
    void anotherObjectsFieldIsOtherState() {
        AtomicityValidator validator = new AtomicityValidator();
        Object first = new Object();
        Object second = new Object();
        Object mine = new Object();
        Object theirs = new Object();

        validator.recordCoroutineAccess(mine, FIELD, first, 1, false);
        validator.recordCoroutineAccess(theirs, FIELD, second, 2, true);
        validator.recordCoroutineAccess(mine, FIELD, first, 3, true);

        assertTrue(validator.analyzeAtomicity().checkThenActViolations.isEmpty());
    }
}
