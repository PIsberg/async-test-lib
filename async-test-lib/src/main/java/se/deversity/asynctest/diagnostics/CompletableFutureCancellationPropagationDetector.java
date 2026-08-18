package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Detects work that keeps running after the {@link CompletableFuture} in front of it was
 * cancelled, and cancellation calls that assume an interrupt {@code CompletableFuture} never
 * delivers.
 *
 * <p>{@link CompletableFuture#cancel(boolean)} completes <em>that</em> future with a
 * {@link java.util.concurrent.CancellationException} and nothing else. It does not reach back
 * into the stage that feeds it, it does not stop a supplier already running on a pool, and it
 * ignores {@code mayInterruptIfRunning} entirely - the JDK documents that a
 * {@code CompletableFuture} never interrupts anything, whichever value is passed. Callers
 * routinely read {@code cancel(true)} as "stop the work", then are surprised when the upstream
 * task finishes, writes to the database, and charges the card.
 *
 * <p>Two findings, both grounded in something observed rather than inferred:
 * <ul>
 *   <li>{@link IssueSeverity#HIGH} - a stage body was recorded <em>finishing</em> after a cancel
 *       on the same pipeline. The work ran to the end regardless of the cancellation; the
 *       detector saw both events and their order. A stage that merely <em>started</em> after the
 *       cancel is not a finding on its own: {@code cancel()} dequeues nothing, so a body that was
 *       already submitted will begin whatever happens, and a cooperative one begins, looks, and
 *       returns. Starts after the cancel are counted and named in the message when a completion
 *       also follows.</li>
 *   <li>{@link IssueSeverity#MEDIUM} - {@code cancel(true)} was called on a
 *       {@code CompletableFuture}. The flag has no effect on this type, so any code relying on
 *       an interrupt to unblock the task is relying on something that will not happen.</li>
 * </ul>
 *
 * <p>Cooperative cancellation - the stage polling {@code isCancelled()} or a volatile flag and
 * returning early - records no completion after the cancel and produces no finding, whether the
 * cancel landed before the body was dispatched or during it.
 *
 * <p>The pipeline label is what ties a cancel to the stages it should have stopped, so it must
 * name one pipeline instance. Under {@code @AsyncTest} every worker builds its own, and a shared
 * literal would let one worker's cancel be matched against another worker's stages: derive the
 * label per instance (a request id, or {@code Thread.currentThread().getName()}) unless the
 * workers really do feed one shared future.
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var d = AsyncTestContext.cfCancellationPropagationDetector();
 * String pipeline = "report-" + Thread.currentThread().getName();   // one label per pipeline instance
 *
 * CompletableFuture<String> upstream = CompletableFuture.supplyAsync(() -> {
 *     d.recordWorkStarted(pipeline, "fetch", Thread.currentThread());
 *     String body = slowFetch();
 *     d.recordWorkCompleted(pipeline, "fetch", Thread.currentThread());  // never reached if cooperative
 *     return body;
 * });
 * CompletableFuture<String> view = upstream.thenApply(this::render);
 *
 * d.cancel(view, pipeline, "view", true);   // records the cancel and the ignored interrupt flag
 * }</pre>
 *
 * @since 1.10.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER,
        note = "One ConcurrentHashMap entry per pipeline; events go on copy-on-write lists and carry "
             + "an atomically issued sequence number, so ordering never depends on a wall clock.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/CompletableFutureCancellationPropagationDetectorTest.java"
)
public final class CompletableFutureCancellationPropagationDetector {

    /** The two kinds of stage event; only a completion after a cancel is a finding. */
    private static final String STARTED   = "started";
    private static final String COMPLETED = "completed";
    private static final class CancelEvent {
        final long    seq;
        final String  futureLabel;
        final String  threadName;
        final boolean mayInterruptIfRunning;
        final boolean cancelReturned;

        CancelEvent(long seq, String futureLabel, String threadName,
                    boolean mayInterruptIfRunning, boolean cancelReturned) {
            this.seq                   = seq;
            this.futureLabel           = futureLabel;
            this.threadName            = threadName;
            this.mayInterruptIfRunning = mayInterruptIfRunning;
            this.cancelReturned        = cancelReturned;
        }
    }

    private static final class WorkEvent {
        final long   seq;
        final String stageLabel;
        final String threadName;
        final String kind;   // "started" or "completed"

        WorkEvent(long seq, String stageLabel, String threadName, String kind) {
            this.seq        = seq;
            this.stageLabel = stageLabel;
            this.threadName = threadName;
            this.kind       = kind;
        }
    }

    private static final class PipelineState {
        final String            name;
        final List<CancelEvent> cancels = new CopyOnWriteArrayList<>();
        final List<WorkEvent>   work    = new CopyOnWriteArrayList<>();

        PipelineState(String name) { this.name = name; }
    }

    private final Map<String, PipelineState> pipelines = new ConcurrentHashMap<>();
    private final AtomicLong                 sequence  = new AtomicLong();
    private volatile boolean                 enabled   = true;

    /**
     * Cancel {@code future} and record the call, reading {@code cancel()}'s own return value.
     *
     * @param future                the future to cancel; ignored when {@code null}
     * @param pipeline              the pipeline this future belongs to, matching the label used
     *                              for {@link #recordWorkStarted}
     * @param futureLabel           a label identifying the cancelled future in the report
     * @param mayInterruptIfRunning the flag to pass on - {@code CompletableFuture} ignores it
     * @return what {@link CompletableFuture#cancel(boolean)} returned
     */
    public boolean cancel(CompletableFuture<?> future, String pipeline,
                          String futureLabel, boolean mayInterruptIfRunning) {
        if (future == null) return false;
        boolean cancelled = future.cancel(mayInterruptIfRunning);
        recordCancel(pipeline, futureLabel, mayInterruptIfRunning, cancelled, Thread.currentThread());
        return cancelled;
    }

    /**
     * Record a {@link CompletableFuture#cancel(boolean)} call made by the caller.
     *
     * @param pipeline              the pipeline the cancelled future belongs to
     * @param futureLabel           a label identifying the cancelled future in the report
     * @param mayInterruptIfRunning the flag that was passed to {@code cancel}
     * @param cancelReturned        what {@code cancel} returned
     * @param thread                the cancelling thread
     */
    public void recordCancel(String pipeline, String futureLabel,
                             boolean mayInterruptIfRunning, boolean cancelReturned, Thread thread) {
        if (!enabled || thread == null) return;
        state(pipeline).cancels.add(new CancelEvent(
                sequence.incrementAndGet(),
                futureLabel != null ? futureLabel : "future",
                thread.getName(), mayInterruptIfRunning, cancelReturned));
    }

    /**
     * Record that an upstream stage body has begun running.
     *
     * <p>A start recorded after the cancel is counted in the report but is not a finding on its
     * own: {@code cancel()} dequeues nothing, so a body already submitted begins regardless, and a
     * cooperative body begins, checks, and returns. Only {@link #recordWorkCompleted} after the
     * cancel says the work ran to the end.
     *
     * @param pipeline   the pipeline this stage belongs to
     * @param stageLabel a label identifying the stage in the report
     * @param thread     the executing thread
     */
    public void recordWorkStarted(String pipeline, String stageLabel, Thread thread) {
        recordWork(pipeline, stageLabel, thread, STARTED);
    }

    /**
     * Record that an upstream stage body has run to completion.
     *
     * <p>Call this at the end of the stage body. A cooperative stage that returns early on
     * cancellation must <em>not</em> reach this call - that is what keeps the correctly written
     * pipeline silent.
     *
     * @param pipeline   the pipeline this stage belongs to
     * @param stageLabel a label identifying the stage in the report
     * @param thread     the executing thread
     */
    public void recordWorkCompleted(String pipeline, String stageLabel, Thread thread) {
        recordWork(pipeline, stageLabel, thread, COMPLETED);
    }

    private void recordWork(String pipeline, String stageLabel, Thread thread, String kind) {
        if (!enabled || thread == null) return;
        state(pipeline).work.add(new WorkEvent(
                sequence.incrementAndGet(),
                stageLabel != null ? stageLabel : "stage",
                thread.getName(), kind));
    }

    private PipelineState state(String pipeline) {
        String key = pipeline != null ? pipeline : "pipeline";
        return pipelines.computeIfAbsent(key, PipelineState::new);
    }

    /** Turn recording off; already-recorded events are kept. */
    public void disable() { enabled = false; }

    /** Turn recording back on. */
    public void enable() { enabled = true; }

    /**
     * Analyses the recorded cancel and stage events and builds the report.
     *
     * <p>The {@code HIGH} finding is anchored to completions: a stage that recorded
     * {@code completed} after the pipeline's first cancel ran to the end regardless. Stage starts
     * after the cancel are counted alongside, but a start on its own is what a cooperative stage
     * dispatched late also produces, so it never triggers the finding by itself.
     *
     * @return the findings this detector collected during the run
     */
    public Report analyze() {
        Report r = new Report();
        for (PipelineState p : pipelines.values()) {
            List<CancelEvent> cancels = new ArrayList<>(p.cancels);
            if (cancels.isEmpty()) continue;

            CancelEvent firstCancel = cancels.get(0);
            for (CancelEvent c : cancels) {
                if (c.seq < firstCancel.seq) firstCancel = c;
            }
            long firstCancelSeq = firstCancel.seq;

            List<WorkEvent> finishedAfter = new ArrayList<>();
            int startedAfter = 0;
            for (WorkEvent w : p.work) {
                if (w.seq <= firstCancelSeq) continue;
                if (COMPLETED.equals(w.kind)) finishedAfter.add(w);
                else startedAfter++;
            }

            if (!finishedAfter.isEmpty()) {
                Set<String> stages = new LinkedHashSet<>();
                Set<String> threads = new LinkedHashSet<>();
                for (WorkEvent w : finishedAfter) {
                    stages.add(w.stageLabel + " " + w.kind);
                    threads.add(w.threadName);
                }
                String startedNote = startedAfter == 0
                        ? ""
                        : String.format(", and %d stage start(s) were recorded after the cancel as well",
                                        startedAfter);
                String msg = String.format(
                        "Pipeline '%s': %d stage(s) ran to completion after '%s' was cancelled by thread '%s' "
                        + "- [%s] on thread(s) %s%s. cancel() completes only the future it is called on; the "
                        + "stage feeding it kept running, so its side effects still landed.",
                        p.name, finishedAfter.size(), firstCancel.futureLabel, firstCancel.threadName,
                        String.join(", ", stages), String.join(", ", threads), startedNote);
                r.violations.add(msg);
                r.structuredViolations.add(new Violation(
                        "CompletableFutureCancellationPropagation",
                        IssueSeverity.HIGH,
                        msg,
                        List.of(),
                        Map.of(
                                "pipeline", p.name,
                                "cancelledFuture", firstCancel.futureLabel,
                                "cancelledBy", firstCancel.threadName,
                                "eventsAfterCancel", finishedAfter.size(),
                                "startedAfterCancel", startedAfter,
                                "stages", String.join(",", stages)
                        ),
                        Instant.now()));
            }

            for (CancelEvent c : cancels) {
                if (!c.mayInterruptIfRunning) continue;
                String msg = String.format(
                        "Pipeline '%s': cancel(true) was called on '%s' from thread '%s'. CompletableFuture "
                        + "ignores mayInterruptIfRunning - it never interrupts the thread running the stage - "
                        + "so nothing blocked inside that stage will be woken by this call.",
                        p.name, c.futureLabel, c.threadName);
                r.violations.add(msg);
                r.structuredViolations.add(new Violation(
                        "CompletableFutureCancellationPropagation",
                        IssueSeverity.MEDIUM,
                        msg,
                        List.of(),
                        Map.of(
                                "pipeline", p.name,
                                "cancelledFuture", c.futureLabel,
                                "cancelledBy", c.threadName,
                                "mayInterruptIfRunning", true,
                                "cancelReturned", c.cancelReturned
                        ),
                        Instant.now()));
            }
        }
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static final class Report {
        /** Findings as human-readable lines, for the text report. */
        public final List<String> violations = new ArrayList<>();
        /** The same findings as {@link Violation} objects, for machine-readable reports. */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "COMPLETABLE FUTURE CANCELLATION PROPAGATION - clean";
            StringBuilder sb = new StringBuilder("COMPLETABLE FUTURE CANCELLATION PROPAGATION DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Why: cancel() on a CompletableFuture completes that one future exceptionally and stops\n")
              .append("       there. It does not propagate upstream, and it never interrupts - the JDK documents\n")
              .append("       that mayInterruptIfRunning has no effect on this type. A caller that treats cancel()\n")
              .append("       as 'the work stopped' is wrong about a task that is still writing.\n")
              .append("  Fix:\n")
              .append("    - Make the stage cooperative: check a volatile flag, or isCancelled() on the future it\n")
              .append("      feeds, at the points where abandoning the work is safe\n")
              .append("    - Hold the Future returned by the executor when you need a real interrupt, and cancel\n")
              .append("      that; CompletableFuture.supplyAsync gives you no handle on the running task\n")
              .append("    - Use orTimeout()/completeOnTimeout() for deadlines, and remember they bound the wait,\n")
              .append("      not the work - the abandoned stage still runs to the end\n");
            return sb.toString();
        }
    }
}
