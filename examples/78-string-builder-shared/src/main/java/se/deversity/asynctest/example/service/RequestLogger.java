package se.deversity.asynctest.example.service;

/**
 * BUGGY service that demonstrates shared mutable StringBuilder.
 *
 * BUG: a single StringBuilder is used as an instance field and appended from
 *      multiple threads without synchronization. StringBuilder is not thread-safe:
 *      concurrent appends corrupt internal state, produce garbled output, or
 *      throw ArrayIndexOutOfBoundsException when the backing array is resized.
 *
 * FIX: synchronize on the StringBuilder, use StringBuffer, or collect per-thread
 *      strings and merge them at a safe boundary (e.g., end of request).
 */
public class RequestLogger {

    // BUG: shared mutable state — StringBuilder is not thread-safe.
    private final StringBuilder log = new StringBuilder();

    /**
     * Append a log message.
     * BUG: no synchronization — concurrent calls corrupt the builder.
     */
    public void append(String msg) {
        log.append(msg).append("\n");
    }

    /**
     * Return the accumulated log as a String.
     * BUG: no synchronization — reading while another thread writes is a data race.
     */
    public String getLog() {
        return log.toString();
    }

    /** Expose the raw StringBuilder so the test can register it with the detector. */
    public StringBuilder getRawBuilder() {
        return log;
    }

    /** Clear the log (for test setup). */
    public void clear() {
        log.setLength(0);
    }
}
