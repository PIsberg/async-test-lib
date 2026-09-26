package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConcurrentModificationDetector.
 */
public class ConcurrentModificationDetectorTest {

    @Test
    void testNormalCollectionUsage() {
        ConcurrentModificationDetector detector = new ConcurrentModificationDetector();
        List<String> list = new ArrayList<>();
        
        detector.registerCollection(list, "normal-list");
        detector.recordModification(list, "normal-list", "add");
        list.add("item1");
        
        ConcurrentModificationDetector.ConcurrentModificationReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.hasIssues(), "Normal usage should not report issues");
    }

    @Test
    void testConcurrentModificationDetection() {
        ConcurrentModificationDetector detector = new ConcurrentModificationDetector();
        List<String> list = new ArrayList<>();
        
        detector.registerCollection(list, "concurrent-list");
        
        // Simulate iteration started
        detector.recordIterationStarted(list, "concurrent-list");
        
        // Modification during iteration - bug!
        detector.recordModificationDuringIteration(list, "concurrent-list", "add");
        
        ConcurrentModificationDetector.ConcurrentModificationReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect concurrent modification");
        assertFalse(report.concurrentModifications.isEmpty(), "Should report concurrent modifications");
    }

    @Test
    void readOnlyConcurrentIterationIsNotReported() throws InterruptedException {
        ConcurrentModificationDetector detector = new ConcurrentModificationDetector();
        List<String> list = new ArrayList<>();
        list.add("item");

        detector.registerCollection(list, "read-only-list");

        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(2);
        Runnable readOnly = () -> {
            try {
                startLatch.await();
                detector.recordIterationStarted(list, "read-only-list");
                for (String ignored : list) {
                    // read, never write
                }
                detector.recordIterationEnded(list, "read-only-list");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        };

        Thread t1 = new Thread(readOnly);
        Thread t2 = new Thread(readOnly);
        t1.start();
        t2.start();
        startLatch.countDown();
        doneLatch.await();

        ConcurrentModificationDetector.ConcurrentModificationReport report = detector.analyze();

        assertTrue(report.concurrentIterations.isEmpty(),
            "Two threads iterating a list nobody mutates is correct code, and this detector is "
                + "VERDICT tier, so it must stay silent: " + report.concurrentIterations);
        assertFalse(report.hasIssues(), "Read-only concurrent iteration is not an issue");
    }

    @Test
    void testConcurrentIterationWithModificationIsReported() throws InterruptedException {
        ConcurrentModificationDetector detector = new ConcurrentModificationDetector();
        List<String> list = new ArrayList<>();
        
        detector.registerCollection(list, "iterated-list");
        
        // Use a barrier to ensure both threads iterate at the same time
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(2);
        
        Thread t1 = new Thread(() -> {
            try {
                startLatch.await();
                detector.recordIterationStarted(list, "iterated-list");
                Thread.sleep(10); // Hold iteration
                detector.recordIterationEnded(list, "iterated-list");
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });
        
        Thread t2 = new Thread(() -> {
            try {
                startLatch.await();
                detector.recordIterationStarted(list, "iterated-list");
                Thread.sleep(10); // Hold iteration
                detector.recordIterationEnded(list, "iterated-list");
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });
        
        t1.start();
        t2.start();
        startLatch.countDown(); // Release both threads simultaneously
        doneLatch.await(); // Wait for both to complete

        // Someone mutates the list the two threads are walking. Without this the iteration is
        // read-only, which is correct code and no longer reported.
        detector.recordModification(list, "iterated-list", "add");

        ConcurrentModificationDetector.ConcurrentModificationReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect concurrent iterations");
        assertFalse(report.concurrentIterations.isEmpty(), "Should report concurrent iterations");
    }

    @Test
    void iterationAndMutationUnderTheCollectionsOwnMonitorIsNotReported() throws InterruptedException {
        ConcurrentModificationDetector detector = new ConcurrentModificationDetector();
        List<String> list = new ArrayList<>();
        list.add("item");
        detector.registerCollection(list, "guarded-list");

        Runnable iterateThenAdd = () -> {
            synchronized (list) {
                detector.recordIterationStarted(list, "guarded-list");
                for (String ignored : list) {
                    // read under the lock every writer also takes
                }
                detector.recordIterationEnded(list, "guarded-list");
                list.add("more");
                detector.recordModification(list, "guarded-list", "add");
            }
        };
        runOnTwoThreads(iterateThenAdd, iterateThenAdd);

        ConcurrentModificationDetector.ConcurrentModificationReport report = detector.analyze();
        assertTrue(report.concurrentIterations.isEmpty(),
            "Every iteration and every add held the list's own monitor, which is the idiom "
                + "Collections.synchronizedList documents for iterating. No iterator can see a "
                + "writer, and this detector is VERDICT tier: " + report.concurrentIterations);
        assertFalse(report.hasIssues(), "a consistently locked list is correct code: " + report);
    }

    @Test
    void iterationUnderADeclaredLockThatEveryWriterHoldsIsNotReported() throws InterruptedException {
        ConcurrentModificationDetector detector = new ConcurrentModificationDetector();
        List<String> list = new ArrayList<>();
        java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        detector.registerCollection(list, "declared-lock-list");

        Runnable iterateThenAdd = () -> {
            try (var held = se.deversity.asynctest.AsyncTestContext.holdingLock(lock)) {
                lock.lock();
                try {
                    detector.recordIterationStarted(list, "declared-lock-list");
                    detector.recordIterationEnded(list, "declared-lock-list");
                    list.add("more");
                    detector.recordModification(list, "declared-lock-list", "add");
                } finally {
                    lock.unlock();
                }
            }
        };
        runOnTwoThreads(iterateThenAdd, iterateThenAdd);

        assertFalse(detector.analyze().hasIssues(),
            "one declared lock covered every iteration and every mutation");
    }

    @Test
    void iterationOutsideTheLockTheWritersHoldIsStillReported() throws InterruptedException {
        ConcurrentModificationDetector detector = new ConcurrentModificationDetector();
        List<String> list = new ArrayList<>();
        list.add("item");
        detector.registerCollection(list, "half-guarded-list");

        Runnable iterateUnlockedThenAddLocked = () -> {
            detector.recordIterationStarted(list, "half-guarded-list");
            detector.recordIterationEnded(list, "half-guarded-list");
            synchronized (list) {
                list.add("more");
                detector.recordModification(list, "half-guarded-list", "add");
            }
        };
        runOnTwoThreads(iterateUnlockedThenAddLocked, iterateUnlockedThenAddLocked);

        ConcurrentModificationDetector.ConcurrentModificationReport report = detector.analyze();
        assertFalse(report.concurrentIterations.isEmpty(),
            "the writers locked and the iterators did not, so an iterator can still see a "
                + "structural change mid-walk; locking only the writes is the classic half-fix");
    }

    private static void runOnTwoThreads(Runnable first, Runnable second) throws InterruptedException {
        java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
        Thread[] workers = {
            new Thread(() -> awaitThen(start, first)),
            new Thread(() -> awaitThen(start, second))
        };
        for (Thread worker : workers) {
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
    }

    private static void awaitThen(java.util.concurrent.CyclicBarrier start, Runnable body) {
        try {
            start.await();
        } catch (InterruptedException | java.util.concurrent.BrokenBarrierException e) {
            Thread.currentThread().interrupt();
            return;
        }
        body.run();
    }

    @Test
    void testConcurrentMutationDetection() {
        ConcurrentModificationDetector detector = new ConcurrentModificationDetector();
        List<String> list = new ArrayList<>();
        
        detector.registerCollection(list, "mutated-list");
        
        // Simulate multiple threads modifying
        Thread t1 = new Thread(() -> {
            detector.recordModification(list, "mutated-list", "add");
        });
        
        Thread t2 = new Thread(() -> {
            detector.recordModification(list, "mutated-list", "add");
        });
        
        t1.start();
        t2.start();
        
        try {
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        ConcurrentModificationDetector.ConcurrentModificationReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect concurrent mutations");
        assertFalse(report.concurrentMutations.isEmpty(), "Should report concurrent mutations");
    }

    @Test
    void testIterationLifecycle() {
        ConcurrentModificationDetector detector = new ConcurrentModificationDetector();
        List<String> list = new ArrayList<>();
        
        detector.registerCollection(list, "lifecycle-list");
        
        detector.recordIterationStarted(list, "lifecycle-list");
        // During iteration, activeIterators should be 1
        detector.recordIterationEnded(list, "lifecycle-list");
        // After ending, activeIterators should be 0
        
        ConcurrentModificationDetector.ConcurrentModificationReport report = detector.analyze();
        
        assertNotNull(report);
        // No issues if no modifications during iteration
        assertTrue(report.concurrentModifications.isEmpty(), "Should have no concurrent modifications");
    }

    @Test
    void testNullSafety() {
        ConcurrentModificationDetector detector = new ConcurrentModificationDetector();
        
        // Should not throw on null inputs
        detector.registerCollection(null, "null-collection");
        detector.recordIterationStarted(null, "null");
        detector.recordIterationEnded(null, "null");
        detector.recordModification(null, "null", "add");
        detector.recordModificationDuringIteration(null, "null", "add");
        
        ConcurrentModificationDetector.ConcurrentModificationReport report = detector.analyze();
        assertNotNull(report);
    }

    @Test
    void testReportToString() {
        ConcurrentModificationDetector detector = new ConcurrentModificationDetector();
        List<String> list = new ArrayList<>();
        
        detector.registerCollection(list, "test-list");
        detector.recordIterationStarted(list, "test-list");
        detector.recordModificationDuringIteration(list, "test-list", "add");
        
        ConcurrentModificationDetector.ConcurrentModificationReport report = detector.analyze();
        
        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("CONCURRENT MODIFICATION ISSUES DETECTED"), "Report should have header");
        assertTrue(reportStr.contains("Modifications During Iteration"), "Report should mention modifications during iteration");
    }

    @Test
    void testCollectionActivityTracking() {
        ConcurrentModificationDetector detector = new ConcurrentModificationDetector();
        List<String> list = new ArrayList<>();
        
        detector.registerCollection(list, "active-list");
        detector.recordModification(list, "active-list", "add");
        detector.recordModification(list, "active-list", "add");
        
        ConcurrentModificationDetector.ConcurrentModificationReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.collectionActivity.isEmpty(), "Should track collection activity");
        assertTrue(report.collectionActivity.get("active-list").contains("modifications: 2"),
                   "Should report correct modification count");
    }
}
