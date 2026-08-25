package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Example demonstrating LockUpgradeDeadlockDetector flagging read-to-write lock upgrades.
 */
class LockUpgradeDeadlockTest {

    private ReentrantReadWriteLock rwLock;

    @BeforeEach
    void setUp() {
        rwLock = new ReentrantReadWriteLock();
    }

    @Test
    void testNormalReadWrite_doesNotFlag() {
        rwLock.readLock().lock();
        try {
            // Read stuff
        } finally {
            rwLock.readLock().unlock();
        }

        rwLock.writeLock().lock();
        try {
            // Write stuff
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Disabled("Remove @Disabled to see the bug detected by LockUpgradeDeadlockDetector")
    @AsyncTest(threads = 2, invocations = 5, detectAll = false, detectLockUpgradeDeadlock = true, failOn = FailOn.LOW)
    void test_concurrent_detectsLockUpgradeDeadlock() {
        var mon = AsyncTestContext.lockUpgradeDeadlockDetector();
        Thread thread = Thread.currentThread();

        // Safe actions would not try to acquire write lock while holding read lock
        rwLock.readLock().lock();
        mon.recordReadLockAcquired(rwLock, "shared-rwlock", thread);
        try {
            // Bug: Attempting to upgrade to write lock while holding the read lock (will deadlock)
            mon.recordWriteLockAcquisitionAttempt(rwLock, "shared-rwlock", thread);
            if (rwLock.writeLock().tryLock()) {
                try {
                    // Write stuff
                } finally {
                    rwLock.writeLock().unlock();
                }
            }
        } finally {
            rwLock.readLock().unlock();
            mon.recordReadLockReleased(rwLock, thread);
        }
    }
}
