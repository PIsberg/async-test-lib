package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import se.deversity.vibetags.annotations.AITestDriven;

/**
 * Detects blocking calls ({@link Thread#sleep}, {@link Object#wait}, {@code Future.get()},
 * blocking I/O) made from within a {@link java.util.concurrent.ForkJoinTask} body.
 *
 * <p>{@link java.util.concurrent.ForkJoinPool} uses a bounded set of carrier threads.
 * A blocked task ties up a carrier without doing useful work, starving all other submitted
 * tasks and parallel streams. For tasks that must block, the correct pattern is
 * {@link java.util.concurrent.ForkJoinPool#managedBlock}.
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var mon = AsyncTestContext.forkJoinTaskBlockingMonitor();
 * mon.recordForkJoinTaskEntered(Thread.currentThread());
 * try {
 *     Thread.sleep(100); // BUG: blocks carrier thread
 *     mon.recordBlockingCallAttempted(Thread.currentThread(), "Thread.sleep");
 * } finally {
 *     mon.recordForkJoinTaskExited(Thread.currentThread());
 * }
 * }</pre>
 */
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/ForkJoinTaskBlockingDetectorTest.java"
)
public class ForkJoinTaskBlockingDetector {

    private final Set<Long>    activeForkJoinThreads = ConcurrentHashMap.newKeySet();
    private final List<String> blockingCalls         = new CopyOnWriteArrayList<>();

    /** Call at the start of a {@code ForkJoinTask.compute()} or {@code exec()} body. */
    public void recordForkJoinTaskEntered(Thread thread) {
        if (thread == null) return;
        activeForkJoinThreads.add(thread.getId());
    }

    /** Call at the end of a {@code ForkJoinTask.compute()} or {@code exec()} body. */
    public void recordForkJoinTaskExited(Thread thread) {
        if (thread == null) return;
        activeForkJoinThreads.remove(thread.getId());
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
        if (!activeForkJoinThreads.contains(thread.getId())) return;
        String type = callType != null ? callType : "blocking call";
        blockingCalls.add(String.format(
            "Thread '%s' called %s inside a ForkJoinTask — "
            + "blocks the carrier thread and starves the pool; use ForkJoinPool.managedBlock instead",
            thread.getName(), type));
    }

    /** {@return report of blocking calls inside ForkJoin tasks} */
    public ForkJoinTaskBlockingReport analyze() {
        ForkJoinTaskBlockingReport r = new ForkJoinTaskBlockingReport();
        r.blockingCalls.addAll(blockingCalls);
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static class ForkJoinTaskBlockingReport {
        final List<String> blockingCalls = new ArrayList<>();

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
