package se.deversity.asynctest.diagnostics;

import java.util.*;
import java.util.concurrent.*;

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
        final long                threadId;
        final String              threadName;
        final Map<String, String> startMdc;
        volatile Map<String, String> endMdc;

        TaskSnapshot(long threadId, String threadName, Map<String, String> startMdc) {
            this.threadId   = threadId;
            this.threadName = threadName;
            this.startMdc   = startMdc != null ? new LinkedHashMap<>(startMdc) : Collections.emptyMap();
        }
    }

    private final Map<Long, TaskSnapshot> snapshots = new ConcurrentHashMap<>();

    /**
     * Records the MDC state at the start of a task.
     *
     * @param thread      the task's thread (null-safe)
     * @param mdcSnapshot {@code MDC.getCopyOfContextMap()} result; may be {@code null} (treated as empty)
     */
    public void recordTaskStart(Thread thread, Map<String, String> mdcSnapshot) {
        if (thread == null) return;
        snapshots.put(thread.getId(),
                new TaskSnapshot(thread.getId(), thread.getName(), mdcSnapshot));
    }

    /**
     * Records the MDC state at the end of a task (call from {@code finally}).
     *
     * @param thread      the task's thread (null-safe)
     * @param mdcSnapshot {@code MDC.getCopyOfContextMap()} result; may be {@code null} (treated as empty)
     */
    public void recordTaskEnd(Thread thread, Map<String, String> mdcSnapshot) {
        if (thread == null) return;
        TaskSnapshot snap = snapshots.get(thread.getId());
        if (snap == null) return;
        snap.endMdc = mdcSnapshot != null ? new LinkedHashMap<>(mdcSnapshot) : Collections.emptyMap();
    }

    /** @return report of threads that left MDC entries behind after task completion */
    public MdcContextLeakReport analyze() {
        MdcContextLeakReport r = new MdcContextLeakReport();
        for (TaskSnapshot snap : snapshots.values()) {
            if (snap.endMdc == null) continue;
            Set<String> leaked = new LinkedHashSet<>(snap.endMdc.keySet());
            leaked.removeAll(snap.startMdc.keySet());
            if (!leaked.isEmpty()) {
                r.violations.add(String.format(
                        "Thread '%s' left %d MDC key(s) behind after task completion: %s — "
                                + "these will contaminate the next task run on this thread",
                        snap.threadName, leaked.size(), leaked));
            }
        }
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static class MdcContextLeakReport {
        final List<String> violations = new ArrayList<>();

        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("MDC CONTEXT LEAK DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append("\n");
            sb.append("  Fix: call MDC.clear() (or MDC.remove(key) for each key added) "
                    + "in a finally block to prevent leakage to pooled-thread reuse");
            return sb.toString();
        }
    }
}
