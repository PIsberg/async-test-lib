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

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    @DisplayName("waits on two unregistered locks sharing an identity hash print as two lines")
    void waitsOnTwoUnnamedCollidingLocksAreTwoLines() {
        ReentrantLockDetector detector = new ReentrantLockDetector();
        for (ReentrantLock lock : IdentityCollisions.pair(ReentrantLock::new)) {
            detector.recordStarvation(lock, "worker", 5);
            detector.recordStarvation(lock, "worker", 5);
        }

        String report = detector.analyze().toString();
        assertEquals(2, report.split("worker on ", -1).length - 1,
                "one line per lock, and each lock keeps one name across its records (#854): " + report);
    }

    // ---- the holder is a thread, not a name ----

    @Test
    @DisplayName("a hold leaked by a finished named virtual thread fires while an unnamed one is still working")
    void aLeakByANamedVirtualThreadIsNotExcusedByAnotherThread() throws InterruptedException {
        ReentrantLockDetector detector = new ReentrantLockDetector();
        ReentrantLock lock = new ReentrantLock();
        detector.registerLock(lock, "named-leak-lock");

        // The runner's arrangement: its virtual workers are named with a counter.
        Thread leaker = Thread.ofVirtual().name("async-test-worker-7").start(() -> {
            lock.lock(); // taken and never given back
            detector.recordLockAcquired(lock, "leaker");
        });
        leaker.join(10_000);
        assertFalse(leaker.isAlive(), "the premise: the holder has finished");

        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread bystander = Thread.ofVirtual().start(() -> {
            detector.registerLock(lock, "named-leak-lock");
            registered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS); // alive and working
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertTrue(registered.await(10, TimeUnit.SECONDS));

            ReentrantLockDetector.ReentrantLockReport report = detector.analyze();
            assertTrue(report.hasIssues(),
                    "the lock names the thread that recorded taking it, and that thread has finished; "
                            + "another thread still running is not the holder. Report:\n" + report);
            assertTrue(report.toString().contains("async-test-worker-7, which has finished"),
                    report::toString);
        } finally {
            release.countDown();
            bystander.join(10_000);
        }
    }

    // ---- #848: the lock names its holder only by name ----

    /**
     * Runs {@code check} while a thread from {@code holder}, which records nothing, holds
     * {@code lock} and is still running.
     */
    private static void whileAnUnrecordedThreadHolds(ReentrantLock lock, Thread.Builder holder,
                                                     ThrowingCheck check) throws InterruptedException {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread thread = holder.start(() -> {
            lock.lock();
            try {
                holding.countDown();
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        });
        try {
            assertTrue(holding.await(10, TimeUnit.SECONDS));
            assertTrue(thread.isAlive(), "the premise: the holder is still running");
            check.run();
        } finally {
            release.countDown();
            thread.join(10_000);
        }
    }

    /** A check that may wait. */
    @FunctionalInterface
    private interface ThrowingCheck {
        void run() throws InterruptedException;
    }

    /** Records one acquire and one release on {@code lock} from a thread of {@code recorder}, then lets it end. */
    private static void recordATakeAndRelease(ReentrantLockDetector detector, ReentrantLock lock,
                                              Thread.Builder recorder) throws InterruptedException {
        Thread thread = recorder.start(() -> {
            lock.lock();
            try {
                detector.recordLockAcquired(lock, "recorded");
            } finally {
                detector.recordLockReleased(lock, "recorded");
                lock.unlock();
            }
        });
        thread.join(10_000);
        assertFalse(thread.isAlive(), "the premise: the recorded thread has finished");
    }

    private static void assertHeldButNotJudged(ReentrantLockDetector.ReentrantLockReport report, String why) {
        assertFalse(report.hasIssues(), why + ". Report:\n" + report);
        assertFalse(report.toString().contains("which has finished"),
                "the report must not claim the holder finished: " + report);
        assertTrue(report.toString().contains("a thread the detector cannot identify"),
                "the hold is printed as context, saying what is known: " + report);
    }

    @Test
    @DisplayName("#848: a lock an unrecorded unnamed virtual thread holds while still working is not held by a finished thread")
    void anUnrecordedUnnamedVirtualHolderStillWorkingIsNotReportedAsFinished() throws InterruptedException {
        ReentrantLockDetector detector = new ReentrantLockDetector();
        ReentrantLock lock = new ReentrantLock();
        detector.registerLock(lock, "handed-on-lock");
        recordATakeAndRelease(detector, lock, Thread.ofVirtual());

        whileAnUnrecordedThreadHolds(lock, Thread.ofVirtual(), () -> {
            assertEquals("", ReentrantLockDetector.holderNameOf(lock), "the premise: the lock names no one");
            assertHeldButNotJudged(detector.analyze(),
                    "every unnamed virtual thread is \"\", and none can be listed, so the empty name "
                            + "the lock gives is no evidence that its holder finished");
        });
    }

    @Test
    @DisplayName("#848: a lock an unrecorded named virtual thread holds while still working is not held by a finished thread")
    void anUnrecordedNamedVirtualHolderStillWorkingIsNotReportedAsFinished() throws InterruptedException {
        ReentrantLockDetector detector = new ReentrantLockDetector();
        ReentrantLock lock = new ReentrantLock();
        detector.registerLock(lock, "handed-on-lock");
        recordATakeAndRelease(detector, lock, Thread.ofPlatform().name("recorder"));

        whileAnUnrecordedThreadHolds(lock, Thread.ofVirtual().name("unseen-holder"), () ->
                assertHeldButNotJudged(detector.analyze(),
                        "a virtual thread is not among the platform threads a scan can find, so "
                                + "finding none of its name is no evidence that it finished"));
    }

    @Test
    @DisplayName("#848: a hold whose name a live platform thread shares with the finished recorded holder is not judged")
    void aHolderWhoseNameAnotherLivePlatformThreadSharesIsNotJudged() throws InterruptedException {
        ReentrantLockDetector detector = new ReentrantLockDetector();
        ReentrantLock lock = new ReentrantLock();
        detector.registerLock(lock, "pooled-lock");
        // Recorded taking the lock and gave it back without recording that, so it stays the
        // recorded holder; a live thread of the same name, which records nothing, holds it now.
        Thread recorded = Thread.ofPlatform().name("pooled").start(() -> {
            lock.lock();
            detector.recordLockAcquired(lock, "pooled");
            lock.unlock();
        });
        recorded.join(10_000);
        assertFalse(recorded.isAlive(), "the premise: the recorded thread has finished");

        whileAnUnrecordedThreadHolds(lock, Thread.ofPlatform().name("pooled"), () ->
                assertHeldButNotJudged(detector.analyze(),
                        "two threads carry the name the lock gives, and the live one may still release"));
    }

    @Test
    @DisplayName("#848: a hold left by a finished unnamed virtual thread is context, since its empty name identifies no thread")
    void aHoldLeftByAFinishedUnnamedVirtualThreadIsContext() throws InterruptedException {
        ReentrantLockDetector detector = new ReentrantLockDetector();
        ReentrantLock lock = new ReentrantLock();
        detector.registerLock(lock, "unnamed-leak-lock");

        Thread leaker = Thread.ofVirtual().start(() -> {
            lock.lock(); // taken and never given back
            detector.recordLockAcquired(lock, "leaker");
        });
        leaker.join(10_000);
        assertFalse(leaker.isAlive(), "the premise: the holder has finished");
        assertTrue(lock.isLocked(), "the premise: the lock is still taken");

        // From analysis this looks exactly like the unrecorded unnamed holder above that is still
        // running: the lock says "held by ''", and no scan can list virtual threads.
        assertHeldButNotJudged(detector.analyze(),
                "an empty holder name cannot say the recorded thread is the one still holding");
    }

    @Test
    @DisplayName("a lock held by a working unnamed virtual thread is not a leak because another unnamed one finished")
    void aWorkingUnnamedHolderIsNotReportedBecauseAnotherUnnamedThreadFinished() throws InterruptedException {
        ReentrantLockDetector detector = new ReentrantLockDetector();
        ReentrantLock lock = new ReentrantLock();

        Thread finished = Thread.ofVirtual().start(() -> {
            lock.lock();
            detector.recordLockAcquired(lock, "finished");
            detector.recordLockReleased(lock, "finished");
            lock.unlock();
        });
        finished.join(10_000);
        assertFalse(finished.isAlive());

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> {
            lock.lock();
            detector.recordLockAcquired(lock, "holder");
            try {
                held.countDown();
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                detector.recordLockReleased(lock, "holder");
                lock.unlock();
            }
        });
        try {
            assertTrue(held.await(10, TimeUnit.SECONDS));
            ReentrantLockDetector.ReentrantLockReport report = detector.analyze();
            assertFalse(report.hasIssues(),
                    "the holder is alive and working; the finished thread gave its hold back. Report:\n"
                            + report);
        } finally {
            release.countDown();
            holder.join(10_000);
        }
    }
}
