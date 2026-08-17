package se.deversity.asynctest.example.service;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Formats audit timestamps, three ways: the ThreadLocal that was a cache, the immutable
 * replacement, and a small pool of the helper itself.
 *
 * <p>{@link SimpleDateFormat} is not thread-safe, so it cannot simply be shared. That is the
 * constraint every version here works around, and the reason the ThreadLocal looked right.
 */
public final class AuditFormatter {

    /** Counts constructions, which is what stops being bounded when the pool goes away. */
    private static final AtomicInteger CONSTRUCTED = new AtomicInteger();

    /** The pattern everyone here formats with. */
    private static final String PATTERN = "yyyy-MM-dd'T'HH:mm:ss";

    /**
     * The buggy shape under virtual threads: one {@code SimpleDateFormat} per thread. On a pool
     * that is one per worker for the life of the process; per task it is one per request.
     */
    public static final ThreadLocal<SimpleDateFormat> PER_THREAD = ThreadLocal.withInitial(() -> {
        CONSTRUCTED.incrementAndGet();
        return new SimpleDateFormat(PATTERN, Locale.ROOT);
    });

    /**
     * The fixed shape: {@link DateTimeFormatter} is immutable and thread-safe, so one instance
     * serves every thread and the ThreadLocal is not needed at all.
     */
    public static final DateTimeFormatter SHARED =
            DateTimeFormatter.ofPattern(PATTERN, Locale.ROOT).withZone(ZoneOffset.UTC);

    private AuditFormatter() {
    }

    /** Formats with this thread's own mutable formatter. */
    public static String formatPerThread(Instant when) {
        return PER_THREAD.get().format(Date.from(when));
    }

    /** Formats with the one shared immutable formatter. */
    public static String formatShared(Instant when) {
        return SHARED.format(when);
    }

    /** {@return how many mutable formatters have been constructed so far} */
    public static int constructedCount() {
        return CONSTRUCTED.get();
    }

    /** Resets the construction count between tests. */
    public static void resetCount() {
        CONSTRUCTED.set(0);
    }
}
