package se.deversity.asynctest.example;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.HighContentionAtomicDetector;
import se.deversity.asynctest.example.service.RequestMetricsService;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for RequestMetricsService.
 *
 * ========================================================================
 * DETECTOR: HighContentionAtomicDetector
 *           (DetectorType.HIGH_CONTENTION_ATOMIC)
 * ========================================================================
 *
 * This one is an ADVISORY, not a correctness bug — severity LOW. AtomicLong
 * is thread-safe and incrementAndGet() always returns the right number. The
 * finding is about throughput: incrementAndGet() is a CAS loop, and under
 * contention every thread retries against one cache line ping-ponging
 * between cores. Throughput collapses long before the CPUs are busy.
 *
 * THE PATTERN:
 *   - one AtomicLong counter incremented by every request thread
 *
 * THE RECOMMENDATION:
 *   - LongAdder for statistics counters: same total, contention spread
 *     across per-thread cells
 *
 * THE THING TO KEEP IN MIND:
 *   - LongAdder gives up the value-with-the-update guarantee. Sequence
 *     numbers, ID allocation and threshold checks need AtomicLong, and the
 *     advisory is wrong for them. That is why it is LOW and not a failure.
 *
 * Three conditions must all hold before anything is reported: at least two
 * distinct threads, at least `attemptThreshold` attempts, and a failed-CAS
 * ratio of at least 10%. A quiet counter is never flagged.
 */
class RequestMetricsServiceTest {

    /** Production default is 1000 attempts; a lower bar keeps the test fast. */
    private static final long TEST_THRESHOLD = 100L;

    // -----------------------------------------------------------------------
    // Part 1: uncontended — one thread, every CAS succeeds. Nothing to report.
    // -----------------------------------------------------------------------

    @Test
    void uncontendedCounter_isClean() {
        var detector = new HighContentionAtomicDetector(TEST_THRESHOLD);
        var service = new RequestMetricsService();
        var counter = new AtomicLong();

        for (int i = 0; i < 500; i++) {
            detector.recordCasAttempt(counter, true);
            service.recordRequest();
        }

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                () -> "One thread and no failed CAS is not contention:\n" + report);
        assertEquals(500L, service.requestCount());
    }

    // -----------------------------------------------------------------------
    // Part 2: two threads, but almost every CAS succeeds. Still clean —
    // sharing a counter is not the problem, losing races on it is.
    // -----------------------------------------------------------------------

    @Test
    void sharedButLowContentionCounter_isClean() throws Exception {
        var detector = new HighContentionAtomicDetector(TEST_THRESHOLD);
        var counter = new AtomicLong();

        Runnable worker = () -> {
            for (int i = 0; i < 200; i++) {
                detector.recordCasAttempt(counter, i % 50 != 0);   // 2% failures
            }
        };
        Thread a = new Thread(worker, "metrics-a");
        Thread b = new Thread(worker, "metrics-b");
        a.start();
        b.start();
        a.join();
        b.join();

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                () -> "A 2% failure ratio is below the 10% advisory threshold:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 3: two threads losing CAS races on one counter — advisory raised.
    // -----------------------------------------------------------------------

    @Test
    void hotCounterAcrossThreads_isFlagged() throws Exception {
        var detector = new HighContentionAtomicDetector(TEST_THRESHOLD);
        var counter = new AtomicLong();

        Runnable worker = () -> {
            for (int i = 0; i < 200; i++) {
                detector.recordCasAttempt(counter, i % 2 == 0);    // 50% failures
            }
        };
        Thread a = new Thread(worker, "metrics-a");
        Thread b = new Thread(worker, "metrics-b");
        a.start();
        b.start();
        a.join();
        b.join();

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "Expected contention advisory:\n" + report);
        String violation = report.violations.get(0);
        assertTrue(violation.contains("AtomicLong"), violation);
        assertTrue(violation.contains("2 threads"), violation);
        assertTrue(violation.contains("50.0%"), violation);
        assertTrue(violation.contains("LongAdder"), violation);
    }

    // -----------------------------------------------------------------------
    // Part 4: the recommendation. LongAdder totals correctly under the same
    // load — it gives up the value-with-the-update, not the count.
    // -----------------------------------------------------------------------

    @Test
    void longAdder_totalsCorrectlyUnderTheSameLoad() throws Exception {
        var service = new RequestMetricsService();

        Runnable worker = () -> {
            for (int i = 0; i < 1_000; i++) {
                service.recordBytes(10L);
            }
        };
        Thread a = new Thread(worker, "adder-a");
        Thread b = new Thread(worker, "adder-b");
        a.start();
        b.start();
        a.join();
        b.join();

        assertEquals(20_000L, service.bytesServed(),
                "LongAdder.sum() is exact once the writers have finished");
    }

    // -----------------------------------------------------------------------
    // Part 5: when the advisory does NOT apply — a sequence number needs the
    // value AS PART OF the update, which is precisely what LongAdder drops.
    // -----------------------------------------------------------------------

    @Test
    void sequenceNumbers_stillNeedAtomicLong() {
        var service = new RequestMetricsService();

        long first = service.nextSequenceNumber();
        long second = service.nextSequenceNumber();

        assertEquals(first + 1, second,
                "incrementAndGet() hands back this caller's number — LongAdder.add() "
                        + "returns nothing and sum() is not atomic with the add");
    }
}
