package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects SLF4J MDC (Mapped Diagnostic Context) entries that are not cleared at task end,
 * causing leakage to the next task run on the same pooled thread.
 *
 * <p>When a thread pool reuses threads, MDC state set by one task survives to the next
 * task if {@code MDC.clear()} (or selective {@code MDC.remove()}) is not called.
 * This makes log entries from unrelated requests look correlated (wrong request-ID,
 * wrong user, wrong trace-ID).
 *
 * <p>This detector has no dependency on SLF4J: callers supply the MDC snapshot as a
 * plain {@link Map} by calling {@code MDC.getCopyOfContextMap()} themselves.
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var d = AsyncTestContext.mdcContextLeakDetector();
 * Map<String,String> before = MDC.getCopyOfContextMap(); // may be null
 * d.recordTaskStart(Thread.currentThread(), before);
 * try {
 *     MDC.put("requestId", "abc");
 *     // ... task work ...
 * } finally {
 *     d.recordTaskEnd(Thread.currentThread(), MDC.getCopyOfContextMap());
 *     // MDC.clear(); // fix: add this line
 * }
 * }</pre>
 *
 * @since 0.10.0
 */
public class MdcContextLeakDetector {

    private static class TaskSnapshot {
        final Map<String, String> startMdc;

        TaskSnapshot(@Nullable Map<String, String> startMdc) {
            this.startMdc = startMdc != null ? new LinkedHashMap<>(startMdc) : Collections.emptyMap();
        }
    }

    /** The keys tasks on one thread left behind, over every task that thread ran. */
    private static class ThreadLeaks {
        final String      threadName;
        final Set<String> keys = Collections.synchronizedSet(new LinkedHashSet<>());

        ThreadLeaks(String threadName) {
            this.threadName = threadName;
        }
    }

    /** The task each thread is running now; the next task's start replaces it. */
    private final Map<Long, TaskSnapshot> snapshots = new ConcurrentHashMap<>();
    // Leaks are settled when each task ends, not at analysis: a pooled thread runs one task per
    // round, and the next task starts with the leaked keys already in its MDC, so comparing only
    // the last task's start and end hid every leak on a reused thread.
    private final Map<Long, ThreadLeaks> leaks = new ConcurrentHashMap<>();

    /**
     * Records the MDC state at the start of a task.
     *
     * @param thread      the task's thread (null-safe)
     * @param mdcSnapshot {@code MDC.getCopyOfContextMap()} result; may be {@code null} (treated as empty)
     */
    public void recordTaskStart(Thread thread, Map<String, String> mdcSnapshot) {
        if (thread == null) return;
        snapshots.put(thread.threadId(), new TaskSnapshot(mdcSnapshot));
    }

    /**
     * Records the MDC state at the end of a task (call from {@code finally}).
     *
     * @param thread      the task's thread (null-safe)
     * @param mdcSnapshot {@code MDC.getCopyOfContextMap()} result; may be {@code null} (treated as empty)
     */
    public void recordTaskEnd(Thread thread, Map<String, String> mdcSnapshot) {
        if (thread == null) return;
        TaskSnapshot snap = snapshots.remove(thread.threadId());
        if (snap == null || mdcSnapshot == null) return;
        Set<String> leaked = new LinkedHashSet<>(mdcSnapshot.keySet());
        leaked.removeAll(snap.startMdc.keySet());
        if (leaked.isEmpty()) return;
        leaks.computeIfAbsent(thread.threadId(), id -> new ThreadLeaks(thread.getName()))
             .keys.addAll(leaked);
    }

    /**
     * {@return report of threads that left MDC entries behind after task completion}
     */
    public MdcContextLeakReport analyze() {
        MdcContextLeakReport r = new MdcContextLeakReport();
        for (ThreadLeaks t : leaks.values()) {
            Set<String> leaked;
            synchronized (t.keys) {
                leaked = new LinkedHashSet<>(t.keys);
            }
            String finding = String.format(
                    "Thread '%s' left %d MDC key(s) behind after task completion: %s — "
                            + "these will contaminate the next task run on this thread",
                    t.threadName, leaked.size(), leaked);
            r.violations.add(finding);
            r.structuredViolations.add(new Violation("MdcContextLeak", IssueSeverity.HIGH,
                    finding, List.of(), Map.of(), Instant.now()));
        }
        return DetectorFailurePolicy.checkedReport(this, r);
    }

    /** Report produced by {@link #analyze()}. */
    public static class MdcContextLeakReport {
        final List<String> violations = new ArrayList<>();
        /** The findings as Violations, at the severity the text resolved to (#801). */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("MDC CONTEXT LEAK DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append("\n");
            sb.append("""
  Why: SLF4J's MDC (Mapped Diagnostic Context) is stored in a ThreadLocal. In a thread pool, a thread
       that sets MDC keys and never clears them contaminates the next task running on that thread with
       stale context. Log entries from Task B then carry Task A's request ID, user, or tenant — a
       data-leakage and mis-attribution bug.
  Fix: Always clear MDC in a task-finally block:
       try { MDC.put("requestId", id); doWork(); } finally { MDC.clear(); }
       Or clear only specific keys: finally { MDC.remove("requestId"); }\
""");
            return sb.toString();
        }
    }
}
