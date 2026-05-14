package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.diagnostics.AtomicityValidator;
import se.deversity.asynctest.example.service.HitCounterService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for HitCounterService.
 *
 * ========================================================================
 * DETECTOR: AtomicityValidator
 * ========================================================================
 *
 * This test demonstrates a common concurrency bug where:
 * - A sequential @Test PASSES (counts are always exact)
 * - The same test with @AsyncTest + AtomicityValidator reveals the
 *   non-atomic read-modify-write that loses increments under concurrency
 *
 * THE BUG:
 * HitCounterService.increment() performs three separate steps:
 *   1. Read:  long value = cell[0]
 *   2. Add:   long next  = value + 1
 *   3. Write: cell[0]    = next
 *
 * Under concurrent load with 10 threads all calling increment("/home"):
 *   - Thread A reads cell[0] = 42
 *   - Thread B reads cell[0] = 42  (same value, before A's write)
 *   - Thread A writes cell[0] = 43
 *   - Thread B writes cell[0] = 43  (B's increment is lost!)
 *   - Expected: 44, Actual: 43
 *
 * WHY @Test PASSES:
 * Single-threaded increments are fully sequential. There is never a
 * concurrent reader to observe the intermediate state.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * AtomicityValidator tracks every recordFieldAccess() call across all
 * threads. When it observes that the same field name ("count") was both
 * read and written by multiple threads, it reports a TOCTOU (time-of-
 * check-to-time-of-use) violation and a mixed read/write unsynchronised
 * access. The @AfterEach assertion verifies that detection fired.
 *
 * DETECTORS TRIGGERED:
 * AtomicityValidator — Primary: non-atomic read-modify-write on "count"
 *
 * FIX:
 * - Replace long[] cell with AtomicLong and call cell.incrementAndGet()
 * - Or use LongAdder per page for the highest write throughput
 */
class HitCounterServiceTest {

    private HitCounterService service;
    private AtomicityValidator atomicityValidator;
    // Guard flag so the @AfterEach assertion only runs after the @AsyncTest.
    private volatile boolean runningAsyncTest = false;

    @BeforeEach
    void setUp() {
        service = new HitCounterService();
        atomicityValidator = new AtomicityValidator();
    }

    /**
     * After the @AsyncTest run completes, verify the validator detected
     * the non-atomic compound operation. @AfterEach runs once after all
     * threads and invocations finish.
     */
    @AfterEach
    void verifyAtomicityViolationDetected() {
        if (!runningAsyncTest) {
            return;
        }
        AtomicityValidator.AtomicityReport report = atomicityValidator.analyzeAtomicity();
        assertTrue(report.hasIssues(),
                "AtomicityValidator should have flagged the non-atomic read-modify-write.\n" + report);
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes, counts are exact in single-threaded mode
    // -------------------------------------------------------------------------

    @Test
    void testIncrement_singleThread_exactCount() {
        service.increment("/home");
        service.increment("/home");
        service.increment("/about");

        assertEquals(2L, service.getCount("/home"));
        assertEquals(1L, service.getCount("/about"));
    }

    @Test
    void testGetCount_unknownPage_returnsZero() {
        assertEquals(0L, service.getCount("/nonexistent"));
    }

    @Test
    void testReset_clearsCount() {
        service.increment("/news");
        service.increment("/news");
        service.reset("/news");

        assertEquals(0L, service.getCount("/news"));
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the non-atomic read-modify-write
    // -------------------------------------------------------------------------

    /**
     * The bug: with 10 threads all calling increment() simultaneously on
     * the same page, the read-add-write sequence is not atomic.
     * AtomicityValidator captures the interleaved field accesses and
     * reports the compound operation as a TOCTOU race.
     * The @AfterEach assertion verifies that detection fired after the run.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — @AfterEach will assert that AtomicityValidator
     *    flagged "count" as a non-atomic compound operation
     * 3. Fix: replace long[] with AtomicLong.incrementAndGet()
     */
    @Disabled("Remove @Disabled to see atomicity violation detected by AtomicityValidator")
    @AsyncTest(threads = 10, invocations = 100, detectAll = false, detectAtomicityViolations = true)
    void testIncrement_concurrent_detectsAtomicityViolation() {
        runningAsyncTest = true;
        String page = "/home";

        // Record a read of the current count. Multiple threads will call
        // this concurrently, all reading the same shared "count" field.
        long before = service.getCount(page);
        atomicityValidator.recordFieldAccess("count", before, false);  // read

        // Perform the non-atomic read-modify-write
        service.increment(page);

        // Record a write. Because many threads interleave their reads and
        // writes on the same "count" field, analyzeAtomicity() will detect
        // "count: mixed read/write compound access across N threads" and
        // "count: state changed between check/use windows on N threads".
        long after = service.getCount(page);
        atomicityValidator.recordFieldAccess("count", after, true);    // write
    }

    /**
     * Fixed version: AtomicLong.incrementAndGet() is a single atomic CAS
     * operation — no interleaving window, no lost increments.
     */
    @Test
    void testIncrement_fixedWithAtomicLong_singleThread() {
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.LongAdder> fixedCounts =
                new java.util.concurrent.ConcurrentHashMap<>();

        fixedCounts.computeIfAbsent("/home", k -> new java.util.concurrent.atomic.LongAdder()).increment();
        fixedCounts.computeIfAbsent("/home", k -> new java.util.concurrent.atomic.LongAdder()).increment();
        fixedCounts.computeIfAbsent("/about", k -> new java.util.concurrent.atomic.LongAdder()).increment();

        assertEquals(2L, fixedCounts.get("/home").sum());
        assertEquals(1L, fixedCounts.get("/about").sum());
    }
}
