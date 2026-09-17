package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CyclicBarrierDetector.
 */
public class CyclicBarrierDetectorTest {

    @Test
    void testNormalBarrierUsage() throws Exception {
        CyclicBarrierDetector detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = new CyclicBarrier(3);

        detector.registerBarrier(barrier, "normalBarrier", 3);
        detector.recordArrival(barrier);
        detector.recordArrival(barrier);
        detector.recordArrival(barrier);
        detector.recordBarrierComplete(barrier);

        CyclicBarrierDetector.CyclicBarrierReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Normal usage should not report issues");
    }

    @Test
    void testRecordedTimeoutAloneIsNotAFinding() {
        CyclicBarrierDetector detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = new CyclicBarrier(3);

        detector.registerBarrier(barrier, "timeoutBarrier", 3);
        detector.recordArrival(barrier);
        detector.recordArrival(barrier);
        // A handled timeout: the caller backs off or resets; nothing awaits the barrier again (#595).
        detector.recordTimeout(barrier);

        CyclicBarrierDetector.CyclicBarrierReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "A recorded timeout with no later await on a broken barrier is not a finding");
    }

    /** A two-party barrier broken for real, by a timed await that cannot succeed. */
    private static CyclicBarrier brokenBarrier() {
        CyclicBarrier barrier = new CyclicBarrier(2);
        assertThrows(TimeoutException.class, () -> barrier.await(1, TimeUnit.NANOSECONDS));
        return barrier;
    }

    @Test
    void testRecordedBreakAloneIsNotAFinding() {
        CyclicBarrierDetector detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = new CyclicBarrier(2);

        detector.registerBarrier(barrier, "brokenBarrier", 2);
        detector.recordArrival(barrier);
        detector.recordBroken(barrier);  // a break alone: cancelling by breaking is correct (#584)

        CyclicBarrierDetector.CyclicBarrierReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "A recorded break with no later await is not a finding");
    }

    @Test
    void testMultiCycleBarrier() throws Exception {
        CyclicBarrierDetector detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = new CyclicBarrier(2);

        detector.registerBarrier(barrier, "multiCycle", 2);

        CyclicBarrierDetector.CyclicBarrierReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Multi-cycle usage should work correctly");
    }

    @Test
    void testReportToString() {
        CyclicBarrierDetector detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = brokenBarrier();

        detector.registerBarrier(barrier, "testBarrier", 2);
        detector.recordTimeout(barrier);   // context: what broke it
        detector.recordAwait(barrier);     // the finding: awaited while still broken

        CyclicBarrierDetector.CyclicBarrierReport report = detector.analyze();

        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("CYCLICBARRIER ISSUES DETECTED"), "Report should have header");
        assertTrue(reportStr.contains("a timeout was recorded earlier"), "Report should name the timeout as the cause");
    }

    @Test
    void testAwaitOnBrokenBarrierIsFlagged() {
        CyclicBarrierDetector detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = brokenBarrier();

        detector.registerBarrier(barrier, "reuseAfterBrokenBarrier", 2);
        detector.recordArrival(barrier);
        detector.recordBroken(barrier);
        detector.recordAwait(barrier);  // Reused without reset - should be flagged

        CyclicBarrierDetector.CyclicBarrierReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect reuse of a broken barrier");
        assertTrue(report.getReuseAfterBrokenBarriers().contains(barrier),
            "Barrier should be tracked as reused after broken");
    }

    @Test
    void testAwaitAfterResetIsNotFlagged() {
        CyclicBarrierDetector detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = new CyclicBarrier(2);

        detector.registerBarrier(barrier, "resetBarrier", 2);
        detector.recordArrival(barrier);
        detector.recordBroken(barrier);
        detector.recordReset(barrier);  // Repaired before reuse
        detector.recordAwait(barrier);

        CyclicBarrierDetector.CyclicBarrierReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.getReuseAfterBrokenBarriers().contains(barrier),
            "Barrier reset before reuse should not be flagged");
        assertFalse(report.hasIssues(), "Reset barrier reused correctly should not report issues");
    }

    @Test
    void testAwaitOnNeverBrokenBarrierIsNotFlagged() {
        CyclicBarrierDetector detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = new CyclicBarrier(2);

        detector.registerBarrier(barrier, "healthyBarrier", 2);
        detector.recordArrival(barrier);
        detector.recordAwait(barrier);
        detector.recordAwait(barrier);
        detector.recordBarrierComplete(barrier);

        CyclicBarrierDetector.CyclicBarrierReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.getReuseAfterBrokenBarriers().contains(barrier),
            "Barrier that never broke should not be flagged");
        assertFalse(report.hasIssues(), "Healthy barrier usage should not report issues");
    }

    @Test
    void testReuseAfterBrokenDescribedInReport() {
        CyclicBarrierDetector detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = brokenBarrier();

        detector.registerBarrier(barrier, "describedBarrier", 2);
        detector.recordArrival(barrier);
        detector.recordBroken(barrier);
        detector.recordAwait(barrier);

        CyclicBarrierDetector.CyclicBarrierReport report = detector.analyze();

        String reportStr = report.toString();
        assertTrue(reportStr.contains("Reuse After Broken Barriers"), "Report should have a reuse section");
        assertTrue(reportStr.contains("BrokenBarrierException"), "Report should explain the hazard");
        assertTrue(reportStr.contains("reset()"), "Report should mention the fix");
    }

    @Test
    void nullBarrierIsIgnoredOnEveryRecordPath() {
        CyclicBarrierDetector detector = new CyclicBarrierDetector();
        assertDoesNotThrow(() -> {
            detector.registerBarrier(null, "n", 2);
            detector.recordArrival(null);
            detector.recordTimeout(null);
            detector.recordBroken(null);
            detector.recordReset(null);
            detector.recordAwait(null);
            detector.recordBarrierComplete(null);
        });
    }

    @Test
    void strandedBarrierDetectedDirectlyAtAnalysis() throws Exception {
        CyclicBarrierDetector detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = new CyclicBarrier(3);
        detector.registerBarrier(barrier, "strandedBarrier");

        Runnable untimedParty = () -> {
            detector.recordAwait(barrier);
            try {
                barrier.await();
            } catch (Exception ignored) {
            }
        };
        Thread p1 = new Thread(untimedParty);
        Thread p2 = new Thread(untimedParty);
        p1.setDaemon(true);
        p2.setDaemon(true);
        p1.start();
        p2.start();

        awaitParked(barrier, 2, p1, p2);

        CyclicBarrierDetector.CyclicBarrierReport report = detector.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.getStrandedBarriers().contains(barrier));
        assertEquals(2, report.getWaitingParties(barrier));
        assertTrue(report.toString().contains("Stranded Barriers (party short)"));
        assertTrue(report.toString().contains("2 of 3 parties waiting"));

        barrier.reset();
        p1.join(1000);
        p2.join(1000);
    }

    @Test
    void strandedBarrierDetectedViaMarkRoundTimedOut() throws Exception {
        CyclicBarrierDetector detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = new CyclicBarrier(2);
        detector.registerBarrier(barrier, "timedOutBarrier", 2);

        Thread p1 = new Thread(() -> {
            detector.recordAwait(barrier);
            try {
                barrier.await();
            } catch (Exception ignored) {
            }
        });
        p1.setDaemon(true);
        p1.start();

        awaitParked(barrier, 1, p1);

        detector.markRoundTimedOut();

        // Worker interrupted by runner timeout, breaking barrier
        p1.interrupt();
        p1.join(1000);
        assertEquals(0, barrier.getNumberWaiting(), "premise: worker interrupted and left barrier");

        CyclicBarrierDetector.CyclicBarrierReport report = detector.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.getStrandedBarriers().contains(barrier));
        assertEquals(1, report.getWaitingParties(barrier));
        assertTrue(report.toString().contains("Stranded Barriers (party short)"));
        assertTrue(report.toString().contains("1 of 2 parties waiting"));
    }

    @Test
    void autoRegistrationOnRecordArrivalMaintainsParties() throws Exception {
        CyclicBarrierDetector detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = new CyclicBarrier(2);

        Thread p1 = new Thread(() -> {
            detector.recordArrival(barrier);
            try {
                barrier.await();
            } catch (Exception ignored) {
            }
        });
        p1.setDaemon(true);
        p1.start();

        awaitParked(barrier, 1, p1);

        CyclicBarrierDetector.CyclicBarrierReport report = detector.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.getStrandedBarriers().contains(barrier));
        assertTrue(report.toString().contains("<unregistered barrier>"));
        assertTrue(report.toString().contains("1 of 2 parties waiting"));

        barrier.reset();
        p1.join(1000);
    }

    /**
     * A barrier action runs holding the barrier's lock, so {@code getNumberWaiting()} and
     * {@code isBroken()} block while it runs. The runner calls {@code markRoundTimedOut()} exactly
     * when a round is stuck, which is when an action may be stuck too; if the probe takes that lock
     * the runner never cancels its workers and the test hangs instead of failing.
     */
    @Test
    void roundTimeoutProbeReturnsWhileABarrierActionHoldsTheBarrierLock() throws Exception {
        CyclicBarrierDetector detector = new CyclicBarrierDetector();
        CountDownLatch actionStarted = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        CyclicBarrier barrier = new CyclicBarrier(1, () -> {
            actionStarted.countDown();
            try {
                releaseAction.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        detector.registerBarrier(barrier, "blocked-action", 1);

        Thread party = new Thread(() -> {
            detector.recordAwait(barrier);
            try {
                barrier.await();
            } catch (Exception ignored) {
            }
        });
        party.setDaemon(true);
        party.start();
        try {
            assertTrue(actionStarted.await(5, TimeUnit.SECONDS), "premise: the barrier action is running");

            AtomicReference<CyclicBarrierDetector.CyclicBarrierReport> report = new AtomicReference<>();
            Thread probe = new Thread(() -> {
                detector.markRoundTimedOut();
                report.set(detector.analyze());
            });
            probe.setDaemon(true);
            probe.start();
            probe.join(2000);

            assertFalse(probe.isAlive(),
                    "markRoundTimedOut() and analyze() must not wait for the barrier lock a blocked action holds");
            assertFalse(report.get().getStrandedBarriers().contains(barrier),
                    "every party arrived; a running action is not a party short: " + report.get());
        } finally {
            releaseAction.countDown();
            party.join(2000);
        }
    }

    @Test
    void timedAwaitStillParkedAtTimeoutIsNotStranded() throws Exception {
        CyclicBarrierDetector detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = new CyclicBarrier(3);
        detector.registerBarrier(barrier, "timed-waiters", 3);

        Runnable timedParty = () -> {
            detector.recordAwait(barrier);
            try {
                barrier.await(30, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        };
        Thread p1 = new Thread(timedParty);
        Thread p2 = new Thread(timedParty);
        p1.setDaemon(true);
        p2.setDaemon(true);
        p1.start();
        p2.start();
        try {
            awaitParked(barrier, 2, p1, p2);

            detector.markRoundTimedOut();
            CyclicBarrierDetector.CyclicBarrierReport report = detector.analyze();

            assertFalse(report.hasIssues(),
                    "a timed await ends on its own and breaks the barrier, so it is not stranded: " + report);
        } finally {
            barrier.reset();
            p1.join(1000);
            p2.join(1000);
        }
    }

    @Test
    void untimedWaiterThatRecordedNothingIsNotReported() throws Exception {
        CyclicBarrierDetector detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = new CyclicBarrier(2);
        detector.registerBarrier(barrier, "unrecorded-waiter", 2);

        Thread outsider = new Thread(() -> {
            try {
                barrier.await();
            } catch (Exception ignored) {
            }
        });
        outsider.setDaemon(true);
        outsider.start();
        try {
            awaitParked(barrier, 1, outsider);

            detector.markRoundTimedOut();
            CyclicBarrierDetector.CyclicBarrierReport report = detector.analyze();

            assertFalse(report.hasIssues(),
                    "only a waiter that recorded its await is attributed to the barrier: " + report);
        } finally {
            barrier.reset();
            outsider.join(1000);
        }
    }

    private static void awaitParked(CyclicBarrier barrier, int waiting, Thread... parties) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!allParked(parties) || barrier.getNumberWaiting() < waiting) {
            if (System.nanoTime() > deadline) {
                fail("premise: " + waiting + " parties parked on the barrier");
            }
            Thread.onSpinWait();
        }
    }

    private static boolean allParked(Thread... parties) {
        for (Thread t : parties) {
            Thread.State state = t.getState();
            if (state != Thread.State.WAITING && state != Thread.State.TIMED_WAITING) {
                return false;
            }
        }
        return true;
    }
}