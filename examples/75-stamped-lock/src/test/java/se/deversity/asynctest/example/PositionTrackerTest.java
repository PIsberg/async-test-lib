package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.PositionTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for PositionTracker.
 *
 * ========================================================================
 * DETECTOR: StampedLockDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - Sequential @Test PASSES (single thread never blocks itself)
 * - The same scenario with @AsyncTest FAILS (exposes unreleased write stamp)
 *
 * THE BUG:
 * PositionTracker.moveTo() acquires a StampedLock write lock but never calls
 * unlockWrite(stamp). The first invocation permanently holds the write lock.
 * All subsequent threads calling moveTo() block indefinitely on writeLock().
 *
 * WHY @Test PASSES:
 * A single thread calling moveTo() holds the write lock and then exits the
 * method. The JVM does not automatically release StampedLock stamps at
 * method exit, but with one thread there are no contenders to block.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * StampedLockDetector.recordWriteLock() and recordStampNotReleased() track
 * every stamp lifecycle. When the detector finds stamps acquired but never
 * passed to recordUnlock(), the analysis report flags them as leaked stamps.
 *
 * DETECTORS TRIGGERED:
 *   StampedLockDetector — primary: detects unreleased write/read stamps
 *
 * FIX: wrap the write-lock body in a try/finally and call unlockWrite(stamp)
 *      in the finally block.
 */
class PositionTrackerTest {

    private PositionTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new PositionTracker();
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, passes cleanly (no contender to block)
    // -----------------------------------------------------------------------

    @Test
    void test_singleThread_moveTo_updatesCoordinates() {
        tracker.moveTo(1.0, 2.0);
        // After one call the lock is held but with no other threads it does
        // not manifest as a deadlock.
        assertNotNull(tracker.getLock());
    }

    @Test
    void test_singleThread_getX_returnsZeroInitially() {
        assertEquals(0.0, tracker.getX(), 0.001);
        assertEquals(0.0, tracker.getY(), 0.001);
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes unreleased write stamp
    // -----------------------------------------------------------------------

    @Disabled("Remove @Disabled to see unreleased StampedLock stamp detected by StampedLockDetector")
    @AsyncTest(threads = 8, invocations = 2, detectAll = false,
            detectStampedLockIssues = true, failOn = FailOn.LOW)

    void test_concurrent_detectsUnreleasedStamp() throws InterruptedException {
        var detector = AsyncTestContext.get().stampedLockMonitor();
        var lock = tracker.getLock();

        detector.registerLock(lock, "position-lock");

        // The previous version took lock.writeLock() here and then called moveTo(), which takes
        // it again. StampedLock is not reentrant, so the very first thread deadlocked against
        // itself: nothing was recorded, and the round timed out with an empty report. See
        // issue #363.
        //
        // Bounded, because moveTo() never gives the stamp back. The first caller returns and
        // leaves the lock write-locked forever, so every later caller would park in writeLock().
        // tryWriteLock asks the same question with a deadline, and its expiry is the leak
        // observed from outside - which is what StampedLockReport.hasIssues() gates on.
        long probe = lock.tryWriteLock(100, TimeUnit.MILLISECONDS);
        if (probe == 0L) {
            detector.recordStampNotReleased("position-lock", 0L);
            return;
        }

        // Hand the probe straight back: moveTo() has to take the lock itself, because taking it
        // and not releasing it is the bug being demonstrated.
        lock.unlockWrite(probe);

        tracker.moveTo(Thread.currentThread().threadId(),
                Thread.currentThread().threadId() * 2.0);
        detector.recordWriteLock(lock, "position-lock", 0L);

        // Stamp 0 is not a placeholder: tryOptimisticRead() returns 0 while the lock is
        // write-held, which is exactly the state moveTo() has just left behind.
        if (lock.isWriteLocked()) {
            detector.recordStampNotReleased("position-lock", lock.tryOptimisticRead());
        }
    }
}
