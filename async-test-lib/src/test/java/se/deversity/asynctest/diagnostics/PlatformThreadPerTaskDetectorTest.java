package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PlatformThreadPerTaskDetector}: short-lived platform-thread churn above
 * the threshold is flagged, live pool workers and virtual threads are not, and a thread-per-task
 * executor backed by platform threads is identified by its probe.
 */
class PlatformThreadPerTaskDetectorTest {

    private PlatformThreadPerTaskDetector detector;

    @BeforeEach
    void setUp() {
        detector = new PlatformThreadPerTaskDetector();
    }

    @Test
    void churnAboveThreshold_withTerminatedThreads_isFlagged() throws InterruptedException {
        detector.setChurnThreshold(4);
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(() -> { }, "per-task-" + i);
            detector.recordThreadCreated(t);
            t.start();
            t.join();
        }

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "4 dead one-task platform threads at threshold 4 must be flagged: " + report);
        assertFalse(report.structuredViolations.isEmpty(), "A structured Violation must be emitted");
        assertTrue(report.toString().contains("4 platform threads"),
                "The report carries the creation count: " + report);
    }

    @Test
    void churnBelowThreshold_isClean() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            Thread t = new Thread(() -> { }, "few-" + i);
            detector.recordThreadCreated(t);
            t.start();
            t.join();
        }

        assertFalse(detector.analyze().hasIssues(),
                "3 platform threads under the default threshold of "
                        + PlatformThreadPerTaskDetector.DEFAULT_CHURN_THRESHOLD + " must be clean");
    }

    @Test
    void longLivedPoolWorkers_areNotChurn() throws InterruptedException {
        detector.setChurnThreshold(2);
        CountDownLatch release = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Thread worker = new Thread(() -> {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "pool-worker-" + i);
            detector.recordThreadCreated(worker);
            worker.start();
            workers.add(worker);
        }

        try {
            assertFalse(detector.analyze().hasIssues(),
                    "Live pool workers are not per-task churn and must not be flagged");
        } finally {
            release.countDown();
            for (Thread worker : workers) {
                worker.join();
            }
        }
    }

    @Test
    void virtualThreads_doNotFeedTheChurnSignal() {
        for (int i = 0; i < PlatformThreadPerTaskDetector.DEFAULT_CHURN_THRESHOLD + 4; i++) {
            detector.recordThreadCreated(Thread.ofVirtual().unstarted(() -> { }));
        }

        assertFalse(detector.analyze().hasIssues(),
                "Virtual threads are the fix, not the finding");
    }

    @Test
    void threadPerTaskExecutorWithPlatformFactory_isFlagged() {
        try (ExecutorService perTask = Executors.newThreadPerTaskExecutor(Thread.ofPlatform().factory())) {
            detector.registerExecutor(perTask, "platform-per-task");
        }

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "Thread-per-task on platform threads must be flagged: " + report);
        assertTrue(report.toString().contains("platform-per-task"),
                "The report names the registered executor: " + report);
    }

    @Test
    void virtualThreadPerTaskExecutor_isClean() {
        try (ExecutorService perTask = Executors.newVirtualThreadPerTaskExecutor()) {
            detector.registerExecutor(perTask, "virtual-per-task");
        }

        assertFalse(detector.analyze().hasIssues(),
                "newVirtualThreadPerTaskExecutor is the correct pattern and must not be flagged");
    }

    @Test
    void pooledExecutor_isNotProbed() {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            detector.registerExecutor(pool, "bounded-pool");
        } finally {
            pool.shutdownNow();
        }

        assertFalse(detector.analyze().hasIssues(),
                "A bounded pool is a legitimate choice — not this detector's finding");
    }

    @Test
    void nullInputs_areIgnored() {
        detector.recordThreadCreated(null);
        detector.registerExecutor(null, "null-executor");

        assertFalse(detector.analyze().hasIssues());
    }

    @Test
    void analyze_isIdempotent_onceThreadsTerminated() throws InterruptedException {
        detector.setChurnThreshold(2);
        for (int i = 0; i < 2; i++) {
            Thread t = new Thread(() -> { }, "idem-" + i);
            detector.recordThreadCreated(t);
            t.start();
            t.join();
        }

        String first = detector.analyze().toString();
        String second = detector.analyze().toString();
        assertEquals(first, second, "analyze() must be idempotent on quiescent state");
    }
}
