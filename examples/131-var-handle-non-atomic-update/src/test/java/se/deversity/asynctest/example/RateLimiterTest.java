package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.VarHandleNonAtomicUpdateDetector;
import se.deversity.asynctest.diagnostics.VarHandleNonAtomicUpdateDetector.Mode;
import se.deversity.asynctest.example.service.RateLimiter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for RateLimiter.
 *
 * ========================================================================
 * DETECTOR: VarHandleNonAtomicUpdateDetector
 *           (DetectorType.VAR_HANDLE_NON_ATOMIC_UPDATE)
 * ========================================================================
 *
 * A volatile read and a volatile write are each indivisible. The pair is
 * not. getVolatile followed by setVolatile is a read-modify-write split
 * across two operations, with the whole world free to interleave in the
 * gap — two threads reading 5 both write 6, and one increment is lost.
 *
 * Volatile gives visibility. It never gives atomicity of a compound
 * update. That distinction is the entire bug, and it survives code review
 * often because both halves have "volatile" in the name.
 *
 * THE BUG:
 *   - COUNT.getVolatile(this) then COUNT.setVolatile(this, current + 1)
 *
 * THE FIX:
 *   - COUNT.getAndAdd(this, 1), or a compareAndSet retry loop when the new
 *     value is not a simple sum. One indivisible operation either way, at
 *     the cost the author was trying to get in the first place.
 *
 * A SECOND, DIFFERENT BUG:
 *   Plain-mode get/set carry no ordering at all. A plain write may never
 *   become visible to a reader on another thread. The detector reports
 *   that separately from the lost update, because the fix is different:
 *   the answer there is volatile mode, not an atomic operation.
 */
class RateLimiterTest {

    private VarHandleNonAtomicUpdateDetector detector;
    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        detector = new VarHandleNonAtomicUpdateDetector();
        limiter = new RateLimiter();
    }

    // -----------------------------------------------------------------------
    // Part 1: getAndAdd is one operation. Nothing to report.
    // -----------------------------------------------------------------------

    @Test
    void atomicGetAndAdd_isClean() {
        Thread t = Thread.currentThread();

        limiter.recordRequest();
        detector.recordAtomicUpdate(RateLimiter.countHandle(), limiter, "count", t);

        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "getAndAdd is indivisible and must stay silent:\n" + report);
        assertEquals(1, limiter.count());
    }

    // -----------------------------------------------------------------------
    // Part 2: the lost update. get-then-set on one field, flagged.
    // -----------------------------------------------------------------------

    @Test
    void getThenSet_isDetectedAsALostUpdate() {
        Thread t = Thread.currentThread();

        int current = limiter.count();
        detector.recordGet(RateLimiter.countHandle(), limiter, "count", Mode.VOLATILE, t);
        limiter.recordRequestNonAtomically();
        detector.recordSet(RateLimiter.countHandle(), limiter, "count", Mode.VOLATILE, t);

        var report = detector.analyze();
        assertTrue(report.hasIssues(),
                () -> "get-then-set through a VarHandle must be flagged:\n" + report);
        assertTrue(report.toString().contains("count"), report.toString());
        assertEquals(current + 1, limiter.count());
    }

    // -----------------------------------------------------------------------
    // Part 3: plain mode across threads — a visibility bug, not a lost
    // update, and reported as its own finding.
    // -----------------------------------------------------------------------

    @Test
    void plainModeAcrossThreads_isDetected() {
        Thread writer = new Thread(() -> { }, "limiter-writer");
        Thread reader = new Thread(() -> { }, "limiter-reader");

        limiter.openGateWithPlainWrite();
        detector.recordSet(RateLimiter.plainFlagHandle(), limiter, "flag", Mode.PLAIN, writer);
        detector.recordGet(RateLimiter.plainFlagHandle(), limiter, "flag", Mode.PLAIN, reader);

        var report = detector.analyze();
        assertTrue(report.hasIssues(),
                () -> "Plain-mode sharing across threads must be flagged:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 4: why it matters. Run the buggy increment on real threads and
    // the counter ends up short — the rate limiter lets requests through.
    // -----------------------------------------------------------------------

    @Test
    void lostUpdatesUnderRealContention_leaveTheCounterShort() throws Exception {
        var racy = new RateLimiter();
        var safe = new RateLimiter();
        int threads = 4;
        int perThread = 5_000;

        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            workers.add(new Thread(() -> {
                for (int n = 0; n < perThread; n++) {
                    racy.recordRequestNonAtomically();
                    safe.recordRequest();
                }
            }, "limiter-" + i));
        }
        for (Thread w : workers) {
            w.start();
        }
        for (Thread w : workers) {
            w.join();
        }

        int expected = threads * perThread;
        assertEquals(expected, safe.count(), "getAndAdd must not lose an increment");
        assertTrue(racy.count() <= expected,
                "the racy counter can only undercount, never exceed: " + racy.count());
    }
}
