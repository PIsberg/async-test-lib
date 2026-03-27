package com.github.asynctest.diagnostics;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects concurrent use of non-thread-safe SimpleDateFormat instances.
 * 
 * Common SimpleDateFormat misuse issues detected:
 * - Shared SimpleDateFormat instance accessed by multiple threads without synchronization
 * - Date formatting/parsing corruption from concurrent access
 * - Thread contention on SimpleDateFormat causing performance degradation
 * 
 * Note: SimpleDateFormat is NOT thread-safe. For concurrent scenarios, use:
 * - DateTimeFormatter (Java 8+) which is immutable and thread-safe
 * - ThreadLocal<SimpleDateFormat> for legacy code
 * - Synchronized access blocks
 * 
 * Usage:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectSimpleDateFormatIssues = true)
 * void testDateFormatUsage() {
 *     SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
 *     AsyncTestContext.simpleDateFormatMonitor()
 *         .registerFormatter(sdf, "date-formatter");
 *     
 *     // This will be detected as shared access (not thread-safe!)
 *     String formatted = sdf.format(new Date());
 *     AsyncTestContext.simpleDateFormatMonitor()
 *         .recordFormat(sdf, "date-formatter", "format");
 * }
 * }</pre>
 */
public class SimpleDateFormatDetector {

    private static class FormatterState {
        final String name;
        final SimpleDateFormat formatter;
        final AtomicInteger formatCount = new AtomicInteger(0);
        final AtomicInteger parseCount = new AtomicInteger(0);
        final AtomicInteger errorCount = new AtomicInteger(0);
        final Set<Long> accessingThreads = ConcurrentHashMap.newKeySet();
        final Map<String, AtomicInteger> methodCounts = new ConcurrentHashMap<>();
        volatile Long firstAccessTime = null;
        volatile Long lastAccessTime = null;

        FormatterState(SimpleDateFormat formatter, String name) {
            this.formatter = formatter;
            this.name = name != null ? name : "formatter@" + System.identityHashCode(formatter);
        }
    }

    private final Map<Integer, FormatterState> formatters = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

    /**
     * Register a SimpleDateFormat for monitoring.
     * 
     * @param formatter the SimpleDateFormat to monitor
     * @param name a descriptive name for reporting
     */
    public void registerFormatter(SimpleDateFormat formatter, String name) {
        if (!enabled || formatter == null) {
            return;
        }
        formatters.put(System.identityHashCode(formatter), new FormatterState(formatter, name));
    }

    /**
     * Record a format() call.
     * 
     * @param formatter the SimpleDateFormat
     * @param name the formatter name (should match registration)
     */
    public void recordFormat(SimpleDateFormat formatter, String name) {
        recordAccess(formatter, name, "format");
    }

    /**
     * Record a parse() call.
     * 
     * @param formatter the SimpleDateFormat
     * @param name the formatter name (should match registration)
     */
    public void recordParse(SimpleDateFormat formatter, String name) {
        recordAccess(formatter, name, "parse");
    }

    /**
     * Record a formatting/parsing error (potential corruption).
     * 
     * @param formatter the SimpleDateFormat
     * @param name the formatter name (should match registration)
     * @param errorType the type of error (ParseException, IllegalArgumentException, etc.)
     */
    public void recordError(SimpleDateFormat formatter, String name, String errorType) {
        if (!enabled || formatter == null) {
            return;
        }
        FormatterState state = formatters.get(System.identityHashCode(formatter));
        if (state != null) {
            state.errorCount.incrementAndGet();
        }
    }

    private void recordAccess(SimpleDateFormat formatter, String name, String methodName) {
        if (!enabled || formatter == null) {
            return;
        }
        FormatterState state = formatters.get(System.identityHashCode(formatter));
        if (state == null) {
            // Auto-register
            state = new FormatterState(formatter, name != null ? name : "formatter@" + System.identityHashCode(formatter));
            formatters.put(System.identityHashCode(formatter), state);
        }

        long now = System.currentTimeMillis();
        state.accessingThreads.add(Thread.currentThread().threadId());

        if ("format".equals(methodName)) {
            state.formatCount.incrementAndGet();
        } else if ("parse".equals(methodName)) {
            state.parseCount.incrementAndGet();
        }

        if (state.firstAccessTime == null) {
            state.firstAccessTime = now;
        }
        state.lastAccessTime = now;

        state.methodCounts.computeIfAbsent(methodName, k -> new AtomicInteger(0))
            .incrementAndGet();
    }

