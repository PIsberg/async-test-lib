package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.DateConverterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.Calendar;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for DateConverterService.
 *
 * ========================================================================
 * DETECTOR: CalendarDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - A sequential @Test PASSES (but gives false confidence)
 * - The same test with @AsyncTest FAILS (exposing the real concurrent bug)
 *
 * THE BUG:
 * DateConverterService holds a single Calendar instance shared by all threads.
 * Calendar.set() and Calendar.getTime() are not thread-safe. Concurrent calls
 * produce corrupted Date values — wrong year, month, or day.
 *
 * WHY @Test PASSES:
 * Single-threaded execution reads and writes the Calendar sequentially.
 * No two threads interleave set() and getTime(), so the result is always correct.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * 8 threads simultaneously call convertToDate() with different arguments.
 * Thread A's set(YEAR, 2020) is overwritten by Thread B's set(YEAR, 1999)
 * before Thread A calls getTime(), producing a Date from the wrong year.
 * CalendarDetector observes concurrent accesses on the same Calendar instance
 * and reports the shared-state violation.
 *
 * DETECTORS TRIGGERED:
 *   CalendarDetector — primary: detects concurrent access on a shared Calendar
 *
 * FIX: instantiate Calendar inside the method, or use LocalDate.of(year, month, day).
 */
class DateConverterServiceTest {

    private DateConverterService service;

    @BeforeEach
    void setUp() {
        service = new DateConverterService();
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, passes cleanly
    // -----------------------------------------------------------------------

    @Test
    void testConvertToDate_singleThread_works() {
        Date result = service.convertToDate(2023, Calendar.JANUARY, 15);
        assertNotNull(result);
    }

    @Test
    void testConvertToDate_distinctYears_sequential() {
        Date d1 = service.convertToDate(2000, Calendar.MARCH, 1);
        Date d2 = service.convertToDate(2024, Calendar.JUNE, 30);
        assertTrue(d1.before(d2), "2000 should be before 2024");
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes shared Calendar race
    // -----------------------------------------------------------------------

    @Disabled("Remove @Disabled to see shared-Calendar race detected by CalendarDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectCalendarIssues = true, failOn = FailOn.LOW)

    void testConvertToDate_concurrent_detectsSharingBug() {
        Calendar cal = service.getCalendar();

        AsyncTestContext.get().calendarMonitor()
                .registerCalendar(cal, "date-converter-calendar");

        // Recorded, not thrown. A shared Calendar that loses the race throws out of its own
        // internals, and an exception escaping this body fails the run before the failOn gate
        // reports the finding, so the reader gets a java.util stack trace instead of the
        // detector's report. See issue #363.
        try {
            AsyncTestContext.get().calendarMonitor()
                    .recordSet(cal, "date-converter-calendar");

            int year = 2000 + (int) (Thread.currentThread().threadId() % 30);
            service.convertToDate(year, Calendar.JANUARY, 1);

            AsyncTestContext.get().calendarMonitor()
                    .recordGet(cal, "date-converter-calendar");
        } catch (RuntimeException corrupted) {
            AsyncTestContext.get().calendarMonitor()
                    .recordError(cal, "date-converter-calendar",
                            corrupted.getClass().getSimpleName());
        }
    }
}
