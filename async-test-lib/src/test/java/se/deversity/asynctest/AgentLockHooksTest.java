package se.deversity.asynctest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.HeldLocks;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.StampedLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the woven lock hooks tell {@link HeldLocks}, asked through the fingerprints the telemetry
 * path records.
 *
 * <p>Every case runs on the test thread, acquires through the hook exactly as woven code would,
 * and asks the per-mode fingerprint the producer asks. The read-write and stamped cases pin the
 * one property that makes them worth modelling: both views resolve to the owner, so a reader and
 * a writer share a lockset, while shared mode never guards a write.
 */
class AgentLockHooksTest {

    @AfterEach
    void clearThreadState() {
        HeldLocks.clear();
    }

    @Test
    @DisplayName("a write stamp is exclusive, a read stamp is shared, and unlock releases either")
    void stampedModesFollowTheStamp() {
        StampedLock stamped = new StampedLock();

        long writeStamp = AgentLockHooks.writeLock(stamped);
        long underWrite = HeldLocks.lockFingerprint(true);
        assertNotEquals(0L, underWrite, "a write stamp must guard a write");
        AgentLockHooks.unlockWrite(stamped, writeStamp);
        assertEquals(0L, HeldLocks.lockFingerprint(true), "unlockWrite must release the entry");

        long readStamp = AgentLockHooks.readLock(stamped);
        assertEquals(underWrite, HeldLocks.lockFingerprint(false),
                "the read stamp resolves to the same lock identity the write stamp did");
        assertEquals(0L, HeldLocks.lockFingerprint(true),
                "a read stamp admits other readers, so it guards no write");
        AgentLockHooks.unlock(stamped, readStamp);
        assertEquals(0L, HeldLocks.lockFingerprint(false),
                "the mode-blind unlock releases whatever the thread held: the lock is not reentrant");
    }

    @Test
    @DisplayName("a failed try records nothing")
    void failedTryRecordsNothing() {
        StampedLock stamped = new StampedLock();
        long writeStamp = AgentLockHooks.writeLock(stamped);

        assertEquals(0L, AgentLockHooks.tryWriteLock(stamped),
                "StampedLock is not reentrant, so the same thread's try must fail");
        assertEquals(0L, AgentLockHooks.tryReadLock(stamped),
                "a write holder cannot also take the read lock");
        long single = HeldLocks.lockFingerprint(true);
        AgentLockHooks.unlockWrite(stamped, writeStamp);
        assertNotEquals(0L, single, "the original write entry must have been undisturbed");
        assertEquals(0L, HeldLocks.lockFingerprint(true), "and released exactly once");
    }

    @Test
    @DisplayName("conversions move the one entry between modes, and optimistic is nothing")
    void conversionsFollowTheProtocol() {
        StampedLock stamped = new StampedLock();

        long readStamp = AgentLockHooks.readLock(stamped);
        assertEquals(0L, HeldLocks.lockFingerprint(true), "a read stamp guards no write");

        long writeStamp = AgentLockHooks.tryConvertToWriteLock(stamped, readStamp);
        assertNotEquals(0L, writeStamp, "the sole reader can upgrade");
        assertNotEquals(0L, HeldLocks.lockFingerprint(true), "after upgrading, writes are guarded");

        long observeStamp = AgentLockHooks.tryConvertToOptimisticRead(stamped, writeStamp);
        assertNotEquals(0L, observeStamp, "downgrading to an observation succeeds");
        assertEquals(0L, HeldLocks.lockFingerprint(false),
                "an optimistic read holds nothing, so nothing may remain in the set");
        assertTrue(stamped.validate(observeStamp), "nothing wrote, so the observation validates");
    }

    @Test
    @DisplayName("read-write and stamped views resolve to their owner with the mode kept apart")
    void viewsResolveToTheirOwner() {
        ReentrantReadWriteLock readWrite = new ReentrantReadWriteLock();

        ReentrantReadWriteLock.WriteLock writeView = AgentLockHooks.writeLock(readWrite);
        AgentLockHooks.lock(writeView);
        long exclusive = HeldLocks.lockFingerprint(true);
        assertNotEquals(0L, exclusive, "the write view guards writes");
        AgentLockHooks.unlock(writeView);

        ReentrantReadWriteLock.ReadLock readView = AgentLockHooks.readLock(readWrite);
        AgentLockHooks.lock(readView);
        assertEquals(exclusive, HeldLocks.lockFingerprint(false),
                "reader and writer share the owner's identity, or they could never agree");
        assertEquals(0L, HeldLocks.lockFingerprint(true), "the read view guards no write");
        AgentLockHooks.unlock(readView);

        StampedLock stamped = new StampedLock();
        long stampedExclusive;
        long stamp = AgentLockHooks.writeLock(stamped);
        stampedExclusive = HeldLocks.lockFingerprint(true);
        AgentLockHooks.unlockWrite(stamped, stamp);

        Lock stampedReadView = AgentLockHooks.asReadLock(stamped);
        AgentLockHooks.lock(stampedReadView);
        assertEquals(stampedExclusive, HeldLocks.lockFingerprint(false),
                "the Lock view of a StampedLock resolves to the same owner its stamps do");
        assertEquals(0L, HeldLocks.lockFingerprint(true), "and stays shared");
        AgentLockHooks.unlock(stampedReadView);
    }

