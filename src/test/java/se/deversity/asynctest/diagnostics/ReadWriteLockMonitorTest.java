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
}
