package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.diagnostics.DaemonThreadHygieneDetector;
import se.deversity.asynctest.example.service.BackgroundWorker;
import org.junit.jupiter.api.AfterEach;
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
 * After all invocations, it checks which recorded threads are STILL ALIVE and
 * not marked as daemon, and reports those.
 *
 * "Still alive" is the part this example used to miss. A thread that has already
 * terminated cannot hold the JVM open, so the detector deliberately says nothing
 * about it - and the task the demonstration started was a thousand additions,
 * over in microseconds, dead long before analysis. Enabling the demonstration
 * reported nothing, however many threads it started. See issue #346.
 *
 * So the demonstration starts pollers instead: background threads that keep
 * running until they are told to stop, which is what a background worker
 * usually is and the only shape in which a missing daemon flag costs anything.
 * @AfterEach stops them.
 *
 * WHY THE SERVICE BUILDS ITS POLLER WITH A ThreadFactory:
 * A thread inherits the daemon flag of the thread that created it, and @AsyncTest
 * runs test bodies on daemon workers in both thread modes - virtual threads always
 * are, and the platform workers were made daemon so a deadlocked one could not hold
 * the JVM open (#479). So a poller built with `new Thread(...)` inside the body is
 * already a daemon thread whatever BackgroundWorker does, and this detector has
 * nothing to report however wrong the service is. useVirtualThreads = false was the
 * documented way round that until 2026-09-03 and is not one any more (#730).
 * Executors.defaultThreadFactory(), which is what every JDK thread pool uses, calls
 * setDaemon(false) on each thread it hands back whoever calls it, so the flag is the
 * service's decision again and the demonstration demonstrates.
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

    /**
     * Stops every poller this test started. Without it the non-daemon threads would keep the
     * JVM alive, which is the bug working as advertised and no way to run a build.
     */
    @AfterEach
    void stopPollers() {
        worker.shutdown();
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

    /**
     * The detector's positive direction: a non-daemon thread that is still running when the
     * report is taken.
     */
    @Test
    void testDaemonThreadHygieneDetector_liveNonDaemonThread_reports() {
        DaemonThreadHygieneDetector detector = new DaemonThreadHygieneDetector();

        Thread poller = worker.startPoller("live");
        detector.recordThread(poller, "background-poller");

        assertTrue(detector.analyze().hasIssues(),
                "a non-daemon thread still alive at analysis time blocks JVM exit");
    }

    /**
     * And the other direction, twice over, because there are two ways to be fine: be a daemon,
     * or be finished.
     */
    @Test
    void testDaemonThreadHygieneDetector_finishedThread_isSilent() throws Exception {
        DaemonThreadHygieneDetector detector = new DaemonThreadHygieneDetector();

        Thread quick = worker.start("quick", () -> { });
        detector.recordThread(quick, "background-worker");
        quick.join(2000);

        assertFalse(quick.isAlive(), "the task was a no-op, so the thread is done");
        assertFalse(detector.analyze().hasIssues(),
                "a thread that has terminated cannot hold the JVM open");
    }

    @Test
    void testDaemonThreadHygieneDetector_liveDaemonThread_isSilent() throws Exception {
        DaemonThreadHygieneDetector detector = new DaemonThreadHygieneDetector();
        CountDownLatch release = new CountDownLatch(1);

        Thread daemon = new Thread(() -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "daemon-poller");
        daemon.setDaemon(true);
        daemon.start();
        try {
            detector.recordThread(daemon, "daemon-poller");

            assertTrue(daemon.isAlive(), "still waiting, so still alive");
            assertFalse(detector.analyze().hasIssues(),
                    "a daemon thread does not block JVM exit, however long it runs");
        } finally {
            release.countDown();
            daemon.join(2000);
        }
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the concurrency bug
    // -------------------------------------------------------------------------

    /**
     * With 8 concurrent threads each spawning a background thread, the detector
     * records every new thread. At analysis time it reports threads that are still
     * alive and not marked as daemon — a JVM-shutdown hygiene violation.
     *
     * <p>The threads it can report are the ones {@code BackgroundWorker}'s
     * {@code ThreadFactory} produced; see the class comment above for why a bare
     * {@code new Thread(...)} in this body could not be one of them.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test
     * 3. To fix: add thread.setDaemon(true) in BackgroundWorker.start()
     */
    @Disabled("Remove @Disabled to see the bug detected by DaemonThreadHygieneDetector")
    // No useVirtualThreads = false: it used to be what made this detector able to see anything,
    // and stopped being so when the runner's platform workers became daemon threads too (#730).
    // The finding comes from the service's ThreadFactory instead, which decides the daemon flag
    // whoever calls it - so this fires on the runner everybody actually gets, the default one.
    @AsyncTest(threads = 8, invocations = 5, detectAll = false,
            detectDaemonThreadHygiene = true, failOn = FailOn.LOW)
    void testStart_concurrent_detectsNonDaemonThread() {
        // A poller, not a thousand additions. The detector reports non-daemon threads that are
        // still alive when the run is analysed, and the old task was over in microseconds.
        Thread poller = worker.startPoller("async-" + Thread.currentThread().threadId());

        AsyncTestContext.daemonThreadHygieneDetector()
                .recordThread(poller, "background-poller");
    }
}
