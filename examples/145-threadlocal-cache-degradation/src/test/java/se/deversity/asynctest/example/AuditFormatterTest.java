package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.diagnostics.ThreadLocalCacheDegradationDetector;
import se.deversity.asynctest.example.service.AuditFormatter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for AuditFormatter.
 *
 * ========================================================================
 * DETECTOR: ThreadLocalCacheDegradationDetector
 *           (DetectorType.THREAD_LOCAL_CACHE_DEGRADATION)
 * ========================================================================
 *
 * ThreadLocal<SimpleDateFormat> is the standard fix for a helper that is
 * not thread-safe, and on a pool it is a good one: eight workers means
 * eight formatters, built once and reused for the life of the process.
 * The instance count is bounded by the pool, which is why nobody counts
 * it.
 *
 * Virtual threads remove that bound. A thread per task means an instance
 * per task, so the same line now allocates a formatter for every request
 * and retains it for as long as its thread lives. Nothing fails - the
 * object is still confined to one thread, so it is still correct. It has
 * simply stopped being a cache, and the code reads exactly as it did
 * when it was one.
 *
 * VIRTUAL_THREAD_CONTEXT_LEAKS does not see this: it counts distinct
 * ThreadLocal KEYS per thread. Here there is one key, and the question
 * is how many INSTANCES it produced.
 *
 * THE BUG:
 *   - ThreadLocal<SimpleDateFormat> read from a thread per task
 *
 * THE FIX:
 *   - DateTimeFormatter, which is immutable, so one instance is shared
 *     by every thread and the ThreadLocal disappears
 *   - or pool the helper rather than the thread, borrowing it per call
 */
class AuditFormatterTest {

    private static final int TASKS = 8;
    private static final Instant WHEN = Instant.parse("2026-08-17T12:00:00Z");

    private ThreadLocalCacheDegradationDetector detector;

    @BeforeEach
    void setUp() {
        detector = new ThreadLocalCacheDegradationDetector();
        AuditFormatter.resetCount();
    }

    // -----------------------------------------------------------------------
    // Part 1: the buggy shape. A thread per task means a formatter per task -
    // the cache caches nothing.
    // -----------------------------------------------------------------------

    @Test
    void oneFormatterPerVirtualThread_isDetected() throws InterruptedException {
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < TASKS; i++) {
            workers.add(Thread.ofVirtual().start(() -> {
                AuditFormatter.formatPerThread(WHEN);
                detector.recordCachedValue("AUDIT_FORMAT", AuditFormatter.PER_THREAD.get(),
                        Thread.currentThread());
            }));
        }
        for (Thread t : workers) t.join();

        assertEquals(TASKS, AuditFormatter.constructedCount(),
                "eight tasks built eight formatters - nothing was reused");

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "a cache that caches nothing:\n" + report);
        var v = report.structuredViolations.get(0);
        assertEquals(IssueSeverity.MEDIUM, v.severity());
        assertEquals(TASKS, v.attributes().get("virtualInstances"));
        assertEquals("SimpleDateFormat", v.attributes().get("valueType"));
    }

    // -----------------------------------------------------------------------
    // Part 2: the same code on a pool, which is why it was fine for years.
    // Eight tasks, two workers, two formatters - the bound is real.
    // -----------------------------------------------------------------------

    @Test
    void theSameThreadLocalOnAPool_isClean() throws InterruptedException {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < TASKS; i++) {
                pool.submit(() -> {
                    AuditFormatter.formatPerThread(WHEN);
                    detector.recordCachedValue("AUDIT_FORMAT", AuditFormatter.PER_THREAD.get(),
                            Thread.currentThread());
                });
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertTrue(AuditFormatter.constructedCount() <= 2,
                "at most one formatter per pooled worker, however many tasks run");

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                () -> "on a pool the instance count is bounded, which is the point:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 3: the fixed shape. DateTimeFormatter is immutable, so one shared
    // instance serves every virtual thread and no ThreadLocal is needed.
    // -----------------------------------------------------------------------

    @Test
    void sharedImmutableFormatter_isClean() throws InterruptedException {
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < TASKS; i++) {
            workers.add(Thread.ofVirtual().start(() -> {
                AuditFormatter.formatShared(WHEN);
                detector.recordCachedValue("AUDIT_FORMAT", AuditFormatter.SHARED, Thread.currentThread());
            }));
        }
        for (Thread t : workers) t.join();

        assertEquals(0, AuditFormatter.constructedCount(), "no mutable formatter was ever built");

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                () -> "one instance across every thread is still a cache:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 4: the other fix. Keep the mutable helper but pool the helper
    // rather than the thread - eight tasks, two borrowed formatters.
    // -----------------------------------------------------------------------

    @Test
    void aPooledHelperBorrowedPerCall_isClean() throws InterruptedException {
        Object borrowedA = new Object();
        Object borrowedB = new Object();

        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < TASKS; i++) {
            Object borrowed = (i % 2 == 0) ? borrowedA : borrowedB;
            workers.add(Thread.ofVirtual().start(() ->
                    detector.recordCachedValue("BORROWED_FORMAT", borrowed, Thread.currentThread())));
        }
        for (Thread t : workers) t.join();

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                () -> "2 instances across 8 threads is reuse, which is the fix:\n" + report);
    }
}
