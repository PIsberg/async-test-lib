package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LivelockDetector.
 */
public class LivelockDetectorTest {

    @Test
    void emptySnapshotsReturnNoIssues() {
        LivelockDetector detector = new LivelockDetector();

        LivelockDetector.LivelockReport report = detector.analyzeLivelocks();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "No snapshots captured — should report no issues");
    }

    @Test
    void captureSnapshotRunsWithoutError() {
        LivelockDetector detector = new LivelockDetector();

        // captureSnapshot() must not throw under normal JVM conditions
        assertDoesNotThrow(detector::captureSnapshot);
    }

    @Test
    void reportHasIssuesFalseWhenEmpty() {
        LivelockDetector.LivelockReport report = new LivelockDetector.LivelockReport();

        assertFalse(report.hasIssues(),
                "Empty report (no starved, livelock, or no-progress threads) must not have issues");
    }

    @Test
    void reportToStringNoIssuesMessage() {
        LivelockDetector.LivelockReport report = new LivelockDetector.LivelockReport();

        String text = report.toString();
        assertNotNull(text);
        assertEquals("No livelock or starvation issues detected.", text,
                "Empty report toString() should return the clean no-issue message");
    }

    @Test
    void reportToStringWithIssuesContainsDetails() {
        LivelockDetector.LivelockReport report = new LivelockDetector.LivelockReport();
        report.starvedThreads.add("worker-1");
        report.livelockCandidates.add("worker-2");
        report.noProgressThreads.add("worker-3");

        String text = report.toString();

        assertNotNull(text);
        assertTrue(text.contains("LIVELOCK"), "Should mention LIVELOCK / STARVATION in header");
        assertTrue(text.contains("worker-1"), "Should list starved thread");
        assertTrue(text.contains("worker-2"), "Should list livelock candidate");
        assertTrue(text.contains("worker-3"), "Should list no-progress thread");
        assertTrue(report.hasIssues(), "Populated report must report hasIssues() == true");
    }

    @Test
    void resetClearsState() {
        LivelockDetector detector = new LivelockDetector();

        // Capture some snapshots
        detector.captureSnapshot();
        detector.captureSnapshot();

        detector.reset();

        // After reset the history should be empty — no issues can be detected
        LivelockDetector.LivelockReport report = detector.analyzeLivelocks();
        assertFalse(report.hasIssues(), "After reset() no issues should be detectable");
    }

    @Test
    void analyzeAfterCaptureReturnsReport() {
        LivelockDetector detector = new LivelockDetector();

        detector.captureSnapshot();
        LivelockDetector.LivelockReport report = detector.analyzeLivelocks();

        assertNotNull(report, "analyzeLivelocks() must return a non-null report");
        // A single snapshot cannot produce starvation/livelock findings (requires >=2 snapshots)
        assertFalse(report.hasIssues(),
                "Single snapshot is insufficient for livelock/starvation detection");
    }

    /**
     * The limit the catalog used to describe backwards: a busy spin is not reported.
     *
     * <p>{@code madeProgress} returns true for any RUNNABLE thread, on purpose, because a busy
     * worker's measured CPU time can look flat when several snapshots land inside one clock tick
     * and reporting those produced findings against healthy JVMs. The consequence is that a
     * spin-retry loop burning attempts without completing work - which is what the word livelock
     * usually means - is not something this detector reports. {@code examples/07-livelock} was
     * retired over exactly this in #362, and the catalog claimed the opposite until #373.
     *
     * <p>Pinned rather than written down, so the prose cannot drift back.
     */
    @Test
    void aBusyRunnableThreadIsNotReported() throws Exception {
        LivelockDetector detector = new LivelockDetector();

        Thread spinner = new Thread(() -> {
            long sink = 0;
            for (int i = 0; i < 40; i++) {
                for (int j = 0; j < 50_000; j++) {
                    sink += j;                      // genuinely on the CPU between snapshots
                }
                if (sink == Long.MIN_VALUE) {
                    throw new IllegalStateException("unreachable");
                }
                detector.captureSnapshot();
            }
        }, "busy-spinner");
        spinner.start();
        spinner.join(30_000);

        LivelockDetector.LivelockReport report = detector.analyzeLivelocks();
        assertFalse(report.hasIssues(),
                "forty snapshots of a thread that was RUNNABLE throughout. This detector reports "
                        + "starvation and rapid state cycling, not a busy spin, and a finding here "
                        + "would be a false positive on a thread that was doing work the whole "
                        + "time. Report: " + report);
    }

    /**
     * Two threads handing a value back and forth through {@link SynchronousQueue}s, each sampling
     * after its own step, is correct code that finishes: every hand-off is progress. By the rule
     * alone each thread alternates between RUNNABLE, when it samples, and WAITING, when its peer does,
     * which is exactly the "rapid state cycling" a livelock was read from (#1180).
     */
    @Test
    void aCorrectPingPongSampledByItsOwnThreadsIsNotALivelock() throws Exception {
        LivelockDetector detector = new LivelockDetector();
        SynchronousQueue<Integer> ping = new SynchronousQueue<>();
        SynchronousQueue<Integer> pong = new SynchronousQueue<>();
        int rounds = 40;
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread server = new Thread(() -> {
            try {
                for (int i = 0; i < rounds; i++) {
                    int v = ping.take();
                    detector.captureSnapshot();
                    pong.put(v + 1);
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "pong-server");
        Thread client = new Thread(() -> {
            try {
                for (int i = 0; i < rounds; i++) {
                    ping.put(i);
                    detector.captureSnapshot();
                    if (pong.take() != i + 1) {
                        throw new AssertionError("hand-off " + i + " returned the wrong value");
                    }
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "ping-client");
        server.start();
        client.start();
        client.join(30_000);
        server.join(30_000);
        assertNull(failure.get(), "the ping-pong itself failed: " + failure.get());
        assertFalse(client.isAlive() || server.isAlive(), "the ping-pong did not finish");

        LivelockDetector.LivelockReport report = detector.analyzeLivelocks();
        assertTrue(report.livelockCandidates.isEmpty(),
                "forty completed hand-offs between two threads are progress, not a livelock. Report: "
                        + report);
    }

    /**
     * The same correct hand-off, sampled the way the javadoc offers {@code captureSnapshot()}: by a
     * thread watching its peers. Each worker does a little work per step and waits for the other in
     * between, so a sampler sees each one swing between RUNNABLE and WAITING while every step
     * completes (#1180).
     */
    @Test
    void aCorrectPingPongSampledByAWatcherIsNotALivelock() throws Exception {
        // The cycling rule this pins as gone fired in 12 of 50 single runs, so one run would let it
        // back in three times out of four. Ten independent trials miss it about once in sixteen.
        for (int trial = 0; trial < 10; trial++) {
            pingPongSampledByAWatcher(trial);
        }
    }

    private static void pingPongSampledByAWatcher(int trial) throws Exception {
        LivelockDetector detector = new LivelockDetector();
        SynchronousQueue<Integer> ping = new SynchronousQueue<>();
        SynchronousQueue<Integer> pong = new SynchronousQueue<>();
        int rounds = 200;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch registered = new CountDownLatch(2);

        Thread server = new Thread(() -> {
            try {
                detector.captureSnapshot();
                registered.countDown();
                for (int i = 0; i < rounds; i++) {
                    int v = ping.take();
                    pong.put(v + busyWork());
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "pong-server");
        Thread client = new Thread(() -> {
            try {
                detector.captureSnapshot();
                registered.countDown();
                registered.await();
                for (int i = 0; i < rounds; i++) {
                    ping.put(i + busyWork());
                    pong.take();
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "ping-client");
        server.start();
        client.start();
        registered.await();
        while (client.isAlive()) {
            detector.captureSnapshot();
            LockSupport.parkNanos(500_000);
        }
        client.join(30_000);
        server.join(30_000);
        assertNull(failure.get(), "the ping-pong itself failed: " + failure.get());

        LivelockDetector.LivelockReport report = detector.analyzeLivelocks();
        assertTrue(report.livelockCandidates.isEmpty(),
                "trial " + trial + ": two hundred completed hand-offs are progress, not a livelock. Report: " + report);
    }

    /** {@return zero, after some CPU work the JIT cannot drop} */
    private static int busyWork() {
        long sink = 0;
        for (int j = 0; j < 20_000; j++) {
            sink += j ^ (sink >>> 3);
        }
        return sink == Long.MIN_VALUE ? 1 : 0;
    }

    @Test
    void analyze_delegatesToAnalyzeLivelocks() {
        LivelockDetector detector = new LivelockDetector();
        detector.captureSnapshot();

        LivelockDetector.LivelockReport viaAnalyze = detector.analyze();
        LivelockDetector.LivelockReport viaAnalyzeLivelocks = detector.analyzeLivelocks();

        assertEquals(viaAnalyzeLivelocks.hasIssues(), viaAnalyze.hasIssues());
        assertEquals(viaAnalyzeLivelocks.toString(), viaAnalyze.toString());
    }
}
