package se.deversity.asynctest.diagnostics;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "The barrier was used after it broke" is a claim about the barrier, so it is decided by asking
 * the barrier (#584).
 *
 * <p>{@code recordBroken} used to be the finding: a barrier broken on purpose to cancel its
 * parties, then discarded, was reported CRITICAL, and a barrier that really was broken when a
 * party awaited it was silent unless the body had also recorded the break.
 */
class CyclicBarrierDetectorAccuracyTest {

    /** Breaks a two-party barrier for real, on the calling thread, with a wait that cannot succeed. */
    private static CyclicBarrier brokenBarrier() {
        CyclicBarrier barrier = new CyclicBarrier(2);
        assertThrows(TimeoutException.class, () -> barrier.await(1, TimeUnit.NANOSECONDS));
        assertTrue(barrier.isBroken(), "premise: a timed-out await breaks the barrier");
        return barrier;
    }

    @Test
    @DisplayName("a barrier broken on purpose to cancel its parties, then discarded, is not reported")
    void brokenOnPurposeAndDiscardedIsSilent() throws Exception {
        var detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = new CyclicBarrier(2);
        detector.registerBarrier(barrier, "cancelled", 2);

        CountDownLatch released = new CountDownLatch(1);
        Thread party = new Thread(() -> {
            detector.recordArrival(barrier);
            detector.recordAwait(barrier);
            try {
                barrier.await();
            } catch (BrokenBarrierException e) {
                detector.recordBroken(barrier);
                released.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        party.setDaemon(true);
        party.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (barrier.getNumberWaiting() == 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(barrier.getNumberWaiting() == 1, "premise: the party is waiting at the barrier");

        detector.recordReset(barrier);
        barrier.reset();   // cancel: the waiting party leaves with BrokenBarrierException
        assertTrue(released.await(5, TimeUnit.SECONDS), "premise: the reset released the party");

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                "breaking a barrier to cancel its parties and dropping it is correct: " + report);
    }

    @Test
    @DisplayName("an await on a barrier that is broken is reported, recorded break or not")
    void awaitOnABrokenBarrierFires() {
        var detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = brokenBarrier();   // broken by a timeout nobody recorded
        detector.registerBarrier(barrier, "reused", 2);

        detector.recordAwait(barrier);
        assertThrows(BrokenBarrierException.class, barrier::await,
                "premise: the await fails immediately on a broken barrier");

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "an await on a broken barrier fails every caller: " + report);
        assertTrue(report.getReuseAfterBrokenBarriers().contains(barrier));
    }

    @Test
    @DisplayName("an arrival at a barrier that is broken is reported the same as an await")
    void arrivalAtABrokenBarrierFires() {
        var detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = brokenBarrier();
        detector.registerBarrier(barrier, "arrived", 2);

        detector.recordArrival(barrier);

        assertTrue(detector.analyze().getReuseAfterBrokenBarriers().contains(barrier));
    }

    @Test
    @DisplayName("a recorded break is not a finding when the barrier is not broken at the await")
    void recordedBreakOnAHealthyBarrierIsSilent() {
        var detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = new CyclicBarrier(2);
        detector.registerBarrier(barrier, "declared", 2);

        detector.recordBroken(barrier);   // the body says so; the barrier says otherwise
        detector.recordAwait(barrier);

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "the barrier was never broken: " + report);
    }

    @Test
    @DisplayName("an await after reset() repaired a broken barrier is not reported")
    void awaitAfterResetIsSilent() {
        var detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = brokenBarrier();
        detector.registerBarrier(barrier, "repaired", 2);
        detector.recordBroken(barrier);

        barrier.reset();
        detector.recordReset(barrier);
        detector.recordAwait(barrier);

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "reset() repaired the barrier before the await: " + report);
    }

    @Test
    @DisplayName("a reset that releases waiting parties counts as a break in the reuse report")
    void resetWithWaitingPartiesCountsAsABreak() throws Exception {
        var detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = new CyclicBarrier(2);
        detector.registerBarrier(barrier, "reset-while-waiting", 2);

        CountDownLatch released = new CountDownLatch(1);
        Thread party = new Thread(() -> {
            try {
                barrier.await();
            } catch (BrokenBarrierException e) {
                released.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        party.setDaemon(true);
        party.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (barrier.getNumberWaiting() == 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        detector.recordReset(barrier);   // one party is waiting: this reset breaks it
        barrier.reset();
        assertTrue(released.await(5, TimeUnit.SECONDS), "premise: the reset released the party");

        // Later the barrier breaks again, unrecorded, and is awaited.
        assertThrows(TimeoutException.class, () -> barrier.await(1, TimeUnit.NANOSECONDS));
        detector.recordAwait(barrier);

        String rendered = detector.analyze().toString();
        assertTrue(rendered.contains("a break was recorded"),
                () -> "the reset with a waiting party is a recorded break: " + rendered);
    }

    // --- #595: a recorded timeout is the caller's declaration, not a finding --------------------

    @Test
    @DisplayName("a timed await whose TimeoutException is handled with reset() is not reported")
    void handledTimeoutFollowedByResetIsSilent() {
        var detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = new CyclicBarrier(2);
        detector.registerBarrier(barrier, "timed-then-reset", 2);

        detector.recordArrival(barrier);
        detector.recordAwait(barrier);
        try {
            barrier.await(1, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            detector.recordTimeout(barrier);   // the fix the report itself prescribes
            detector.recordReset(barrier);
            barrier.reset();
        } catch (InterruptedException | BrokenBarrierException e) {
            throw new AssertionError("premise: a lone party times out", e);
        }

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "a handled timeout followed by reset() is correct: " + report);
    }

    @Test
    @DisplayName("a timed await whose TimeoutException is handled and the barrier dropped is not reported")
    void handledTimeoutWithBarrierDiscardedIsSilent() {
        var detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = new CyclicBarrier(3);
        detector.registerBarrier(barrier, "timed-then-dropped", 3);

        detector.recordArrival(barrier);
        detector.recordArrival(barrier);
        assertThrows(TimeoutException.class, () -> barrier.await(1, TimeUnit.NANOSECONDS));
        detector.recordTimeout(barrier);   // backs off; nobody touches this barrier again

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "a timeout that is handled and not followed by reuse is correct: " + report);
    }

    @Test
    @DisplayName("a timeout that leaves the barrier broken, then an await on it, is reported and names the timeout")
    void timeoutThenAwaitOnTheBrokenBarrierFires() {
        var detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = new CyclicBarrier(2);
        detector.registerBarrier(barrier, "timed-then-reused", 2);

        assertThrows(TimeoutException.class, () -> barrier.await(1, TimeUnit.NANOSECONDS));
        detector.recordTimeout(barrier);
        detector.recordAwait(barrier);   // no reset: this await fails at once
        assertThrows(BrokenBarrierException.class, barrier::await, "premise: the barrier is still broken");

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "an await on the barrier the timeout broke fails every caller: " + report);
        assertTrue(report.getReuseAfterBrokenBarriers().contains(barrier));
        String rendered = report.toString();
        assertTrue(rendered.contains("a timeout was recorded earlier"),
                () -> "the report says what broke the barrier: " + rendered);
    }
}
