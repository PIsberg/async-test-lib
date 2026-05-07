package se.deversity.asynctest.diagnostics;

import java.util.*;
import java.util.concurrent.*;

/**
 * Detects {@link java.util.concurrent.Future} instances returned from
 * {@link java.util.concurrent.ExecutorService#submit} (or similar) that are never inspected.
 *
 * <p>When a submitted task throws an exception, the exception is captured inside the
 * {@code Future}. If the caller never calls {@code get()}, {@code isDone()},
 * {@code isCancelled()}, or {@code cancel()}, the failure is silently discarded.
 * This makes debugging intermittent concurrent failures very difficult.
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var d = AsyncTestContext.futureIgnoredDetector();
 * Future<?> f = executor.submit(task);
 * d.recordSubmit(f, "processOrder", Thread.currentThread());
 * // ... later ...
 * d.recordInspect(f, Thread.currentThread()); // call before f.get()
 * f.get();
 * }</pre>
 *
 * @since 0.10.0
 */
public class FutureIgnoredDetector {

    private static class SubmitRecord {
        final int    futureId;
        final String taskName;
        final long   submitterThreadId;
        final String submitterThreadName;
        volatile boolean inspected = false;

        SubmitRecord(int futureId, String taskName, long tid, String tname) {
            this.futureId           = futureId;
            this.taskName           = taskName;
            this.submitterThreadId  = tid;
            this.submitterThreadName = tname;
        }
    }

    private final Map<Integer, SubmitRecord> submits = new ConcurrentHashMap<>();

    /**
     * Records that a {@code Future} was returned from a {@code submit()} call.
     *
     * @param future   the returned {@code Future} instance (null-safe)
     * @param taskName descriptive name for the submitted task
     * @param thread   the submitting thread (null-safe)
     */
    public void recordSubmit(Object future, String taskName, Thread thread) {
        if (future == null || thread == null) return;
        String label = taskName != null ? taskName
                : "task@" + Integer.toHexString(System.identityHashCode(future));
        submits.put(System.identityHashCode(future),
                new SubmitRecord(System.identityHashCode(future),
                        label, thread.getId(), thread.getName()));
    }

    /**
     * Records that a {@code Future} was inspected ({@code get}, {@code isDone},
     * {@code isCancelled}, or {@code cancel}).
     *
     * @param future the {@code Future} being inspected (null-safe)
     * @param thread the inspecting thread (null-safe)
     */
    public void recordInspect(Object future, Thread thread) {
        if (future == null) return;
        SubmitRecord rec = submits.get(System.identityHashCode(future));
        if (rec != null) rec.inspected = true;
    }

    /** @return report of Futures that were submitted but never inspected */
    public FutureIgnoredReport analyze() {
        FutureIgnoredReport r = new FutureIgnoredReport();
        for (SubmitRecord rec : submits.values()) {
            if (!rec.inspected) {
                r.violations.add(String.format(
                        "Future for task '%s' submitted by thread '%s' was never inspected — "
                                + "exceptions thrown by the task are silently swallowed",
                        rec.taskName, rec.submitterThreadName));
            }
        }
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static class FutureIgnoredReport {
        final List<String> violations = new ArrayList<>();

        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("IGNORED FUTURE DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append("\n");
            sb.append("  Fix: always call future.get() (or at least future.isDone()) to "
                    + "retrieve exceptions; alternatively use CompletableFuture with "
                    + "exceptionally()/whenComplete() to handle failures asynchronously");
            return sb.toString();
        }
    }
}
