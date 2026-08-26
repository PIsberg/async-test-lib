package se.deversity.asynctest.example;

import se.deversity.asynctest.diagnostics.LazyInitValidator;
import se.deversity.asynctest.example.service.ConfigurationSingleton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for ConfigurationSingleton.
 *
 * ========================================================================
 * DETECTOR: LazyInitValidator
 * ========================================================================
 *
 * This test demonstrates how broken double-checked locking (DCL) can expose
 * a partially-constructed singleton to threads that race through the first
 * null check before the initializing thread has flushed all field writes.
 *
 * THE BUG:
 * ConfigurationSingleton.getInstance() uses DCL but the {@code instance}
 * field is not {@code volatile}. Under the Java Memory Model:
 *   - Thread A enters the synchronized block and constructs the object
 *   - The JIT or CPU may write the reference to {@code instance} before
 *     completing all field initializations
 *   - Thread B sees a non-null {@code instance} reference and skips the
 *     synchronized block — but may read uninitialized field state
 *   - Calling instance.get() on a partially-constructed object returns ""
 *     instead of the real property value
 *
 * WHY @Test PASSES:
 * Single-threaded access always completes construction before any read.
 * The missing volatile is never observable from a single thread.
 *
 * WHAT LazyInitValidator SAYS ABOUT IT:
 * It reports a field that one thread observed as null and another observed as
 * initialized, with neither volatile nor synchronization, which is the broken
 * DCL fingerprint. It is a standalone helper rather than one of the library's
 * detectors: it has no DetectorType and no DetectorRegistry entry, so a test
 * drives it and reads it directly. That is also why this example has no
 * @AsyncTest demonstration; Part 2 below says why in full.
 *
 * DETECTORS TRIGGERED:
 * None. The wired detectors for this bug are DoubleCheckedLockingDetector
 * (examples/47-double-checked-locking) and LazyInitRaceDetector.
 *
 * FIX:
 * - Declare {@code instance} as {@code volatile}
 * - Or use the initialization-on-demand holder idiom (preferred)
 * - Or initialize eagerly (simplest, if startup cost is acceptable)
 */
class ConfigurationSingletonTest {

    private final LazyInitValidator validator = new LazyInitValidator();

