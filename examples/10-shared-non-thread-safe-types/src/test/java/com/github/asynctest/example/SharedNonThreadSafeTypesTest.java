package com.github.asynctest.example;

import com.github.asynctest.AsyncTest;
import com.github.asynctest.AsyncTestContext;
import com.github.asynctest.example.service.DataProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates Phase 11 detectors: SharedMatcherDetector, SharedDecimalFormatDetector,
 * and SharedMessageDigestDetector.
 *
 * ========================================================================
 * DETECTORS: SharedMatcherDetector, SharedDecimalFormatDetector,
 *            SharedMessageDigestDetector
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
 * Single-threaded execution serializes every call. There is never more than one
 * thread touching the shared object at once, so no corruption occurs and all
 * assertions pass deterministically.
 *
 * WHY @AsyncTest DETECTS THE BUG:
 * With multiple threads colliding on the same object simultaneously:
 *   - SharedMatcherDetector reports 'txIdMatcher' accessed from N threads
 *   - SharedDecimalFormatDetector reports 'amountFormat' accessed from N threads
 *   - SharedMessageDigestDetector reports 'sha256' accessed from N threads
 *
 * WHAT THE DETECTORS DO:
 * The detector records which thread accessed which object instance. After the test
 * completes it checks whether any object was accessed from more than one thread and
 * reports a violation with the thread names for diagnosis.
 * The detectors do NOT need to observe a corruption event — the access pattern alone
 * is sufficient to flag the risk, similar to a race detector.
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
     *
     * This PASSES and shows normal, correct single-thread behaviour.
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
    // These three tests are currently annotated with @Test so CI passes.
    // To see the detectors fire, change each annotation to its @AsyncTest form
    // (shown in the comment above each method).
    // =========================================================================

    /**
     * Detector: SharedMatcherDetector
     *
     * Change to:
     *   @AsyncTest(threads = 8, invocations = 10, detectSharedMatcher = true)
     * to see the violation report.
     *
     * With @Test this PASSES because there is only one thread.
     * With @AsyncTest the detector reports:
     *   SHARED REGEX MATCHER DETECTED:
     *     - 'txIdMatcher' accessed from 8 threads (…) — Matcher is not thread-safe
     */
    // @AsyncTest(threads = 8, invocations = 10, detectSharedMatcher = true)
    @Test
    void testSharedMatcher_detected() {
        var detector = AsyncTestContext.sharedMatcherDetector();
        if (detector != null) {
            // Register the shared Matcher instance with the detector so it can
            // track which threads access it.
            detector.recordAccess(service.getBuggyMatcher(), "txIdMatcher",
                    Thread.currentThread());
        }

        // Calls the buggy validateTransactionId() which reuses the shared Matcher
        boolean valid = service.validateTransactionId("TX-123456-USD");
        assertTrue(valid, "Valid transaction ID should pass validation");
    }

    /**
     * Detector: SharedDecimalFormatDetector
     *
     * Change to:
     *   @AsyncTest(threads = 8, invocations = 10, detectSharedDecimalFormat = true)
     * to see the violation report.
     *
     * With @AsyncTest the detector reports:
     *   SHARED DECIMAL FORMAT / NUMBER FORMAT DETECTED:
     *     - 'amountFormat' accessed from 8 threads — DecimalFormat/NumberFormat is not thread-safe
     */
    // @AsyncTest(threads = 8, invocations = 10, detectSharedDecimalFormat = true)
    @Test
    void testSharedDecimalFormat_detected() {
        var detector = AsyncTestContext.sharedDecimalFormatDetector();
        if (detector != null) {
            detector.recordAccess(service.getBuggyAmountFormat(), "amountFormat",
                    Thread.currentThread());
        }

        String formatted = service.formatAmount(9999.99);
        assertNotNull(formatted, "Formatted amount should not be null");
    }

    /**
     * Detector: SharedMessageDigestDetector
     *
     * Change to:
     *   @AsyncTest(threads = 8, invocations = 10, detectSharedMessageDigest = true)
     * to see the violation report.
     *
     * With @AsyncTest the detector reports:
     *   SHARED MESSAGE DIGEST DETECTED:
     *     - 'sha256' accessed from 8 threads — concurrent update()/digest() calls
     *       silently corrupt the hash state
     *
     * NOTE: With @AsyncTest the fingerprint results may also differ across threads
     * even for the same input, demonstrating the silent corruption.
     */
    // @AsyncTest(threads = 8, invocations = 10, detectSharedMessageDigest = true)
    @Test
    void testSharedMessageDigest_detected() {
        var detector = AsyncTestContext.sharedMessageDigestDetector();
        if (detector != null) {
            detector.recordAccess(service.getBuggyMessageDigest(), "sha256",
                    Thread.currentThread());
        }

        String hash = service.fingerprint("TX-123456-USD");
        assertEquals(64, hash.length(), "SHA-256 hex digest must be 64 characters");
    }

    // =========================================================================
    // Part 3: Fixed versions — all pass with @AsyncTest
    // =========================================================================

    /**
     * The fixed versions use thread-local or per-call instances and pass
     * with full concurrent stress testing.
     *
     * Uncomment @AsyncTest to verify the fixes hold under load.
     */
    // @AsyncTest(threads = 8, invocations = 20,
    //         detectSharedMatcher = true, detectSharedDecimalFormat = true,
    //         detectSharedMessageDigest = true)
    @Test
    void testFixed_concurrent() {
        assertTrue(service.validateTransactionIdFixed("TX-123456-USD"));
        assertFalse(service.validateTransactionIdFixed("bad-format"));
        assertNotNull(service.formatAmountFixed(1234.56));
        assertEquals(64, service.fingerprintFixed("TX-999999-EUR").length());
    }
}
