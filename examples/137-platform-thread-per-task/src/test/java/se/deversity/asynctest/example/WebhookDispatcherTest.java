package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.PlatformThreadPerTaskDetector;
import se.deversity.asynctest.example.service.WebhookDispatcher;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for WebhookDispatcher.
 *
 * ========================================================================
 * DETECTOR: PlatformThreadPerTaskDetector
 *           (DetectorType.PLATFORM_THREAD_PER_TASK)
 * ========================================================================
 *
 * One OS thread per task is the pattern virtual threads (JEP 444) exist
 * to replace. Each platform thread reserves an OS thread and ~1 MB of
 * stack; the pattern survives every unit test and collapses under the
 * production burst that matters.
 *
 * THE BUG:
 *   - new Thread(() -> deliver(payload)).start() for every webhook —
 *     unbounded OS-thread creation tied directly to load
 *
 * THE FIX:
 *   - Thread.startVirtualThread(...) (or newVirtualThreadPerTaskExecutor):
 *     the model stays thread-per-task, the thread kind changes
 *
 * HOW THE DETECTOR SEES IT:
 *   - recordThreadCreated, called once per created thread, feeds a churn
 *     signal: at the threshold (lowered here from the default 16 to keep
 *     the example small), platform threads that have already terminated
 *     read as per-task churn — live pool workers never trip it
 *   - registerExecutor probes a thread-per-task executor with one no-op
 *     task to learn the actual thread kind
 */
class WebhookDispatcherTest {

    private static final List<String> PAYLOADS =
            List.of("order.created", "order.paid", "order.shipped", "order.closed");

    private WebhookDispatcher dispatcher;
    private PlatformThreadPerTaskDetector detector;

    @BeforeEach
    void setUp() {
        dispatcher = new WebhookDispatcher();
        detector = new PlatformThreadPerTaskDetector();
    }

    // -----------------------------------------------------------------------
    // Part 1: the fixed dispatcher. Virtual threads feed only the
    // informational balance, never the churn signal.
    // -----------------------------------------------------------------------

    @Test
    void virtualThreadPerDelivery_isClean() throws InterruptedException {
        detector.setChurnThreshold(PAYLOADS.size());
        for (Thread worker : dispatcher.dispatchOnVirtualThreads(PAYLOADS, detector::recordThreadCreated)) {
            worker.join();
        }

        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "Virtual thread-per-task must be clean:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 2: the buggy dispatcher. Four short-lived platform threads at a
    // threshold of four — created, delivered, died: that is churn.
    // -----------------------------------------------------------------------

    @Test
    void platformThreadPerDelivery_isDetected() throws InterruptedException {
        detector.setChurnThreshold(PAYLOADS.size());
        for (Thread worker : dispatcher.dispatchOnPlatformThreads(PAYLOADS, detector::recordThreadCreated)) {
            worker.join();
        }

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "Platform thread-per-task churn must be flagged:\n" + report);
        assertTrue(report.toString().contains("platform threads"), report.toString());
    }

    // -----------------------------------------------------------------------
    // Part 3: the same mistake dressed up as an executor. A per-task executor
    // over a platform factory is identified by its probe; the virtual twin is
    // the fix and stays clean.
    // -----------------------------------------------------------------------

    @Test
    void threadPerTaskExecutorOnPlatformThreads_isDetected() {
        try (ExecutorService perTask = Executors.newThreadPerTaskExecutor(Thread.ofPlatform().factory())) {
            detector.registerExecutor(perTask, "webhook-platform-per-task");
        }

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "A platform-backed per-task executor must be flagged:\n" + report);
        assertTrue(report.toString().contains("webhook-platform-per-task"), report.toString());
    }

    @Test
    void threadPerTaskExecutorOnVirtualThreads_isClean() {
        try (ExecutorService perTask = Executors.newVirtualThreadPerTaskExecutor()) {
            detector.registerExecutor(perTask, "webhook-virtual-per-task");
        }

        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "The virtual per-task executor must be clean:\n" + report);
    }
}
