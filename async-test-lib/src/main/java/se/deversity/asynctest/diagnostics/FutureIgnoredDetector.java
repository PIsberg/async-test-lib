package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Detects {@link java.util.concurrent.Future} instances returned from
 * {@link java.util.concurrent.ExecutorService#submit} (or similar) that are never inspected.
 *
 * <p>When a submitted task throws an exception, the exception is captured inside the
 * {@code Future}. If the caller never calls {@code get()}, {@code isDone()},
 * {@code isCancelled()}, or {@code cancel()}, the failure is silently discarded.
 * This makes debugging intermittent concurrent failures very difficult.
 *
 * <p>Usage inside {@code @AsyncTest(includes = DetectorType.FUTURE_IGNORED)}:
 * <pre>{@code
 * var d = AsyncTestContext.futureIgnoredDetector();
 * Future<?> f = executor.submit(task);
 * d.recordSubmit(f, "processOrder", Thread.currentThread());
 * // ... later ...
 * d.recordInspect(f, Thread.currentThread()); // call before f.get()
 * f.get();
 * }</pre>
 *
 * <p><strong>With the agent attached</strong> ({@code collections=true}) no recording is needed
 * for the shape that loses an exception for certain: a {@code submit} whose result the caller
 * discards. The woven call site pops the future, so nothing can ever inspect it, and
 * {@link #recordDiscarded} receives it. That path reports a future only when its task actually
 * failed, judged from the future itself at analysis, so fire-and-forget of a task that succeeded,
 * or that is still running when the run ends, draws nothing.
 *
 * @since 0.10.0
 */
public class FutureIgnoredDetector extends AbstractInstanceDetector<FutureIgnoredDetector.SubmitRecord> {

    static final class SubmitRecord {
        final String taskName;
        final String submitterThreadName;
        volatile boolean inspected = false;
        volatile java.lang.ref.@org.jspecify.annotations.Nullable WeakReference<Object> futureRef;

        boolean isObserved() {
            if (inspected) {
                return true;
            }
            java.lang.ref.WeakReference<Object> ref = futureRef;
            Object f = ref != null ? ref.get() : null;
            return (f instanceof Future<?> fut && fut.isCancelled())
                    || (f instanceof java.util.concurrent.CompletableFuture<?> cf && cf.getNumberOfDependents() > 0);
        }

        SubmitRecord(String taskName, String tname) {
            this.taskName           = taskName;
            this.submitterThreadName = tname;
        }
    }

    /**
     * Records that a {@code Future} was returned from a {@code submit()} call.
     *
     * @param future   the returned {@code Future} instance (null-safe)
     * @param taskName descriptive name for the submitted task
     * @param thread   the submitting thread (null-safe)
     */
    public void recordSubmit(Object future, String taskName, Thread thread) {
        if (future == null || thread == null) return;
        SubmitRecord rec = stateFor(future, taskName, "task", label -> new SubmitRecord(label, ReportSections.threadLabel(thread)));
        rec.futureRef = new java.lang.ref.WeakReference<>(future);
        if (future instanceof java.util.concurrent.CompletableFuture<?> cf && cf.getNumberOfDependents() > 0) {
            rec.inspected = true;
        }
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
        SubmitRecord rec = trackedState(future);
        if (rec != null) rec.inspected = true;
    }

    /** How many discarded futures one run keeps; later ones are counted and named in the report. */
    static final int MAX_DISCARDED = 4_096;

    /** A future whose caller dropped it, and the thread that did. */
    private record Discarded(Future<?> future, String threadLabel, String call) { }

    private final Queue<Discarded> discarded = new ConcurrentLinkedQueue<>();
    private final AtomicInteger discardedCount = new AtomicInteger();
    private final LongAdder discardedDropped = new LongAdder();

    /**
     * Records a future its caller discarded at the call that returned it, which the agent sees as
     * the {@code POP} after a woven {@code submit}.
     *
     * <p>A discarded future can never be inspected, so its task's exception, if it throws one,
     * reaches nobody. Whether it did is read from the future at {@link #analyze()}: a task that
     * succeeded, or that has not finished, is not reported.
     *
     * @param future the discarded future (null-safe)
     * @param thread the thread that discarded it (null-safe)
     * @param call   the call that returned it, for the report, such as {@code ExecutorService.submit}
     * @since 1.13.2
     */
    public void recordDiscarded(Future<?> future, Thread thread, String call) {
        if (future == null || thread == null) return;
        if (discardedCount.incrementAndGet() > MAX_DISCARDED) {
            discardedDropped.increment();
            return;
        }
        discarded.add(new Discarded(future, ReportSections.threadLabel(thread), call));
    }

    /** {@return the failure {@code future} completed with, or {@code null} if it has not failed} */
    private static @org.jspecify.annotations.Nullable Throwable failureOf(Future<?> future) {
        try {
            return future.isDone() && future.state() == Future.State.FAILED
                    ? future.exceptionNow() : null;
        } catch (RuntimeException unreadable) { // NOPMD AvoidCatchingGenericException - a third-party Future's state() may throw anything
            return null;
        }
    }

    /**
     * {@return report of Futures that were submitted but never inspected}
     */
    public FutureIgnoredReport analyze() {
        FutureIgnoredReport r = new FutureIgnoredReport();
        for (SubmitRecord rec : states()) {
            if (!rec.isObserved()) {
                String finding = String.format(
                        "Future for task '%s' submitted by thread '%s' was never inspected — "
                                + "exceptions thrown by the task are silently swallowed",
                        rec.taskName, rec.submitterThreadName);
                r.violations.add(finding);
                r.structuredViolations.add(new Violation("FutureIgnored", IssueSeverity.HIGH,
                        finding, List.of(), Map.of(), Instant.now()));
            }
        }
        for (Discarded d : discarded) {
            Throwable failure = failureOf(d.future());
            if (failure == null) {
                continue;
            }
            String finding = String.format(
                    "Future returned by %s was discarded by thread '%s' and its task failed with "
                            + "%s: %s — nothing can ever observe that exception",
                    d.call(), d.threadLabel(), failure.getClass().getName(), failure.getMessage());
            r.violations.add(finding);
            r.structuredViolations.add(new Violation("FutureIgnored", IssueSeverity.HIGH,
                    finding, List.of(), Map.of(
                            "call", d.call(),
                            "exception", failure.getClass().getName()),
                    Instant.now()));
        }
        r.discardedNotTracked = discardedDropped.sum();
        return DetectorFailurePolicy.checkedReport(this, r);
    }

    /** Report produced by {@link #analyze()}. */
    public static class FutureIgnoredReport {
        final List<String> violations = new ArrayList<>();
        /** The findings as Violations, at the severity the text resolved to (#801). */
        public final List<Violation> structuredViolations = new ArrayList<>();
        /** Discarded futures past {@link #MAX_DISCARDED} that were counted and not judged. */
        long discardedNotTracked;

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("IGNORED FUTURE DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append("\n");
            if (discardedNotTracked > 0) {
                sb.append("  Note: ").append(discardedNotTracked).append(" discarded future(s) past the first ")
                  .append(MAX_DISCARDED).append(" were counted and not judged.\n");
            }
            sb.append("""
  Why: A Future that is submitted but never retrieved (get()/join() never called) means the task runs
       fire-and-forget. Exceptions thrown by the task are silently swallowed — the calling thread never
       knows the task failed. The result is also discarded, hiding data-processing errors.
  Fix: Always retrieve Future results: call get() or join(), or chain with thenApply/thenAccept/exceptionally.
       If fire-and-forget is intentional, at least register an exception handler:
       future.exceptionally(ex -> { log(ex); return null; });\
""");
            return sb.toString();
        }
    }
}
