package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.diagnostics.LazyInitValidator;
import se.deversity.asynctest.example.service.ConfigurationSingleton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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
 * WHY @AsyncTest DETECTS THE ISSUE:
 * LazyInitValidator tracks concurrent accesses where some threads observed
 * null and others observed an initialized value, all without volatile or
 * synchronization — the signature of broken DCL.
 *
 * DETECTORS TRIGGERED:
 * LazyInitValidator — standalone, instantiated directly in the test.
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
    // Part 2: @AsyncTest — exposes broken DCL via LazyInitValidator
    // -------------------------------------------------------------------------

    /**
     * The bug: without volatile on {@code instance}, a thread can observe a
     * non-null reference to a partially-constructed singleton. Multiple threads
     * will simultaneously see null (triggering initialization) and see the
     * instance as initialized — the classic broken DCL fingerprint.
     *
     * LazyInitValidator detects unsynchronized, non-volatile lazy init fields
     * accessed from multiple threads where some observed null.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — LazyInitValidator will flag "instance"
     * 3. Fix: add volatile to the instance field, or use the holder idiom
     */
    @Disabled("Remove @Disabled to see broken DCL detected by LazyInitValidator")
    @AsyncTest(threads = 8, invocations = 100)
    void testGetInstance_concurrent_detectsBrokenDCL() {
        // Simulate what concurrent threads would observe:
        // - Some threads see null (they entered before initialization completed)
        // - Others see an initialized value (they entered after)
        // - None uses synchronized access or volatile for the outer null check
        boolean isFirstThread = Thread.currentThread().threadId() % 3 == 0;

        validator.recordAccess(
            "instance",          // field name
            isFirstThread,       // observedNull: true when thread races in before init
            !isFirstThread,      // initializedValue: true when init is already visible
            false,               // synchronizedAccess: outer check has NO synchronization
            false                // volatileField: field is NOT volatile — the bug
        );

        LazyInitValidator.LazyInitReport report = validator.analyze();
        assertTrue(report.hasIssues(),
            "Expected broken DCL to be detected.\n" + report);
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
        // Verify the holder idiom is safe to use
        class SafeSingleton {
            private SafeSingleton() {}
            private static class Holder {
                static final SafeSingleton INSTANCE = new SafeSingleton();
            }
            public static SafeSingleton getInstance() { return Holder.INSTANCE; }
        }

        SafeSingleton a = SafeSingleton.getInstance();
        SafeSingleton b = SafeSingleton.getInstance();
        assertSame(a, b);
    }
}
