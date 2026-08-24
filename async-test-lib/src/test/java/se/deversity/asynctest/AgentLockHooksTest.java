package se.deversity.asynctest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.HeldLocks;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.StampedLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
