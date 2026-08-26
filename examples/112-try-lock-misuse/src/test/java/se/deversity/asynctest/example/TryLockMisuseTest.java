package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.diagnostics.TryLockMisuseDetector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Example demonstrating TryLockMisuseDetector flagging unsafe unlock calls.
 *
 * <p>THE BUG: {@code tryLock()} returns whether the lock was taken, and code that ignores the
 * answer and calls {@code unlock()} anyway is not releasing anything. On a lock this thread does
 * not hold, {@code unlock()} throws {@code IllegalMonitorStateException}; on a re-entrant hold it
 * silently drops somebody's count. The detector reports the pair: a {@code tryLock()} that
 * returned false followed by an {@code unlock()} from the same thread.
 *
 * <p>The lock is held for the length of the demonstration by a thread that is not part of the
 * run, so {@code tryLock()} fails for every worker every time. Before that, two workers raced for
 * an empty critical section and both usually won, so nothing was ever recorded as unacquired and
 * the demonstration passed. It fired in one run of three in the 2026-08-25 audit and in none of
 * three on 2026-08-26, which is what put it on {@code .github/known-silent-demos.txt}. A
 * demonstration that needs luck is a poor demonstration whether or not it is a correct one. See
 * issue #362.
 */
class TryLockMisuseTest {

    private ReentrantLock lock;
    private Thread holder;
    private final CountDownLatch held = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws InterruptedException {
        lock = new ReentrantLock();
        holder = new Thread(() -> {
            lock.lock();
            try {
                held.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }, "lock-holder");
        holder.setDaemon(true);
        holder.start();
        assertTrue(held.await(5, TimeUnit.SECONDS), "the holder must have the lock before the test runs");
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        release.countDown();
        holder.join(5_000);
    }

    // -------------------------------------------------------------------------
    // Part 1: what the detector says about correct code
    // -------------------------------------------------------------------------

    @Test
    void testCorrectTryLock_doesNotFlag() {
        ReentrantLock uncontended = new ReentrantLock();
        TryLockMisuseDetector detector = new TryLockMisuseDetector();
        Thread thread = Thread.currentThread();

        boolean acquired = uncontended.tryLock();
        detector.recordTryLockResult(uncontended, "uncontended", acquired, thread);
        assertTrue(acquired, "an uncontended tryLock() succeeds");
        try {
            // Critical section
        } finally {
            uncontended.unlock();
            detector.recordUnlock(uncontended, "uncontended", thread);
        }

        assertFalse(detector.analyze().hasIssues(),
                "unlocking a lock tryLock() actually gave you is correct, and must not be flagged");
    }

    @Test
    void testUnlockAfterFailedTryLock_isFlagged() {
        TryLockMisuseDetector detector = new TryLockMisuseDetector();
        Thread thread = Thread.currentThread();

        boolean acquired = lock.tryLock();
        assertFalse(acquired, "the holder thread has it, so this must fail");
        detector.recordTryLockResult(lock, "shared-lock", acquired, thread);
        detector.recordUnlock(lock, "shared-lock", thread);

        assertTrue(detector.analyze().hasIssues(),
                "unlock() after a tryLock() that returned false is the misuse this reports");
    }

    // -------------------------------------------------------------------------
    // Part 2: the demonstration
    // -------------------------------------------------------------------------

    @Disabled("Remove @Disabled to see the unchecked unlock detected by TryLockMisuseDetector")
    @AsyncTest(threads = 2, invocations = 5, detectAll = false, detectTryLockMisuse = true,
            failOn = FailOn.LOW)
    void test_concurrent_detectsTryLockMisuse() {
        var mon = AsyncTestContext.tryLockMisuseDetector();
        Thread thread = Thread.currentThread();

        // Fails every time: the holder thread started in setUp() has the lock.
        boolean acquired = lock.tryLock();
        mon.recordTryLockResult(lock, "shared-lock", acquired, thread);

        try {
            // The work this thread believes it is doing under a lock it does not hold.
        } finally {
            // BUG: unlock() without looking at what tryLock() returned.
            //
            // The exception is the bug behaving as documented, and it is absorbed rather than
            // propagated: letting it escape fails the run before the failOn gate is reached, so
            // the reader gets java.util.concurrent's stack trace where the detector's report
            // should be. See issue #363.
            try {
                lock.unlock();
            } catch (IllegalMonitorStateException notHeld) {
                // Exactly what unlocking a lock you never took does.
            }
            mon.recordUnlock(lock, "shared-lock", thread);
        }
    }
}
