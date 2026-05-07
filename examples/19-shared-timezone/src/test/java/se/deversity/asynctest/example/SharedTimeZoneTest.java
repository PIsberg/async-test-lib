package se.deversity.asynctest.example;

import org.junit.jupiter.api.Test;
import java.time.ZoneId;
import java.util.TimeZone;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates the SharedTimeZoneDetector (Phase 12).
 *
 * ============================================================
 * NOTE: SharedTimeZoneDetector ships in async-test-lib 0.10.0.
 * This example targets 0.9.0 so it compiles from Maven Central.
 * ============================================================
 *
 * THE BUG: A scheduling service adjusts a shared TimeZone's raw offset
 * to simulate different regions during tests. TimeZone is mutable and
 * setRawOffset is not thread-safe — concurrent adjustments produce
 * non-deterministic offsets, causing wrong date/time arithmetic silently.
 *
 * WHY @Test PASSES: Sequential execution means each adjustment is visible
 * before the next one; the single-thread arithmetic is always correct.
 *
 * WHY @AsyncTest DETECTS THE BUG (0.10.0): The detector tracks mutations
 * and reports when multiple threads mutate the same TimeZone instance.
 */
class SharedTimeZoneTest {

    static class SchedulingService {
        private final TimeZone sharedTz = TimeZone.getTimeZone("UTC");

        // Buggy: mutates shared TimeZone
        int getOffsetHours(int offsetMillis) {
            sharedTz.setRawOffset(offsetMillis); // BUG: mutates shared instance
            return sharedTz.getRawOffset() / 3_600_000;
        }

        // Fixed: create a new TimeZone per operation, or use immutable ZoneId
        int getOffsetHoursFixed(int offsetMillis) {
            TimeZone tz = TimeZone.getTimeZone("UTC");
            tz.setRawOffset(offsetMillis); // own copy — safe
            return tz.getRawOffset() / 3_600_000;
        }
    }

    // =========================================================================
    // Part 1: @Test — passes, gives false confidence
    // =========================================================================

    @Test
    void part1_getOffset_singleThread() {
        SchedulingService svc = new SchedulingService();
        assertEquals(5, svc.getOffsetHours(5 * 3_600_000));
    }

    // =========================================================================
    // Part 2: Upgrade to @AsyncTest (0.10.0) to detect the bug
    //
    // @AsyncTest(threads = 4, invocations = 3, detectSharedTimeZone = true, timeoutMs = 5000)
    // =========================================================================

    @Test
    void part2_detectSharedTimeZone_placeholder() {
        // After upgrading to 0.10.0, replace with:
        //
        //   var d = AsyncTestContext.sharedTimeZoneDetector();
        //   d.recordMutation(sharedTz, "setRawOffset", Thread.currentThread());
        //   sharedTz.setRawOffset(5 * 3_600_000); // mutating shared instance — flagged!
        //
        // The detector will report "TimeZone instance mutated from N threads —
        // concurrent mutations corrupt date/time arithmetic silently."
        assertTrue(true, "Placeholder — see comments above");
    }

    // =========================================================================
    // Part 3: Fixed — use immutable ZoneId (java.time)
    // =========================================================================

    @Test
    void part3_fixed_immutableZoneId() {
        // ZoneId is immutable and thread-safe
        ZoneId utcPlus5 = ZoneId.of("UTC+5");
        assertNotNull(utcPlus5);
        assertEquals("UTC+05:00", utcPlus5.toString());
    }
}
