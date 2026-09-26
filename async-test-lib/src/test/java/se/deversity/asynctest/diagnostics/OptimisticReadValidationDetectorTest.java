package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import java.util.concurrent.locks.StampedLock;
import static org.junit.jupiter.api.Assertions.*;

public class OptimisticReadValidationDetectorTest {

    @Test
    void testNoIssuesWhenEmpty() {
        var d = new OptimisticReadValidationDetector();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueWhenValidationSucceeds() {
        var d = new OptimisticReadValidationDetector();
        StampedLock lock = new StampedLock();
        long stamp = lock.tryOptimisticRead();
        Thread t = Thread.currentThread();
        d.recordOptimisticReadStarted(lock, stamp, t);
        d.recordDataAccessed(lock, stamp, t, "sharedX");
        d.recordValidateCalled(lock, stamp, true, t); // validation passed
        assertFalse(d.analyze().hasIssues());
    }

    /**
     * The class javadoc's own idiom, with a writer landing between the read and the validate: the
     * validation fails, the optimistic values are thrown away, and the value used is re-read under
     * the read lock. Correct code, so no finding.
     */
    @Test
    void theValidateAndRetryIdiomIsSilentWhenValidationFails() {
        var d = new OptimisticReadValidationDetector();
        StampedLock lock = new StampedLock();
        int[] shared = {1};
        Thread t = Thread.currentThread();

        long stamp = lock.tryOptimisticRead();
        d.recordOptimisticReadStarted(lock, stamp, t);
        int x = shared[0];
        d.recordDataAccessed(lock, stamp, t, "sharedX");

        long write = lock.writeLock();                 // the concurrent writer
        shared[0] = 2;
        lock.unlockWrite(write);

        boolean valid = lock.validate(stamp);
        d.recordValidateCalled(lock, stamp, valid, t);
        assertFalse(valid, "premise: the write invalidated the optimistic stamp");
        if (!valid) {
            long read = lock.readLock();
            try {
                x = shared[0];
            } finally {
                lock.unlockRead(read);
            }
        }

        assertEquals(2, x, "the value used is the one re-read under the lock");
        assertFalse(d.analyze().hasIssues(),
                "a failed validate() is the idiom telling the caller to retry, not a torn read "
                        + "that was used: " + d.analyze().violations);
    }

    /**
     * The bug the retry idiom exists to prevent: the write lands, validate() says so, and the caller
     * uses the optimistic values anyway. Reported once however many times the use is recorded.
     */
    @Test
    void usingTheValuesAfterAFailedValidateIsReported() {
        var d = new OptimisticReadValidationDetector();
        StampedLock lock = new StampedLock();
        int[] shared = {1};
        Thread t = Thread.currentThread();

        long stamp = lock.tryOptimisticRead();
        d.recordOptimisticReadStarted(lock, stamp, t);
        int x = shared[0];
        d.recordDataAccessed(lock, stamp, t, "sharedX");

        long write = lock.writeLock();                 // the concurrent writer
        shared[0] = 2;
        lock.unlockWrite(write);

        boolean valid = lock.validate(stamp);
        d.recordValidateCalled(lock, stamp, valid, t);
        assertFalse(valid, "premise: the write invalidated the optimistic stamp");
        assertEquals(1, x, "premise: the value used is the torn one read before the write");
        d.recordValuesUsed(lock, stamp, t);            // no retry: the torn value is used
        d.recordValuesUsed(lock, stamp, t);

        var report = d.analyze();
        assertEquals(1, report.violations.size(),
                "using values whose validate() failed must be reported, once: " + report.violations);
        assertTrue(report.violations.get(0).contains("sharedX"), report.violations.get(0));
        assertTrue(report.violations.get(0).contains("validate() returned false"),
                report.violations.get(0));
    }

    @Test
    void usingTheValuesAfterASuccessfulValidateIsSilent() {
        var d = new OptimisticReadValidationDetector();
        StampedLock lock = new StampedLock();
        Thread t = Thread.currentThread();

        long stamp = lock.tryOptimisticRead();
        d.recordOptimisticReadStarted(lock, stamp, t);
        d.recordDataAccessed(lock, stamp, t, "sharedX");
        boolean valid = lock.validate(stamp);
        d.recordValidateCalled(lock, stamp, valid, t);
        assertTrue(valid, "premise: no writer, so the stamp is still valid");
        d.recordValuesUsed(lock, stamp, t);

        assertFalse(d.analyze().hasIssues(), d.analyze().violations.toString());
    }

    /**
     * The javadoc idiom with the use recorded: the validation fails, the values are re-read under the
     * read lock, and the use names the read-lock stamp they were read under. Correct code.
     */
    @Test
    void usingTheValuesReReadUnderTheReadLockAfterAFailedValidateIsSilent() {
        var d = new OptimisticReadValidationDetector();
        StampedLock lock = new StampedLock();
        int[] shared = {1};
        Thread t = Thread.currentThread();

        long stamp = lock.tryOptimisticRead();
        d.recordOptimisticReadStarted(lock, stamp, t);
        int x = shared[0];
        d.recordDataAccessed(lock, stamp, t, "sharedX");

        long write = lock.writeLock();
        shared[0] = 2;
        lock.unlockWrite(write);

        boolean valid = lock.validate(stamp);
        d.recordValidateCalled(lock, stamp, valid, t);
        assertFalse(valid, "premise: the write invalidated the optimistic stamp");
        long read = lock.readLock();
        try {
            x = shared[0];
            d.recordValuesUsed(lock, read, t);
        } finally {
            lock.unlockRead(read);
        }

        assertEquals(2, x, "the value used is the one re-read under the lock");
        assertFalse(d.analyze().hasIssues(),
                "the values used were re-read under the read lock: " + d.analyze().violations);
    }

    /**
     * Revalidation of a stamp that already validated once: the first validate() passes, a writer
     * lands, the second validate() of the same stamp fails, and the caller uses the optimistic
     * values anyway. The use is judged against the latest validate() before it, not the first.
     */
    @Test
    void usingTheValuesAfterAFailedRevalidationOfAValidatedStampIsReported() {
        var d = new OptimisticReadValidationDetector();
        StampedLock lock = new StampedLock();
        int[] shared = {1};
        Thread t = Thread.currentThread();

        long stamp = lock.tryOptimisticRead();
        d.recordOptimisticReadStarted(lock, stamp, t);
        int x = shared[0];
        d.recordDataAccessed(lock, stamp, t, "sharedX");
        boolean first = lock.validate(stamp);
        d.recordValidateCalled(lock, stamp, first, t);
        assertTrue(first, "premise: no writer yet, so the stamp still validates");
        d.recordValuesUsed(lock, stamp, t);
        assertFalse(d.analyze().hasIssues(),
                "a use after a successful validate() is correct: " + d.analyze().violations);

        long write = lock.writeLock();                 // the concurrent writer
        shared[0] = 2;
        lock.unlockWrite(write);

        boolean second = lock.validate(stamp);
        d.recordValidateCalled(lock, stamp, second, t);
        assertFalse(second, "premise: the write invalidated the stamp that validated before it");
        assertEquals(1, x, "premise: the value used is the one read before the write");
        d.recordValuesUsed(lock, stamp, t);            // no retry: the stale value is used

        var report = d.analyze();
        assertEquals(1, report.violations.size(),
                "using values whose latest validate() failed must be reported, once: "
                        + report.violations);
        assertTrue(report.violations.get(0).contains("sharedX"), report.violations.get(0));
        assertTrue(report.violations.get(0).contains("validate() returned false"),
                report.violations.get(0));
    }

    /**
     * The retry idiom around a revalidation: the stamp validates, a writer lands, the second
     * validate() fails, and the value is re-read under the read lock before the use. Correct code,
     * as is a second validate() that still passes.
     */
    @Test
    void reReadingUnderTheReadLockAfterAFailedRevalidationIsSilent() {
        var d = new OptimisticReadValidationDetector();
        StampedLock lock = new StampedLock();
        int[] shared = {1};
        Thread t = Thread.currentThread();

        long stamp = lock.tryOptimisticRead();
        d.recordOptimisticReadStarted(lock, stamp, t);
        int x = shared[0];
        d.recordDataAccessed(lock, stamp, t, "sharedX");
        d.recordValidateCalled(lock, stamp, lock.validate(stamp), t);
        boolean stillValid = lock.validate(stamp);
        d.recordValidateCalled(lock, stamp, stillValid, t);
        assertTrue(stillValid, "premise: no writer yet, so a revalidation passes too");
        d.recordValuesUsed(lock, stamp, t);

        long write = lock.writeLock();
        shared[0] = 2;
        lock.unlockWrite(write);

        boolean valid = lock.validate(stamp);
        d.recordValidateCalled(lock, stamp, valid, t);
        assertFalse(valid, "premise: the write invalidated the stamp");
        long read = lock.readLock();
        try {
            x = shared[0];
            d.recordValuesUsed(lock, read, t);
        } finally {
            lock.unlockRead(read);
        }

        assertEquals(2, x, "the value used is the one re-read under the lock");
        assertFalse(d.analyze().hasIssues(),
                "the values used were re-read under the read lock: " + d.analyze().violations);
    }

    /**
     * A StampedLock stamp that failed validation never validates again: the writer moved the
     * lock's version on, so revalidating returns false for good and the use is still reported,
     * once. A true after a false can only come from a caller recording a result the lock did not
     * return; the detector then judges the use against that latest record, and stays silent.
     */
    @Test
    void aStampThatFailedValidationStaysFailedAndTheLatestRecordedOutcomeWins() {
        var d = new OptimisticReadValidationDetector();
        StampedLock lock = new StampedLock();
        Thread t = Thread.currentThread();

        long stamp = lock.tryOptimisticRead();
        d.recordOptimisticReadStarted(lock, stamp, t);
        d.recordDataAccessed(lock, stamp, t, "sharedX");
        long write = lock.writeLock();
        boolean whileWriting = lock.validate(stamp);
        lock.unlockWrite(write);
        boolean afterRelease = lock.validate(stamp);
        assertFalse(whileWriting, "premise: validate() fails while the writer holds the lock");
        assertFalse(afterRelease, "premise: and still fails once the writer has released it");
        d.recordValidateCalled(lock, stamp, whileWriting, t);
        d.recordValidateCalled(lock, stamp, afterRelease, t);
        d.recordValuesUsed(lock, stamp, t);
        d.recordValidateCalled(lock, stamp, lock.validate(stamp), t);
        d.recordValuesUsed(lock, stamp, t);
        assertEquals(1, d.analyze().violations.size(),
                "a use after the real sequence fail, fail is reported once: "
                        + d.analyze().violations);

        var synthetic = new OptimisticReadValidationDetector();
        synthetic.recordOptimisticReadStarted(lock, 7L, t);
        synthetic.recordDataAccessed(lock, 7L, t, "sharedX");
        synthetic.recordValidateCalled(lock, 7L, false, t);
        synthetic.recordValidateCalled(lock, 7L, true, t);
        synthetic.recordValuesUsed(lock, 7L, t);
        assertFalse(synthetic.analyze().hasIssues(),
                "the use is judged against the latest recorded validate(): "
                        + synthetic.analyze().violations);
    }

    /** The loop form of the idiom: a failed attempt, a fresh optimistic read that validates, then the use. */
    @Test
    void usingTheValuesOfARetriedOptimisticReadIsSilent() {
        var d = new OptimisticReadValidationDetector();
        StampedLock lock = new StampedLock();
        Thread t = Thread.currentThread();

        long first = lock.tryOptimisticRead();
        d.recordOptimisticReadStarted(lock, first, t);
        d.recordDataAccessed(lock, first, t, "sharedX");
        lock.unlockWrite(lock.writeLock());
        d.recordValidateCalled(lock, first, lock.validate(first), t);

        long second = lock.tryOptimisticRead();
        d.recordOptimisticReadStarted(lock, second, t);
        d.recordDataAccessed(lock, second, t, "sharedX");
        d.recordValidateCalled(lock, second, lock.validate(second), t);
        d.recordValuesUsed(lock, second, t);

        assertFalse(d.analyze().hasIssues(), d.analyze().violations.toString());
    }

    /**
     * A use with no validate() at all is the missing validation the detector already reports; the
     * use adds no second finding for the same read.
     */
    @Test
    void usingTheValuesWithNoValidateIsReportedOnceAsNeverValidated() {
        var d = new OptimisticReadValidationDetector();
        StampedLock lock = new StampedLock();
        Thread t = Thread.currentThread();

        long stamp = lock.tryOptimisticRead();
        d.recordOptimisticReadStarted(lock, stamp, t);
        d.recordDataAccessed(lock, stamp, t, "sharedX");
        d.recordValuesUsed(lock, stamp, t);

        var report = d.analyze();
        assertEquals(1, report.violations.size(), report.violations.toString());
        assertTrue(report.violations.get(0).contains("sharedX"), report.violations.get(0));
        assertTrue(report.violations.get(0).contains("never called"), report.violations.get(0));
    }

    /**
     * A validate() covers only the reads before it. Here x is read and validated, a writer lands,
     * and y is read under the same stamp and used without a second validate(): x and y come from
     * different writes, a torn pair. The finding names y, the read no validate() covered.
     */
    @Test
    void dataReadAfterASuccessfulValidateAndUsedWithoutRevalidatingIsReported() {
        var d = new OptimisticReadValidationDetector();
        StampedLock lock = new StampedLock();
        int[] shared = {1, 1};
        Thread t = Thread.currentThread();

        long stamp = lock.tryOptimisticRead();
        d.recordOptimisticReadStarted(lock, stamp, t);
        int x = shared[0];
        d.recordDataAccessed(lock, stamp, t, "sharedX");
        boolean valid = lock.validate(stamp);
        d.recordValidateCalled(lock, stamp, valid, t);
        assertTrue(valid, "premise: no writer yet, so the stamp validates");

        long write = lock.writeLock();                 // the concurrent writer
        shared[0] = 2;
        shared[1] = 2;
        lock.unlockWrite(write);

        int y = shared[1];
        d.recordDataAccessed(lock, stamp, t, "sharedY");
        d.recordValuesUsed(lock, stamp, t);            // no second validate(): x and y are torn
        d.recordValuesUsed(lock, stamp, t);
        assertNotEquals(x, y, "premise: the pair used is torn");

        var report = d.analyze();
        assertEquals(1, report.violations.size(),
                "a read after the validate() that covered the earlier ones must be reported, once: "
                        + report.violations);
        assertTrue(report.violations.get(0).contains("sharedY"), report.violations.get(0));
        assertFalse(report.violations.get(0).contains("sharedX"),
                "x was covered by the validate(): " + report.violations.get(0));
    }

    /** Everything read before the one validate(), then used: the textbook shape, silent. */
    @Test
    void readingEveryFieldBeforeTheValidateIsSilent() {
        var d = new OptimisticReadValidationDetector();
        StampedLock lock = new StampedLock();
        Thread t = Thread.currentThread();

        long stamp = lock.tryOptimisticRead();
        d.recordOptimisticReadStarted(lock, stamp, t);
        d.recordDataAccessed(lock, stamp, t, "sharedX");
        d.recordDataAccessed(lock, stamp, t, "sharedY");
        d.recordValidateCalled(lock, stamp, lock.validate(stamp), t);
        d.recordValuesUsed(lock, stamp, t);

        assertFalse(d.analyze().hasIssues(), d.analyze().violations.toString());
    }

    /** Revalidating after each read before its use: every value used was covered, silent. */
    @Test
    void revalidatingAfterEachReadUnderOneStampIsSilent() {
        var d = new OptimisticReadValidationDetector();
        StampedLock lock = new StampedLock();
        Thread t = Thread.currentThread();

        long stamp = lock.tryOptimisticRead();
        d.recordOptimisticReadStarted(lock, stamp, t);
        d.recordDataAccessed(lock, stamp, t, "sharedX");
        d.recordValidateCalled(lock, stamp, lock.validate(stamp), t);
        d.recordValuesUsed(lock, stamp, t);
        d.recordDataAccessed(lock, stamp, t, "sharedY");
        d.recordValidateCalled(lock, stamp, lock.validate(stamp), t);
        d.recordValuesUsed(lock, stamp, t);

        assertFalse(d.analyze().hasIssues(), d.analyze().violations.toString());
    }

    /**
     * A read after a successful validate() that then fails its own revalidation, and is used anyway:
     * one finding, the failed use, not a second one for the missing validate() it was re-armed for.
     */
    @Test
    void aReadAfterAValidateThatFailsItsRevalidationAndIsUsedIsReportedOnce() {
        var d = new OptimisticReadValidationDetector();
        StampedLock lock = new StampedLock();
        Thread t = Thread.currentThread();

        long stamp = lock.tryOptimisticRead();
        d.recordOptimisticReadStarted(lock, stamp, t);
        d.recordDataAccessed(lock, stamp, t, "sharedX");
        d.recordValidateCalled(lock, stamp, lock.validate(stamp), t);
        lock.unlockWrite(lock.writeLock());
        d.recordDataAccessed(lock, stamp, t, "sharedY");
        boolean valid = lock.validate(stamp);
        d.recordValidateCalled(lock, stamp, valid, t);
        assertFalse(valid, "premise: the write invalidated the stamp");
        d.recordValuesUsed(lock, stamp, t);

        var report = d.analyze();
        assertEquals(1, report.violations.size(), report.violations.toString());
        assertTrue(report.violations.get(0).contains("validate() returned false"),
                report.violations.get(0));
    }

    /**
     * A read already reported is not re-armed: reading more under the stamp whose failed validate()
     * was already reported for a use adds no second finding for the same optimistic read.
     */
    @Test
    void aReadAlreadyReportedIsNotReArmedByMoreReadsUnderItsStamp() {
        var d = new OptimisticReadValidationDetector();
        StampedLock lock = new StampedLock();
        Thread t = Thread.currentThread();

        long stamp = lock.tryOptimisticRead();
        d.recordOptimisticReadStarted(lock, stamp, t);
        d.recordDataAccessed(lock, stamp, t, "sharedX");
        lock.unlockWrite(lock.writeLock());
        d.recordValidateCalled(lock, stamp, lock.validate(stamp), t);
        d.recordValuesUsed(lock, stamp, t);            // reported: the torn x is used
        d.recordDataAccessed(lock, stamp, t, "sharedY");
        d.recordValuesUsed(lock, stamp, t);

        assertEquals(1, d.analyze().violations.size(), d.analyze().violations.toString());
    }

    /**
     * A read after a successful validate() that is abandoned for a fresh optimistic read is reported
     * once, when the fresh read replaces it, and not again at analysis.
     */
    @Test
    void anUnrevalidatedReadReplacedByAFreshOptimisticReadIsReportedOnce() {
        var d = new OptimisticReadValidationDetector();
        StampedLock lock = new StampedLock();
        Thread t = Thread.currentThread();

        d.recordOptimisticReadStarted(lock, 1L, t);
        d.recordDataAccessed(lock, 1L, t, "sharedX");
        d.recordValidateCalled(lock, 1L, true, t);
        d.recordDataAccessed(lock, 1L, t, "sharedY");
        d.recordOptimisticReadStarted(lock, 2L, t);
        d.recordDataAccessed(lock, 2L, t, "sharedZ");
        d.recordValidateCalled(lock, 2L, true, t);

        var report = d.analyze();
        assertEquals(1, report.violations.size(), report.violations.toString());
        assertTrue(report.violations.get(0).contains("sharedY"), report.violations.get(0));
    }

    /**
     * The StampedLock javadoc's own loop (distanceFromOrigin): an optimistic attempt, and on a failed
     * validate() the loop retries holding the read lock. Everything read under a stamp is read
     * before that stamp's validate(). Silent with and without a writer landing.
     */
    @Test
    void theStampedLockJavadocRetryLoopFallingBackToTheReadLockIsSilent() {
        for (boolean writerLands : new boolean[] {false, true}) {
            var d = new OptimisticReadValidationDetector();
            StampedLock lock = new StampedLock();
            int[] shared = {1, 1};
            Thread t = Thread.currentThread();

            long stamp = lock.tryOptimisticRead();
            d.recordOptimisticReadStarted(lock, stamp, t);
            int sum;
            boolean first = true;
            try {
                for (;; stamp = lock.readLock()) {
                    int x = shared[0];
                    d.recordDataAccessed(lock, stamp, t, "sharedX");
                    if (first && writerLands) {
                        long write = lock.writeLock();
                        shared[0] = 2;
                        shared[1] = 2;
                        lock.unlockWrite(write);
                    }
                    first = false;
                    int y = shared[1];
                    d.recordDataAccessed(lock, stamp, t, "sharedY");
                    boolean valid = lock.validate(stamp);
                    d.recordValidateCalled(lock, stamp, valid, t);
                    if (!valid) {
                        continue;
                    }
                    d.recordValuesUsed(lock, stamp, t);
                    sum = x + y;
                    break;
                }
            } finally {
                if (StampedLock.isReadLockStamp(stamp)) {
                    lock.unlockRead(stamp);
                }
            }

            assertEquals(writerLands ? 4 : 2, sum, "premise: the sum used is consistent");
            assertFalse(d.analyze().hasIssues(),
                    "writerLands=" + writerLands + ": " + d.analyze().violations);
        }
    }

    /** Two locks read optimistically in turn, each validated: correct, even if their hashes collide. */
    @Test
    void twoLocksWhoseIdentityHashesCollideAreNotMerged() {
        StampedLock[] pair = collidingLocks();
        org.junit.jupiter.api.Assumptions.assumeTrue(pair != null,
                "no identity-hash collision found among the locks allocated");
        var d = new OptimisticReadValidationDetector();
        Thread t = Thread.currentThread();

        long first = pair[0].tryOptimisticRead();
        d.recordOptimisticReadStarted(pair[0], first, t);
        d.recordDataAccessed(pair[0], first, t, "a");
        long second = pair[1].tryOptimisticRead();
        d.recordOptimisticReadStarted(pair[1], second, t);
        d.recordDataAccessed(pair[1], second, t, "b");
        d.recordValidateCalled(pair[1], second, true, t);
        d.recordValidateCalled(pair[0], first, true, t);

        assertFalse(d.analyze().hasIssues(),
                "keyed by identity hash, the second lock's read replaced the first's and reported "
                        + "it never validated: " + d.analyze().violations);
    }

    /** {@return two distinct locks with the same identity hash, or null if none turned up} */
    private static StampedLock[] collidingLocks() {
        java.util.Map<Integer, StampedLock> seen = new java.util.HashMap<>();
        for (int i = 0; i < 2_000_000; i++) {
            StampedLock lock = new StampedLock();
            StampedLock earlier = seen.putIfAbsent(System.identityHashCode(lock), lock);
            if (earlier != null) {
                return new StampedLock[] {earlier, lock};
            }
        }
        return null;
    }

    @Test
    void testDetectsDataAccessedWithoutValidation() {
        var d = new OptimisticReadValidationDetector();
        StampedLock lock = new StampedLock();
        long stamp = lock.tryOptimisticRead();
        Thread t = Thread.currentThread();
        d.recordOptimisticReadStarted(lock, stamp, t);
        d.recordDataAccessed(lock, stamp, t, "sharedY");
        // validate never called — detected at analyze() time
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().violations.get(0).contains("sharedY"));
        assertTrue(d.analyze().violations.get(0).contains("never called"));
    }

