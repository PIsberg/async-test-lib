package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.LogFormatterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for LogFormatterService.
 *
 * ========================================================================
 * DETECTOR: SharedFormatterDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - A sequential @Test PASSES (but gives false confidence)
 * - The same test with @AsyncTest FAILS (exposing the real concurrent bug)
 *
 * THE BUG:
 * LogFormatterService holds a single java.util.Formatter backed by one
 * StringBuilder. Formatter.format() appends to the shared buffer without any
 * synchronisation. Concurrent calls from multiple threads interleave their
 * format arguments inside the buffer, producing garbled log lines.
 *
 * WHY @Test PASSES:
 * A single thread appends and reads the buffer sequentially. The output always
 * reflects the single call, and the test assertion passes cleanly.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * 8 threads simultaneously call formatEntry(). SharedFormatterDetector records
 * every recordAccess() on the Formatter instance and detects that more than one
 * thread is accessing it concurrently, flagging a shared-formatter violation.
 *
 * DETECTORS TRIGGERED:
 *   SharedFormatterDetector — primary: detects concurrent access on shared Formatter
 *
 * FIX: create a new Formatter per call, or use the stateless String.format().
 */
class LogFormatterServiceTest {

    private LogFormatterService service;

    @BeforeEach
    void setUp() {
        service = new LogFormatterService();
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, passes cleanly
    // -----------------------------------------------------------------------

    @Test
    void testFormatEntry_singleThread_containsLevelAndMessage() {
        String result = service.formatEntry("INFO", "application started");
        assertTrue(result.contains("INFO"), "Output must contain log level");
        assertTrue(result.contains("application started"), "Output must contain message");
    }

    @Test
    void testFormatEntry_errorLevel_containsError() {
        String result = service.formatEntry("ERROR", "null pointer");
        assertTrue(result.contains("ERROR"), "Output must contain ERROR level");
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes shared Formatter race
    // -----------------------------------------------------------------------

    @Disabled("Remove @Disabled to see shared Formatter race detected by SharedFormatterDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectSharedFormatter = true)
    void testFormatEntry_concurrent_detectsSharedFormatter() {
        // Record access on the shared Formatter instance — core anti-pattern
        AsyncTestContext.sharedFormatterMonitor()
                .recordAccess(service.getFormatter(), "log-formatter", Thread.currentThread());

        // Call the buggy format method — concurrent calls garble the buffer
        String result = service.formatEntry(
                "INFO",
                "request-" + Thread.currentThread().getId()
        );

        assertNotNull(result, "formatEntry must not return null");
    }
}
