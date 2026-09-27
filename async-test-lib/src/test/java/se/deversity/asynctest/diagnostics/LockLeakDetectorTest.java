package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LockLeakDetector.
 */
public class LockLeakDetectorTest {

    @Test
    void testNormalLockUsage() {
        LockLeakDetector detector = new LockLeakDetector();
        ReentrantLock lock = new ReentrantLock();
        
        detector.registerLock(lock, "normal-lock");
        
        lock.lock();
        detector.recordLockAcquired(lock, "normal-lock");
        try {
            // critical section
        } finally {
            lock.unlock();
            detector.recordLockReleased(lock, "normal-lock");
        }
        
        LockLeakDetector.LockLeakReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.hasIssues(), "Normal usage should not report issues");
    }

    @Test
    void testLockLeakDetection() {
        LockLeakDetector detector = new LockLeakDetector();
        ReentrantLock lock = new ReentrantLock();
        
        detector.registerLock(lock, "leaky-lock");
        
        lock.lock();
        detector.recordLockAcquired(lock, "leaky-lock");
        // Bug: never releasing the lock!
        
        LockLeakDetector.LockLeakReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect lock leak");
        assertFalse(report.lockLeaks.isEmpty(), "Should report lock leaks");
    }

    @Test
    void testHeldLockDetection() throws InterruptedException {
        LockLeakDetector detector = new LockLeakDetector();
        ReentrantLock lock = new ReentrantLock();
        
        detector.registerLock(lock, "held-lock");
        
        lock.lock();
        detector.recordLockAcquired(lock, "held-lock");
        
        // Wait a bit to ensure the lock appears held
        Thread.sleep(50);
        
        LockLeakDetector.LockLeakReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect held lock");
        assertFalse(report.heldLocks.isEmpty(), "Should report held locks");
        
        lock.unlock();
    }

    @Test
    void testExcessiveHoldTimeDetection() throws InterruptedException {
        LockLeakDetector detector = new LockLeakDetector();
        ReentrantLock lock = new ReentrantLock();
        
        detector.registerLock(lock, "slow-lock");
        
        lock.lock();
        detector.recordLockAcquired(lock, "slow-lock");
        
        // Hold for more than 5 seconds threshold
        Thread.sleep(100); // Use shorter time for test, detector uses 5000ms threshold
        
        lock.unlock();
        detector.recordLockReleased(lock, "slow-lock");
        
        LockLeakDetector.LockLeakReport report = detector.analyze();
        
        assertNotNull(report);
        // Hold time was only 100ms, should not trigger excessive hold time
        assertTrue(report.excessiveHoldTimes.isEmpty(), "Should not report excessive hold for short hold");
    }

    @Test
    void testThreadActivityTracking() {
        LockLeakDetector detector = new LockLeakDetector();
        ReentrantLock lock = new ReentrantLock();
        
        detector.registerLock(lock, "multi-thread-lock");
        
        // Simulate multiple threads acquiring and releasing
        Thread t1 = new Thread(() -> {
            lock.lock();
            detector.recordLockAcquired(lock, "multi-thread-lock");
            lock.unlock();
            detector.recordLockReleased(lock, "multi-thread-lock");
        });
        
        Thread t2 = new Thread(() -> {
            lock.lock();
            detector.recordLockAcquired(lock, "multi-thread-lock");
            lock.unlock();
            detector.recordLockReleased(lock, "multi-thread-lock");
        });
        
        t1.start();
        t2.start();
        
        try {
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        LockLeakDetector.LockLeakReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.threadActivity.isEmpty(), "Should track thread activity");
        assertTrue(report.threadActivity.stream().filter(a -> a.startsWith("multi-thread-lock: ")).findFirst().orElse("").contains("2 threads"),
                   "Should report 2 threads participated");
    }

    @Test
    void testRecordLockAcquiredAutoRegisters() {
        LockLeakDetector detector = new LockLeakDetector();
        ReentrantLock lock = new ReentrantLock();

        // Note: no registerLock() call - recordLockAcquired must auto-register
        lock.lock();
        detector.recordLockAcquired(lock, "auto-registered-lock");
        // Bug: never releasing the lock!

        LockLeakDetector.LockLeakReport report = detector.analyze();

        assertTrue(report.hasIssues(), "Auto-registered lock leak should be detected");
        assertFalse(report.lockLeaks.isEmpty(), "Should report lock leak for auto-registered lock");
    }

    @Test
    void testRecordLockReleasedAutoRegisters() {
        LockLeakDetector detector = new LockLeakDetector();
        ReentrantLock lock = new ReentrantLock();

        // Note: no registerLock() call anywhere in this test. recordLockReleased is
        // exercised first (on a lock the detector has never seen) so it must auto-register
        // rather than silently drop the event; the state it creates is then verified by
        // running a full acquire/release cycle and confirming it lands on that same state
        // (tracked, balanced, no leak).
        lock.lock();
        lock.unlock();
        detector.recordLockReleased(lock, "release-first-lock");

        lock.lock();
        detector.recordLockAcquired(lock, "release-first-lock");
        lock.unlock();
        detector.recordLockReleased(lock, "release-first-lock");

        LockLeakDetector.LockLeakReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.threadActivity.isEmpty(), "Auto-registered lock should be tracked");
        assertTrue(report.lockLeaks.isEmpty(), "Balanced acquire/release should not report a leak");
    }

    @Test
    void testNullSafety() {
        LockLeakDetector detector = new LockLeakDetector();
        
        // Should not throw on null inputs
        detector.registerLock(null, "null-lock");
        detector.recordLockAcquired(null, "null");
        detector.recordLockReleased(null, "null");
        
        LockLeakDetector.LockLeakReport report = detector.analyze();
        assertNotNull(report);
    }

    @Test
    void testReportToString() {
        LockLeakDetector detector = new LockLeakDetector();
        ReentrantLock lock = new ReentrantLock();
        
        detector.registerLock(lock, "test-lock");
        
        lock.lock();
        detector.recordLockAcquired(lock, "test-lock");
        // Leak the lock
        
        LockLeakDetector.LockLeakReport report = detector.analyze();
        
        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("LOCK LEAK ISSUES DETECTED"), "Report should have header");
        assertTrue(reportStr.contains("Lock Leaks"), "Report should mention lock leaks");
    }

    @Test
    void testMultipleAcquireRelease() {
        LockLeakDetector detector = new LockLeakDetector();
        ReentrantLock lock = new ReentrantLock();
        
        detector.registerLock(lock, "reentrant-lock");
        
        // Multiple acquire/release cycles
        for (int i = 0; i < 5; i++) {
            lock.lock();
            detector.recordLockAcquired(lock, "reentrant-lock");
            lock.unlock();
            detector.recordLockReleased(lock, "reentrant-lock");
        }
        
        LockLeakDetector.LockLeakReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.hasIssues(), "Should not report issues for balanced acquire/release");
    }

    /**
     * Two locks may share a name. Each keeps its own thread-activity line; filed under the name,
     * the second lock's line overwrote the first's (#789).
     */
    @Test
    void twoLocksWithTheSameNameEachKeepTheirThreadActivity() {
        LockLeakDetector detector = new LockLeakDetector();
        ReentrantLock released = new ReentrantLock();
        ReentrantLock kept = new ReentrantLock();
        detector.registerLock(released, "db");
        detector.registerLock(kept, "db");
        detector.recordLockAcquired(released, "db");
        detector.recordLockReleased(released, "db");
        detector.recordLockAcquired(kept, "db");

        String report = detector.analyze().toString();
        assertTrue(report.contains("db: 1 threads acquired, 1 threads released"),
                "the released lock's line survives beside the kept lock's: " + report);
        assertTrue(report.contains("db: 1 threads acquired, 0 threads released"),
                "the kept lock's line survives beside the released lock's: " + report);
    }

    // ---- grades follow the path, not one tier for the detector (#754) ---------------------------

    /** {@return the report's per-finding grades, failing if the report does not grade} */
    private static List<GradedFindings.Grade> gradesOf(Object report) {
        return assertInstanceOf(GradedFindings.class, report,
                "the report must grade each finding by the path behind it: " + report).grades();
    }

    @Test
    void anUnbalancedAcquireAndALockLeftHeldAreFacts() {
        LockLeakDetector detector = new LockLeakDetector();
        ReentrantLock lock = new ReentrantLock();
        detector.registerLock(lock, "leaky");
        detector.recordLockAcquired(lock, "leaky");
        detector.recordLockAcquired(lock, "leaky");
        detector.recordLockReleased(lock, "leaky");
        detector.recordLockAcquired(lock, "leaky");

        LockLeakDetector.LockLeakReport report = detector.analyze();
        List<GradedFindings.Grade> grades = DetectorTrust.clampToCap("LockLeakDetector", gradesOf(report));
        assertEquals(2, grades.size(), "the imbalance and the lock still held: " + grades);
        for (GradedFindings.Grade grade : grades) {
            assertEquals(TrustTier.FACT, grade.tier(),
                    "the counts are the acquires and releases the test recorded, true as recorded: " + grade);
            assertEquals(DetectorTrust.Evidence.ASSERTED, grade.evidence(), grade.toString());
            assertEquals(DetectorDefaultSeverity.of("LockLeakDetector", report.toString()), grade.severity(),
                    "the severity is the one the gate always read for this report: " + grade);
        }
    }

    @Test
    void aHoldOverTheThresholdStaysAPrompt() {
        LockLeakDetector.LockLeakReport report = new LockLeakDetector.LockLeakReport();
        // Five seconds of real hold time is too slow for a unit test; the finding is the line.
        report.excessiveHoldTimes.add("slow: lock held for up to 6000ms (potential deadlock precursor)");

        List<GradedFindings.Grade> grades = DetectorTrust.clampToCap("LockLeakDetector", gradesOf(report));
        assertEquals(1, grades.size(), grades.toString());
        assertEquals(TrustTier.PROMPT, grades.get(0).tier(),
                "a hold time over 5 s is a threshold, and a slow critical section is not a leak: " + grades);
        assertEquals(DetectorTrust.Evidence.HEURISTIC, grades.get(0).evidence(), grades.toString());
    }

    @Test
    void aBalancedLockCarriesNoGrade() {
        LockLeakDetector detector = new LockLeakDetector();
        ReentrantLock lock = new ReentrantLock();
        lock.lock();
        detector.recordLockAcquired(lock, "balanced");
        lock.unlock();
        detector.recordLockReleased(lock, "balanced");

        assertEquals(List.of(), gradesOf(detector.analyze()), "the correct twin grades nothing");
    }

    // ---- the lock itself confirms the leak (#837) ------------------------------------------------

    /** Takes {@code lock} on a thread of its own, records it and ends without unlocking. */
    private static void leakOnAThreadThatEnds(LockLeakDetector detector, java.util.concurrent.locks.Lock lock,
                                              String name) throws InterruptedException {
        Thread leaker = new Thread(() -> {
            lock.lock();
            detector.recordLockAcquired(lock, name);
        }, name + "-leaker");
        leaker.start();
        leaker.join();
    }

    @Test
    void aLeakTheLockStillHoldsForAThreadThatEndedIsAVerdict() throws InterruptedException {
        LockLeakDetector detector = new LockLeakDetector();
        ReentrantLock lock = new ReentrantLock();
        leakOnAThreadThatEnds(detector, lock, "orphaned");
        assertTrue(lock.isLocked(), "precondition: a ReentrantLock outlives the thread that holds it");

        LockLeakDetector.LockLeakReport report = detector.analyze();
        List<GradedFindings.Grade> grades = DetectorTrust.clampToCap("LockLeakDetector", gradesOf(report));
        assertEquals(2, grades.size(), "the imbalance and the lock still held: " + grades);
        for (GradedFindings.Grade grade : grades) {
            assertEquals(TrustTier.VERDICT, grade.tier(),
                    "ReentrantLock.isLocked() says the hold outlived its holder, which is the JVM's "
                            + "answer and not the recording's: " + grade);
            assertEquals(DetectorTrust.Evidence.OBSERVED, grade.evidence(), grade.toString());
        }
        assertTrue(report.toString().contains("isLocked()"), "the report names what confirmed it: " + report);
    }

    @Test
    void aPoolWorkerLeftIdleWithTheLockIsAVerdict() throws Exception {
        LockLeakDetector detector = new LockLeakDetector();
        ReentrantLock lock = new ReentrantLock();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            Thread worker = pool.submit(() -> {
                lock.lock();
                detector.recordLockAcquired(lock, "pooled");
                return Thread.currentThread();
            }).get();
            waitUntilIdle(worker);

            List<GradedFindings.Grade> grades = gradesOf(detector.analyze());
            assertFalse(grades.isEmpty(), "the leak is reported");
            assertTrue(grades.stream().allMatch(g -> g.tier() == TrustTier.VERDICT),
                    "the task ended and its worker waits for the next one, holding the lock: " + grades);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aHolderStillWorkingStaysAFact() throws InterruptedException {
        LockLeakDetector detector = new LockLeakDetector();
        ReentrantLock lock = new ReentrantLock();
        java.util.concurrent.CountDownLatch holding = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread holder = new Thread(() -> {
            lock.lock();
            try {
                detector.recordLockAcquired(lock, "busy");
                holding.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }, "busy-holder");
        holder.start();
        try {
            holding.await();
            List<GradedFindings.Grade> grades = gradesOf(detector.analyze());
            assertFalse(grades.isEmpty(), "the recorded imbalance is still reported");
            for (GradedFindings.Grade grade : grades) {
                assertEquals(TrustTier.FACT, grade.tier(),
                        "a holder that is still running may yet release the lock: " + grade);
                assertEquals(DetectorTrust.Evidence.ASSERTED, grade.evidence(), grade.toString());
            }
        } finally {
            release.countDown();
            holder.join();
        }
    }

    @Test
    void theAnalysingThreadsOwnHoldIsNotConfirmed() {
        LockLeakDetector detector = new LockLeakDetector();
        ReentrantLock lock = new ReentrantLock();
        lock.lock();
        try {
            detector.recordLockAcquired(lock, "mine");
            List<GradedFindings.Grade> grades = gradesOf(detector.analyze());
            assertFalse(grades.isEmpty(), grades.toString());
            assertTrue(grades.stream().allMatch(g -> g.tier() == TrustTier.FACT),
                    "the thread analysing is not done with its own hold: " + grades);
        } finally {
            lock.unlock();
        }
    }

    @Test
    void aLeakOnALockThatCannotBeAskedStaysAFact() throws InterruptedException {
        LockLeakDetector detector = new LockLeakDetector();
        java.util.concurrent.locks.Lock writeLock = new java.util.concurrent.locks.ReentrantReadWriteLock().writeLock();
        leakOnAThreadThatEnds(detector, writeLock, "rw");

        List<GradedFindings.Grade> grades = gradesOf(detector.analyze());
        assertFalse(grades.isEmpty(), grades.toString());
        assertTrue(grades.stream().allMatch(g -> g.tier() == TrustTier.FACT),
                "only a ReentrantLock is asked whether it is still held: " + grades);
    }

    @Test
    void aReleaseOnAThreadThatEndedCarriesNoGrade() throws InterruptedException {
        LockLeakDetector detector = new LockLeakDetector();
        ReentrantLock lock = new ReentrantLock();
        Thread worker = new Thread(() -> {
            lock.lock();
            try {
                detector.recordLockAcquired(lock, "tidy");
            } finally {
                lock.unlock();
                detector.recordLockReleased(lock, "tidy");
            }
        }, "tidy-worker");
        worker.start();
        worker.join();

        assertEquals(List.of(), gradesOf(detector.analyze()), "unlock in finally is the correct twin");
    }

    // ---- the lock names its holder only by name (#843) -------------------------------------------

    /**
     * Records an acquire on a thread from {@code recorder} while it holds {@code lock}, then releases
     * the lock without recording the release, so the counts show a leak and the lock is free.
     */
    private static void recordAnAcquireAndReleaseUnseen(LockLeakDetector detector, ReentrantLock lock,
                                                        Thread.Builder recorder) throws InterruptedException {
        Thread thread = recorder.start(() -> {
            lock.lock();
            detector.recordLockAcquired(lock, "shared");
            lock.unlock();
        });
        thread.join();
    }

    /**
     * Runs {@code check} while a thread from {@code holder}, which records nothing, holds
     * {@code lock} and is still running.
     */
    private static void whileAnUnrecordedThreadHolds(ReentrantLock lock, Thread.Builder holder,
                                                     Runnable check) throws InterruptedException {
        java.util.concurrent.CountDownLatch holding = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread thread = holder.start(() -> {
            lock.lock();
            try {
                holding.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        });
        try {
            holding.await();
            check.run();
        } finally {
            release.countDown();
            thread.join();
        }
    }

    private static void assertEveryGradeIsARecordedFact(LockLeakDetector detector, String why) {
        List<GradedFindings.Grade> grades = DetectorTrust.clampToCap("LockLeakDetector", gradesOf(detector.analyze()));
        assertFalse(grades.isEmpty(), "the recorded imbalance is still reported");
        for (GradedFindings.Grade grade : grades) {
            assertEquals(TrustTier.FACT, grade.tier(), why + ": " + grade);
            assertEquals(DetectorTrust.Evidence.ASSERTED, grade.evidence(), grade.toString());
        }
    }

    /**
     * The blind spot #837 left open. Every unnamed virtual thread is named {@code ""}, so the lock
     * says only "held by ''". The one the detector recorded has ended, but the holder is another
     * unnamed virtual thread that is still running and may yet release, and no probe of the lock or
     * of the live threads can tell the two apart. A verdict here would be a guess.
     */
    @Test
    void anUnnamedVirtualHolderTheDetectorNeverSawStaysAFact() throws InterruptedException {
        LockLeakDetector detector = new LockLeakDetector();
        ReentrantLock lock = new ReentrantLock();
        recordAnAcquireAndReleaseUnseen(detector, lock, Thread.ofVirtual());
        whileAnUnrecordedThreadHolds(lock, Thread.ofVirtual(), () -> {
            assertEquals("", ReentrantLockDetector.holderNameOf(lock), "precondition: the lock names no one");
            assertEveryGradeIsARecordedFact(detector,
                    "an unnamed virtual thread still holding the lock cannot be told from the ended one that recorded");
        });
    }

    /**
     * The same name from the other side: the unnamed virtual thread that recorded really did end
     * holding the lock. It stays a fact because, from analysis, it looks exactly like the case above.
     */
    @Test
    void anUnnamedVirtualThreadThatEndedHoldingTheLockStaysAFact() throws InterruptedException {
        LockLeakDetector detector = new LockLeakDetector();
        ReentrantLock lock = new ReentrantLock();
        Thread leaker = Thread.ofVirtual().start(() -> {
            lock.lock();
            detector.recordLockAcquired(lock, "unnamed");
        });
        leaker.join();
        assertTrue(lock.isLocked(), "precondition: the ended thread still holds the lock");

        assertEveryGradeIsARecordedFact(detector,
                "an empty holder name identifies no thread, so the lock cannot confirm which one ended");
    }

    /**
     * A virtual holder the detector never recorded, with a name of its own. The live platform
     * threads cannot list it, so finding none of that name is no evidence that it ended.
     */
    @Test
    void aNamedVirtualHolderTheDetectorNeverSawStaysAFact() throws InterruptedException {
        LockLeakDetector detector = new LockLeakDetector();
        ReentrantLock lock = new ReentrantLock();
        recordAnAcquireAndReleaseUnseen(detector, lock, Thread.ofPlatform().name("recorder"));
        whileAnUnrecordedThreadHolds(lock, Thread.ofVirtual().name("unseen-holder"), () ->
                assertEveryGradeIsARecordedFact(detector,
                        "a virtual thread is not among the platform threads a scan can find, alive or not"));
    }

    /**
     * The recorded thread ended, and a live thread of the same name, which never recorded, holds
     * the lock. The name the lock gives is that live thread's as much as the recorded one's.
     */
    @Test
    void aHolderWhoseNameAnotherLiveThreadSharesStaysAFact() throws InterruptedException {
        LockLeakDetector detector = new LockLeakDetector();
        ReentrantLock lock = new ReentrantLock();
        recordAnAcquireAndReleaseUnseen(detector, lock, Thread.ofPlatform().name("pooled"));
        whileAnUnrecordedThreadHolds(lock, Thread.ofPlatform().name("pooled"), () ->
                assertEveryGradeIsARecordedFact(detector,
                        "two threads carry the name the lock gives, and the live one may still release"));
    }

    /**
     * The runner's own arrangement: its virtual workers are named with a counter, so a named virtual
     * thread that recorded and ended holding the lock is still a verdict.
     */
    @Test
    void aNamedVirtualThreadThatEndedHoldingTheLockIsAVerdict() throws InterruptedException {
        LockLeakDetector detector = new LockLeakDetector();
        ReentrantLock lock = new ReentrantLock();
        Thread leaker = Thread.ofVirtual().name("named-virtual-leaker").start(() -> {
            lock.lock();
            detector.recordLockAcquired(lock, "named");
        });
        leaker.join();

        List<GradedFindings.Grade> grades = DetectorTrust.clampToCap("LockLeakDetector", gradesOf(detector.analyze()));
        assertEquals(2, grades.size(), "the imbalance and the lock still held: " + grades);
        assertTrue(grades.stream().allMatch(g -> g.tier() == TrustTier.VERDICT),
                "the lock names the thread that recorded, and that thread has ended: " + grades);
    }

    /** Waits until {@code worker} is parked waiting for its pool's next task. */
    private static void waitUntilIdle(Thread worker) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (ReentrantLockDetector.idleInAPool(worker)) {
                return;
            }
            Thread.sleep(5);
        }
        fail("the pool worker never went idle");
    }
}
