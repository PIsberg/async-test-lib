package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.locks.StampedLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link StampedLockDetector}'s mode conversions and its one wrong-stamp release, against
 * real {@link StampedLock}s (#604).
 *
 * <p>A conversion changes the stamp: the detector has to retire the stamp it came from and track
 * the one it returned, or a converted hold is counted against a stamp the body no longer has. The
 * wrong-stamp release it reports is the one the lock does not refuse: a read stamp released twice
 * while another reader still holds the lock, which silently takes that reader's hold. Every other
 * wrong stamp is refused by the lock itself in the caller's own thread, and the first test pins
 * that premise so a JDK that stops throwing is noticed.
 */
class StampedLockConversionTest {

    @Test
    @DisplayName("premise: the lock refuses a mismatched or repeated write release, but not a repeated read release")
    void premiseWhatTheLockRefusesItself() {
        StampedLock mismatched = new StampedLock();
        long readStamp = mismatched.readLock();
        assertThrows(IllegalMonitorStateException.class, () -> mismatched.unlockWrite(readStamp));
        mismatched.unlockRead(readStamp);
        long writeStamp = mismatched.writeLock();
        assertThrows(IllegalMonitorStateException.class, () -> mismatched.unlockRead(writeStamp));
        mismatched.unlockWrite(writeStamp);
        assertThrows(IllegalMonitorStateException.class, () -> mismatched.unlockWrite(writeStamp));

        StampedLock shared = new StampedLock();
        long first = shared.readLock();
        long second = shared.readLock();
        shared.unlockRead(first);
        shared.unlockRead(first); // no exception: this takes the second reader's hold
        assertEquals(0, shared.getReadLockCount(),
                "the repeated release is accepted and the other reader's hold is gone");
        assertThrows(IllegalMonitorStateException.class, () -> shared.unlockRead(second),
                "the lock only complains later, in the thread that held the stolen stamp");
    }

    @Test
    @DisplayName("an optimistic read converted to a read lock counts as validated")
    void anOptimisticReadConvertedToAReadLockIsValidated() {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();
        detector.registerLock(lock, "point");

        long optimistic = lock.tryOptimisticRead();
        detector.recordOptimisticRead(lock, "point", optimistic);
        long read = lock.tryConvertToReadLock(optimistic);
        detector.recordConversion(lock, "point", optimistic, read);
        assertNotEquals(0L, read, "no writer intervened, so the conversion succeeds");
        try {
            // read fields under the converted read lock
        } finally {
            lock.unlockRead(read);
            detector.recordUnlock(lock, "point", read);
        }

        assertFalse(detector.analyze().hasIssues(),
                "tryConvertToReadLock succeeds only for a stamp that still validates, so the "
                        + "optimistic read was validated by the conversion: " + detector.analyze());
    }

    @Test
    @DisplayName("an optimistic conversion that failed, with no fallback, is an ignored failed validation")
    void aFailedOptimisticConversionWithNoFallbackFires() {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();
        detector.registerLock(lock, "point");

        long optimistic = lock.tryOptimisticRead();
        detector.recordOptimisticRead(lock, "point", optimistic);
        long writer = lock.writeLock(); // a writer intervenes
        long read = lock.tryConvertToReadLock(optimistic);
        detector.recordConversion(lock, "point", optimistic, read);
        assertEquals(0L, read);
        lock.unlockWrite(writer);
        // The stale optimistic value is used as read: no read lock, no retry.

        StampedLockDetector.StampedLockReport report = detector.analyze();
        assertTrue(report.hasIssues(), report.toString());
        assertTrue(report.toString().contains("failed or zero-stamp"),
                "a failed conversion is a failed validation, not an unvalidated read: " + report);
    }

    @Test
    @DisplayName("an optimistic conversion that failed and fell back to readLock stays silent")
    void aFailedOptimisticConversionWithAFallbackIsSilent() {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();
        detector.registerLock(lock, "point");

        long optimistic = lock.tryOptimisticRead();
        detector.recordOptimisticRead(lock, "point", optimistic);
        long writer = lock.writeLock();
        long converted = lock.tryConvertToReadLock(optimistic);
        detector.recordConversion(lock, "point", optimistic, converted);
        lock.unlockWrite(writer);
        long read = lock.readLock();
        detector.recordReadLock(lock, "point", read);
        lock.unlockRead(read);
        detector.recordUnlock(lock, "point", read);

        assertFalse(detector.analyze().hasIssues(), detector.analyze().toString());
    }

    @Test
    @DisplayName("a read lock upgraded to write and leaked is reported against the write stamp")
    void anUpgradeThenLeakIsCountedAgainstTheConvertedStamp() {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();
        detector.registerLock(lock, "ledger");

        long read = lock.readLock();
        detector.recordReadLock(lock, "ledger", read);
        long write = lock.tryConvertToWriteLock(read);
        detector.recordConversion(lock, "ledger", read, write);
        assertNotEquals(0L, write, "the only reader can upgrade");
        // The write is never released.

        StampedLockDetector.StampedLockReport report = detector.analyze();
        assertTrue(report.hasIssues(), report.toString());
        assertTrue(report.toString().contains("1 recorded write stamp(s) never released"),
                "the body gave up the read stamp in the conversion; what leaked is the write: "
                        + report);
    }

    @Test
    @DisplayName("a write lock downgraded to read and leaked is reported against the read stamp")
    void aDowngradeThenLeakIsCountedAgainstTheConvertedStamp() {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();
        detector.registerLock(lock, "ledger");

        long write = lock.writeLock();
        detector.recordWriteLock(lock, "ledger", write);
        long read = lock.tryConvertToReadLock(write);
        detector.recordConversion(lock, "ledger", write, read);

        StampedLockDetector.StampedLockReport report = detector.analyze();
        assertTrue(report.hasIssues(), report.toString());
        assertTrue(report.toString().contains("1 recorded read stamp(s) never released"),
                "the downgrade released the write; the read it returned is what leaked: " + report);
    }

    @Test
    @DisplayName("an upgrade whose converted stamp is released stays silent")
    void anUpgradeReleasedThroughTheConvertedStampIsSilent() {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();
        detector.registerLock(lock, "ledger");

        long stamp = lock.readLock();
        detector.recordReadLock(lock, "ledger", stamp);
        try {
            long write = lock.tryConvertToWriteLock(stamp);
            detector.recordConversion(lock, "ledger", stamp, write);
            if (write != 0L) {
                stamp = write;
            }
        } finally {
            lock.unlock(stamp);
            detector.recordUnlock(lock, "ledger", stamp);
        }
        // A reader the subject took and still holds keeps the lock busy at analysis; the
        // retired read stamp must not be counted as its leak.
        long other = lock.readLock();

        assertFalse(detector.analyze().hasIssues(),
                "every recorded stamp came back; the hold at analysis was never recorded: "
                        + detector.analyze());
        lock.unlockRead(other);
    }

    @Test
    @DisplayName("a lock converted to an optimistic read is released, and the optimistic stamp is not judged")
    void aConversionToOptimisticReleasesTheLock() {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();
        detector.registerLock(lock, "cache");

        long write = lock.writeLock();
        detector.recordWriteLock(lock, "cache", write);
        long optimistic = lock.tryConvertToOptimisticRead(write);
        detector.recordConversion(lock, "cache", write, optimistic);
        // Data written under the lock is still valid; whether a later read validates is not
        // something this conversion says. Another reader holds the lock at analysis.
        long other = lock.readLock();

        assertFalse(detector.analyze().hasIssues(),
                "the write went back in the conversion, and a downgrade to optimistic is not a "
                        + "read that needs validating: " + detector.analyze());
        lock.unlockRead(other);
    }

    @Test
    @DisplayName("a failed upgrade leaves the read stamp held, and releasing it stays silent")
    void aFailedUpgradeKeepsTheReadStamp() {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();
        detector.registerLock(lock, "ledger");

        long mine = lock.readLock();
        detector.recordReadLock(lock, "ledger", mine);
        long theirs = lock.readLock();
        detector.recordReadLock(lock, "ledger", theirs);
        long write = lock.tryConvertToWriteLock(mine);
        detector.recordConversion(lock, "ledger", mine, write);
        assertEquals(0L, write, "two readers: the upgrade fails and the read is still held");
        lock.unlockRead(mine);
        detector.recordUnlock(lock, "ledger", mine);
        lock.unlockRead(theirs);
        detector.recordUnlock(lock, "ledger", theirs);

        assertFalse(detector.analyze().hasIssues(), detector.analyze().toString());
    }

    @Test
    @DisplayName("a read stamp released twice while another reader holds the lock fires")
    void aReadStampReleasedTwiceWhileAnotherReaderHoldsFires() {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();
        detector.registerLock(lock, "index");

        long first = lock.readLock();
        detector.recordReadLock(lock, "index", first);
        long second = lock.readLock();
        detector.recordReadLock(lock, "index", second);
        lock.unlockRead(first);
        detector.recordUnlock(lock, "index", first);
        lock.unlockRead(first); // the defect: a second release, which the lock accepts
        detector.recordUnlock(lock, "index", first);

        StampedLockDetector.StampedLockReport report = detector.analyze();
        assertTrue(report.hasIssues(), report.toString());
        assertTrue(report.toString().contains("released twice"),
                "the second release took the other reader's hold, which the lock never refused: "
                        + report);
    }

    @Test
    @DisplayName("a read stamp released from another thread is legal and stays silent")
    void aReleaseFromAnotherThreadIsSilent() throws InterruptedException {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();
        detector.registerLock(lock, "handoff");

        long stamp = lock.readLock();
        detector.recordReadLock(lock, "handoff", stamp);
        Thread releaser = new Thread(() -> {
            lock.unlockRead(stamp);
            detector.recordUnlock(lock, "handoff", stamp);
        });
        releaser.start();
        releaser.join();

        assertFalse(detector.analyze().hasIssues(),
                "StampedLock has no owner thread; handing a stamp to another thread to release "
                        + "is allowed: " + detector.analyze());
    }

    @Test
    @DisplayName("a read stamp value reused by a later hold is released once per hold and stays silent")
    void aReusedStampValueIsSilent() {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();
        detector.registerLock(lock, "index");

        long earlier = lock.readLock();
        detector.recordReadLock(lock, "index", earlier);
        lock.unlockRead(earlier);
        detector.recordUnlock(lock, "index", earlier);
        long later = lock.readLock();
        detector.recordReadLock(lock, "index", later);
        assertEquals(earlier, later, "with no write in between the lock hands out the same value");
        lock.unlockRead(later);
        detector.recordUnlock(lock, "index", later);

        assertFalse(detector.analyze().hasIssues(), detector.analyze().toString());
    }

    @Test
    @DisplayName("a release whose acquisition was never recorded is a recording gap, not a double release")
    void anUnrecordedAcquisitionReleasedWhileAnotherReaderHoldsIsSilent() {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();
        detector.registerLock(lock, "index");

        long unrecorded = lock.readLock(); // taken inside the subject, never recorded
        long recorded = lock.readLock();
        detector.recordReadLock(lock, "index", recorded);
        lock.unlockRead(unrecorded);
        detector.recordUnlock(lock, "index", unrecorded);
        lock.unlockRead(recorded);
        detector.recordUnlock(lock, "index", recorded);

        assertFalse(detector.analyze().hasIssues(),
                "every hold the lock handed out was released exactly once: " + detector.analyze());
    }
}
