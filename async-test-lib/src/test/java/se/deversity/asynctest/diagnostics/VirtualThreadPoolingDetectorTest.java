package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link VirtualThreadPoolingDetector}: a pooled executor manufacturing virtual
 * threads is flagged, per-task and platform-pool executors are not, and a virtual thread observed
 * running more than one task is flagged as reuse.
 */
class VirtualThreadPoolingDetectorTest {

    private VirtualThreadPoolingDetector detector;

    @BeforeEach
    void setUp() {
        detector = new VirtualThreadPoolingDetector();
    }

    @Test
    void fixedPoolWithVirtualFactory_isFlagged() {
        ExecutorService pool = Executors.newFixedThreadPool(2, Thread.ofVirtual().factory());
        try {
            detector.registerExecutor(pool, "virtual-fixed-pool");
        } finally {
            pool.shutdownNow();
        }

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "Pooling virtual threads must be flagged: " + report);
        assertFalse(report.structuredViolations.isEmpty(), "A structured Violation must be emitted");
        assertTrue(report.toString().contains("virtual-fixed-pool"),
                "The report names the registered executor: " + report);
    }

    @Test
    void scheduledPoolWithVirtualFactory_isFlagged() {
        var pool = Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());
        try {
            detector.registerExecutor(pool, "virtual-scheduled-pool");
        } finally {
            pool.shutdownNow();
        }

        assertTrue(detector.analyze().hasIssues(),
                "ScheduledThreadPoolExecutor extends ThreadPoolExecutor and must be probed too");
    }

    @Test
    void virtualThreadPerTaskExecutor_isClean() {
        try (ExecutorService perTask = Executors.newVirtualThreadPerTaskExecutor()) {
            detector.registerExecutor(perTask, "per-task");
        }

        assertFalse(detector.analyze().hasIssues(),
                "newVirtualThreadPerTaskExecutor is the correct pattern and must not be flagged");
    }

    @Test
    void fixedPoolWithPlatformFactory_isClean() {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            detector.registerExecutor(pool, "platform-pool");
        } finally {
            pool.shutdownNow();
        }

        assertFalse(detector.analyze().hasIssues(),
                "Pooling platform threads is what pools are for — not this detector's finding");
    }

    @Test
    void virtualThreadReuseAcrossTasks_isFlagged() throws InterruptedException {
        Thread worker = Thread.ofVirtual().name("reused-vt").start(() -> {
            detector.recordTaskExecution("hand-rolled-pool");
            detector.recordTaskExecution("hand-rolled-pool");
        });
        worker.join();

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "Two tasks on one virtual thread must be flagged: " + report);
        assertTrue(report.toString().contains("reused-vt"),
                "The report names the reused thread: " + report);
    }

    @Test
    void singleTaskPerVirtualThread_isClean() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            Thread.ofVirtual().name("vt-" + i).start(() -> detector.recordTaskExecution("per-task")).join();
        }

        assertFalse(detector.analyze().hasIssues(),
                "One task per virtual thread is the designed use and must be clean");
    }

    @Test
    void platformThreadReuse_isIgnored() throws InterruptedException {
        Thread worker = new Thread(() -> {
            detector.recordTaskExecution("pool");
            detector.recordTaskExecution("pool");
        }, "platform-worker");
        worker.start();
        worker.join();

        assertFalse(detector.analyze().hasIssues(),
                "Reusing platform threads is what pools are for — must be clean");
    }

    @Test
    void nullInputs_areIgnored() {
        detector.registerExecutor(null, "null-executor");
        detector.recordTaskExecution("task", null);

        assertFalse(detector.analyze().hasIssues());
    }

    @Test
    void analyze_isIdempotent() {
        ExecutorService pool = Executors.newFixedThreadPool(1, Thread.ofVirtual().factory());
        try {
            detector.registerExecutor(pool, "virtual-pool");
        } finally {
            pool.shutdownNow();
        }

        String first = detector.analyze().toString();
        String second = detector.analyze().toString();
        assertEquals(first, second, "analyze() must be idempotent on quiescent state");
    }
}
