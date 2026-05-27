package se.deversity.asynctest.example.service;

import java.util.Calendar;
import java.util.Date;

/**
 * BUGGY service that demonstrates shared Calendar misuse.
 *
 * BUG: A single Calendar instance is shared across threads. Calendar is not
 *      thread-safe — concurrent set()/getTime() calls corrupt each other's
 *      internal field cache, producing wrong dates.
 *
 * FIX: Create a new Calendar per call, or switch to the java.time API:
 *      return LocalDate.of(year, month, day).atStartOfDay()
 *               .toInstant(ZoneOffset.UTC);
 */
public class DateConverterService {

    // BUG: shared mutable Calendar — not thread-safe
    private final Calendar calendar = Calendar.getInstance();

    /**
     * Converts year/month/day to a {@link Date}.
     * Thread-unsafe: calendar.set() and calendar.getTime() race under concurrency.
     */
    public Date convertToDate(int year, int month, int day) {
        calendar.set(Calendar.YEAR, year);    // BUG: unsynchronized write
        calendar.set(Calendar.MONTH, month);  // BUG: unsynchronized write
        calendar.set(Calendar.DAY_OF_MONTH, day); // BUG: unsynchronized write
        return calendar.getTime();            // BUG: unsynchronized read of dirty state
    }

    public Calendar getCalendar() {
        return calendar;
    }
}