    @Test
    void testNoIssueWhenNoDataAccessedBeforeFailedValidation() {
        var d = new OptimisticReadValidationDetector();
        StampedLock lock = new StampedLock();
        long stamp = lock.tryOptimisticRead();
        Thread t = Thread.currentThread();
        d.recordOptimisticReadStarted(lock, stamp, t);
        // no data accessed
        d.recordValidateCalled(lock, stamp, false, t); // failed but no data was read
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNullSafety() {
        var d = new OptimisticReadValidationDetector();
        assertDoesNotThrow(() -> {
            d.recordOptimisticReadStarted(null, 0L, Thread.currentThread());
            d.recordOptimisticReadStarted(new StampedLock(), 0L, null);
            d.recordDataAccessed(null, 0L, Thread.currentThread(), "x");
            d.recordValidateCalled(null, 0L, false, Thread.currentThread());
            d.recordValuesUsed(null, 0L, Thread.currentThread());
            d.recordValuesUsed(new StampedLock(), 0L, null);
        });
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testUnvalidatedReadSurvivesANewOptimisticReadOnSameLockAndThread() {
        var d = new OptimisticReadValidationDetector();
        StampedLock lock = new StampedLock();
        Thread t = Thread.currentThread();

        // First optimistic read: data accessed, validate() never called — a real bug.
        d.recordOptimisticReadStarted(lock, 1L, t);
        d.recordDataAccessed(lock, 1L, t, "sharedX");

        // Second optimistic read on the same lock from the same thread, done correctly.
        d.recordOptimisticReadStarted(lock, 2L, t);
        d.recordDataAccessed(lock, 2L, t, "sharedY");
        d.recordValidateCalled(lock, 2L, true, t);

        var report = d.analyze();
        assertTrue(report.hasIssues(),
            "the first read's missing validate() must still be reported after a later, correct read");
        assertTrue(report.violations.stream()
                .anyMatch(v -> v.contains("sharedX") && v.contains("never called")),
            "violation should identify the unvalidated first read: " + report.violations);
    }

    @Test
    void testValidateWithWrongStampDoesNotDiscardThePendingRead() {
        var d = new OptimisticReadValidationDetector();
        StampedLock lock = new StampedLock();
        Thread t = Thread.currentThread();

        d.recordOptimisticReadStarted(lock, 1L, t);
        d.recordDataAccessed(lock, 1L, t, "sharedZ");
        // validate() called with a stamp from some other read — the pending read
        // above is still unvalidated and must not be silently forgotten.
        d.recordValidateCalled(lock, 99L, true, t);

        var report = d.analyze();
        assertTrue(report.hasIssues(),
            "a stamp-mismatched validate() must not erase the unvalidated read");
        assertTrue(report.violations.get(0).contains("sharedZ"));
        assertTrue(report.violations.get(0).contains("never called"));
    }

    @Test
    void testReportToStringContainsFixHint() {
        var d = new OptimisticReadValidationDetector();
        StampedLock lock = new StampedLock();
        long stamp = lock.tryOptimisticRead();
        Thread t = Thread.currentThread();
        d.recordOptimisticReadStarted(lock, stamp, t);
        d.recordDataAccessed(lock, stamp, t, "field");
        d.recordValidateCalled(lock, stamp, false, t);
        String s = d.analyze().toString();
        assertTrue(s.contains("OPTIMISTIC READ VALIDATION"));
        assertTrue(s.contains("Fix"));
    }
}
