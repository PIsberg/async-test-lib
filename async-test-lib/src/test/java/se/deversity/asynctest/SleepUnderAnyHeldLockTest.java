package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.HeldLocks;
import se.deversity.asynctest.diagnostics.SleepInLockDetector.SleepInLockReport;

import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.StampedLock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A sleep is reported under whichever held lock can be confirmed, not only the innermost one.
 *
 * <p>The woven {@code Thread.sleep} used to hand {@code SleepInLockDetector} the top of the
 * thread's lockset and nothing else (#543). Two shapes were dropped as a result: a
 * {@code StampedLock} on top, which keeps no owner, and a confirmable {@code ReentrantLock} one
 * entry below an unconfirmable one. Both are a thread sleeping inside a critical section every
 * other caller queues behind.
 *
 * <p>Each case drives the lockset through {@link AgentLockHooks}, the same calls the weaver
 * substitutes, so what is under test is the path a user's code takes with the agent attached.
 */
class SleepUnderAnyHeldLockTest {

    @Test
    @DisplayName("a sleep holding a StampedLock write lock is reported")
    void reportsASleepUnderAStampedWriteLock() {
        StampedLock lock = new StampedLock();
        assertTrue(report(() -> {
            long stamp = AgentLockHooks.writeLock(lock);
            try {
                AgentSleepHooks.sleep(2L);
            } finally {
                AgentLockHooks.unlockWrite(lock, stamp);
            }
        }).hasIssues(), "a writer sleeping with the stamp held blocks every reader and writer");
    }

    @Test
    @DisplayName("a sleep holding a StampedLock read lock is reported")
    void reportsASleepUnderAStampedReadLock() {
        StampedLock lock = new StampedLock();
        assertTrue(report(() -> {
            long stamp = AgentLockHooks.readLock(lock);
            try {
                AgentSleepHooks.sleep(2L);
            } finally {
                AgentLockHooks.unlockRead(lock, stamp);
            }
        }).hasIssues(), "a reader sleeping with the stamp held blocks every writer");
    }

    @Test
    @DisplayName("an unconfirmable lock on top does not hide a confirmable one below it")
    void reportsTheConfirmableLockBelowTheTop() {
        ReentrantLock outer = new ReentrantLock();
        StampedLock inner = new StampedLock();
        SleepInLockReport report = report(() -> {
            AgentLockHooks.lock(outer);
            try {
                // Declared on the lockset but never actually taken, so the top entry is one the
                // detector cannot confirm by any means and the outer lock is the only evidence.
                HeldLocks.acquired(inner);
                try {
                    AgentSleepHooks.sleep(2L);
                } finally {
                    HeldLocks.released(inner);
                }
            } finally {
                AgentLockHooks.unlock(outer);
            }
        });
        assertTrue(report.hasIssues(),
                "the thread holds a ReentrantLock for the whole sleep; the entry above it cannot "
                        + "be confirmed and must not stop the walk. Report: " + report);
        assertTrue(report.toString().contains("ReentrantLock"),
                "and the report names the lock that was confirmed: " + report);
    }

    @Test
    @DisplayName("a StampedLock on the lockset that nobody holds is not evidence")
    void staysSilentForADeclaredStampedLockNobodyHolds() {
        StampedLock lock = new StampedLock();
        assertFalse(report(() -> {
            HeldLocks.acquired(lock);
            try {
                AgentSleepHooks.sleep(2L);
            } finally {
                HeldLocks.released(lock);
            }
        }).hasIssues(), "the lockset says held and the lock says nobody holds it in any mode, so "
                + "recording would be taking the lockset's word over the lock's");
    }

    @Test
    @DisplayName("a sleep after every lock was released is not reported")
    void staysSilentAfterRelease() {
        StampedLock lock = new StampedLock();
        assertFalse(report(() -> {
            long stamp = AgentLockHooks.writeLock(lock);
            AgentLockHooks.unlockWrite(lock, stamp);
            AgentSleepHooks.sleep(2L);
        }).hasIssues(), "nothing is held while this thread sleeps");
    }

    /** A body that sleeps, which may be interrupted in principle. */
    @FunctionalInterface
    private interface Sleeping {
        void run() throws InterruptedException;
    }

    /** {@return the sleep-in-lock report {@code body} produced inside an installed context} */
    private static SleepInLockReport report(Sleeping body) {
        AsyncTestConfig cfg = AsyncTestConfig.builder().detectSleepInLock(true).build();
        AsyncTestContext.install(new AsyncTestContext(cfg));
        try {
            AsyncTestContext.sleepInLockDetector().startMonitoring();
            body.run();
            return AsyncTestContext.sleepInLockDetector().analyze();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("nothing here interrupts", e);
        } finally {
            AsyncTestContext.uninstall();
        }
    }
}
