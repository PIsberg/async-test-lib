package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.diagnostics.LambdaLostUpdateDetector;
import se.deversity.asynctest.example.service.HitCounter;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for HitCounter.
 *
 * ========================================================================
 * DETECTOR: LambdaLostUpdateDetector
 *           (DetectorType.LAMBDA_LOST_UPDATE)
 * ========================================================================
 *
 * A lambda captures the container, not a copy. The classic int[] trick
 * gets past the effectively-final rule and leaves the contents as shared
 * and unguarded as any field, so hits[0] = hits[0] + 1 from two threads
 * loses an increment: both read the same value, both write back, and one
 * of the writes never happened as far as the result is concerned.
 *
 * HOW THIS DIFFERS FROM STATEFUL_LAMBDA:
 *   StatefulLambdaDetector reports the shape - "this lambda ran on
 *   several threads and mutated a capture" - which is a co-occurrence
 *   and fires identically on a correctly locked counter. This detector
 *   compares the values two threads observed. It fires only where two
 *   threads read the SAME pre-value before writing back, which is a lost
 *   update rather than the risk of one, and it stays silent when every
 *   update held the same monitor.
 *
 * THE BUG:
 *   - read, add one, write back, from N threads, with nothing between
 *
 * THE FIX:
 *   - AtomicInteger.incrementAndGet(): one operation, no window
 *   - or hold one monitor across the whole read-modify-write, not
 *     around the read and the write separately
 */
class HitCounterTest {

    private HitCounter counter;
    private LambdaLostUpdateDetector detector;

    @BeforeEach
    void setUp() {
        counter = new HitCounter();
        detector = new LambdaLostUpdateDetector();
    }

    // -----------------------------------------------------------------------
    // Part 1: the buggy shape. Both threads read before either writes, so
    // both compute from 0 and the counter ends at 1 instead of 2.
    // -----------------------------------------------------------------------

    @Test
    void capturedArrayReadModifyWrite_isDetected() throws InterruptedException {
        CyclicBarrier bothHaveRead = new CyclicBarrier(2);
        CountDownLatch done = new CountDownLatch(2);
        Runnable task = () -> { };   // the captured lambda's identity

        for (int i = 0; i < 2; i++) {
            new Thread(() -> {
                int before = counter.read();
                awaitBarrier(bothHaveRead);      // makes the race deterministic
                int after = before + 1;
                counter.write(after);
                detector.recordReadModifyWrite(task, "hits", before, after, Thread.currentThread());
                done.countDown();
            }, "worker-" + i).start();
        }
        assertTrue(done.await(5, TimeUnit.SECONDS));

        assertEquals(1, counter.read(), "two increments, one survivor - the lost update itself");

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "a proven lost update:\n" + report);
        assertEquals(IssueSeverity.HIGH, report.structuredViolations.get(0).severity());
        assertEquals(1, report.structuredViolations.get(0).attributes().get("lostWrites"));
        assertTrue(report.toString().contains("all read 0"), report.toString());
    }

    // -----------------------------------------------------------------------
    // Part 2: the fixed shape. incrementAndGet() gives every thread its own
    // pre-value, so no two threads ever read the same one.
    // -----------------------------------------------------------------------

    @Test
    void atomicIncrement_isClean() throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(4);
        Runnable task = () -> { };

        for (int i = 0; i < 4; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    int after = counter.incrementAtomically();
                    detector.recordReadModifyWrite(task, "hits", after - 1, after, Thread.currentThread());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "worker-" + i).start();
        }
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));

        assertEquals(4, counter.atomicValue(), "every increment survived");
        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "distinct pre-values, nothing lost:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 3: the other fixed shape. One monitor held across the whole
    // read-modify-write serialises the sequence, and naming that monitor
    // lets the detector see it and stay quiet.
    // -----------------------------------------------------------------------

    @Test
    void oneMonitorAcrossTheWholeUpdate_isClean() throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(4);
        Runnable task = () -> { };

        for (int i = 0; i < 4; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    synchronized (counter.guard()) {
                        int before = counter.read();
                        int after = before + 1;
                        counter.write(after);
                        detector.recordReadModifyWrite(
                                task, "hits", before, after, counter.guard(), Thread.currentThread());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "worker-" + i).start();
        }
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));

        assertEquals(4, counter.read(), "every increment survived");
        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "a consistently held monitor must be clean:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 4: the lock that is not quite a lock. One thread takes the
    // monitor, another does not. That is worse than no lock at all, because
    // the code reads as if the sequence were atomic - and it is still
    // reported.
    // -----------------------------------------------------------------------

    @Test
    void inconsistentlyHeldMonitor_isStillDetected() throws InterruptedException {
        CyclicBarrier bothHaveRead = new CyclicBarrier(2);
        CountDownLatch done = new CountDownLatch(2);
        Runnable task = () -> { };

        new Thread(() -> {
            synchronized (counter.guard()) {
                int before = counter.read();
                awaitBarrier(bothHaveRead);
                counter.write(before + 1);
                detector.recordReadModifyWrite(
                        task, "hits", before, before + 1, counter.guard(), Thread.currentThread());
            }
            done.countDown();
        }, "locked-worker").start();

        new Thread(() -> {
            int before = counter.read();          // no monitor here
            awaitBarrier(bothHaveRead);
            counter.write(before + 1);
            detector.recordReadModifyWrite(
                    task, "hits", before, before + 1, counter.guard(), Thread.currentThread());
            done.countDown();
        }, "unlocked-worker").start();

        assertTrue(done.await(5, TimeUnit.SECONDS));

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "half a lock is not a lock:\n" + report);
        assertEquals(true, report.structuredViolations.get(0).attributes().get("partiallyGuarded"));
        assertTrue(report.toString().contains("Some - not all - of these updates held"),
                report.toString());
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("barrier never tripped", e);
        }
    }
}
