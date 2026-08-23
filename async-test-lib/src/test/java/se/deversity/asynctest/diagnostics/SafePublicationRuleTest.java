package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule that separates double-checked locking from a check-then-act bug.
 *
 * <p>Both shapes look identical to a lockset: a field read with no lock held and written with one.
 * What decides between them is whether the field is {@code volatile}, because that is what makes
 * the unguarded read see a fully constructed value. The two tests below are the same event stream
 * with that one bit flipped, which is the only honest way to show the rule is doing the work.
 */
class SafePublicationRuleTest {

    private static final long GUARD = 4242L;

    /** Reads with no lock, writes under one: the shape of every correct DCL implementation. */
    private static void replayDoubleCheckedLocking(AtomicityValidator validator, boolean isVolatile) {
        validator.recordFieldAccessUnderLocks("Holder.instance", null, false, 1L, 0L, isVolatile);
        validator.recordFieldAccessUnderLocks("Holder.instance", null, false, 2L, 0L, isVolatile);
        validator.recordFieldAccessUnderLocks("Holder.instance", null, true, 1L, GUARD, isVolatile);
        validator.recordFieldAccessUnderLocks("Holder.instance", null, true, 2L, GUARD, isVolatile);
    }

    @Test
    @DisplayName("a volatile field whose writes shared a lock is safe publication, not a finding")
    void volatileFieldWithGuardedWritesIsNotReported() {
        AtomicityValidator validator = new AtomicityValidator();
        replayDoubleCheckedLocking(validator, true);

        assertTrue(validator.analyzeAtomicity().unsafeFieldAccesses.isEmpty(),
                "reads outside the lock and writes under it, on a volatile field, is "
                        + "double-checked locking: correct since Java 5. Reporting it makes every "
                        + "correct lazy initialiser a finding.");
    }

    @Test
    @DisplayName("the same stream on a non-volatile field is still reported")
    void nonVolatileFieldWithGuardedWritesIsStillReported() {
        AtomicityValidator validator = new AtomicityValidator();
        replayDoubleCheckedLocking(validator, false);

        assertFalse(validator.analyzeAtomicity().unsafeFieldAccesses.isEmpty(),
                "without volatile the unguarded read can see a half-published object, which is the "
                        + "classic broken DCL. If this goes silent the rule is suppressing on the "
                        + "lock alone and has stopped distinguishing the two shapes.");
    }

    @Test
    @DisplayName("a volatile field written with no lock held is still reported")
    void volatileFieldWithUnguardedWritesIsStillReported() {
        AtomicityValidator validator = new AtomicityValidator();
        validator.recordFieldAccessUnderLocks("Counter.count", null, false, 1L, 0L, true);
        validator.recordFieldAccessUnderLocks("Counter.count", null, true, 1L, 0L, true);
        validator.recordFieldAccessUnderLocks("Counter.count", null, false, 2L, 0L, true);
        validator.recordFieldAccessUnderLocks("Counter.count", null, true, 2L, 0L, true);

        assertFalse(validator.analyzeAtomicity().unsafeFieldAccesses.isEmpty(),
                "volatile makes each access atomic, not the compound. A volatile count++ is the "
                        + "textbook race and must stay reportable: if this goes silent, volatile "
                        + "has become a blanket excuse.");
    }
}