    @Test
    @DisplayName("the interruptible and timed Lock hooks acquire like lock() and release like unlock()")
    void interruptibleAndTimedLockHooks() throws InterruptedException {
        ReentrantLock lock = new ReentrantLock();

        AgentLockHooks.lockInterruptibly(lock);
        long held = HeldLocks.lockFingerprint(true);
        assertNotEquals(0L, held, "lockInterruptibly must record the acquisition");
        assertTrue(lock.isHeldByCurrentThread(), "and really take the lock");
        AgentLockHooks.unlock(lock);
        assertEquals(0L, HeldLocks.lockFingerprint(true), "unlock releases it");

        assertTrue(AgentLockHooks.tryLock(lock), "a free lock is taken by the untimed try");
        assertEquals(held, HeldLocks.lockFingerprint(true), "and recorded under the same identity");
        AgentLockHooks.unlock(lock);

        assertTrue(AgentLockHooks.tryLock(lock, 10, TimeUnit.MILLISECONDS),
                "a free lock is taken by the timed try");
        assertEquals(held, HeldLocks.lockFingerprint(true), "and recorded the same way");
        AgentLockHooks.unlock(lock);
        assertEquals(0L, HeldLocks.lockFingerprint(true), "nothing is left held");
    }

    @Test
    @DisplayName("a try that another thread defeats records nothing, on both Lock forms")
    void defeatedTriesRecordNothing() throws InterruptedException {
        ReentrantLock lock = new ReentrantLock();
        CountDownLatch taken = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            lock.lock();
            taken.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }, "lock-holder");
        holder.start();
        taken.await();
        try {
            assertFalse(AgentLockHooks.tryLock(lock), "the lock is held elsewhere");
            assertFalse(AgentLockHooks.tryLock(lock, 1, TimeUnit.MILLISECONDS),
                    "and the timed try times out on it");
            assertEquals(0L, HeldLocks.lockFingerprint(true), "a failed try leaves nothing behind");
        } finally {
            release.countDown();
            holder.join();
        }
    }

    @Test
    @DisplayName("the interruptible, timed and read-releasing StampedLock hooks follow the stamp")
    void stampedInterruptibleAndTimedHooks() throws InterruptedException {
        StampedLock stamped = new StampedLock();

        long write = AgentLockHooks.writeLockInterruptibly(stamped);
        long exclusive = HeldLocks.lockFingerprint(true);
        assertNotEquals(0L, exclusive, "an interruptible write stamp guards a write");
        AgentLockHooks.unlockWrite(stamped, write);

        long read = AgentLockHooks.readLockInterruptibly(stamped);
        assertEquals(exclusive, HeldLocks.lockFingerprint(false),
                "an interruptible read stamp is shared, under the same identity");
        AgentLockHooks.unlockRead(stamped, read);
        assertEquals(0L, HeldLocks.lockFingerprint(false), "unlockRead releases the shared entry");

        long timedWrite = AgentLockHooks.tryWriteLock(stamped, 10, TimeUnit.MILLISECONDS);
        assertNotEquals(0L, timedWrite, "a timed write try on a free lock succeeds");
        assertEquals(exclusive, HeldLocks.lockFingerprint(true), "and records the write");
        AgentLockHooks.unlockWrite(stamped, timedWrite);

        long timedRead = AgentLockHooks.tryReadLock(stamped, 10, TimeUnit.MILLISECONDS);
        assertNotEquals(0L, timedRead, "a timed read try on a free lock succeeds");
        assertEquals(exclusive, HeldLocks.lockFingerprint(false), "and records the read");
        AgentLockHooks.unlockRead(stamped, timedRead);
        assertEquals(0L, HeldLocks.lockFingerprint(false), "nothing is left held");
    }

    @Test
    @DisplayName("a write stamp converted to a read stamp moves from exclusive to shared")
    void conversionToReadFollowsTheProtocol() {
        StampedLock stamped = new StampedLock();
        long write = AgentLockHooks.writeLock(stamped);
        long exclusive = HeldLocks.lockFingerprint(true);

        long read = AgentLockHooks.tryConvertToReadLock(stamped, write);
        assertNotEquals(0L, read, "a write holder can always downgrade");
        assertEquals(0L, HeldLocks.lockFingerprint(true), "no write is guarded any more");
        assertEquals(exclusive, HeldLocks.lockFingerprint(false), "the same lock is now held shared");
        AgentLockHooks.unlockRead(stamped, read);
        assertEquals(0L, HeldLocks.lockFingerprint(false), "and released");
    }

    @Test
    @DisplayName("the interface-typed read-write views and the stamped write view resolve to their owner")
    void interfaceViewsResolveToTheirOwner() {
        ReentrantReadWriteLock readWrite = new ReentrantReadWriteLock();
        ReadWriteLock asInterface = readWrite;

        Lock writeView = AgentLockHooks.writeLock(asInterface);
        AgentLockHooks.lock(writeView);
        long exclusive = HeldLocks.lockFingerprint(true);
        assertNotEquals(0L, exclusive, "the interface-typed write view guards writes");
        AgentLockHooks.unlock(writeView);

        Lock readView = AgentLockHooks.readLock(asInterface);
        AgentLockHooks.lock(readView);
        assertEquals(exclusive, HeldLocks.lockFingerprint(false), "the read view shares the owner");
        assertEquals(0L, HeldLocks.lockFingerprint(true), "and guards no write");
        AgentLockHooks.unlock(readView);

        StampedLock stamped = new StampedLock();
        long stamp = AgentLockHooks.writeLock(stamped);
        long stampedExclusive = HeldLocks.lockFingerprint(true);
        AgentLockHooks.unlockWrite(stamped, stamp);

        Lock stampedWriteView = AgentLockHooks.asWriteLock(stamped);
        AgentLockHooks.lock(stampedWriteView);
        assertEquals(stampedExclusive, HeldLocks.lockFingerprint(true),
                "the write view of a StampedLock is its owner, held exclusive");
        AgentLockHooks.unlock(stampedWriteView);
        assertEquals(0L, HeldLocks.lockFingerprint(true), "and released");
    }
}
