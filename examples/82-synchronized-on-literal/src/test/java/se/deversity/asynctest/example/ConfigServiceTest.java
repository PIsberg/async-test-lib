package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for ConfigService.
 *
 * ========================================================================
 * DETECTOR: SynchronizedOnLiteralDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - Sequential @Test PASSES (single-thread, no unintended lock sharing visible)
 * - The same scenario with @AsyncTest FAILS (literal lock identified)
 *
 * THE BUG:
 * ConfigService uses synchronized("config-lock") — a JVM-interned String
 * literal. Any other class using the same literal shares the lock, causing
 * unexpected contention and difficult-to-diagnose deadlocks.
 *
 * WHY @Test PASSES:
 * A single thread interacting with ConfigService never encounters an unrelated
 * class holding the same literal lock. The functional correctness is unaffected.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * SynchronizedOnLiteralDetector.recordMonitorAcquired() inspects the monitor
 * object with describeIfLiteral(). String literals, Boolean.TRUE/FALSE, and
 * boxed small integers are flagged as dangerous interned monitors.
 *
 * DETECTORS TRIGGERED:
 *   SynchronizedOnLiteralDetector — primary: identifies interned-literal monitors
 *
 * FIX: replace "config-lock" with a private final Object lock field.
 */
class ConfigServiceTest {

    private ConfigService service;

    @BeforeEach
    void setUp() {
        service = new ConfigService();
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, always correct
    // -----------------------------------------------------------------------

    @Test
    void test_singleThread_setAndGet_works() {
        service.set("host", "localhost");
        assertEquals("localhost", service.get("host"));
    }

    @Test
    void test_singleThread_missingKey_returnsNull() {
        assertNull(service.get("absent"));
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes synchronization on interned String literal
    // -----------------------------------------------------------------------

    @Disabled("Remove @Disabled to see literal lock detected by SynchronizedOnLiteralDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectSynchronizedOnLiteral = true)
    void test_concurrent_detectsLiteralLock() {
        var detector = AsyncTestContext.get().synchronizedOnLiteralMonitor();

        // The monitor passed here is the interned String literal "config-lock".
        // describeIfLiteral() will identify it as a String literal and flag it.
        String literalMonitor = "config-lock";
        detector.recordMonitorAcquired(
            literalMonitor,
            Thread.currentThread(),
            "ConfigService.set/get"
        );

        // Execute the buggy service operation.
        String key = "key-" + Thread.currentThread().getName();
        service.set(key, "value");
        service.get(key);
    }
}
