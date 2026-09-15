package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link ReentrantLockDetector} reports, decided against a real {@link ReentrantLock} (#589).
 *
 * <p>Before #589 the two findings were the caller's own declarations: any {@code recordLockTimeout}
 * was a finding, so a {@code tryLock(t)} that timed out and was handled (back off, report busy) drew
 * the same HIGH as one whose false return was discarded, and {@code recordStarvation} fired for any
 * wait, including none, against a threshold its javadoc described and the code never had. The
 * finding the detector can stand behind is one the lock itself confirms: a hold still taken when
 * the run is analysed, by a thread other than the one analysing.
 */
class ReentrantLockDetectorModelTest {

    /** Runs {@code work} on a fresh thread and waits for it to finish. */
    static void onAnotherThread(Runnable work) throws InterruptedException {
        Thread worker = new Thread(work, "reentrant-lock-model-worker");
        worker.start();
        worker.join(10_000);
        assertFalse(worker.isAlive(), "the worker did not finish");
    }

    /** The CounterService shape: lock twice on one path, unlock once, with a balanced record pair. */
    static void leakOneHold(ReentrantLockDetector detector, ReentrantLock lock) {
        lock.lock();
        detector.recordLockAcquired(lock, "worker");
        try {
            lock.lock(); // re-entered by a helper that never unlocks
        } finally {
            detector.recordLockReleased(lock, "worker");
            lock.unlock();
        }
    }

    @Test
    @DisplayName("a tryLock timeout the caller handles is not a finding")
    void aHandledTryLockTimeoutIsNotAFinding() throws InterruptedException {
        ReentrantLockDetector detector = new ReentrantLockDetector();
        ReentrantLock lock = new ReentrantLock();
        detector.registerLock(lock, "busy-lock");

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            lock.lock();
            try {
                held.countDown();
                done.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }, "holder");
        holder.start();
        assertTrue(held.await(10, TimeUnit.SECONDS));

        AtomicBoolean backedOff = new AtomicBoolean();
        onAnotherThread(() -> {
            try {
                if (lock.tryLock(10, TimeUnit.MILLISECONDS)) {
                    lock.unlock();
                } else {
                    detector.recordLockTimeout(lock); // the fix: give up and say so
                    backedOff.set(true);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        done.countDown();
        holder.join(10_000);

        assertTrue(backedOff.get(), "the premise: the tryLock really timed out");
        ReentrantLockDetector.ReentrantLockReport report = detector.analyze();
        assertFalse(report.hasIssues(),
                "the caller handled the false return and the lock is free at analysis. Whether a "
                        + "timeout was discarded is TRY_LOCK_MISUSE's question; this detector cannot "
                        + "see it. Report:\n" + report);
        assertTrue(report.toString().contains("busy-lock"),
                "the timeout is still printed as context: " + report);
    }

    @Test
    @DisplayName("recordStarvation with no wait at all is not a finding")
    void aStarvationRecordWithNoWaitIsNotAFinding() {
        ReentrantLockDetector detector = new ReentrantLockDetector();
        detector.recordStarvation("worker-1", 0);
        detector.recordStarvation("worker-2", -5);

        assertFalse(detector.analyze().hasIssues(),
                "a wait of zero or less is not a wait, so it cannot be starvation. Report:\n"
                        + detector.analyze());
    }

    @Test
    @DisplayName("recordStarvation applies no threshold: any recorded wait is the caller's finding")
    void aRecordedStarvationIsReportedWithoutAThreshold() {
        ReentrantLockDetector detector = new ReentrantLockDetector();
        detector.recordStarvation("worker-1", 1);

        ReentrantLockDetector.ReentrantLockReport report = detector.analyze();
        assertTrue(report.hasIssues(),
                "the javadoc says the caller decides what counts as starvation; the detector does "
                        + "not second-guess it with a duration of its own");
        assertTrue(report.toString().contains("worker-1"), report::toString);
    }

    @Test
    @DisplayName("a hold still taken at analysis, by a thread that has finished, fires")
    void aLeakedHoldFires() throws InterruptedException {
        ReentrantLockDetector detector = new ReentrantLockDetector();
        ReentrantLock lock = new ReentrantLock();
        detector.registerLock(lock, "counter-lock");

        // The recorded pair is balanced, which is exactly why counts cannot see this; the lock can.
        onAnotherThread(() -> leakOneHold(detector, lock));

        ReentrantLockDetector.ReentrantLockReport report = detector.analyze();
        assertTrue(lock.isLocked(), "the premise: the hold really leaked");
        assertTrue(report.hasIssues(),
                "the worker is gone and the lock is still taken, so every later caller parks "
                        + "forever. Report:\n" + report);
        assertTrue(report.toString().contains("counter-lock"), report::toString);
    }

    @Test
    @DisplayName("a lock every thread released is silent")
    void aReleasedLockIsSilent() throws InterruptedException {
        ReentrantLockDetector detector = new ReentrantLockDetector();
        ReentrantLock lock = new ReentrantLock();
        detector.registerLock(lock, "clean-lock");
        onAnotherThread(() -> {
            lock.lock();
            detector.recordLockAcquired(lock, "worker");
            try {
                lock.lock();
                lock.unlock();
            } finally {
                detector.recordLockReleased(lock, "worker");
                lock.unlock();
            }
        });

        assertFalse(detector.analyze().hasIssues(), detector.analyze()::toString);
    }

    @Test
    @DisplayName("a lock the analysing thread itself holds is not reported")
    void aLockHeldByTheAnalysingThreadIsNotReported() {
        ReentrantLockDetector detector = new ReentrantLockDetector();
        ReentrantLock lock = new ReentrantLock();
        detector.registerLock(lock, "callers-own-lock");
        lock.lock();
        try {
            assertFalse(detector.analyze().hasIssues(),
                    "the caller analysing under its own lock has not leaked anything: "
                            + detector.analyze());
        } finally {
            lock.unlock();
        }
    }

    @Test
    @DisplayName("a timed-out lock nobody registered is still checked for a leaked hold")
    void anUnregisteredTimedOutLockIsCheckedForALeakedHold() throws InterruptedException {
        ReentrantLockDetector detector = new ReentrantLockDetector();
        ReentrantLock lock = new ReentrantLock();
        onAnotherThread(lock::lock); // taken and never given back
        detector.recordLockTimeout(lock);

        ReentrantLockDetector.ReentrantLockReport report = detector.analyze();
        assertTrue(report.hasIssues(),
                "the timeout was the symptom and the leaked hold is the cause. Report:\n" + report);
    }
}
