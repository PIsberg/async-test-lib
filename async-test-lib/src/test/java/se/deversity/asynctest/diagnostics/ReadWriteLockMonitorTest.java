package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReadWriteLockMonitorTest {

    @Test
    void noLocksReturnNoFairnessIssues() {
        ReadWriteLockMonitor monitor = new ReadWriteLockMonitor();
        ReadWriteLockMonitor.ReadWriteLockReport report = monitor.analyzeFairness();
        assertFalse(report.hasFairnessIssues());
    }

    @Test
    void balancedReadWriteNoIssues() {
        ReadWriteLockMonitor monitor = new ReadWriteLockMonitor();
        Object lock = new Object();
        monitor.registerLock(lock, "balancedLock");
        monitor.recordReadLockAcquired(lock, 1L);
        monitor.recordReadLockReleased(lock);
        monitor.recordWriteLockAcquired(lock, 1L);
        monitor.recordWriteLockReleased(lock);
        ReadWriteLockMonitor.ReadWriteLockReport report = monitor.analyzeFairness();
        assertFalse(report.hasFairnessIssues());
    }

    @Test
    void reportHasFairnessIssuesFalseByDefault() {
        ReadWriteLockMonitor monitor = new ReadWriteLockMonitor();
        ReadWriteLockMonitor.ReadWriteLockReport report = monitor.analyzeFairness();
        assertFalse(report.hasFairnessIssues());
        assertTrue(report.readerDominatedLocks.isEmpty());
        assertTrue(report.starvedWriters.isEmpty());
        assertTrue(report.longWriteWaits.isEmpty());
    }

    @Test
    void registerAndAnalyzeNoIssues() {
        ReadWriteLockMonitor monitor = new ReadWriteLockMonitor();
        Object lock = new Object();
        monitor.registerLock(lock, "myRWLock");
        monitor.recordReadLockAcquired(lock, 0L);
        monitor.recordReadLockReleased(lock);
        ReadWriteLockMonitor.ReadWriteLockReport report = monitor.analyzeFairness();
        assertNotNull(report);
        assertFalse(report.hasFairnessIssues());
    }

    @Test
    void reportToStringNoIssues() {
        ReadWriteLockMonitor monitor = new ReadWriteLockMonitor();
        ReadWriteLockMonitor.ReadWriteLockReport report = monitor.analyzeFairness();
        String text = report.toString();
        assertNotNull(text);
        assertFalse(text.isBlank());
    }

    @Test
    void resetClearsState() {
        ReadWriteLockMonitor monitor = new ReadWriteLockMonitor();
        Object lock = new Object();
        monitor.registerLock(lock, "resetLock");
        monitor.recordWriteLockAcquired(lock, 500L);
        monitor.recordWriteLockReleased(lock);
        monitor.reset();
        ReadWriteLockMonitor.ReadWriteLockReport report = monitor.analyzeFairness();
        assertFalse(report.hasFairnessIssues());
        assertTrue(report.currentWriteHolders.isEmpty());
        assertTrue(report.currentReadHolders.isEmpty());
    }

    @Test
    void nullLockHandled() {
        ReadWriteLockMonitor monitor = new ReadWriteLockMonitor();
        assertDoesNotThrow(() -> monitor.registerLock(null, "nullLock"));
        assertDoesNotThrow(() -> monitor.recordReadLockAcquired(null, 0L));
        assertDoesNotThrow(() -> monitor.recordReadLockReleased(null));
    }

    @Test
    void analyze_delegatesToAnalyzeFairness() {
        ReadWriteLockMonitor monitor = new ReadWriteLockMonitor();
        Object lock = new Object();
        monitor.registerLock(lock, "lock1");
        monitor.recordWriteLockAcquired(lock, 150L);

        ReadWriteLockMonitor.ReadWriteLockReport viaAnalyze = monitor.analyze();
        ReadWriteLockMonitor.ReadWriteLockReport viaAnalyzeFairness = monitor.analyzeFairness();

        assertEquals(viaAnalyzeFairness.hasFairnessIssues(), viaAnalyze.hasFairnessIssues());
        assertEquals(viaAnalyzeFairness.toString(), viaAnalyze.toString());
    }

    /**
     * Two read-write locks whose identity hashes collide are two locks. Keyed by the bare hash,
     * the second registration was dropped as a duplicate and every read taken on the second lock
     * was counted, and reported, under the first lock's name.
     */
    @Test
    void locksSharingAnIdentityHashAreReportedUnderTheirOwnNames() {
        ReadWriteLockMonitor monitor = new ReadWriteLockMonitor();
        java.util.List<Object> colliding = IdentityCollisions.pair(Object::new);
        monitor.registerLock(colliding.get(0), "first");
        monitor.registerLock(colliding.get(1), "second");

        for (int i = 0; i < 30; i++) {
            monitor.recordReadLockAcquired(colliding.get(1), 0);
            monitor.recordReadLockReleased(colliding.get(1));
        }
        monitor.recordWriteLockAcquired(colliding.get(1), 0);
        monitor.recordWriteLockReleased(colliding.get(1));

        ReadWriteLockMonitor.ReadWriteLockReport report = monitor.analyzeFairness();
        assertEquals(1, report.readerDominatedLocks.size(), report.toString());
        assertTrue(report.readerDominatedLocks.iterator().next().startsWith("second:"),
                "the reads were taken on the lock registered as 'second': " + report.readerDominatedLocks);
    }
}