    /**
     * Analyze SimpleDateFormat usage for issues.
     * 
     * @return a report of detected issues
     */
    public SimpleDateFormatReport analyze() {
        SimpleDateFormatReport report = new SimpleDateFormatReport();
        report.enabled = enabled;

        for (FormatterState state : formatters.values()) {
            // Check for shared access (multiple threads using same formatter)
            if (state.accessingThreads.size() > 1) {
                report.sharedFormatters.add(String.format(
                    "%s: accessed by %d threads (format: %d, parse: %d) - NOT THREAD SAFE!",
                    state.name, state.accessingThreads.size(),
                    state.formatCount.get(), state.parseCount.get()));

                // Build method breakdown
                StringBuilder methods = new StringBuilder();
                for (Map.Entry<String, AtomicInteger> entry : state.methodCounts.entrySet()) {
                    if (methods.length() > 0) methods.append(", ");
                    methods.append(entry.getKey()).append(":").append(entry.getValue().get());
                }
                report.methodBreakdown.put(state.name, methods.toString());
            }

            // Check for errors (potential corruption)
            if (state.errorCount.get() > 0) {
                report.formattingErrors.add(String.format(
                    "%s: %d formatting/parsing errors detected (potential data corruption)",
                    state.name, state.errorCount.get()));
            }

            // Track activity
            int totalAccesses = state.formatCount.get() + state.parseCount.get();
            if (totalAccesses > 0) {
                report.formatterActivity.put(state.name, String.format(
                    "%d accesses from %d threads (format: %d, parse: %d, errors: %d)",
                    totalAccesses, state.accessingThreads.size(),
                    state.formatCount.get(), state.parseCount.get(), state.errorCount.get()));
            }
        }

        return report;
    }

    /**
     * Report class for SimpleDateFormat analysis.
     */
    public static class SimpleDateFormatReport {
        private boolean enabled = true;
        final java.util.List<String> sharedFormatters = new java.util.ArrayList<>();
        final java.util.List<String> formattingErrors = new java.util.ArrayList<>();
        final Map<String, String> methodBreakdown = new ConcurrentHashMap<>();
        final Map<String, String> formatterActivity = new ConcurrentHashMap<>();

        /**
         * Check if any issues were detected.
         */
        public boolean hasIssues() {
            return !sharedFormatters.isEmpty() || !formattingErrors.isEmpty();
        }

        @Override
        public String toString() {
            if (!enabled) {
                return "SimpleDateFormatReport: disabled";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("SIMPLE DATE FORMAT ISSUES DETECTED:\n");

            if (!sharedFormatters.isEmpty()) {
                sb.append("  Shared Formatter Instances (NOT THREAD SAFE):\n");
                for (String issue : sharedFormatters) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!formattingErrors.isEmpty()) {
                sb.append("  Formatting/Parsing Errors:\n");
                for (String issue : formattingErrors) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!methodBreakdown.isEmpty()) {
                sb.append("  Method Breakdown:\n");
                for (Map.Entry<String, String> entry : methodBreakdown.entrySet()) {
                    sb.append("    - ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
            }

            if (!formatterActivity.isEmpty()) {
                sb.append("  Formatter Activity:\n");
                for (Map.Entry<String, String> entry : formatterActivity.entrySet()) {
                    sb.append("    - ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
            }

            if (!hasIssues()) {
                sb.append("  No issues detected.\n");
            }

            sb.append("  Fix: use DateTimeFormatter (Java 8+) or ThreadLocal<SimpleDateFormat>");
            return sb.toString();
        }
    }
}
