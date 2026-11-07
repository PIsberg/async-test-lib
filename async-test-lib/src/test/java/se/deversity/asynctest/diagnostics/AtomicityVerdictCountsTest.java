package se.deversity.asynctest.diagnostics;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AtomicityValidator#verdictCounts(String)} says which rule decided each multi-thread write
 * group of a field, so a silent hand-off row can name the excuse that cleared it (#1133).
 *
 * <p>{@link AtomicityOwnershipTakesTest} covers the feed's half: the takes reached the validator.
 * Three CI occurrences then showed them arriving and every round still clearing, which leaves the
 * analysis, and nothing kept from those runs says which of its rules did it.
 */
class AtomicityVerdictCountsTest {

    private static final String FIELD = "Box.count";
    private static final long LOCK = 0x5EEDL;

    @Test
    @DisplayName("an unguarded two-thread write group is counted as reported")
    void unguardedGroupIsReported() {
        AtomicityValidator validator = new AtomicityValidator();
        validator.recordFieldAccessUnderLocks(FIELD, null, false, 101L, 0L);
        validator.recordFieldAccessUnderLocks(FIELD, null, true, 101L, 0L);
        validator.recordFieldAccessUnderLocks(FIELD, null, false, 202L, 0L);
        validator.recordFieldAccessUnderLocks(FIELD, null, true, 202L, 0L);

        assertTrue(validator.analyzeAtomicity().hasIssues(), "premise: the group reports");
        assertEquals(Map.of("reported", 1), validator.verdictCounts(FIELD));
    }

    @Test
    @DisplayName("a group every access of which held one lock is counted as cleared by the lockset")
    void lockedGroupIsClearedByTheLockset() {
        AtomicityValidator validator = new AtomicityValidator();
        validator.recordFieldAccessUnderLocks(FIELD, null, true, 101L, LOCK);
        validator.recordFieldAccessUnderLocks(FIELD, null, true, 202L, LOCK);

        assertTrue(!validator.analyzeAtomicity().hasIssues(), "premise: the group is silent");
        assertEquals(Map.of("lockset", 1), validator.verdictCounts(FIELD));
    }

    @Test
    @DisplayName("a volatile field with one writer thread is counted under its own rule")
    void volatileSingleWriterIsNamed() {
        AtomicityValidator validator = new AtomicityValidator();
        validator.recordFieldAccessUnderLocks(FIELD, null, true, 101L, 0L, true);
        validator.recordFieldAccessUnderLocks(FIELD, null, false, 202L, 0L, true);

        assertTrue(!validator.analyzeAtomicity().hasIssues(), "premise: the group is silent");
        assertEquals(Map.of("volatileSingleWriter", 1), validator.verdictCounts(FIELD));
    }

    @Test
    @DisplayName("groups are counted per round, a one-thread round under its own key")
    void countsPerRound() {
        AtomicityValidator validator = new AtomicityValidator();
        validator.markInvocationStart();
        validator.recordFieldAccessUnderLocks(FIELD, null, true, 101L, 0L);
        validator.recordFieldAccessUnderLocks(FIELD, null, true, 202L, 0L);
        validator.markInvocationStart();
        validator.recordFieldAccessUnderLocks(FIELD, null, true, 101L, 0L);
        validator.recordFieldAccessUnderLocks(FIELD, null, true, 202L, 0L);
        validator.markInvocationStart();
        validator.recordFieldAccessUnderLocks(FIELD, null, true, 101L, 0L);

        validator.analyzeAtomicity();
        assertEquals(Map.of("reported", 2, "oneThread", 1), validator.verdictCounts(FIELD));
    }

    @Test
    @DisplayName("a field is matched by its suffix, and nothing is counted before an analysis or after a reset")
    void suffixMatchAndReset() {
        AtomicityValidator validator = new AtomicityValidator();
        validator.recordFieldAccessUnderLocks("com.example.Outer$Box.count", null, true, 101L, 0L);
        validator.recordFieldAccessUnderLocks("com.example.Outer$Box.count", null, true, 202L, 0L);
        assertEquals(Map.of(), validator.verdictCounts(FIELD), "no analysis has run yet");

        validator.analyzeAtomicity();
        assertEquals(Map.of("reported", 1), validator.verdictCounts(FIELD));

        validator.reset();
        assertEquals(Map.of(), validator.verdictCounts(FIELD), "reset drops the counts");
    }
}
