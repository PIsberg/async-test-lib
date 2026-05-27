package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.BackgroundWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for BackgroundWorker.
 *
 * ========================================================================
 * DETECTOR: DaemonThreadHygieneDetector
 * ========================================================================
 *
 * THE BUG:
 * BackgroundWorker.start() creates a Thread without calling setDaemon(true).
 * User (non-daemon) threads prevent JVM shutdown. Each test invocation that
 * calls start() leaves a live user thread behind, accumulating over many
 * invocations and delaying process exit.
 *
 * WHY @Test PASSES:
 * A single test finishes quickly and the JVM does not attempt to exit mid-run.
 * The non-daemon thread is invisible unless you explicitly check its daemon status.
 *
 * WHY @AsyncTest DETECTS:
 * DaemonThreadHygieneDetector.recordThread() is called for every new thread.
 * After all invocations, it checks which recorded threads are still alive and
 * not marked as daemon, reporting them as hygiene violations.
 *
 * FIX:
 * Call thread.setDaemon(true) before thread.start(), or use a ThreadFactory
 * that sets daemon status on every created thread.
 */
class BackgroundWorkerTest {

    private BackgroundWorker worker;

    @BeforeEach
    void setUp() {
        worker = new BackgroundWorker();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes but gives false confidence
    // -------------------------------------------------------------------------

    @Test
    void testStart_singleThread_taskCountIncremented() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Thread t = worker.start("test", done::countDown);
        assertTrue(done.await(2, TimeUnit.SECONDS), "Task should complete");
        assertEquals(1, worker.getTaskCount());
        t.join(2000);
    }

    @Test
    void testStart_createsNonDaemonThread() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Thread t = worker.start("check", latch::countDown);
        // The bug: the thread is NOT a daemon thread
        assertFalse(t.isDaemon(), "Bug confirmed: thread was not set as daemon");
        latch.await(2, TimeUnit.SECONDS);
        t.join(2000);
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the concurrency bug
    // -------------------------------------------------------------------------

    /**
     * With 8 concurrent threads each spawning a background thread, the detector
     * records every new thread. At analysis time it reports threads that are still
     * alive and not marked as daemon — a JVM-shutdown hygiene violation.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test
     * 3. To fix: add thread.setDaemon(true) in BackgroundWorker.start()
     */
    @Disabled("Remove @Disabled to see the bug detected by DaemonThreadHygieneDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectDaemonThreadHygiene = true)
    void testStart_concurrent_detectsNonDaemonThread() {
        Thread t = worker.start("async-" + Thread.currentThread().threadId(), () -> {
            // Simulate short background work
            long sum = 0;
            for (int i = 0; i < 1000; i++) sum += i;
            if (sum < 0) throw new RuntimeException("unreachable");
        });

        // Instrument the detector with the newly started thread
        AsyncTestContext.daemonThreadHygieneDetector()
                .recordThread(t, "background-worker");
    }
}
