package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.lang.reflect.Field;
import java.util.Calendar;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects concurrent use of non-thread-safe {@link java.util.Calendar} instances.
 *
 * <p>{@code java.util.Calendar} is <strong>not thread-safe</strong>. When multiple
 * threads share the same instance, operations like {@code get()}, {@code set()},
 * {@code add()}, and {@code getTime()} can interleave, producing silently wrong
 * results — corrupted dates without any exception being thrown.
 *
 * <p>Common issues detected:
 * <ul>
 *   <li>Shared {@code Calendar} instance accessed by multiple threads</li>
 *   <li>Calendar mutation ({@code set()}/{@code add()}) during reads</li>
 *   <li>Mixed-operation contention producing corrupted date values</li>
 * </ul>
 *
 * <p>Preferred thread-safe alternatives:
 * <ul>
 *   <li>{@code java.time.*} (Java 8+) — all classes are immutable and thread-safe</li>
 *   <li>{@code ThreadLocal<Calendar>} — one instance per thread</li>
 *   <li>Synchronized access blocks (last resort)</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectCalendarIssues = true)
 * void testCalendarUsage() {
 *     Calendar cal = Calendar.getInstance();
 *     AsyncTestContext.calendarMonitor()
 *         .registerCalendar(cal, "shared-calendar");
 *
 *     // This will be flagged — multiple threads sharing one Calendar
 *     cal.set(Calendar.YEAR, 2024);
 *     AsyncTestContext.calendarMonitor()
 *         .recordSet(cal, "shared-calendar");
 * }
 * }</pre>
 */
public class CalendarDetector {

    /** Labels for objects the test gave no name, numbered per kind within this detector (#860). */
    private final UnnamedLabels unnamedLabels = new UnnamedLabels();

    private static class CalendarState extends SelfGuard.TrackedInstance {
        final String name;
        final AtomicInteger getCount   = new AtomicInteger(0);
        final AtomicInteger setCount   = new AtomicInteger(0);
        final AtomicInteger addCount   = new AtomicInteger(0);
        final AtomicInteger errorCount = new AtomicInteger(0);
        final Set<Long> accessingThreads = ConcurrentHashMap.newKeySet();
        volatile long firstAccessTime = 0;
        /**
         * Whether a recorded {@code set} or {@code add} left fields for the next {@code get} to
         * recompute (#807). That {@code get} writes the time and every field into the instance,
         * so it needs the exclusive lock a {@code set} does; the gets after it only read, and a
         * read lock guards them. A recorded {@code setTime}, or an {@code add} to a field below
         * the month, computes every field and clears it (#820). It starts as the calendar is when
         * first seen ({@link PendingFields}): {@code getInstance()} returns a complete one, while
         * {@code new GregorianCalendar(year, month, day)} only sets the fields it is given.
         */
        final AtomicBoolean fieldsPending;

        CalendarState(Calendar calendar, String name, UnnamedLabels labels) {
            this.name = name != null ? name : labels.next("calendar");
            this.fieldsPending = new AtomicBoolean(PendingFields.of(calendar));
        }
    }

    private final Map<IdentityKey, CalendarState> calendars = new ConcurrentHashMap<>();

    /**
     * Register a {@code Calendar} instance for monitoring.
     *
     * @param calendar the Calendar to monitor
     * @param name     a descriptive label used in reports
     */
    public void registerCalendar(Calendar calendar, String name) {
        if (calendar == null) return;
        // computeIfAbsent, so the calendar's state is read once, when it is first seen.
        calendars.computeIfAbsent(new IdentityKey(calendar), k -> new CalendarState(calendar, name, unnamedLabels));
    }

    /**
     * Record a {@code get()} or {@code getTime()} call.
     *
     * @param calendar the Calendar instance
     * @param name     the label (should match registration)
     */
    public void recordGet(Calendar calendar, String name) {
        recordAccess(calendar, name, "get", false);
    }

