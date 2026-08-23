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

    @Test
    @DisplayName("a field every thread writes the same constant to is not a check-then-act")
    void oneConstantWrittenByEveryThreadIsNotReported() {
        AtomicityValidator validator = new AtomicityValidator();
        // FixedOrderComparator.compare writes isLocked = true on every call, having never read it.
        for (long thread = 1; thread <= 3; thread++) {
            validator.recordFieldAccessUnderLocks("Comparator.isLocked", null, false, thread, 0L, false, Integer.MIN_VALUE);
            validator.recordFieldAccessUnderLocks("Comparator.isLocked", null, true, thread, 0L, false, 1);
        }

        assertTrue(validator.analyzeAtomicity().unsafeFieldAccesses.isEmpty(),
                "every write stored the same constant from a method that never read the field, so "
                        + "the field settles at that value whatever the interleaving. Reporting it "
                        + "makes a benign monotonic flag look like a race.");
    }

    @Test
    @DisplayName("a write the weaver could not read as a constant is still reported")
    void writesWithoutAConstantTagAreStillReported() {
        AtomicityValidator validator = new AtomicityValidator();
        for (long thread = 1; thread <= 3; thread++) {
            validator.recordFieldAccessUnderLocks("Counter.count", null, false, thread, 0L, false, Integer.MIN_VALUE);
            validator.recordFieldAccessUnderLocks("Counter.count", null, true, thread, 0L, false, Integer.MIN_VALUE);
        }

        assertFalse(validator.analyzeAtomicity().unsafeFieldAccesses.isEmpty(),
                "count++ stores a computed value, not a constant. If this goes silent the tag is "
                        + "defaulting to 'constant' and the rule has become a blanket excuse.");
    }

    @Test
    @DisplayName("threads writing different constants to one field are still reported")
    void differingConstantsAreStillReported() {
        AtomicityValidator validator = new AtomicityValidator();
        validator.recordFieldAccessUnderLocks("Flag.state", null, false, 1L, 0L, false, Integer.MIN_VALUE);
        validator.recordFieldAccessUnderLocks("Flag.state", null, true, 1L, 0L, false, 1);
        validator.recordFieldAccessUnderLocks("Flag.state", null, false, 2L, 0L, false, Integer.MIN_VALUE);
        validator.recordFieldAccessUnderLocks("Flag.state", null, true, 2L, 0L, false, 0);

        assertFalse(validator.analyzeAtomicity().unsafeFieldAccesses.isEmpty(),
                "one thread writing true while another writes false is a real race on the flag: "
                        + "the rule must only cover a field that settles at a single value.");
    }
}