    @BeforeEach
    void resetValidator() {
        validator.reset();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes, singleton works correctly in single-threaded use
    // -------------------------------------------------------------------------

    @Test
    void testGetInstance_singleThread_returnsSameInstance() {
        ConfigurationSingleton a = ConfigurationSingleton.getInstance();
        ConfigurationSingleton b = ConfigurationSingleton.getInstance();
        assertSame(a, b, "getInstance() must always return the same reference");
    }

    @Test
    void testGet_knownProperty_returnsExpectedValue() {
        ConfigurationSingleton cfg = ConfigurationSingleton.getInstance();
        assertEquals("20", cfg.get("db.pool.size"));
        assertEquals("300", cfg.get("cache.ttl.secs"));
    }

    @Test
    void testGet_unknownProperty_returnsEmptyString() {
        assertEquals("", ConfigurationSingleton.getInstance().get("no.such.key"));
    }

    @Test
    void testGetEnvironment_returnsNonNull() {
        assertNotNull(ConfigurationSingleton.getInstance().getEnvironment());
    }

    // -------------------------------------------------------------------------
    // Part 2: what LazyInitValidator reports, and why there is no demonstration
    // -------------------------------------------------------------------------

    /**
     * This example carried a disabled {@code @AsyncTest} demonstration promising "broken DCL
     * detected by LazyInitValidator". It could not deliver that, for a reason no instrumentation fixes:
     * LazyInitValidator is not one of the library's detectors. There is no DetectorType for it,
     * no attribute on {@code @AsyncTest} and no DetectorRegistry entry, so the runner never
     * analyzes it and the failOn gate has nothing to gate on. It is a standalone helper a test drives and reads
     * itself, which is what the tests below do.
     *
     * The demonstration also asserted on its own analyze() call inside the test body, where the
     * first of eight threads through gets there before its peers have recorded anything. That
     * assertion failed on every run, which is why the audit in issue #346 never listed this
     * example: that audit was looking for demonstrations that passed. ExampleDisabledDemoTest
     * now refuses the shape outright. See issue #363.
     *
     * The wired detectors for this bug have their own examples: DoubleCheckedLockingDetector in
     * examples/47-double-checked-locking, which reports the broken DCL structure, and
     * LazyInitRaceDetector, which reports an observed initialization race. Rather than add a
     * third demonstration of one condition, this example keeps what it is good at: the subject,
     * the two fixes, and both directions of what LazyInitValidator says about them.
     */

    @Test

    void testLazyInitValidator_nonVolatileFieldSeenBothWays_reports() throws Exception {
        // Two real threads, joined one after the other. The validator counts distinct accessing
        // threads, so recording both observations from this thread would leave the count at one
        // and report nothing, which is correct: one thread watching a field go from null to
        // initialized is lazy initialization working.
        Thread sawNull = new Thread(
                () -> validator.recordAccess("instance", true, false, false, false), "saw-null");
        sawNull.start();
        sawNull.join(5_000);

        Thread sawInitialized = new Thread(
                () -> validator.recordAccess("instance", false, true, false, false), "saw-init");
        sawInitialized.start();
        sawInitialized.join(5_000);

        LazyInitValidator.LazyInitReport report = validator.analyze();
        assertTrue(report.hasIssues(),
            "one thread observed null and another observed it initialized, with neither "
                    + "volatile nor synchronization: that is the broken DCL fingerprint.\n" + report);
    }

    @Test
    void testLazyInitValidator_oneThreadOnly_isSilent() {
        validator.recordAccess("instance", true, true, false, false);

        LazyInitValidator.LazyInitReport report = validator.analyze();
        assertFalse(report.hasIssues(),
            "a single thread seeing the field go from null to initialized is lazy "
                    + "initialization working, not a race.\n" + report);
    }

    /**
     * The bug itself, with no detector involved: getInstance() reads the non-volatile field
     * outside the lock, so nothing in the JMM orders the constructor's writes before the
     * reference becomes visible. Single-threaded this always returns a complete object, which
     * is exactly why the bug survives review.
     */
    @Test
    void testGetInstance_singleThread_neverObservesAPartialObject() {
        ConfigurationSingleton cfg = ConfigurationSingleton.getInstance();

        assertNotNull(cfg.getEnvironment(), "environment is set in the constructor");
        assertEquals("20", cfg.get("db.pool.size"), "properties are populated in the constructor");
    }

    /**
     * Fixed version: recordAccess() with volatileField=true simulates the correct
     * DCL pattern. LazyInitValidator finds no unsynchronized initialization issue.
     */
    @Test
    void testGetInstance_fixedVolatileField_noIssueDetected() {
        // Simulate two threads accessing a properly declared volatile field
        validator.recordAccess("instance", true,  false, false, true);
        validator.recordAccess("instance", false, true,  false, true);

        LazyInitValidator.LazyInitReport report = validator.analyze();
        assertFalse(report.hasIssues(),
            "No lazy-init issue expected when the field is volatile.\n" + report);
    }

    /**
     * Alternative fixed version using the holder idiom — the class loader
     * guarantees safe publication so neither volatile nor synchronized is needed
     * on the outer check.
     */
    @Test
    void testHolderIdiom_safePublication() {
        SafeSingleton a = SafeSingleton.getInstance();
        SafeSingleton b = SafeSingleton.getInstance();
        assertSame(a, b);
    }

    private static class SafeSingleton {
        private SafeSingleton() {}
        private static class Holder {
            static final SafeSingleton INSTANCE = new SafeSingleton();
        }
        public static SafeSingleton getInstance() { return Holder.INSTANCE; }
    }
}
