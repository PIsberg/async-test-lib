package se.deversity.asynctest.example;

import se.deversity.asynctest.example.service.DataProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates Phase 11 detectors: SharedMatcherDetector, SharedDecimalFormatDetector,
 * and SharedMessageDigestDetector.
 *
 * ========================================================================
 * NOTE: The Phase 11 detectors (detectSharedMatcher, detectSharedDecimalFormat,
 * detectSharedMessageDigest, detectWeakReferenceRace, detectStatefulLambda) ship
 * in async-test-lib 0.9.0. This example targets 0.8.0 so it runs from Maven
 * Central without a local build.
 *
 * Upgrade steps (shown as comments throughout this file):
 *   1. Bump async-test-lib.version in pom.xml to 0.9.0
 *   2. Change @Test to @AsyncTest on the three Part 2 methods
 *   3. Uncomment the AsyncTestContext detector calls inside each method
 * ========================================================================
 *
 * THE PATTERN:
 *
 * A single service instance holds three shared, stateful JDK objects:
 *   - Matcher        — stateful regex engine
 *   - DecimalFormat  — stateful number formatter
 *   - MessageDigest  — stateful hash engine
 *
 * All three are non-thread-safe: they maintain mutable internal state that is
 * corrupted when multiple threads call them concurrently without synchronization.
 *
 * WHY @Test PASSES:
 * Single-threaded execution serialises every call. There is never more than one
 * thread touching the shared object at once, so no corruption occurs and all
 * assertions pass deterministically.
 *
 * WHY @AsyncTest DETECTS THE BUG (0.9.0):
 * With multiple threads colliding on the same object simultaneously the detectors
 * report which threads accessed which instance — the access pattern alone is
 * sufficient to flag the risk, similar to a race detector:
 *   - SharedMatcherDetector     reports 'txIdMatcher' accessed from N threads
 *   - SharedDecimalFormatDetector reports 'amountFormat' accessed from N threads
 *   - SharedMessageDigestDetector reports 'sha256' accessed from N threads
 */
class SharedNonThreadSafeTypesTest {

    private DataProcessingService service;

    @BeforeEach
    void setUp() {
        service = new DataProcessingService();
    }

    // =========================================================================
    // Part 1: @Test — passes, gives false confidence
    // =========================================================================

    /**
     * Sequential execution: all operations complete correctly because there is
     * never contention on the shared objects.
     */
    @Test
    void testValidate_singleThread() {
        assertTrue(service.validateTransactionId("TX-123456-USD"),
                "Valid transaction ID should pass validation");
        assertFalse(service.validateTransactionId("INVALID"),
                "Invalid format should fail validation");
    }

    @Test
    void testFormat_singleThread() {
        assertEquals("1,234.56", service.formatAmount(1234.56),
                "Amount should be formatted with grouping and two decimal places");
        assertEquals("0.01", service.formatAmount(0.01));
    }

    @Test
    void testFingerprint_singleThread() {
        String hash = service.fingerprint("TX-123456-USD");
        assertEquals(64, hash.length(), "SHA-256 hex digest must be 64 characters");
        assertEquals(hash, service.fingerprint("TX-123456-USD"),
                "Same input must produce the same hash");
    }

    // =========================================================================
    // Part 2: @AsyncTest — exposes the concurrent bug via detector reports
    //
    // Currently @Test so this compiles against 0.8.0.
    //
    // To activate Phase 11 detection (requires 0.9.0):
    //   1. Bump pom.xml: <async-test-lib.version>0.9.0</async-test-lib.version>
    //   2. Change @Test to @AsyncTest on each method below
    //   3. Uncomment the AsyncTestContext / import lines
    // =========================================================================

    // import se.deversity.asynctest.AsyncTest;           // uncomment for 0.9.0
    // import se.deversity.asynctest.AsyncTestContext;    // uncomment for 0.9.0

    /**
     * Detector: SharedMatcherDetector (0.9.0)
     *
     * Change to:
     *   @AsyncTest(threads = 8, invocations = 10, detectSharedMatcher = true)
     * and uncomment the detector lines to see:
     *
     *   SHARED REGEX MATCHER DETECTED:
     *     - 'txIdMatcher' accessed from 8 threads — Matcher is not thread-safe;
     *       Pattern is safe but each Matcher holds mutable match state
     */
    @Test
    void testSharedMatcher_concurrent() {
        // With 0.9.0 — uncomment these lines and change to @AsyncTest above:
        // AsyncTestContext.sharedMatcherDetector()
        //     .recordAccess(service.getBuggyMatcher(), "txIdMatcher", Thread.currentThread());

        boolean valid = service.validateTransactionId("TX-123456-USD");
        assertTrue(valid, "Valid transaction ID should pass validation");
    }

    /**
     * Detector: SharedDecimalFormatDetector (0.9.0)
     *
     * Change to:
     *   @AsyncTest(threads = 8, invocations = 10, detectSharedDecimalFormat = true)
     * and uncomment the detector lines to see:
     *
     *   SHARED DECIMAL FORMAT / NUMBER FORMAT DETECTED:
     *     - 'amountFormat' accessed from 8 threads — DecimalFormat/NumberFormat is not thread-safe
     */
    @Test
    void testSharedDecimalFormat_concurrent() {
        // With 0.9.0 — uncomment these lines and change to @AsyncTest above:
        // AsyncTestContext.sharedDecimalFormatDetector()
        //     .recordAccess(service.getBuggyAmountFormat(), "amountFormat", Thread.currentThread());

        String formatted = service.formatAmount(9999.99);
        assertNotNull(formatted, "Formatted amount should not be null");
    }

    /**
     * Detector: SharedMessageDigestDetector (0.9.0)
     *
     * Change to:
     *   @AsyncTest(threads = 8, invocations = 10, detectSharedMessageDigest = true)
     * and uncomment the detector lines to see:
     *
     *   SHARED MESSAGE DIGEST DETECTED:
     *     - 'sha256' accessed from 8 threads — concurrent update()/digest() calls
     *       silently corrupt the hash state
     *
     * NOTE: Under concurrent load the fingerprint() results may also differ across
     * threads for the same input, demonstrating the silent corruption directly.
     */
    @Test
    void testSharedMessageDigest_concurrent() {
        // With 0.9.0 — uncomment these lines and change to @AsyncTest above:
        // AsyncTestContext.sharedMessageDigestDetector()
        //     .recordAccess(service.getBuggyMessageDigest(), "sha256", Thread.currentThread());

        String hash = service.fingerprint("TX-123456-USD");
        assertEquals(64, hash.length(), "SHA-256 hex digest must be 64 characters");
    }

    // =========================================================================
    // Part 3: Fixed versions — all pass with @AsyncTest
    // =========================================================================

    /**
     * The fixed versions use thread-local or per-call instances.
     * Uncomment @AsyncTest (0.9.0) to verify the fixes hold under concurrent load.
     */
    // @AsyncTest(threads = 8, invocations = 20,
    //         detectSharedMatcher = true, detectSharedDecimalFormat = true,
    //         detectSharedMessageDigest = true)
    @Test
    void testFixed_singleThread() {
        assertTrue(service.validateTransactionIdFixed("TX-123456-USD"));
        assertFalse(service.validateTransactionIdFixed("bad-format"));
        assertNotNull(service.formatAmountFixed(1234.56));
        assertEquals(64, service.fingerprintFixed("TX-999999-EUR").length());
    }
}
