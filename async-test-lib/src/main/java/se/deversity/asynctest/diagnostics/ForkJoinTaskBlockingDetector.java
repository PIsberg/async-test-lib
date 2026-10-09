package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import java.time.Instant;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Detects blocking calls ({@link Thread#sleep}, {@link Object#wait}, {@code Future.get()},
 * blocking I/O) made from within a {@link java.util.concurrent.ForkJoinTask} body.
 *
 * <p>{@link java.util.concurrent.ForkJoinPool} uses a bounded set of carrier threads.
 * A blocked task ties up a carrier without doing useful work, starving all other submitted
 * tasks and parallel streams. For tasks that must block, the correct pattern is
 * {@link java.util.concurrent.ForkJoinPool#managedBlock}.
 *
 * <p>Usage inside {@code @AsyncTest(includes = DetectorType.FORK_JOIN_TASK_BLOCKING)}:
 * <pre>{@code
 * var mon = AsyncTestContext.forkJoinTaskBlockingDetector();
 * mon.recordForkJoinTaskEntered(Thread.currentThread());
 * try {
 *     Thread.sleep(100); // BUG: blocks carrier thread
 *     mon.recordBlockingCallAttempted(Thread.currentThread(), "Thread.sleep");
 * } finally {
 *     mon.recordForkJoinTaskExited(Thread.currentThread());
 * }
 * }</pre>
 */
public class ForkJoinTaskBlockingDetector {

    /**
     * How many ForkJoinTask bodies each thread is inside. A depth, not membership: a parent's
     * {@code join()} or {@code invoke()} often runs the child inline on the same worker, and the
     * child's exit must not end the parent's task (#940). Only the owning thread changes its own
     * entry.
     */
    private final Map<Long, Integer> taskDepthByThread = new ConcurrentHashMap<>();
    private final List<String> blockingCalls         = new CopyOnWriteArrayList<>();

    /**
     * Call at the start of a {@code ForkJoinTask.compute()} or {@code exec()} body.
     *
     * @param thread the thread performing the operation
     */
    public void recordForkJoinTaskEntered(Thread thread) {
        if (thread == null) return;
        taskDepthByThread.merge(thread.threadId(), 1, Integer::sum);
    }

    /**
     * Call at the end of a {@code ForkJoinTask.compute()} or {@code exec()} body.
     *
     * @param thread the thread performing the operation
     */
    public void recordForkJoinTaskExited(Thread thread) {
        if (thread == null) return;
        taskDepthByThread.computeIfPresent(thread.threadId(), (id, depth) -> depth > 1 ? depth - 1 : null);
    }

    /**
     * Record a blocking call attempted while executing a ForkJoinTask.
     * No-op if the current thread is not inside a recorded ForkJoinTask.
     *
     * @param thread   the calling thread (null-safe)
     * @param callType human-readable name, e.g. "Thread.sleep", "Future.get", "InputStream.read"
     */
    public void recordBlockingCallAttempted(Thread thread, String callType) {
        if (thread == null) return;
        if (!taskDepthByThread.containsKey(thread.threadId())) return;
        String type = callType != null ? callType : "blocking call";
        blockingCalls.add(String.format(
            "Thread '%s' called %s inside a ForkJoinTask — "
            + "blocks the carrier thread and starves the pool; use ForkJoinPool.managedBlock instead",
            thread.getName(), type));
    }

    /**
     * Ends the round's task bodies. A body that threw between enter and exit leaves its thread
     * inside a task, and a pooled worker reused in the next round would have every blocking call
     * reported against a task that is long gone (#940). Called by the runner once the previous
     * round's workers have finished.
     *
     * @since 1.13.1
     */
    public void markInvocationStart() {
        taskDepthByThread.clear();
    }

    /**
     * {@return report of blocking calls inside ForkJoin tasks}
     */
    public ForkJoinTaskBlockingReport analyze() {
        ForkJoinTaskBlockingReport r = new ForkJoinTaskBlockingReport();
        r.blockingCalls.addAll(blockingCalls);
        for (String finding : blockingCalls) {
            r.structuredViolations.add(new Violation("ForkJoinTaskBlocking", IssueSeverity.MEDIUM,
                    finding, List.of(), Map.of(), Instant.now()));
        }
        return DetectorFailurePolicy.checkedReport(this, r);
    }

    /** Report produced by {@link #analyze()}. */
    public static class ForkJoinTaskBlockingReport {
        final List<String> blockingCalls = new ArrayList<>();
        /** The findings as Violations, at the severity the text resolved to (#801). */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !blockingCalls.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("BLOCKING CALL INSIDE FORKJOINTASK DETECTED:\n");
            for (String c : blockingCalls) sb.append("  - ").append(c).append("\n");
            sb.append("  Fix: use ForkJoinPool.managedBlock(blocker) to allow the pool to "
                    + "compensate for the blocked carrier by spawning a replacement thread");
            return sb.toString();
        }
    }
}
