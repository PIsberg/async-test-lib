package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Learning late that a field is lock-free must retract what was already recorded.
 *
 * <p>The binding that proves a field is mutated by compare-and-swap sits in a static initializer,
 * which can run after the first accesses have been recorded. Filtering new accesses is therefore
 * not enough on its own: the early ones are already in, and they are what produces the finding.
 */
class LateAtomicKnowledgeTest {

    private static void replayRace(AtomicityValidator validator, String field) {
        for (long thread = 1; thread <= 3; thread++) {
            validator.recordFieldAccessUnderLocks(field, null, false, thread, 0L);
            validator.recordFieldAccessUnderLocks(field, null, true, thread, 0L);
        }
    }

    @Test
    @DisplayName("forgetting a field discards accesses recorded before the fact was known")
    void forgettingRetractsEarlierAccesses() {
        AtomicityValidator validator = new AtomicityValidator();
        replayRace(validator, "Waiter.next");
        assertFalse(validator.analyzeAtomicity().unsafeFieldAccesses.isEmpty(),
                "precondition: this access pattern is reported before anything is forgotten");

        validator.forgetField("Waiter.next");

        assertTrue(validator.analyzeAtomicity().unsafeFieldAccesses.isEmpty(),
                "the field turned out to be mutated by CAS, so the lockset has no basis for a "
                        + "verdict on it. Filtering later accesses alone would leave exactly the "
                        + "early ones that produced this finding.");
    }

    @Test
    @DisplayName("forgetting one field leaves every other field's history intact")
    void forgettingIsScopedToOneField() {
        AtomicityValidator validator = new AtomicityValidator();
        replayRace(validator, "Waiter.next");
        replayRace(validator, "Counter.count");

        validator.forgetField("Waiter.next");

        assertTrue(validator.analyzeAtomicity().unsafeFieldAccesses.stream()
                        .anyMatch(finding -> finding.contains("Counter.count")),
                "an unrelated racing field must still be reported: retraction is about the one "
                        + "field the library learned something about, not a reset");
    }
}