    /**
     * Record a {@code set()} call.
     *
     * <p>The next recorded {@code get} then counts as a write, which needs an exclusive lock: after
     * a {@code set()} it recomputes the fields into the instance. Record a {@code setTime()} or
     * {@code setTimeInMillis()} with {@link #recordSetTime} instead; recorded here, the {@code get}
     * after it counts as a write too.
     *
     * @param calendar the Calendar instance
     * @param name     the label (should match registration)
     */
    public void recordSet(Calendar calendar, String name) {
        recordAccess(calendar, name, "set", true);
    }

    /**
     * Record a {@code setTime()} or {@code setTimeInMillis()} call.
     *
     * <p>A write, counted with the sets, but it computes every field at once, including the ones
     * an earlier {@code set()} left, so the next recorded {@code get} only reads (#820).
     *
     * @param calendar the Calendar instance
     * @param name     the label (should match registration)
     * @since 1.12.3
     */
    public void recordSetTime(Calendar calendar, String name) {
        recordAccess(calendar, name, "set", false);
    }

    /**
     * Record an {@code add()} or {@code roll()} call.
     *
     * <p>As with {@link #recordSet}, the next recorded {@code get} then counts as a write: an
     * {@code add} to a date field and most rolls leave the fields to recompute. Record an
     * {@code add()} with {@link #recordAdd(Calendar, String, int)} where the field is known.
     *
     * @param calendar the Calendar instance
     * @param name     the label (should match registration)
     */
    public void recordAdd(Calendar calendar, String name) {
        recordAccess(calendar, name, "add", true);
    }

    /**
     * Record an {@code add(field, amount)} call, saying which field it added to.
     *
     * <p>An {@code add} to {@link Calendar#ERA}, {@link Calendar#YEAR} or {@link Calendar#MONTH}
     * sets that field and leaves the rest to recompute, so the next recorded {@code get} counts
     * as a write, as after {@link #recordSet}. An {@code add} to any other field is carried out
     * as a {@code setTimeInMillis}, which computes every field at once, so the next {@code get}
     * only reads, as after {@link #recordSetTime} (#820). A {@code roll()} goes through
     * {@link #recordAdd(Calendar, String)}.
     *
     * @param calendar the Calendar instance
     * @param name     the label (should match registration)
     * @param field    the field the {@code add} was made to, such as {@link Calendar#HOUR}
     * @since 1.12.3
     */
    public void recordAdd(Calendar calendar, String name, int field) {
        recordAccess(calendar, name, "add",
                field == Calendar.ERA || field == Calendar.YEAR || field == Calendar.MONTH);
    }

    /**
     * Record a calendar operation error (e.g. corrupted return value).
     *
     * @param calendar  the Calendar instance
     * @param name      the label
     * @param errorType a short description of the error
     */
    public void recordError(Calendar calendar, String name, String errorType) {
        if (calendar == null) return;
        CalendarState state = calendars.get(new IdentityKey(calendar));
        if (state != null) {
            state.errorCount.incrementAndGet();
        }
    }

    // leavesFieldsPending: for a write, whether it leaves fields for the next get to recompute.
    private void recordAccess(Calendar calendar, String name, String method,
                              boolean leavesFieldsPending) {
        if (calendar == null) return;

        IdentityKey key = new IdentityKey(calendar);
        CalendarState state = calendars.computeIfAbsent(key,
                k -> new CalendarState(calendar, name, unnamedLabels));

        long now = System.currentTimeMillis();
        boolean get = "get".equals(method);
        // A get recomputes the fields a set or add left, which is a write; it consumes that work,
        // so the gets after it read (#807). Checked before the swap to keep plain reads read-only.
        state.noteAccess(calendar, !get
                || state.fieldsPending.get() && state.fieldsPending.getAndSet(false));
        if (!get) {
            state.fieldsPending.set(leavesFieldsPending);
        }
        state.accessingThreads.add(Thread.currentThread().threadId());
        if (state.firstAccessTime == 0) state.firstAccessTime = now;

        switch (method) {
            case "get" -> state.getCount.incrementAndGet();
            case "set" -> state.setCount.incrementAndGet();
            case "add" -> state.addCount.incrementAndGet();
            default -> { /* unrecognized method — ignored */ }
        }
    }

