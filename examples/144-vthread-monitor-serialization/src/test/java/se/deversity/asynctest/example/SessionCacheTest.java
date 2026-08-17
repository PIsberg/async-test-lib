package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.diagnostics.VirtualThreadMonitorSerializationDetector;
import se.deversity.asynctest.example.service.SessionCache;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for SessionCache.
 *
 * ========================================================================
 * DETECTOR: VirtualThreadMonitorSerializationDetector
 *           (DetectorType.VIRTUAL_THREAD_MONITOR_SERIALIZATION)
 * ========================================================================
 *
 * Before JDK 24, a synchronized block that blocked pinned its virtual
 * thread to a carrier, and VirtualThreadPinningDetector reported it.
 * JEP 491 removed the pinning, and that detector now correctly marks
 * monitor events obsolete from JDK 24 on.
 *
 * The scalability problem did not go with it. synchronized still admits
 * one thread at a time, so ten thousand virtual threads reaching one
 * monitor are ten thousand threads in a queue - the carrier is free and
 * the throughput is not. This is easy to miss precisely because the fix
 * landed: upgrading to JDK 24 reads as "the pinning warnings went away"
 * when the bottleneck merely stopped announcing itself.
 *
 * THE BUG:
 *   - every request funnelling through one monitor, with the expensive
 *     part of the work inside the critical section
 *
 * THE FIX:
 *   - a ConcurrentHashMap, which admits as many threads as the hardware
 *     allows; or, where a lock is genuinely needed, a critical section
 *     short enough that nobody piles up behind it
 *
 * WHY THE FINDING IS A FACT:
 *   the peak queue depth and the number of distinct virtual threads in
 *   the queue are both counts. LOCK_CONTENTION cannot make this call:
 *   it has no notion of a virtual thread, so it scores four platform
 *   workers and four thousand virtual ones identically.
 */
class SessionCacheTest {

    private static final int FAN_OUT = 6;

    private SessionCache cache;

    @BeforeEach
    void setUp() {
        cache = new SessionCache();
    }

    // -----------------------------------------------------------------------
    // Part 1: the buggy shape. Six virtual threads reach one monitor at the
    // same moment; five of them wait while the first does the work.
    // -----------------------------------------------------------------------

    @Test
    void oneMonitorForEveryRequest_isDetected() throws InterruptedException {
        // Pinned to JDK 25 so the assertion does not depend on which JDK runs the example.
        var detector = new VirtualThreadMonitorSerializationDetector(4, 25);
        CountDownLatch allQueued = new CountDownLatch(FAN_OUT);
        CountDownLatch release = new CountDownLatch(1);

        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < FAN_OUT; i++) {
            final String key = "session-" + i;
            workers.add(Thread.ofVirtual().start(() -> {
                detector.recordMonitorEnter(cache.lock(), "sessionCache", Thread.currentThread());
                allQueued.countDown();
                await(release);
                synchronized (cache.lock()) {
                    detector.recordMonitorAcquired(cache.lock(), Thread.currentThread());
                    cache.lookupGuarded(key);
                }
            }));
        }
        assertTrue(allQueued.await(5, TimeUnit.SECONDS));
        release.countDown();
        for (Thread t : workers) t.join();

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "6 virtual threads on one monitor:\n" + report);
        var v = report.structuredViolations.get(0);
        assertEquals(IssueSeverity.HIGH, v.severity());
        assertEquals(FAN_OUT, v.attributes().get("peakWaiting"));
        assertEquals(FAN_OUT, v.attributes().get("virtualWaiters"));
    }

    // -----------------------------------------------------------------------
    // Part 2: what the report says depends on which side of JDK 24 you are.
    // Below 24 the pinning detector also reports it; from 24 this finding is
    // the only one left, which is exactly why the detector exists.
    // -----------------------------------------------------------------------

    @Test
    void fromJdk24TheReportSaysNothingElseCatchesIt() throws InterruptedException {
        var onJdk25 = new VirtualThreadMonitorSerializationDetector(2, 25);
        var onJdk21 = new VirtualThreadMonitorSerializationDetector(2, 21);
        queueTwoVirtualThreads(onJdk25);
        queueTwoVirtualThreads(onJdk21);

        assertTrue(onJdk25.analyze().toString().contains("no longer pins the carrier"));
        assertTrue(onJdk21.analyze().toString().contains("VIRTUAL_THREAD_PINNING reports it too"));
    }

    // -----------------------------------------------------------------------
    // Part 3: the fixed shape. No monitor, so the same six threads run
    // straight through and there is no queue to report.
    // -----------------------------------------------------------------------

    @Test
    void concurrentMapWithNoMonitor_isClean() throws InterruptedException {
        var detector = new VirtualThreadMonitorSerializationDetector(4, 25);

        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < FAN_OUT; i++) {
            final String key = "session-" + i;
            workers.add(Thread.ofVirtual().start(() -> cache.lookupConcurrent(key)));
        }
        for (Thread t : workers) t.join();

        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "no monitor, no queue:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 4: the other fixed shape. The monitor stays, but nobody queues on
    // it because each caller is in and out before the next arrives.
    // -----------------------------------------------------------------------

    @Test
    void aMonitorNobodyQueuesOn_isClean() throws InterruptedException {
        var detector = new VirtualThreadMonitorSerializationDetector(4, 25);

        for (int i = 0; i < FAN_OUT; i++) {
            final String key = "session-" + i;
            Thread t = Thread.ofVirtual().start(() -> {
                detector.recordMonitorEnter(cache.lock(), "sessionCache", Thread.currentThread());
                synchronized (cache.lock()) {
                    detector.recordMonitorAcquired(cache.lock(), Thread.currentThread());
                    cache.lookupGuarded(key);
                }
            });
            t.join();      // one at a time: the queue never forms
        }

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                () -> "a monitor is only a problem when threads pile up behind it:\n" + report);
    }

    private void queueTwoVirtualThreads(VirtualThreadMonitorSerializationDetector detector)
            throws InterruptedException {
        CountDownLatch queued = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            workers.add(Thread.ofVirtual().start(() -> {
                detector.recordMonitorEnter(cache.lock(), "sessionCache", Thread.currentThread());
                queued.countDown();
                await(release);
                detector.recordMonitorAcquired(cache.lock(), Thread.currentThread());
            }));
        }
        assertTrue(queued.await(5, TimeUnit.SECONDS));
        release.countDown();
        for (Thread t : workers) t.join();
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
