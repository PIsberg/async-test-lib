package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who holds a lock at analysis (#609), and starvation the lock itself corroborates (#608), each
 * decided against a real {@link ReentrantLock}.
 *
 * <p>#589 made the held-at-analysis finding a question for the lock, but a thread that is still
 * working when analysis starts and legitimately holds the lock looked exactly like a hold nobody
 * gave back. And a recorded starvation stayed the caller's declaration: any positive wait was a
 * finding, whatever the lock did.
 */
class ReentrantLockOwnerAndStarvationTest {

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for: " + what);
            }
            Thread.sleep(1);
        }
    }

    private static boolean idleIn(Thread thread, String frame) {
        return Arrays.stream(thread.getStackTrace())
                .anyMatch(e -> (e.getClassName() + "." + e.getMethodName()).equals(frame));
    }

    // ---- #609: the holder is still running ----

    @Test
    @DisplayName("a lock held at analysis by a platform thread that is still working is not a leak")
    void aLockHeldByAStillRunningPlatformThreadIsNotALeak() throws InterruptedException {
        ReentrantLockDetector detector = new ReentrantLockDetector();
        ReentrantLock lock = new ReentrantLock();
        detector.registerLock(lock, "background-lock");

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread background = new Thread(() -> {
            lock.lock();
            try {
                held.countDown();
                release.await(10, TimeUnit.SECONDS); // still doing its work under the lock
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }, "background-holder");
        background.start();
        try {
            assertTrue(held.await(10, TimeUnit.SECONDS));
            ReentrantLockDetector.ReentrantLockReport report = detector.analyze();
            assertFalse(report.hasIssues(),
                    "the holder is alive and not idle, so the hold is not known to be leaked. "
                            + "Report:\n" + report);
            assertTrue(report.toString().contains("background-holder"),
                    "the hold is still printed, as context: " + report);
        } finally {
            release.countDown();
            background.join(10_000);
        }
    }

    @Test
    @DisplayName("a lock held at analysis by a virtual thread that recorded against it and is still working is not a leak")
    void aLockHeldByAStillRunningVirtualThreadIsNotALeak() throws InterruptedException {
        ReentrantLockDetector detector = new ReentrantLockDetector();
        ReentrantLock lock = new ReentrantLock();
        detector.registerLock(lock, "virtual-lock");

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread background = Thread.ofVirtual().name("virtual-holder").start(() -> {
            lock.lock();
            detector.recordLockAcquired(lock, "virtual-holder");
            try {
                held.countDown();
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                detector.recordLockReleased(lock, "virtual-holder");
                lock.unlock();
            }
        });
        try {
            assertTrue(held.await(10, TimeUnit.SECONDS));
            ReentrantLockDetector.ReentrantLockReport report = detector.analyze();
            assertFalse(report.hasIssues(),
                    "virtual threads are not in Thread.getAllStackTraces(), so the holder must be "
                            + "found among the threads that recorded. Report:\n" + report);
        } finally {
            release.countDown();
            background.join(10_000);
        }
    }

    @Test
    @DisplayName("a hold leaked by a pool thread that is now idle still fires")
    void aHoldLeakedByAnIdlePoolThreadFires() throws Exception {
        ReentrantLockDetector detector = new ReentrantLockDetector();
        ReentrantLock lock = new ReentrantLock();
        detector.registerLock(lock, "pool-leaked-lock");
        AtomicReference<Thread> worker = new AtomicReference<>();
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> new Thread(r, "leaky-pool-worker"));
        try {
            pool.submit(() -> {
                worker.set(Thread.currentThread());
                ReentrantLockDetectorModelTest.leakOneHold(detector, lock);
            }).get(10, TimeUnit.SECONDS);
            awaitTrue(() -> idleIn(worker.get(), "java.util.concurrent.ThreadPoolExecutor.getTask"),
                    "the pool thread to go idle");

            ReentrantLockDetector.ReentrantLockReport report = detector.analyze();
            assertTrue(worker.get().isAlive(), "the premise: the holder is alive, just idle");
            assertTrue(report.hasIssues(),
                    "the task that took the lock has ended and its thread waits for the next one, "
                            + "so nobody will give the hold back. Report:\n" + report);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a hold leaked by a ForkJoinPool worker that is now idle still fires")
    void aHoldLeakedByAnIdleForkJoinWorkerFires() throws Exception {
        ReentrantLockDetector detector = new ReentrantLockDetector();
        ReentrantLock lock = new ReentrantLock();
        detector.registerLock(lock, "fj-leaked-lock");
        AtomicReference<Thread> worker = new AtomicReference<>();
        ForkJoinPool pool = new ForkJoinPool(1);
        try {
            pool.submit(() -> {
                worker.set(Thread.currentThread());
                ReentrantLockDetectorModelTest.leakOneHold(detector, lock);
            }).get(10, TimeUnit.SECONDS);
            awaitTrue(() -> idleIn(worker.get(), "java.util.concurrent.ForkJoinPool.awaitWork"),
                    "the ForkJoinPool worker to go idle");

            assertTrue(detector.analyze().hasIssues(), detector.analyze()::toString);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- #608: starvation the lock corroborates ----

    /**
     * One attempt at the barging scenario: {@code starved} parks in {@code lock()} while
     * {@code barger} holds the lock, then {@code barger} releases and re-takes it twice.
     *
     * @return whether the premise held: the barger re-took the lock twice while the starved thread
     *         stayed queued, as the lock itself reported after each re-take
     */
    private static boolean bargeTwice(ReentrantLockDetector detector, ReentrantLock lock)
            throws InterruptedException {
        AtomicInteger bargedWhileQueued = new AtomicInteger();
        CountDownLatch bargerHolds = new CountDownLatch(1);
        AtomicReference<Thread> starvedRef = new AtomicReference<>();
        Thread starved = new Thread(() -> {
            detector.registerLock(lock, "contended-lock");
            try {
                bargerHolds.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            long start = System.nanoTime();
            lock.lock();
            try {
                detector.recordLockAcquired(lock, "starved");
                detector.recordStarvation(lock, "starved",
                        Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)));
            } finally {
                detector.recordLockReleased(lock, "starved");
                lock.unlock();
            }
        }, "starved");
        starvedRef.set(starved);
        Thread barger = new Thread(() -> {
            detector.registerLock(lock, "contended-lock");
            lock.lock();
            detector.recordLockAcquired(lock, "barger");
            bargerHolds.countDown();
            try {
                awaitTrue(() -> lock.hasQueuedThread(starvedRef.get()), "the starved thread to queue");
                for (int i = 0; i < 2; i++) {
                    detector.recordLockReleased(lock, "barger");
                    lock.unlock();
                    lock.lock(); // a non-fair lock lets this jump the queue
                    detector.recordLockAcquired(lock, "barger");
                    if (lock.hasQueuedThread(starvedRef.get())) {
                        bargedWhileQueued.incrementAndGet();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                detector.recordLockReleased(lock, "barger");
                lock.unlock();
            }
        }, "barger");
        starved.start();
        barger.start();
        barger.join(10_000);
        starved.join(10_000);
        assertFalse(barger.isAlive() || starved.isAlive(), "the scenario did not finish");
        return bargedWhileQueued.get() == 2;
    }

    @Test
    @DisplayName("a waiter a non-fair lock let another thread barge past twice is starvation the lock saw")
    void aWaiterBargedPastOnANonFairLockFires() throws InterruptedException {
        for (int attempt = 0; attempt < 5; attempt++) {
            ReentrantLockDetector detector = new ReentrantLockDetector();
            ReentrantLock lock = new ReentrantLock(false);
            if (!bargeTwice(detector, lock)) {
                continue; // the woken waiter won a race it almost never wins; the premise failed
            }
            ReentrantLockDetector.ReentrantLockReport report = detector.analyze();
            assertTrue(report.hasIssues(),
                    "the barger took the lock twice while the starved thread stayed queued, and the "
                            + "starved thread recorded its wait. Report:\n" + report);
            assertTrue(report.toString().contains("starved"), report::toString);
            return;
        }
        throw new AssertionError("the barging premise failed five times in a row");
    }

    @Test
    @DisplayName("the same load on a fair lock is not starvation: lock() cannot barge")
    void theSameLoadOnAFairLockIsSilent() throws Exception {
        ReentrantLockDetector detector = new ReentrantLockDetector();
        ReentrantLock lock = new ReentrantLock(true);
        CountDownLatch bargerHolds = new CountDownLatch(1);
        AtomicReference<Thread> starvedRef = new AtomicReference<>();
        Thread starved = new Thread(() -> {
            detector.registerLock(lock, "fair-lock");
            try {
                bargerHolds.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            lock.lock();
            try {
                detector.recordLockAcquired(lock, "starved");
                detector.recordStarvation(lock, "starved", 50);
            } finally {
                detector.recordLockReleased(lock, "starved");
                lock.unlock();
            }
        }, "starved");
        starvedRef.set(starved);
        starved.start();

        detector.registerLock(lock, "fair-lock");
        lock.lock();
        detector.recordLockAcquired(lock, "barger");
        bargerHolds.countDown();
        awaitTrue(() -> lock.hasQueuedThread(starvedRef.get()), "the starved thread to queue");
        detector.recordLockReleased(lock, "barger");
        lock.unlock();
        lock.lock(); // fair: queues behind the starved thread instead of barging
        try {
            detector.recordLockAcquired(lock, "barger");
        } finally {
            detector.recordLockReleased(lock, "barger");
            lock.unlock();
        }
        starved.join(10_000);

        ReentrantLockDetector.ReentrantLockReport report = detector.analyze();
        assertFalse(report.hasIssues(),
                "a fair lock hands itself to the longest waiter, so no thread was passed over "
                        + "twice; the recorded wait is contention. Report:\n" + report);
    }

    @Test
    @DisplayName("a recorded wait the lock never corroborated is context, not a finding")
    void anUncorroboratedStarvationRecordIsContext() {
        ReentrantLockDetector detector = new ReentrantLockDetector();
        ReentrantLock lock = new ReentrantLock();
        detector.registerLock(lock, "quiet-lock");
        detector.recordStarvation(lock, "worker-1", 5_000);
        detector.recordStarvation("worker-2", 10_000);

        ReentrantLockDetector.ReentrantLockReport report = detector.analyze();
        assertFalse(report.hasIssues(),
                "a wall-clock wait is not evidence (#575): nothing was seen barging past either "
                        + "thread. Report:\n" + report);
        assertTrue(report.toString().contains("worker-1") && report.toString().contains("worker-2"),
                "both recorded waits are printed as context: " + report);
    }
}
