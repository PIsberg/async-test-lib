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

    // ---- Harness-derived happens-before: rounds are ordered, cross-round pairs cannot race ----

    @Test
    void crossRoundAccessesOrderedByTheHarnessAreNotFlagged() {
        AtomicityValidator validator = new AtomicityValidator();

        validator.markInvocationStart();
        validator.recordFieldAccess("handoff", 1, true, 101L);   // round 1: thread 101 writes

        validator.markInvocationStart();
        validator.recordFieldAccess("handoff", 1, false, 202L);  // round 2: thread 202 reads

        AtomicityValidator.AtomicityReport report = validator.analyzeAtomicity();

        assertFalse(report.hasIssues(),
                "a write in round 1 and a read in round 2 are ordered by the harness's own "
                        + "latch/submit happens-before edges and must not be reported");
    }

    @Test
    void sameRoundMixedAccessStillFlaggedAfterEpochScoping() {
        AtomicityValidator validator = new AtomicityValidator();

        validator.markInvocationStart();
        validator.recordFieldAccess("state", 1, true, 101L);
        validator.recordFieldAccess("state", 1, false, 202L);

        AtomicityValidator.AtomicityReport report = validator.analyzeAtomicity();

        assertFalse(report.unsafeFieldAccesses.isEmpty(),
                "mixed read/write from two threads within one round must still be flagged");
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

    // ---- The lock is judged within one round ---------------------------------------------------
    //
    // A lock that guarded every access of one round says nothing about the next round's: the
    // harness ordered them. A different lock in each round is consistent locking, and two locks
    // inside one round are not.

    static final class Box {
        int value;
    }

    private static void onThreads(Runnable... bodies) throws InterruptedException {
        Thread[] threads = new Thread[bodies.length];
        for (int i = 0; i < bodies.length; i++) {
            threads[i] = new Thread(bodies[i], "worker-" + i);
        }
        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
    }

    /** A read then a write of {@code box.value}, naming the owner, under {@code lock}. */
    private static Runnable ownerAwareIncrementUnder(AtomicityValidator validator, Box box, Object lock) {
        return () -> {
            synchronized (lock) {
                try (var held = HeldLocks.holding(lock)) {
                    validator.recordFieldAccessOn(box, "Box.value", box.value, false);
                    box.value++;
                    validator.recordFieldAccessOn(box, "Box.value", box.value, true);
                }
            }
        };
    }

    /** The same, recorded the way the agent's drain does: with the lock fingerprint. */
    private static Runnable fingerprintedIncrementUnder(AtomicityValidator validator, Object lock) {
        return () -> {
            long thread = Thread.currentThread().threadId();
            synchronized (lock) {
                try (var held = HeldLocks.holding(lock)) {
                    validator.recordFieldAccessUnderLocks("Box.count", null, false, thread,
                            HeldLocks.lockFingerprint(false));
                    validator.recordFieldAccessUnderLocks("Box.count", null, true, thread,
                            HeldLocks.lockFingerprint(true));
                }
            }
        };
    }

    /** A read then a write of {@code box.value}, naming the owner, with no lock held. */
    private static Runnable ownerAwareIncrementUnguarded(AtomicityValidator validator, Box box) {
        return () -> {
            validator.recordFieldAccessOn(box, "Box.value", box.value, false);
            box.value++;
            validator.recordFieldAccessOn(box, "Box.value", box.value, true);
        };
    }

    @Test
    void twoConfinedObjectsWithTheSameFieldAreNotOneHistory() throws InterruptedException {
        AtomicityValidator validator = new AtomicityValidator();
        validator.markInvocationStart();
        onThreads(ownerAwareIncrementUnguarded(validator, new Box()),
                ownerAwareIncrementUnguarded(validator, new Box()));

        AtomicityValidator.AtomicityReport report = validator.analyzeAtomicity();
        assertFalse(report.hasIssues(),
                "each thread touched only its own Box, and recordFieldAccessOn named it: two "
                        + "confined objects sharing a field name share no state (#750). Got "
                        + report);
    }

    @Test
    void oneObjectTwoThreadsNoLockStillFiresOnTheOwnerAwarePath() throws InterruptedException {
        AtomicityValidator validator = new AtomicityValidator();
        Box shared = new Box();
        validator.markInvocationStart();
        onThreads(ownerAwareIncrementUnguarded(validator, shared),
                ownerAwareIncrementUnguarded(validator, shared));

        AtomicityValidator.AtomicityReport report = validator.analyzeAtomicity();
        assertTrue(report.unsafeFieldAccesses.stream().anyMatch(s -> s.startsWith("Box.value")),
                "two threads incrementing one Box with no lock is the lost update itself; if this "
                        + "goes silent, grouping by owner has split one object apart. Got "
                        + report.unsafeFieldAccesses);
    }

    @Test
    void aDifferentLockInEachRoundIsNotInconsistentLocking() throws InterruptedException {
        AtomicityValidator validator = new AtomicityValidator();
        Box box = new Box();
        for (Object lock : new Object[] {new Object(), new Object()}) {
            validator.markInvocationStart();
            onThreads(ownerAwareIncrementUnder(validator, box, lock),
                    ownerAwareIncrementUnder(validator, box, lock),
                    fingerprintedIncrementUnder(validator, lock),
                    fingerprintedIncrementUnder(validator, lock));
        }

        AtomicityValidator.AtomicityReport report = validator.analyzeAtomicity();
        assertFalse(report.hasIssues(),
                "each round held one lock at every access, for both lock models: " + report);
    }

    @Test
    void twoLocksInOneRoundStillFire() throws InterruptedException {
        AtomicityValidator validator = new AtomicityValidator();
        Box box = new Box();
        validator.markInvocationStart();
        onThreads(ownerAwareIncrementUnder(validator, box, new Object()),
                ownerAwareIncrementUnder(validator, box, new Object()),
                fingerprintedIncrementUnder(validator, new Object()),
                fingerprintedIncrementUnder(validator, new Object()));

        AtomicityValidator.AtomicityReport report = validator.analyzeAtomicity();
        assertTrue(report.unsafeFieldAccesses.stream().anyMatch(s -> s.startsWith("Box.value")),
                "owner-aware: two locks in one round exclude nothing; got " + report.unsafeFieldAccesses);
        assertTrue(report.unsafeFieldAccesses.stream().anyMatch(s -> s.startsWith("Box.count")),
                "fingerprinted: two locks in one round exclude nothing; got " + report.unsafeFieldAccesses);
    }

    /**
     * One round of double-checked locking on a volatile field: both threads read with no lock,
     * then write, thread 101 under {@code firstWriteLock} and thread 202 under
     * {@code secondWriteLock}. Fingerprints are synthetic, as in {@code SafePublicationRuleTest}.
     */
    private static void doubleCheckedRound(AtomicityValidator validator, long firstWriteLock,
                                           long secondWriteLock) {
        validator.markInvocationStart();
        validator.recordFieldAccessUnderLocks("Holder.instance", null, false, 101L, 0L, true);
        validator.recordFieldAccessUnderLocks("Holder.instance", null, false, 202L, 0L, true);
        validator.recordFieldAccessUnderLocks("Holder.instance", null, true, 101L, firstWriteLock, true);
        validator.recordFieldAccessUnderLocks("Holder.instance", null, true, 202L, secondWriteLock, true);
    }

    @Test
    void doubleCheckedLockingWithADifferentWriteLockEachRoundIsSafePublication() {
        AtomicityValidator validator = new AtomicityValidator();
        doubleCheckedRound(validator, 4242L, 4242L);
        doubleCheckedRound(validator, 4343L, 4343L);

        AtomicityValidator.AtomicityReport report = validator.analyzeAtomicity();
        assertTrue(report.unsafeFieldAccesses.isEmpty(),
                "every write of each round held that round's lock, so each round is correct "
                        + "double-checked locking; a run-wide write intersection empties only "
                        + "because the harness-ordered rounds used different locks (#749). Got "
                        + report.unsafeFieldAccesses);
    }

    @Test
    void doubleCheckedLockingWithTwoWriteLocksInOneRoundStillFires() {
        AtomicityValidator validator = new AtomicityValidator();
        doubleCheckedRound(validator, 4242L, 4242L);
        doubleCheckedRound(validator, 4242L, 4343L);

        AtomicityValidator.AtomicityReport report = validator.analyzeAtomicity();
        assertFalse(report.unsafeFieldAccesses.isEmpty(),
                "two writes in one round under two different locks exclude nothing: the volatile "
                        + "field was mutated unserialised, which is not safe publication. If this "
                        + "goes silent the per-round write lockset is excusing without looking.");
    }

    /** One agent-fed access to a plain field of instance 91, as the telemetry drain replays it. */
    private static void thresholdAccess(AtomicityValidator validator, boolean write, long thread,
                                        long fingerprint) {
        validator.recordFieldAccessUnderLocks("Segment.resizeThreshold", null, write, thread,
                fingerprint, 0, 0, false, Integer.MIN_VALUE, 91);
    }

    /**
     * One round of the confirmed-hint idiom (#311) on a plain field: each thread reads with no
     * lock, re-reads under its write lock, then writes under it, thread 101 under
     * {@code firstWriteLock} and thread 202 under {@code secondWriteLock}.
     */
    private static void confirmedHintRound(AtomicityValidator validator, long firstWriteLock,
                                           long secondWriteLock) {
        validator.markInvocationStart();
        for (long[] step : new long[][] {{101L, firstWriteLock}, {202L, secondWriteLock}}) {
            thresholdAccess(validator, false, step[0], 0L);
            thresholdAccess(validator, false, step[0], step[1]);
            thresholdAccess(validator, true, step[0], step[1]);
        }
    }

    @Test
    void confirmedHintWithADifferentWriteLockEachRoundIsExcused() {
        AtomicityValidator validator = new AtomicityValidator();
        confirmedHintRound(validator, 4242L, 4242L);
        confirmedHintRound(validator, 4343L, 4343L);

        AtomicityValidator.AtomicityReport report = validator.analyzeAtomicity();
        assertTrue(report.unsafeFieldAccesses.isEmpty(),
                "every write of each round held that round's lock and every unlocked read was "
                        + "re-read under it before the write, so each round is the confirmed-hint "
                        + "idiom; a run-wide write intersection empties only because the "
                        + "harness-ordered rounds used different locks (#781). Got "
                        + report.unsafeFieldAccesses);
    }

    @Test
    void confirmedHintWithTwoWriteLocksInOneRoundStillFires() {
        AtomicityValidator validator = new AtomicityValidator();
        confirmedHintRound(validator, 4242L, 4242L);
        confirmedHintRound(validator, 4242L, 4343L);

        AtomicityValidator.AtomicityReport report = validator.analyzeAtomicity();
        assertFalse(report.unsafeFieldAccesses.isEmpty(),
                "two writes in one round under two different locks exclude nothing, so a re-read "
                        + "under either confirms nothing. If this goes silent the per-round write "
                        + "lockset is excusing without looking.");
    }
}
