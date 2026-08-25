package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.diagnostics.AtomicityValidator;
import se.deversity.asynctest.example.service.HitCounterService;
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
 * AtomicityValidator is recording-fed: it sees the accesses the code under test
 * hands it through recordFieldAccess(). HitCounterService.observeCountAccess
 * wires the read and the write inside increment(), so when the same field name
 * ("count") is read and written by several threads within one invocation round,
 * it reports a mixed read/write compound access and a TOCTOU window. failOn =
 * FailOn.LOW turns that finding into a failed run.
 *
 * DETECTOR ENABLED HERE:
 * AtomicityValidator — non-atomic read-modify-write on "count". It is the only
 * one this demonstration switches on, so it is the only one that can report.
 *
 * FIX:
 * - Replace long[] cell with AtomicLong and call cell.incrementAndGet()
 * - Or use LongAdder per page for the highest write throughput
 */
class HitCounterServiceTest {

    private HitCounterService service;

    @BeforeEach
    void setUp() {
        service = new HitCounterService();
    }

    /**
     * Pins the validator's positive direction without needing the concurrent run: two threads
     * that read the same value and both write it back is the lost update, and the validator
     * must say so.
     */
    @Test
    void testAtomicityValidator_interleavedReadModifyWrite_reports() {
        AtomicityValidator validator = new AtomicityValidator();

        validator.recordFieldAccess("count", 42L, false, 1L);   // thread 1 reads 42
        validator.recordFieldAccess("count", 42L, false, 2L);   // thread 2 reads the same 42
        validator.recordFieldAccess("count", 43L, true, 1L);    // thread 1 stores 43
        validator.recordFieldAccess("count", 43L, true, 2L);    // thread 2 stores 43, losing one

        assertTrue(validator.analyzeAtomicity().hasIssues(),
                "a read-modify-write interleaved across two threads is the violation");
    }

    /**
     * And the other direction: one thread doing the same sequence is just arithmetic. A validator
     * that reported this would report every counter in the program.
     */
    @Test
    void testAtomicityValidator_singleThreadReadModifyWrite_isSilent() {
        AtomicityValidator validator = new AtomicityValidator();

        validator.recordFieldAccess("count", 42L, false, 1L);
        validator.recordFieldAccess("count", 43L, true, 1L);

        assertFalse(validator.analyzeAtomicity().hasIssues(),
                "one thread cannot race itself");
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
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — it fails with
     *      count: mixed read/write compound access across 10 threads
     *      count: state changed between check/use windows on 10 threads
     * 3. Fix: replace long[] with AtomicLong.incrementAndGet()
     */
    @Disabled("Remove @Disabled to see atomicity violation detected by AtomicityValidator")
    @AsyncTest(threads = 10, invocations = 100, detectAll = false, detectAtomicityViolations = true, failOn = FailOn.LOW)
    void testIncrement_concurrent_detectsAtomicityViolation() {
        // The validator has to be the one the run owns. This demonstration used to record into a
        // locally constructed AtomicityValidator and assert on it from @AfterEach; the library
        // never reads that instance, so failOn had nothing to gate on and enabling the test left
        // it green. See issue #346.
        AtomicityValidator validator = AsyncTestContext.atomicityValidator();

        // Recorded inside increment(), at the read and at the write, rather than around the call:
        // the value stored is the evidence, and getCount() before and after would report two
        // extra reads and never that value.
        service.observeCountAccess(
                value -> validator.recordFieldAccess("count", value, false),
                value -> validator.recordFieldAccess("count", value, true));

        service.increment("/home");   // BUG: non-atomic read-modify-write
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
