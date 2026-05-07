package se.deversity.asynctest.example;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates the SystemPropertyMutationDetector (Phase 12).
 *
 * ============================================================
 * NOTE: SystemPropertyMutationDetector ships in async-test-lib 0.10.0.
 * This example targets 0.9.0 so it compiles from Maven Central.
 * ============================================================
 *
 * THE BUG: A configuration service reads and writes a system property
 * ("app.mode") as a simple feature flag. When multiple threads call
 * configure() concurrently the property value races — threads reading
 * the flag see non-deterministic values, and the mutation is not
 * restored after the test, polluting subsequent test methods.
 *
 * WHY @Test PASSES: Sequential execution means only one thread touches
 * the property at a time, so there is no race and the test assertion
 * is deterministic.
 *
 * WHY @AsyncTest DETECTS THE BUG (0.10.0): The detector records every
 * setProperty call and reports when the same key is mutated from
 * multiple threads concurrently.
 */
class SystemPropertyMutationTest {

    static class ConfigService {
        void configure(String mode) {
            // Buggy: mutates global JVM state
            System.setProperty("app.mode", mode);
        }

        String getMode() {
            return System.getProperty("app.mode", "default");
        }

        void configureFixed(String mode, java.util.Map<String, String> localConfig) {
            // Fixed: use a local map instead of System.setProperty
            localConfig.put("app.mode", mode);
        }
    }

    @AfterEach
    void cleanup() {
        System.clearProperty("app.mode");
    }

    // =========================================================================
    // Part 1: @Test — passes, gives false confidence
    // =========================================================================

    @Test
    void part1_configureMode_singleThread() {
        ConfigService svc = new ConfigService();
        svc.configure("production");
        assertEquals("production", svc.getMode());
    }

    // =========================================================================
    // Part 2: Upgrade to @AsyncTest (0.10.0) to detect the bug
    //
    // @AsyncTest(threads = 4, invocations = 3, detectSystemPropertyMutation = true, timeoutMs = 5000)
    // =========================================================================

    @Test
    void part2_detectConcurrentPropertyMutation_placeholder() {
        // After upgrading to 0.10.0, replace with:
        //
        //   var d = AsyncTestContext.systemPropertyMutationDetector();
        //   d.recordSet("app.mode", "test", Thread.currentThread());
        //   System.setProperty("app.mode", "test");
        //
        // The detector will report "'app.mode' mutated from N threads — concurrent
        // property mutation causes non-deterministic configuration and test pollution."
        assertTrue(true, "Placeholder — see comments above");
    }

    // =========================================================================
    // Part 3: Fixed — use a local configuration map
    // =========================================================================

    @Test
    void part3_fixed_useLocalConfig() {
        ConfigService svc = new ConfigService();
        java.util.Map<String, String> config = new java.util.concurrent.ConcurrentHashMap<>();
        svc.configureFixed("production", config);
        assertEquals("production", config.get("app.mode"));
        // System properties are not touched — no pollution between tests
        assertNull(System.getProperty("app.mode"), "System property should be untouched");
    }
}