    /**
     * Analyse Calendar usage and return a report.
     *
     * @return the findings this detector collected during the run
     */
    public CalendarReport analyze() {
        CalendarReport report = new CalendarReport();

        for (CalendarState state : calendars.values()) {
            int reads     = state.getCount.get();
            int writes    = state.setCount.get() + state.addCount.get();
            int threads   = state.accessingThreads.size();
            int errors    = state.errorCount.get();
            int total     = reads + writes;

            if (total == 0) continue;

            report.totalCalendars++;

            if (threads > 1 && state.sawUnguardedSharing()) {
                report.sharedCalendars.add(String.format(
                        "%s: accessed by %d threads (get: %d, set: %d, add: %d) — NOT THREAD SAFE!" + SelfGuard.REPORT_NOTE,
                        state.name, threads,
                        state.getCount.get(), state.setCount.get(), state.addCount.get()));
            }

            if (errors > 0) {
                report.calendarErrors.add(String.format(
                        "%s: %d calendar operation errors detected (possible date corruption)",
                        state.name, errors));
            }

            if (total > 0) {
                report.calendarActivity.put(state.name, String.format(
                        "%d accesses from %d thread(s) (get: %d, set: %d, add: %d, errors: %d)",
                        total, threads,
                        state.getCount.get(), state.setCount.get(), state.addCount.get(), errors));
            }
        }

        if (report.hasIssues()) {
            // The severity the failOn gate read from this text before #801: a marker in it,
            // else the value DetectorDefaultSeverity declared for the detector.
            IssueSeverity severity = IssueSeverity.markedIn(report.toString())
                    .orElse(IssueSeverity.HIGH);
            for (String finding : report.sharedCalendars) {
                report.structuredViolations.add(new Violation("Calendar", severity,
                        finding, List.of(), Map.of(), Instant.now()));
            }
            for (String finding : report.calendarErrors) {
                report.structuredViolations.add(new Violation("Calendar", severity,
                        finding, List.of(), Map.of(), Instant.now()));
            }
        }
        return DetectorFailurePolicy.checkedReport(this, report);
    }

    /**
     * Reads whether a calendar has fields its next {@code get} would compute, before this detector
     * has recorded anything about it (#820).
     *
     * <p>A field that is not set says so through public API: {@code complete()} marks every field
     * computed, so an unset field means it has not run since the fields were last touched, as
     * after {@code new GregorianCalendar(year, month, day)} or {@code clear()}. A {@code set()}
     * on a calendar whose fields were all computed leaves every field set, and only the private
     * {@code isTimeSet}, {@code areFieldsSet} and {@code areAllFieldsSet} flags tell. Those are read only when
     * {@code java.util} is already open to this library, for example by
     * {@code --add-opens java.base/java.util=ALL-UNNAMED}; the library never opens it, as for
     * {@link SelfGuard#relinksOnGet(Object)}. Otherwise such a calendar counts as complete, as
     * every calendar did before.
     */
    static final class PendingFields {

        /** The protected flags {@code complete()} checks, or none when they cannot be read. */
        private static final Field[] FLAGS = flags();

        private PendingFields() {
        }

        /**
         * {@return whether {@code calendar}'s next {@code get} computes fields, as far as can be
         * read}
         *
         * @param calendar the calendar first seen
         */
        static boolean of(Calendar calendar) {
            for (int field = 0; field < Calendar.FIELD_COUNT; field++) {
                if (!calendar.isSet(field)) {
                    return true;
                }
            }
            try {
                for (Field flag : FLAGS) {
                    if (!flag.getBoolean(calendar)) {
                        return true;
                    }
                }
            } catch (IllegalAccessException e) { // NOPMD - unreadable flags are unknown ones
                return false;
            }
            return false;
        }

