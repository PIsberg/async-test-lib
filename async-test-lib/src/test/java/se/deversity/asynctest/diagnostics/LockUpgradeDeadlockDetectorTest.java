package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import static org.junit.jupiter.api.Assertions.*;

class LockUpgradeDeadlockDetectorTest {

    @Test
    void cleanWhenNoUpgrade() {
        var d = new LockUpgradeDeadlockDetector();
        var lock = new ReentrantReadWriteLock();
        d.recordReadLockAcquired(lock, "my-lock", Thread.currentThread());
        d.recordReadLockReleased(lock, Thread.currentThread());
        d.recordWriteLockAcquisitionAttempt(lock, "my-lock", Thread.currentThread());
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void deadlockWhenUpgradeAttempted() {
        var d = new LockUpgradeDeadlockDetector();
        var lock = new ReentrantReadWriteLock();
        d.recordReadLockAcquired(lock, "my-lock", Thread.currentThread());
        d.recordWriteLockAcquisitionAttempt(lock, "my-lock", Thread.currentThread());

        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("my-lock"));
        assertTrue(msg.contains("upgrade attempt detected"));
        assertEquals(1, report.structuredViolations.size());
        assertEquals("LockUpgradeDeadlock", report.structuredViolations.get(0).detector());
        assertEquals(IssueSeverity.HIGH, report.structuredViolations.get(0).severity());
    }

    @Test
    void nullLockAndThreadAreIgnored() {
        var d = new LockUpgradeDeadlockDetector();
        var lock = new ReentrantReadWriteLock();
        d.recordReadLockAcquired(null, "my-lock", Thread.currentThread());
        d.recordReadLockAcquired(lock, "my-lock", null);
        d.recordReadLockReleased(null, Thread.currentThread());
        d.recordReadLockReleased(lock, null);
        d.recordWriteLockAcquisitionAttempt(null, "my-lock", Thread.currentThread());
        d.recordWriteLockAcquisitionAttempt(lock, "my-lock", null);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void releaseWithoutPriorAcquireIsIgnored() {
        var d = new LockUpgradeDeadlockDetector();
        var lock = new ReentrantReadWriteLock();
        d.recordReadLockReleased(lock, Thread.currentThread());
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void writeAttemptWithoutHoldingReadLockIsIgnored() {
        var d = new LockUpgradeDeadlockDetector();
        var lock = new ReentrantReadWriteLock();
        d.recordWriteLockAcquisitionAttempt(lock, "my-lock", Thread.currentThread());
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void missingLockNameFallsBackToIdentity() {
        var d = new LockUpgradeDeadlockDetector();
        var lock = new ReentrantReadWriteLock();
        d.recordReadLockAcquired(lock, "my-lock", Thread.currentThread());
        d.recordWriteLockAcquisitionAttempt(lock, null, Thread.currentThread());

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("ReentrantReadWriteLock@"));
    }

    @Test
    void reportToStringReflectsState() {
        var clean = new LockUpgradeDeadlockDetector().analyze();
        assertEquals("LOCK UPGRADE DEADLOCK — clean", clean.toString());

        var d = new LockUpgradeDeadlockDetector();
        var lock = new ReentrantReadWriteLock();
        d.recordReadLockAcquired(lock, "my-lock", Thread.currentThread());
        d.recordWriteLockAcquisitionAttempt(lock, "my-lock", Thread.currentThread());
        String rendered = d.analyze().toString();
        assertTrue(rendered.contains("LOCK UPGRADE DEADLOCK DETECTED"));
        assertTrue(rendered.contains("my-lock"));
    }

    /**
     * Two read-write locks whose identity hashes collide are two locks. Keyed by the bare hash, a
     * declared read hold on one made a write attempt on the other look like an upgrade.
     */
    @Test
    void readHoldOnOneLockDoesNotMakeAWriteOnAnotherSharingItsIdentityHashAnUpgrade() {
        LockUpgradeDeadlockDetector detector = new LockUpgradeDeadlockDetector();
        java.util.List<ReentrantReadWriteLock> colliding =
                IdentityCollisions.pair(ReentrantReadWriteLock::new);
        Thread self = Thread.currentThread();

        detector.recordReadLockAcquired(colliding.get(0), "first", self);
        detector.recordWriteLockAcquisitionAttempt(colliding.get(1), "second", self);

        LockUpgradeDeadlockDetector.Report report = detector.analyze();
        assertFalse(report.hasIssues(), "the read and the write were on different locks: " + report);
    }

    /** The same declared sequence on one lock is the upgrade, and still fires. */
    @Test
    void declaredReadThenWriteOnTheSameLockIsStillAnUpgrade() {
        LockUpgradeDeadlockDetector detector = new LockUpgradeDeadlockDetector();
        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        Thread self = Thread.currentThread();

        detector.recordReadLockAcquired(lock, "only", self);
        detector.recordWriteLockAcquisitionAttempt(lock, "only", self);

        assertTrue(detector.analyze().hasIssues());
    }

    /**
     * Virtual threads are unnamed by default, so a report that names threads by name printed ""
     * for every one of them and could not tell two apart (#766). Each is now named by its id.
     */
    @Test
    void unnamedVirtualThreadsAreReportedByIdNotByAnEmptyName() {
        LockUpgradeDeadlockDetector detector = new LockUpgradeDeadlockDetector();
        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        Thread first = Thread.ofVirtual().unstarted(() -> { });
        Thread second = Thread.ofVirtual().unstarted(() -> { });
        assertEquals("", first.getName(), "precondition: a default virtual thread has no name");

        for (Thread t : java.util.List.of(first, second)) {
            detector.recordReadLockAcquired(lock, "my-lock", t);
            detector.recordWriteLockAcquisitionAttempt(lock, "my-lock", t);
        }

        LockUpgradeDeadlockDetector.Report report = detector.analyze();
        String msg = report.violations.get(0);
        assertTrue(msg.contains("#" + first.threadId()) && msg.contains("#" + second.threadId()),
                "each unnamed thread must be told apart by its id: " + msg);
        assertEquals(2,
                ((java.util.List<?>) report.structuredViolations.get(0).attributes()
                        .get("deadlockedThreads")).size(),
                "two threads, two entries: " + report.structuredViolations.get(0).attributes());
    }

    /** A named thread is still reported by its name. */
    @Test
    void namedThreadsAreStillReportedByName() {
        LockUpgradeDeadlockDetector detector = new LockUpgradeDeadlockDetector();
        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        Thread worker = new Thread(() -> { }, "worker-1");

        detector.recordReadLockAcquired(lock, "my-lock", worker);
        detector.recordWriteLockAcquisitionAttempt(lock, "my-lock", worker);

        String msg = detector.analyze().violations.get(0);
        assertTrue(msg.contains("worker-1"), "a named thread keeps its name: " + msg);
    }
}
