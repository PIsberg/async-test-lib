package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The agent path's lockset is an intersection, not a digest comparison.
 *
 * <p>Every case here produces its fingerprints with {@link HeldLocks} on the test thread, the way
 * the woven producer does, so what is being tested is the whole chain: the registration that makes
 * a digest resolvable, and the {@link AtomicityValidator} intersection that consumes it. The
 * shapes are the ones the corpus eval caught on real libraries: Guava's cache writes an entry
 * under its segment lock, and on the load path under the entry's monitor as well.
 */
class LocksetIntersectionTest {

    private static final int IDENTITY = 77;

    private static final int NOT_A_CONSTANT = Integer.MIN_VALUE;

    private long fingerprintHolding(Object... locks) {
        HeldLocks.Guard[] guards = new HeldLocks.Guard[locks.length];
        for (int i = 0; i < locks.length; i++) {
            guards[i] = HeldLocks.holding(locks[i]);
        }
        long fingerprint = HeldLocks.lockFingerprint(false);
        for (int i = locks.length - 1; i >= 0; i--) {
            guards[i].close();
        }
        return fingerprint;
    }

    @Test
    @DisplayName("a field guarded by L is guarded on the path that also holds M")
    void nestedLockDoesNotCollapseTheIntersection() {
        Object lockL = new Object();
        Object lockM = new Object();
        long underL = fingerprintHolding(lockL);
        long underLandM = fingerprintHolding(lockL, lockM);

        AtomicityValidator validator = new AtomicityValidator();
        validator.recordFieldAccessUnderLocks("Guarded.state", null, true, 1L, underL,
                0, 0, false, NOT_A_CONSTANT, IDENTITY);
        validator.recordFieldAccessUnderLocks("Guarded.state", null, false, 2L, underLandM,
                0, 0, false, NOT_A_CONSTANT, IDENTITY);
        validator.recordFieldAccessUnderLocks("Guarded.state", null, true, 2L, underLandM,
                0, 0, false, NOT_A_CONSTANT, IDENTITY);

        assertFalse(validator.analyzeAtomicity().hasIssues(),
                "L covered every access; the extra M on one path must not read as inconsistency");
    }

    @Test
    @DisplayName("two disjoint locksets still collapse and report")
    void disjointLocksStillReport() {
        Object lockL = new Object();
        Object lockM = new Object();
        long underL = fingerprintHolding(lockL);
        long underM = fingerprintHolding(lockM);

        AtomicityValidator validator = new AtomicityValidator();
        validator.recordFieldAccessUnderLocks("Raced.state", null, true, 1L, underL,
                0, 0, false, NOT_A_CONSTANT, IDENTITY);
        validator.recordFieldAccessUnderLocks("Raced.state", null, false, 2L, underM,
                0, 0, false, NOT_A_CONSTANT, IDENTITY);
        validator.recordFieldAccessUnderLocks("Raced.state", null, true, 2L, underM,
                0, 0, false, NOT_A_CONSTANT, IDENTITY);

        assertTrue(validator.analyzeAtomicity().hasIssues(),
                "no single lock covered every access: inconsistent locking is the race");
    }

    @Test
    @DisplayName("the volatile write-under-lock rule survives an extra monitor on one write path")
    void safePublicationSurvivesTheNestedWritePath() {
        Object segment = new Object();
        Object entryMonitor = new Object();
        long underSegment = fingerprintHolding(segment);
        long underBoth = fingerprintHolding(entryMonitor, segment);

        AtomicityValidator validator = new AtomicityValidator();
        // The LocalCache shape: writes under {segment} and {entry, segment}, lock-free reads.
        validator.recordFieldAccessUnderLocks("Entry.valueReference", null, true, 1L, underSegment,
                0, 0, true, NOT_A_CONSTANT, IDENTITY);
        validator.recordFieldAccessUnderLocks("Entry.valueReference", null, true, 2L, underBoth,
                0, 0, true, NOT_A_CONSTANT, IDENTITY);
        validator.recordFieldAccessUnderLocks("Entry.valueReference", null, false, 3L, 0L,
                0, 0, true, NOT_A_CONSTANT, IDENTITY);
        validator.recordFieldAccessUnderLocks("Entry.valueReference", null, false, 1L, 0L,
                0, 0, true, NOT_A_CONSTANT, IDENTITY);

        assertFalse(validator.analyzeAtomicity().hasIssues(),
                "volatile plus every write under the segment lock is safe publication, however "
                        + "many extra monitors one write path took");
    }

    @Test
    @DisplayName("a monitor carried outside the fingerprint guards like one inside it")
    void ownMonitorJoinsTheIntersection() {
        int monitor = 4242;

        AtomicityValidator validator = new AtomicityValidator();
        // The synchronized-method shape: no woven monitor instruction, only the probe.
        validator.recordFieldAccessUnderLocks("Sync.state", null, true, 1L, 0L,
                monitor, 0, false, NOT_A_CONSTANT, IDENTITY);
        validator.recordFieldAccessUnderLocks("Sync.state", null, false, 2L, 0L,
                0, monitor, false, NOT_A_CONSTANT, IDENTITY);
        validator.recordFieldAccessUnderLocks("Sync.state", null, true, 2L, 0L,
                monitor, 0, false, NOT_A_CONSTANT, IDENTITY);

        assertFalse(validator.analyzeAtomicity().hasIssues(),
                "the receiver's monitor and the synchronized method's monitor are the same lock, "
                        + "whichever slot carried it");

        AtomicityValidator unguarded = new AtomicityValidator();
        unguarded.recordFieldAccessUnderLocks("Sync.state", null, true, 1L, 0L,
                monitor, 0, false, NOT_A_CONSTANT, IDENTITY);
        unguarded.recordFieldAccessUnderLocks("Sync.state", null, false, 2L, 0L,
                0, 0, false, NOT_A_CONSTANT, IDENTITY);
        unguarded.recordFieldAccessUnderLocks("Sync.state", null, true, 2L, 0L,
                0, 0, false, NOT_A_CONSTANT, IDENTITY);

        assertTrue(unguarded.analyzeAtomicity().hasIssues(),
                "one access held nothing at all, so the field is not consistently guarded");
    }

    @Test
    @DisplayName("a shared-mode lock guards reads and never writes")
    void sharedModeGuardsReadsOnly() {
        Object readWriteLock = new Object();

        HeldLocks.acquired(readWriteLock, false);
        long exclusive = HeldLocks.lockFingerprint(true);
        HeldLocks.released(readWriteLock, false);

        HeldLocks.acquired(readWriteLock, true);
        long sharedForRead = HeldLocks.lockFingerprint(false);
        long sharedForWrite = HeldLocks.lockFingerprint(true);
        HeldLocks.released(readWriteLock, true);

        assertEquals(0L, sharedForWrite,
                "a read lock admits every other reader, so for a write it counts as nothing");

        AtomicityValidator validator = new AtomicityValidator();
        // Correct read-write usage: writes under the write view, reads under the read view.
        validator.recordFieldAccessUnderLocks("Rw.state", null, true, 1L, exclusive,
                0, 0, false, NOT_A_CONSTANT, IDENTITY);
        validator.recordFieldAccessUnderLocks("Rw.state", null, false, 2L, sharedForRead,
                0, 0, false, NOT_A_CONSTANT, IDENTITY);
        validator.recordFieldAccessUnderLocks("Rw.state", null, true, 2L, exclusive,
                0, 0, false, NOT_A_CONSTANT, IDENTITY);

        assertFalse(validator.analyzeAtomicity().hasIssues(),
                "reader under the read view and writer under the write view share the lock");
    }
}
