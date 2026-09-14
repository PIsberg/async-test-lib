package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.locks.StampedLock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what {@link StampedLockDetector} decides on, against real {@link StampedLock}s (#588).
 *
 * <p>A leak is a lock the detector saw acquired and never released <em>and</em> that is still
 * held when the run is analysed, asked of the lock itself. A declaration alone is not a leak, and
 * neither is a forgotten {@code recordUnlock} on a lock that was in fact released. Optimistic
 * reads and failed validations are matched per thread and per lock instance, never per lock
 * name and never on counts pooled across threads.
 */
class StampedLockDetectorModelTest {

    @Test
    @DisplayName("a write stamp taken and never released fires with no declaration")
    void anUnreleasedWriteStampFiresWithoutADeclaration() {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();
        detector.registerLock(lock, "leaky");

        long stamp = lock.writeLock();
        detector.recordWriteLock(lock, "leaky", stamp);
        // No unlockWrite, no recordUnlock, and no recordStampNotReleased: the leak has to be
        // inferred from the acquisition nobody matched and the lock that is still held.

        StampedLockDetector.StampedLockReport report = detector.analyze();
        assertTrue(report.hasIssues(),
                "a write stamp taken and never released leaves the lock held forever; the "
                        + "detector saw the acquisition and can ask the lock: " + report);
        assertTrue(report.toString().contains("leaky"), report.toString());
    }

    @Test
    @DisplayName("an unreleased read stamp fires too")
    void anUnreleasedReadStampFires() {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();
        detector.registerLock(lock, "reader");

        long stamp = lock.readLock();
        detector.recordReadLock(lock, "reader", stamp);

        assertTrue(detector.analyze().hasIssues(),
                "a read stamp never released blocks every writer; the lock still reports a reader");
    }

    @Test
    @DisplayName("the same write stamp released in a finally block stays silent")
    void aReleasedWriteStampIsSilent() {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();
        detector.registerLock(lock, "tidy");

        long stamp = lock.writeLock();
        detector.recordWriteLock(lock, "tidy", stamp);
        try {
            // write
        } finally {
            lock.unlockWrite(stamp);
            detector.recordUnlock(lock, "tidy", stamp);
        }

        assertFalse(detector.analyze().hasIssues(), detector.analyze().toString());
    }

    @Test
    @DisplayName("a lock that was released without recordUnlock is not a leak, because the lock says so")
    void aForgottenRecordUnlockOnAReleasedLockIsSilent() {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();
        detector.registerLock(lock, "unrecorded-release");

        long stamp = lock.writeLock();
        detector.recordWriteLock(lock, "unrecorded-release", stamp);
        lock.unlockWrite(stamp);
        // The caller forgot to record the release. The lock is free, so nothing leaked.

        assertFalse(detector.analyze().hasIssues(),
                "an unmatched record is a gap in the recording, not a leak, when the real lock "
                        + "is not held: " + detector.analyze());
    }

    @Test
    @DisplayName("a declared leak on a lock that is not held stays silent")
    void aDeclaredLeakOnAFreeLockIsSilent() {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();
        detector.registerLock(lock, "declared");

        long stamp = lock.writeLock();
        lock.unlockWrite(stamp);
        detector.recordStampNotReleased("declared", stamp);

        assertFalse(detector.analyze().hasIssues(),
                "recordStampNotReleased is the caller's claim; a lock nobody holds contradicts "
                        + "it: " + detector.analyze());
    }

    @Test
    @DisplayName("a declared leak on a lock that is still held fires")
    void aDeclaredLeakOnAHeldLockFires() {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();
        detector.registerLock(lock, "declared-held");

        // Taken somewhere the caller could not record the stamp (inside the subject), and
        // declared because the lock was seen still held afterwards.
        lock.writeLock();
        detector.recordStampNotReleased("declared-held", 0L);

        assertTrue(detector.analyze().hasIssues(), detector.analyze().toString());
    }

    @Test
    @DisplayName("two locks sharing a name do not share a pending failed validation")
    void sameNamedLocksDoNotSharePendingValidations() {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock first = new StampedLock();
        StampedLock second = new StampedLock();
        detector.registerLock(first, "cache");
        detector.registerLock(second, "cache");

        long optimistic = first.tryOptimisticRead();
        detector.recordOptimisticRead(first, "cache", optimistic);
        detector.recordOptimisticValidation(first, "cache", optimistic, false);
        // The stale value from the first lock is used as read. A read lock taken on a different
        // lock that happens to share the label is not that validation's fallback.
        long readStamp = second.readLock();
        detector.recordReadLock(second, "cache", readStamp);
        second.unlockRead(readStamp);
        detector.recordUnlock(second, "cache", readStamp);

        assertTrue(detector.analyze().hasIssues(),
                "the fallback happened on another lock instance; the failed validation on the "
                        + "first lock was never followed by anything: " + detector.analyze());
    }

    @Test
    @DisplayName("validations on another thread do not cancel a read nobody validated")
    void validationsOnAnotherThreadDoNotCancelAMissingOne() throws InterruptedException {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();
        detector.registerLock(lock, "config");

        Thread trusting = new Thread(() -> {
            long stamp = lock.tryOptimisticRead();
            detector.recordOptimisticRead(lock, "config", stamp);
            // used without validate()
        });
        Thread careful = new Thread(() -> {
            long stamp = lock.tryOptimisticRead();
            detector.recordOptimisticRead(lock, "config", stamp);
            // Validated once per field read, which is legal and common.
            detector.recordOptimisticValidation(lock, "config", stamp, lock.validate(stamp));
            detector.recordOptimisticValidation(lock, "config", stamp, lock.validate(stamp));
        });
        trusting.start();
        trusting.join();
        careful.start();
        careful.join();

        assertTrue(detector.analyze().hasIssues(),
                "two reads and two validations on the lock balance only when counted per lock; "
                        + "the first thread never validated its read: " + detector.analyze());
    }

    @Test
    @DisplayName("a failed validation sealed at a round boundary is not settled by the next round's read lock")
    void aRoundBoundarySealsAPendingFailedValidation() {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();
        detector.registerLock(lock, "pooled");

        long optimistic = lock.tryOptimisticRead();
        detector.recordOptimisticRead(lock, "pooled", optimistic);
        detector.recordOptimisticValidation(lock, "pooled", optimistic, false);

        // Same pooled thread, next round: a read lock taken for an unrelated read.
        detector.markInvocationStart();
        long readStamp = lock.readLock();
        detector.recordReadLock(lock, "pooled", readStamp);
        lock.unlockRead(readStamp);
        detector.recordUnlock(lock, "pooled", readStamp);

        assertTrue(detector.analyze().hasIssues(),
                "the body that saw the validation fail had finished; a read lock in a later "
                        + "round cannot be its fallback: " + detector.analyze());
    }

    @Test
    @DisplayName("a stamp that tryWriteLock returned as zero is not an acquisition")
    void aZeroStampIsNotAnAcquisition() {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();
        detector.registerLock(lock, "contended");

        long holder = lock.writeLock();
        detector.recordWriteLock(lock, "contended", holder);
        long failed = lock.tryWriteLock();
        detector.recordWriteLock(lock, "contended", failed);
        lock.unlockWrite(holder);
        detector.recordUnlock(lock, "contended", holder);

        assertFalse(detector.analyze().hasIssues(), detector.analyze().toString());
    }
}
