package com.github.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects {@link ThreadLocal} context leaks in virtual threads.
 *
 * <p>Virtual threads are designed to be created in large quantities and are pooled
 * by the JVM. When a virtual thread sets a {@code ThreadLocal} value and does not
 * remove it before completing, the value may be retained and visible to a future
 * task scheduled on the same carrier thread or virtual thread instance. In server
 * applications this creates subtle bugs: a request may see data from a previous
 * request.
 *
 * <p><strong>Issues detected:</strong>
 * <ul>
 *   <li><b>ThreadLocal not removed</b> — A ThreadLocal was set in a virtual thread
 *       but {@code remove()} was never called before the thread completed</li>
 *   <li><b>InheritableThreadLocal in virtual threads</b> — {@code InheritableThreadLocal}
 *       values are not propagated to virtual threads by default; using them is likely a bug</li>
 *   <li><b>High ThreadLocal count</b> — More than a configurable threshold of distinct
 *       ThreadLocals set on one virtual thread (design smell: prefer {@code ScopedValue})</li>
 * </ul>
 *
 * <p><strong>Usage:</strong>
 * <pre>{@code
 * private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();
 *
 * @AsyncTest(threads = 20, useVirtualThreads = true, detectVirtualThreadContextLeaks = true)
 * void testRequestScopedData() {
 *     var detector = AsyncTestContext.virtualThreadContextLeakDetector();
 *     String key = "REQUEST_ID";
 *
 *     detector.recordThreadLocalSet(key, Thread.currentThread());
 *     REQUEST_ID.set("req-" + Thread.currentThread().threadId());
 *     try {
 *         // ... handle request ...
 *     } finally {
 *         REQUEST_ID.remove();
 *         detector.recordThreadLocalRemoved(key, Thread.currentThread());
 *     }
 * }
 * }</pre>
 *
 * @since 0.7.0
 */
public class VirtualThreadContextLeakDetector {

    /** High-watermark threshold: warn if a single virtual thread sets this many distinct keys */
    private static final int HIGH_THREAD_LOCAL_COUNT_THRESHOLD = 10;

    private static class ThreadLocalEntry {
        final String key;
        final long threadId;
        final boolean isVirtual;
        final boolean isInheritable;
        volatile boolean removed = false;

        ThreadLocalEntry(String key, long threadId, boolean isVirtual, boolean isInheritable) {
            this.key = key;
            this.threadId = threadId;
            this.isVirtual = isVirtual;
            this.isInheritable = isInheritable;
        }
    }

    // key = "threadId:varKey"
    private final Map<String, ThreadLocalEntry> activeEntries = new ConcurrentHashMap<>();
    private final List<String> leakReports = Collections.synchronizedList(new ArrayList<>());
    private final List<String> inheritableInVirtualReports = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger totalSets = new AtomicInteger(0);
    private final AtomicInteger totalRemoves = new AtomicInteger(0);
    // Track per-virtual-thread count of distinct keys set
    private final Map<Long, Set<String>> perThreadKeys = new ConcurrentHashMap<>();

    /**
     * Record that a {@code ThreadLocal} was set on the current thread.
     *
     * @param variableName a descriptive name for the ThreadLocal variable (e.g. field name)
     * @param thread       the current thread (typically {@code Thread.currentThread()})
     */
    public void recordThreadLocalSet(String variableName, Thread thread) {
        recordThreadLocalSet(variableName, thread, false);
    }

    /**
     * Record that a {@code ThreadLocal} or {@code InheritableThreadLocal} was set.
     *
     * @param variableName  a descriptive name for the variable
     * @param thread        the current thread
     * @param isInheritable {@code true} if the variable is an {@code InheritableThreadLocal}
     */
    public void recordThreadLocalSet(String variableName, Thread thread, boolean isInheritable) {
        if (thread == null) return;
        boolean isVirtual = isVirtualThread(thread);
        long tid = thread.threadId();
        String entryKey = tid + ":" + variableName;

        ThreadLocalEntry entry = new ThreadLocalEntry(variableName, tid, isVirtual, isInheritable);
        activeEntries.put(entryKey, entry);
        totalSets.incrementAndGet();

        // Track distinct key count per virtual thread
        if (isVirtual) {
            perThreadKeys.computeIfAbsent(tid, k -> ConcurrentHashMap.newKeySet()).add(variableName);
        }

        // Warn about InheritableThreadLocal in virtual threads
        if (isInheritable && isVirtual) {
            inheritableInVirtualReports.add(
                "Thread " + thread.getName() + " (id=" + tid + "): "
                + "InheritableThreadLocal '" + variableName + "' used inside a virtual thread. "
                + "InheritableThreadLocal values are NOT inherited by virtual threads by default. "
                + "Use ScopedValue instead."
            );
        }
    }

    /**
     * Record that a {@code ThreadLocal} was removed (via {@code ThreadLocal.remove()})
     * on the current thread.
     *
     * @param variableName the same name passed to {@link #recordThreadLocalSet}
     * @param thread       the current thread
     */
    public void recordThreadLocalRemoved(String variableName, Thread thread) {
        if (thread == null) return;
        long tid = thread.threadId();
        String entryKey = tid + ":" + variableName;
        ThreadLocalEntry entry = activeEntries.remove(entryKey);
        if (entry != null) {
            entry.removed = true;
            totalRemoves.incrementAndGet();
        }
    }

