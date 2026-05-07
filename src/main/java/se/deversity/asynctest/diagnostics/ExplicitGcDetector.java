package se.deversity.asynctest.diagnostics;

import java.util.*;
import java.util.concurrent.*;

/**
 * Detects explicit garbage-collection invocations ({@link System#gc()} or
 * {@link Runtime#gc()}) during a concurrent test run.
 *
 * <p>Explicit GC is a hint to the JVM to perform a full collection, which causes a
 * stop-the-world (STW) pause of indeterminate length. Inside a concurrent stress test
 * this inflates latency measurements, introduces artificial timeouts, and can mask
 * real concurrency bugs by changing the thread-scheduling timing. Explicit GC in
 * production code is almost always wrong.
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var d = AsyncTestContext.explicitGcDetector();
 * // wrap before the call:
 * d.recordGcInvocation(Thread.currentThread(), "CacheManager.evict:58");
 * System.gc();
 * }</pre>
 *
 * @since 0.10.0
 */
public class ExplicitGcDetector {

    private static class GcEvent {
        final long   threadId;
        final String threadName;
        final String location;

        GcEvent(long threadId, String threadName, String location) {
            this.threadId   = threadId;
            this.threadName = threadName;
            this.location   = location;
        }
    }

    private final List<GcEvent> events = new CopyOnWriteArrayList<>();

    /**
     * Records an explicit {@code System.gc()} or {@code Runtime.getRuntime().gc()} call.
     *
     * @param thread   the calling thread (null-safe)
     * @param location human-readable location, e.g. {@code "ClassName.method:lineNum"}
     */
    public void recordGcInvocation(Thread thread, String location) {
        if (thread == null) return;
        events.add(new GcEvent(thread.getId(), thread.getName(),
                location != null ? location : "unknown"));
    }

    /** @return report of explicit GC invocations */
    public ExplicitGcReport analyze() {
        ExplicitGcReport r = new ExplicitGcReport();
        for (GcEvent e : events) {
            r.violations.add(String.format(
                    "Explicit GC requested by thread '%s' at [%s] — "
                            + "System.gc() causes unpredictable STW pauses that corrupt "
                            + "latency measurements and concurrency-timing tests",
                    e.threadName, e.location));
        }
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static class ExplicitGcReport {
        final List<String> violations = new ArrayList<>();

        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("EXPLICIT GC INVOCATION DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append("\n");
            sb.append("  Fix: remove explicit System.gc() / Runtime.gc() calls; "
                    + "rely on the JVM's automatic GC policy instead");
            return sb.toString();
        }
    }
}
