package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.VirtualThreadPoolingDetector;
import se.deversity.asynctest.example.service.ThumbnailService;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for ThumbnailService.
 *
 * ========================================================================
 * DETECTOR: VirtualThreadPoolingDetector
 *           (DetectorType.VIRTUAL_THREAD_POOLING)
 * ========================================================================
 *
 * JEP 444, under "Do not pool virtual threads": a virtual thread is a
 * cheap, single-task object. The migration mistake this example models is
 * swapping Thread.ofVirtual().factory() into existing pool wiring, which
 * keeps the pool's cap, its queue, and its worker recycling — and gains
 * nothing from virtual threads at all.
 *
 * THE BUG:
 *   - Executors.newFixedThreadPool(4, Thread.ofVirtual().factory())
 *     caps concurrency at 4, keeps four never-terminating virtual
 *     workers alive with whatever ThreadLocals renders leave behind,
 *     and queues every submission above the cap
 *
 * THE FIX:
 *   - Executors.newVirtualThreadPerTaskExecutor(), one fresh virtual
 *     thread per render; bound downstream capacity with a Semaphore
 *     around the guarded operation, not with a smaller pool
 *
 * HOW THE DETECTOR SEES IT:
 *   - registerExecutor probes the executor's ThreadFactory with one
 *     unstarted, discarded thread — a ThreadPoolExecutor whose factory
 *     manufactures virtual threads is the finding
 *   - recordTaskExecution, called once per task, flags any virtual
 *     thread that runs a second task: reuse implies recycling upstream
 */
class ThumbnailServiceTest {

    private static final byte[] SOURCE_IMAGE = new byte[64];

    private VirtualThreadPoolingDetector detector;

    @BeforeEach
    void setUp() {
        detector = new VirtualThreadPoolingDetector();
    }

    // -----------------------------------------------------------------------
    // Part 1: the fixed service. A per-task executor has no pool to misuse,
    // and one task per virtual thread is exactly the designed use.
    // -----------------------------------------------------------------------

    @Test
    void perTaskVirtualExecutor_isClean() throws Exception {
        try (ThumbnailService service = ThumbnailService.perTaskVirtual()) {
            detector.registerExecutor(service.executor(), "thumbnail-per-task");
            service.executor().submit(() -> detector.recordTaskExecution("thumbnail-per-task")).get();
            service.render(SOURCE_IMAGE).get();
        }

        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "The per-task executor must be clean:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 2: the buggy service. The factory probe identifies a pool that
    // manufactures virtual threads without ever starting one.
    // -----------------------------------------------------------------------

    @Test
    void pooledVirtualExecutor_isDetected() {
        try (ThumbnailService service = ThumbnailService.pooledVirtual()) {
            detector.registerExecutor(service.executor(), "thumbnail-pool");
        }

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "Pooled virtual threads must be flagged:\n" + report);
        assertTrue(report.toString().contains("thumbnail-pool"), report.toString());
    }

    // -----------------------------------------------------------------------
    // Part 3: reuse observed at runtime. The same virtual thread running two
    // recorded tasks is the signature of a pool (or hand-rolled recycling),
    // and it is how ThreadLocal state leaks from one task into the next.
    // -----------------------------------------------------------------------

    @Test
    void virtualThreadRunningTwoTasks_isDetected() throws InterruptedException {
        Thread recycled = Thread.ofVirtual().name("recycled-worker").start(() -> {
            detector.recordTaskExecution("hand-rolled-pool");   // first render
            detector.recordTaskExecution("hand-rolled-pool");   // second render, same thread
        });
        recycled.join();

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "A reused virtual thread must be flagged:\n" + report);
        assertTrue(report.toString().contains("recycled-worker"), report.toString());
    }
}
