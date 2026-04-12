package com.github.asynctest.diagnostics;

import java.util.Calendar;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    private static class CalendarState {
        final String name;
        final Calendar calendar;
        final AtomicInteger getCount   = new AtomicInteger(0);
        final AtomicInteger setCount   = new AtomicInteger(0);
        final AtomicInteger addCount   = new AtomicInteger(0);
        final AtomicInteger errorCount = new AtomicInteger(0);
        final Set<Long> accessingThreads = ConcurrentHashMap.newKeySet();
        volatile long firstAccessTime = 0;
        volatile long lastAccessTime  = 0;

        CalendarState(Calendar calendar, String name) {
            this.calendar = calendar;
            this.name = name != null ? name : "calendar@" + System.identityHashCode(calendar);
        }
    }

    private final Map<Integer, CalendarState> calendars = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

    /**
     * Register a {@code Calendar} instance for monitoring.
     *
     * @param calendar the Calendar to monitor
     * @param name     a descriptive label used in reports
     */
    public void registerCalendar(Calendar calendar, String name) {
        if (!enabled || calendar == null) return;
        calendars.putIfAbsent(System.identityHashCode(calendar), new CalendarState(calendar, name));
    }

    /**
     * Record a {@code get()} or {@code getTime()} call.
     *
     * @param calendar the Calendar instance
     * @param name     the label (should match registration)
     */
    public void recordGet(Calendar calendar, String name) {
        recordAccess(calendar, name, "get");
    }

    /**
     * Record a {@code set()} or {@code setTime()} call.
     *
     * @param calendar the Calendar instance
     * @param name     the label (should match registration)
     */
    public void recordSet(Calendar calendar, String name) {
        recordAccess(calendar, name, "set");
    }

    /**
     * Record an {@code add()} or {@code roll()} call.
     *
     * @param calendar the Calendar instance
     * @param name     the label (should match registration)
     */
    public void recordAdd(Calendar calendar, String name) {
        recordAccess(calendar, name, "add");
    }

    /**
     * Record a calendar operation error (e.g. corrupted return value).
     *
     * @param calendar  the Calendar instance
     * @param name      the label
     * @param errorType a short description of the error
     */
    public void recordError(Calendar calendar, String name, String errorType) {
        if (!enabled || calendar == null) return;
        CalendarState state = calendars.get(System.identityHashCode(calendar));
        if (state != null) {
            state.errorCount.incrementAndGet();
        }
    }

    private void recordAccess(Calendar calendar, String name, String method) {
        if (!enabled || calendar == null) return;

        int key = System.identityHashCode(calendar);
        CalendarState state = calendars.computeIfAbsent(key,
                k -> new CalendarState(calendar, name));

        long now = System.currentTimeMillis();
        state.accessingThreads.add(Thread.currentThread().threadId());
        if (state.firstAccessTime == 0) state.firstAccessTime = now;
        state.lastAccessTime = now;

        switch (method) {
            case "get" -> state.getCount.incrementAndGet();
            case "set" -> state.setCount.incrementAndGet();
            case "add" -> state.addCount.incrementAndGet();
        }
    }

    /**
     * Analyse Calendar usage and return a report.
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

            if (threads > 1) {
                report.sharedCalendars.add(String.format(
                        "%s: accessed by %d threads (get: %d, set: %d, add: %d) — NOT THREAD SAFE!",
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

        return report;
    }

    // ---- Report ----------------------------------------------------------------

    /**
     * Report produced by {@link #analyze()}.
     */
    public static class CalendarReport {

        int totalCalendars = 0;
        final java.util.List<String> sharedCalendars  = new java.util.ArrayList<>();
        final java.util.List<String> calendarErrors   = new java.util.ArrayList<>();
        final Map<String, String>   calendarActivity  = new ConcurrentHashMap<>();

        /** Returns {@code true} when shared-access or errors were detected. */
        public boolean hasIssues() {
            return !sharedCalendars.isEmpty() || !calendarErrors.isEmpty();
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

            sb.append("  Fix: replace java.util.Calendar with java.time.* (immutable, thread-safe)");
            return sb.toString();
        }
    }
}
