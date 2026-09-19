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
    @DisplayName("a party that awaits a broken barrier a second time with no reset between is reported, recorded break or not")
    void awaitOnABrokenBarrierFires() {
        var detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = brokenBarrier();   // broken by a timeout nobody recorded
        detector.registerBarrier(barrier, "reused", 2);

        for (int attempt = 0; attempt < 2; attempt++) {
            detector.recordAwait(barrier);   // no recordBroken: the barrier alone decides
            assertThrows(BrokenBarrierException.class, barrier::await,
                    "premise: the await fails immediately on a broken barrier");
        }

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "coming back to a barrier already seen broken fails again: " + report);
        assertTrue(report.getReuseAfterBrokenBarriers().contains(barrier));
    }

    @Test
    @DisplayName("#665: one arrival at a broken barrier is not reported; the party cannot know until its await throws")
    void singleArrivalAtABrokenBarrierIsSilent() {
        var detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = brokenBarrier();
        detector.registerBarrier(barrier, "arrived", 2);

        detector.recordArrival(barrier);
        detector.recordAwait(barrier);   // the same attempt: an arrival and its await are one arrival

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "a single arrival that hits the break is not reuse: " + report);
    }

    @Test
    @DisplayName("#665: a late party that catches BrokenBarrierException from a cancelled barrier and drops it, no reset, is not reported")
    void lateArrivalAtACancelledBarrierDroppedWithoutResetIsSilent() throws Exception {
        var detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = new CyclicBarrier(3);
        detector.registerBarrier(barrier, "cancelled-late", 3);

        Thread early = new Thread(() -> {
            detector.recordArrival(barrier);
            detector.recordAwait(barrier);
            try {
                barrier.await();
            } catch (BrokenBarrierException | InterruptedException e) {
                detector.recordBroken(barrier);
            }
        });
        early.setDaemon(true);
        early.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (barrier.getNumberWaiting() == 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(barrier.getNumberWaiting() == 1, "premise: the early party is waiting");
        early.interrupt();   // cancel: interrupting a waiting party breaks the barrier for every party
        early.join(5000);
        assertTrue(barrier.isBroken(), "premise: the barrier is left broken, and nobody resets it");

        // The late party arrives, its await throws, and it handles that by dropping the barrier.
        detector.recordArrival(barrier);
        detector.recordAwait(barrier);
        try {
            barrier.await();
            throw new AssertionError("premise: the await fails on a broken barrier");
        } catch (BrokenBarrierException e) {
            detector.recordBroken(barrier);   // and never touches the barrier again
        }

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                "cancelling by breaking a barrier and dropping it is correct for every party: " + report);
    }

    @Test
    @DisplayName("#665: parties that each arrive once at a broken barrier are not reported; reuse is per party")
    void differentPartiesArrivingOnceEachAreSilent() throws Exception {
        var detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = brokenBarrier();
        detector.registerBarrier(barrier, "one-arrival-each", 2);

        Runnable party = () -> {
            detector.recordAwait(barrier);
            try {
                barrier.await();
            } catch (BrokenBarrierException e) {
                detector.recordBroken(barrier);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        for (int i = 0; i < 3; i++) {
            Thread t = new Thread(party);
            t.start();
            t.join(5000);
        }

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "no party came back to the broken barrier: " + report);
    }

    @Test
    @DisplayName("#665: a barrier seen whole between a party's two arrivals was reset, recorded or not")
    void barrierSeenWholeBetweenTwoArrivalsIsSilent() throws Exception {
        var detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = brokenBarrier();
        detector.registerBarrier(barrier, "healed-unrecorded", 2);

        detector.recordAwait(barrier);   // this party sees it broken once
        assertThrows(BrokenBarrierException.class, barrier::await);

        Thread coordinator = new Thread(() -> {
            barrier.reset();                  // resets it without recording the reset
            detector.recordArrival(barrier);  // and the next recorded arrival finds it whole
        });
        coordinator.start();
        coordinator.join(5000);
        assertThrows(TimeoutException.class, () -> barrier.await(1, TimeUnit.NANOSECONDS));

        detector.recordAwait(barrier);   // broken again, by a break this party had not seen

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                "the barrier was seen whole between the two arrivals, so a reset happened: " + report);
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

        // Later the barrier breaks again, unrecorded, and is awaited twice with no reset (#665).
        assertThrows(TimeoutException.class, () -> barrier.await(1, TimeUnit.NANOSECONDS));
        detector.recordAwait(barrier);
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

    // --- #662: a handled break followed by reset() is recovery, not reuse -----------------------

    @Test
    @DisplayName("a party that arrives at a broken barrier, catches BrokenBarrierException and resets it is not reported")
    void arrivalAtABrokenBarrierHandledWithResetIsSilent() {
        var detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = brokenBarrier();   // a timeout broke it before this party arrived
        detector.registerBarrier(barrier, "handled-break", 2);

        detector.recordArrival(barrier);
        detector.recordAwait(barrier);
        try {
            barrier.await();
            throw new AssertionError("premise: the await fails on a broken barrier");
        } catch (BrokenBarrierException e) {
            detector.recordBroken(barrier);
            detector.recordReset(barrier);   // the fix the reuse report itself prescribes
            barrier.reset();
        } catch (InterruptedException e) {
            throw new AssertionError("premise: nothing interrupts this thread", e);
        }
        assertFalse(barrier.isBroken(), "premise: reset() repaired the barrier");

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "a handled break followed by reset() is correct: " + report);
    }

    @Test
    @DisplayName("a party that catches BrokenBarrierException and awaits the still-broken barrier again is reported")
    void brokenBarrierRetriedWithoutResetFires() {
        var detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = brokenBarrier();
        detector.registerBarrier(barrier, "retried-break", 2);

        for (int attempt = 0; attempt < 2; attempt++) {
            detector.recordAwait(barrier);
            assertThrows(BrokenBarrierException.class, barrier::await,
                    "premise: nothing reset the barrier, so every await fails at once");
            detector.recordBroken(barrier);
        }

        var report = detector.analyze();
        assertTrue(report.getReuseAfterBrokenBarriers().contains(barrier),
                "retrying a barrier nobody reset fails every attempt: " + report);
    }

    @Test
    @DisplayName("#665: after a reset, a party's next arrival at a barrier broken again is its first, not reuse")
    void arrivalAfterAResetThatWasFollowedByAnotherBreakIsSilent() {
        var detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = brokenBarrier();
        detector.registerBarrier(barrier, "broke-again", 2);

        detector.recordAwait(barrier);
        detector.recordReset(barrier);
        barrier.reset();

        assertThrows(TimeoutException.class, () -> barrier.await(1, TimeUnit.NANOSECONDS));
        detector.recordAwait(barrier);   // broken again, by a break the party learns of only here

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "the reset closed what the party had seen: " + report);
    }

    @Test
    @DisplayName("a reset recovers only what was seen before it: a later break awaited twice without a reset is reported")
    void reuseAfterAResetThatWasFollowedByAnotherBreakFires() {
        var detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = brokenBarrier();
        detector.registerBarrier(barrier, "broke-again-twice", 2);

        detector.recordAwait(barrier);
        detector.recordReset(barrier);
        barrier.reset();

        assertThrows(TimeoutException.class, () -> barrier.await(1, TimeUnit.NANOSECONDS));
        detector.recordAwait(barrier);   // broken again: the first arrival since the reset
        detector.recordAwait(barrier);   // and back again with nobody resetting it this time

        assertTrue(detector.analyze().getReuseAfterBrokenBarriers().contains(barrier));
    }
}