    /**
     * Analyze all recorded ThreadLocal events for leak patterns.
     *
     * @return a report describing any detected context leaks
     */
    public VirtualThreadContextLeakReport analyze() {
        // Everything still in activeEntries at analysis time was never removed
        for (ThreadLocalEntry entry : activeEntries.values()) {
            if (entry.isVirtual) {
                leakReports.add(
                    "Virtual thread (id=" + entry.threadId + "): "
                    + "ThreadLocal '" + entry.key + "' was set but never removed. "
                    + "This value will persist and may leak into subsequent tasks on the same thread."
                );
            }
        }

        // High-count warnings
        List<String> highCountWarnings = new ArrayList<>();
        for (Map.Entry<Long, Set<String>> e : perThreadKeys.entrySet()) {
            int count = e.getValue().size();
            if (count > HIGH_THREAD_LOCAL_COUNT_THRESHOLD) {
                highCountWarnings.add(
                    "Virtual thread (id=" + e.getKey() + "): set " + count
                    + " distinct ThreadLocal keys (threshold=" + HIGH_THREAD_LOCAL_COUNT_THRESHOLD
                    + "). Consider using ScopedValue for cleaner context propagation."
                );
            }
        }

        return new VirtualThreadContextLeakReport(
            new ArrayList<>(leakReports),
            new ArrayList<>(inheritableInVirtualReports),
            highCountWarnings,
            totalSets.get(),
            totalRemoves.get()
        );
    }

    // ---- Helpers ----

    static boolean isVirtualThread(Thread thread) {
        try {
            return thread.isVirtual();
        } catch (NoSuchMethodError e) {
            return false;
        }
    }

    /**
     * Report of virtual thread ThreadLocal context leak analysis.
     */
    public static class VirtualThreadContextLeakReport {
        private final List<String> leaks;
        private final List<String> inheritableInVirtualIssues;
        private final List<String> highCountWarnings;
        private final int totalSets;
        private final int totalRemoves;

        VirtualThreadContextLeakReport(
                List<String> leaks,
                List<String> inheritableInVirtualIssues,
                List<String> highCountWarnings,
                int totalSets,
                int totalRemoves) {
            this.leaks = leaks;
            this.inheritableInVirtualIssues = inheritableInVirtualIssues;
            this.highCountWarnings = highCountWarnings;
            this.totalSets = totalSets;
            this.totalRemoves = totalRemoves;
        }

        /** @return true if any context leak issues were detected */
        public boolean hasIssues() {
            return !leaks.isEmpty() || !inheritableInVirtualIssues.isEmpty();
        }

        public List<String> getLeaks()                        { return leaks; }
        public List<String> getInheritableInVirtualIssues()   { return inheritableInVirtualIssues; }
        public List<String> getHighCountWarnings()             { return highCountWarnings; }
        public int          getTotalSets()                     { return totalSets; }
        public int          getTotalRemoves()                  { return totalRemoves; }

        @Override
        public String toString() {
            if (!hasIssues() && highCountWarnings.isEmpty()) {
                return "VirtualThreadContextLeakReport: No ThreadLocal context leaks detected";
            }

            StringBuilder sb = new StringBuilder();

            if (!leaks.isEmpty() || !inheritableInVirtualIssues.isEmpty()) {
                sb.append(IssueSeverity.HIGH.format())
                  .append(": Virtual thread ThreadLocal context leak detected\n");
            } else {
                sb.append(IssueSeverity.MEDIUM.format())
                  .append(": Virtual thread ThreadLocal usage warnings\n");
            }

            sb.append("  Sets=").append(totalSets)
              .append(", Removes=").append(totalRemoves)
              .append(", Unremoved=").append(totalSets - totalRemoves).append("\n");

            appendSection(sb, "ThreadLocal leaks (set but never removed)", leaks);
            appendSection(sb, "InheritableThreadLocal misuse in virtual threads", inheritableInVirtualIssues);
            appendSection(sb, "High ThreadLocal usage per virtual thread", highCountWarnings);

            sb.append("\n\n").append("=".repeat(60));
            sb.append("\n").append(getLearningContent());
            sb.append("=".repeat(60));

            return sb.toString();
        }

        private static void appendSection(StringBuilder sb, String title, List<String> items) {
            if (items.isEmpty()) return;
            sb.append("\n  ").append(title).append(":\n");
            for (String item : items) {
                sb.append("    - ").append(item).append("\n");
            }
        }

        private static String getLearningContent() {
            return """
                📚 LEARNING: ThreadLocals and Virtual Threads

                Virtual threads are designed to be cheap and plentiful. However, ThreadLocal
                values set inside a virtual thread task can leak into subsequent tasks if not
                explicitly removed.

                Problem example (leaking request ID):
                  ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();
                  // task 1: REQUEST_ID.set("request-A"); // ← never removed
                  // task 2: REQUEST_ID.get(); // ← may return "request-A" ← BUG!

                Safe patterns:
                  1. Always use try-finally to ensure remove():
                     REQUEST_ID.set(value);
                     try { ... } finally { REQUEST_ID.remove(); }

                  2. Prefer ScopedValue (Java 21+) — automatically scoped to the task:
                     ScopedValue<String> REQUEST_ID = ScopedValue.newInstance();
                     ScopedValue.where(REQUEST_ID, "request-A").run(() -> handleRequest());

                  3. InheritableThreadLocal does NOT propagate to virtual threads by default.
                     Use ScopedValue for structured context propagation.
                """;
        }
    }
}
