package se.deversity.asynctest.example.service;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * BUGGY service that demonstrates shared SimpleDateFormat misuse.
 *
 * BUG: A single static SimpleDateFormat instance is shared across all threads.
 *      SimpleDateFormat is not thread-safe — it maintains an internal Calendar
 *      field that is read and written without synchronisation during format()
 *      and parse(). Concurrent calls corrupt the Calendar state, producing
 *      wrong dates or throwing NumberFormatException / ParseException.
 *
 * FIX: Use the thread-safe java.time.format.DateTimeFormatter instead:
 *      private static final DateTimeFormatter DTF =
 *          DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
 *      Or: create a new SimpleDateFormat per call inside a try block.
 *      Or: use a ThreadLocal<SimpleDateFormat>.
 */
public class AuditLogger {

    // BUG: static shared SimpleDateFormat — not thread-safe
    private static final SimpleDateFormat SDF =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    /**
     * Format a Date to audit log timestamp string.
     * Thread-unsafe: SDF.format() mutates internal Calendar state.
     */
    public String formatTimestamp(Date date) {
        return SDF.format(date); // BUG: unsynchronised Calendar mutation
    }

    /**
     * Parse a timestamp string back to a Date.
     * Thread-unsafe: SDF.parse() mutates internal Calendar state.
     */
    public Date parseTimestamp(String s) throws ParseException {
        return SDF.parse(s); // BUG: unsynchronised Calendar mutation
    }

    /** Exposed so tests can register the instance with the detector. */
    public static SimpleDateFormat getSdf() {
        return SDF;
    }
}
