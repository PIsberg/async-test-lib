package se.deversity.asynctest.example.service;

import java.util.Formatter;

/**
 * BUGGY service that demonstrates shared Formatter misuse.
 *
 * BUG: A single Formatter instance backed by a shared StringBuilder is used
 *      across all threads. Formatter.format() is not thread-safe — concurrent
 *      calls append to the same buffer simultaneously, producing garbled output
 *      where entries from different threads are interleaved.
 *
 * FIX: Create a new Formatter and StringBuilder per formatEntry() call:
 *      StringBuilder sb = new StringBuilder();
 *      try (Formatter fmt = new Formatter(sb)) {
 *          fmt.format("[%s] %s%n", level, msg);
 *          return sb.toString();
 *      }
 *      Or simply use String.format("[%s] %s%n", level, msg).
 */
public class LogFormatterService {

    // BUG: shared, mutable Formatter — not thread-safe
    private final Formatter formatter = new Formatter(new StringBuilder());

    /**
     * Format a log entry. Thread-unsafe: concurrent calls corrupt the buffer.
     */
    public String formatEntry(String level, String msg) {
        formatter.format("[%s] %s%n", level, msg); // BUG: concurrent appends corrupt buffer
        return formatter.toString();               // BUG: reads inconsistent shared state
    }

    /** Exposed so tests can reference the shared Formatter instance. */
    public Formatter getFormatter() {
        return formatter;
    }
}
