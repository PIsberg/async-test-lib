package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.diagnostics.VirtualThreadResourceSaturationDetector;
import se.deversity.asynctest.example.service.ConnectionPool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for ConnectionPool.
 *
 * ========================================================================
 * DETECTOR: VirtualThreadResourceSaturationDetector
 *           (DetectorType.VIRTUAL_THREAD_RESOURCE_SATURATION)
 * ========================================================================
 *
 * A fixed pool of eight platform threads could never ask for a ninth
 * connection. Nobody wrote that limit down: it was a side effect of the
 * pool size, and it worked. Switch to a thread per task and the limit
 * goes with the pool, while the connection pool, the rate limiter and
 * the downstream service stay exactly as bounded as they were.
 *
 * JEP 444 says this directly: do not pool virtual threads, use a
 * Semaphore to limit concurrent access to a limited resource. The queue
 * does not disappear when you remove the pool - it moves somewhere that
 * reports it as an acquisition timeout instead of as a threading
 * problem.
 *
 * THE BUG:
 *   - one virtual thread per request, all of them reaching a pool of 2
 *
 * THE FIX:
 *   - a Semaphore sized from the pool itself, acquired around the
 *     borrow and released in a finally
 *
 * WHY THE FINDING IS A FACT:
 *   the detector compares the peak number of virtual threads waiting at
 *   once against the capacity the caller declared. Both are counts. A
 *   fan-out that never queues more than the resource can serve is silent,
 *   and so is a queue that platform threads made.
 */
class ConnectionPoolTest {

    private static final int POOL_SIZE = 2;
    private static final int REQUESTS  = 8;

    private ConnectionPool pool;
    private VirtualThreadResourceSaturationDetector detector;

    @BeforeEach
    void setUp() {
        pool = new ConnectionPool(POOL_SIZE);
        detector = new VirtualThreadResourceSaturationDetector();
        detector.registerResource("connections", pool.getMaximumPoolSize());
    }

    // -----------------------------------------------------------------------
    // Part 1: the buggy shape. Eight virtual threads, two connections, and
    // nothing in between. Six of the eight are queueing rather than working.
    // -----------------------------------------------------------------------

    @Test
    void unboundedVirtualFanOut_isDetected() throws InterruptedException {
        CountDownLatch allQueued = new CountDownLatch(REQUESTS);
        CountDownLatch release = new CountDownLatch(1);

        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            workers.add(Thread.ofVirtual().start(() -> {
                detector.recordAcquireStart("connections", Thread.currentThread());
                allQueued.countDown();
                await(release);
                borrowAndRelease();
            }));
        }
        assertTrue(allQueued.await(5, TimeUnit.SECONDS));
        release.countDown();
        for (Thread t : workers) t.join();

        assertEquals(POOL_SIZE, pool.peakInUse(), "the pool held its limit, as it always does");

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "8 callers queued on a 2-connection pool:\n" + report);
        assertEquals(IssueSeverity.HIGH, report.structuredViolations.get(0).severity());
        assertEquals(REQUESTS, report.structuredViolations.get(0).attributes().get("peakWaiting"));
    }

    // -----------------------------------------------------------------------
    // Part 2: the fixed shape. A semaphore sized from the pool admits two at
    // a time, so the queue in front of the resource never grows past its
    // capacity - and the work still all gets done.
    // -----------------------------------------------------------------------

    @Test
    void fanOutBoundedBySemaphore_isClean() throws InterruptedException {
        Semaphore admission = new Semaphore(pool.getMaximumPoolSize());   // sized from the resource

        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            workers.add(Thread.ofVirtual().start(() -> {
                try {
                    admission.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    detector.recordAcquireStart("connections", Thread.currentThread());
                    borrowAndRelease();
                } finally {
                    admission.release();
                }
            }));
        }
        for (Thread t : workers) t.join();

        assertEquals(POOL_SIZE, pool.peakInUse(), "still two at a time");

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                () -> "a fan-out bounded by the resource's own size must be clean:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 3: scope. The same eight callers on platform threads is not this
    // detector's finding - a bounded pool cannot produce the hazard, and
    // THREAD_POOL_DEADLOCK already covers that ground.
    // -----------------------------------------------------------------------

    @Test
    void platformThreadsQueueing_isAnotherDetectorsFinding() throws InterruptedException {
        CountDownLatch allQueued = new CountDownLatch(REQUESTS);
        CountDownLatch release = new CountDownLatch(1);

        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            workers.add(Thread.ofPlatform().start(() -> {
                detector.recordAcquireStart("connections", Thread.currentThread());
                allQueued.countDown();
                await(release);
                borrowAndRelease();
            }));
        }
        assertTrue(allQueued.await(5, TimeUnit.SECONDS));
        release.countDown();
        for (Thread t : workers) t.join();

        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "platform threads are out of scope here:\n" + report);
    }

    /**
     * The acquisition, instrumented the way a real pool needs: got one, or gave up. A timeout is
     * how saturation surfaces, and a caller that gives up without saying so would stay in the
     * detector's queue for the rest of the run.
     */
    private void borrowAndRelease() {
        try {
            if (pool.borrow(5_000)) {
                detector.recordAcquired("connections", Thread.currentThread());
                try {
                    Thread.sleep(1);
                } finally {
                    pool.release();
                }
            } else {
                detector.recordAcquireAbandoned("connections", Thread.currentThread());   // timed out
            }
        } catch (InterruptedException e) {
            detector.recordAcquireAbandoned("connections", Thread.currentThread());
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("latch never opened");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