        private static Field[] flags() {
            try {
                Field[] flags = {
                    Calendar.class.getDeclaredField("isTimeSet"),
                    Calendar.class.getDeclaredField("areFieldsSet"),
                    Calendar.class.getDeclaredField("areAllFieldsSet"),
                };
                for (Field flag : flags) {
                    // trySetAccessible answers false, and opens nothing, unless java.util is
                    // already open to this library's module.
                    if (flag.getType() != boolean.class || !flag.trySetAccessible()) {
                        return new Field[0];
                    }
                }
                return flags;
            } catch (NoSuchFieldException | RuntimeException e) { // NOPMD - unreadable is unknown
                return new Field[0];
            }
        }
    }

    // ---- Report ----------------------------------------------------------------

    /**
     * Report produced by {@link #analyze()}.
     */
    public static class CalendarReport implements GradedFindings {

        int totalCalendars = 0;
        final List<String> sharedCalendars  = new ArrayList<>();
        /** The findings as Violations, at the severity the text resolved to (#801). */
        public final List<Violation> structuredViolations = new ArrayList<>();
        final List<String> calendarErrors   = new ArrayList<>();
        final Map<String, String>   calendarActivity  = new ConcurrentHashMap<>();

        /**
         * Returns {@code true} when shared-access or errors were detected.
         *
         * @return {@code true} when this detector recorded something worth reporting
         */
        public boolean hasIssues() {
            return !sharedCalendars.isEmpty() || !calendarErrors.isEmpty();
        }

        /**
         * One grade per finding, set by the path that produced it (#754).
         *
         * <p>A shared calendar is reported only when the per-round lockset {@link SelfGuard} keeps
         * found no common lock, so the {@code synchronized (calendar)} twin, a declared lock and a
         * woven monitor all stay silent: a {@link TrustTier#VERDICT} on
         * {@link DetectorTrust.Evidence#CONTEXTUAL} evidence. A recorded error is the test's own
         * {@code recordError} call, a {@link TrustTier#FACT} on
         * {@link DetectorTrust.Evidence#ASSERTED} evidence; before this the whole detector was rated
         * by it. Every grade keeps the severity the gate has always read for this report.
         */
        @Override
        public List<GradedFindings.Grade> grades() {
            if (!hasIssues()) {
                return List.of();
            }
            IssueSeverity severity = DetectorDefaultSeverity.of(CalendarDetector.class.getSimpleName(), toString());
            List<GradedFindings.Grade> out = new ArrayList<>();
            for (String shared : sharedCalendars) {
                out.add(new GradedFindings.Grade(severity, TrustTier.VERDICT, shared, DetectorTrust.Evidence.CONTEXTUAL));
            }
            for (String error : calendarErrors) {
                out.add(new GradedFindings.Grade(severity, TrustTier.FACT, error, DetectorTrust.Evidence.ASSERTED));
            }
            return List.copyOf(out);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("CALENDAR ISSUES DETECTED:\n");

            if (!sharedCalendars.isEmpty()) {
                sb.append("  Shared Calendar Instances (NOT THREAD SAFE):\n");
                for (String issue : sharedCalendars) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!calendarErrors.isEmpty()) {
                sb.append("  Calendar Operation Errors:\n");
                for (String issue : calendarErrors) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!calendarActivity.isEmpty()) {
                sb.append("  Calendar Activity:\n");
                for (Map.Entry<String, String> e : calendarActivity.entrySet()) {
                    sb.append("    - ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
                }
            }

            if (!hasIssues()) {
                sb.append("  No issues detected.\n");
            }

            sb.append("""
  Why: java.util.Calendar is not thread-safe. Its internal fields (year, month, day, etc.) are stored
       in a mutable instance. Concurrent use causes corrupted date computations and non-deterministic
       results — e.g. two threads formatting dates may produce dates that mix each other's day/month fields.
  Fix:
    - Replace with java.time.LocalDate, LocalDateTime, ZonedDateTime — all immutable and thread-safe
    - If Calendar is unavoidable, create a new instance per thread or use ThreadLocal<Calendar>\
""");
            return sb.toString();
        }
    }
}
