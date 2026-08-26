package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.AuditLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for AuditLogger.
 *
 * ========================================================================
 * DETECTOR: SimpleDateFormatDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - A sequential @Test PASSES (but gives false confidence)
 * - The same test with @AsyncTest FAILS (exposing the real concurrent bug)
 *
 * THE BUG:
 * AuditLogger uses a single static SimpleDateFormat shared across all threads.
 * SimpleDateFormat maintains an internal Calendar that is mutated without
 * synchronisation during format() and parse(). Concurrent access from 8
 * threads causes Calendar corruption, producing wrong dates or exceptions.
 *
 * WHY @Test PASSES:
 * A single thread formats and parses dates sequentially. The Calendar state is
 * never concurrently modified so the operations complete correctly.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * 8 threads concurrently call formatTimestamp() and parseTimestamp(). Each
 * invocation uses the shared SDF. SimpleDateFormatDetector records every
 * recordFormat() and recordParse() call, detects concurrent thread access on
 * the same instance, and reports a shared-formatter violation.
 *
 * DETECTORS TRIGGERED:
 *   SimpleDateFormatDetector — primary: detects concurrent format/parse on shared SDF
 *
 * FIX: use DateTimeFormatter (thread-safe), or a ThreadLocal<SimpleDateFormat>.
 */
class AuditLoggerTest {

    private AuditLogger logger;

    @BeforeEach
    void setUp() {
        logger = new AuditLogger();
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, passes cleanly
    // -----------------------------------------------------------------------

    @Test
    void testFormatTimestamp_singleThread_producesExpectedPattern() {
        Date date = new Date(0L); // epoch = 1970-01-01 00:00:00 UTC
        String result = logger.formatTimestamp(date);
        assertNotNull(result, "Formatted timestamp must not be null");
        assertTrue(result.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "Timestamp must match yyyy-MM-dd HH:mm:ss pattern");
    }

    @Test
    void testParseTimestamp_singleThread_roundTrip() throws ParseException {
        Date original = new Date();
        String formatted = logger.formatTimestamp(original);
        Date parsed = logger.parseTimestamp(formatted);
        // Compare to second precision (SDF pattern has no millis)
        assertEquals(formatted, logger.formatTimestamp(parsed),
                "Round-trip format/parse must produce identical string");
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes shared SimpleDateFormat race
    // -----------------------------------------------------------------------

    @Disabled("Remove @Disabled to see shared SDF race detected by SimpleDateFormatDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectSimpleDateFormatIssues = true, failOn = FailOn.LOW)

    void testFormatTimestamp_concurrent_detectsSharedSdf() {
        SimpleDateFormat sdf = AuditLogger.getSdf();

        AsyncTestContext.simpleDateFormatMonitor()
                .registerFormatter(sdf, "audit-logger-sdf");

        // The corruption is recorded, not thrown. A shared SimpleDateFormat does not fail
        // politely: a concurrent Calendar mutation comes back as a ParseException, as a
        // NumberFormatException on a half-written buffer, or as an ArrayIndexOutOfBounds
        // inside format() itself. Any of those escaping this body fails the run before the
        // failOn gate is reached, so the reader gets a java.text stack trace where the
        // detector's report should be, and the example proves the bug instead of the
        // detector finding it. See issue #363.
        try {
            AsyncTestContext.simpleDateFormatMonitor().recordFormat(sdf, "audit-logger-sdf");
            String formatted = logger.formatTimestamp(new Date());

            AsyncTestContext.simpleDateFormatMonitor().recordParse(sdf, "audit-logger-sdf");
            logger.parseTimestamp(formatted);
        } catch (ParseException | RuntimeException corrupted) {
            AsyncTestContext.simpleDateFormatMonitor()
                    .recordError(sdf, "audit-logger-sdf", corrupted.getClass().getSimpleName());
        }
    }
}
