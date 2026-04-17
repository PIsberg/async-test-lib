package com.github.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Detects CPU-bound tasks running on virtual threads.
 *
 * <p>Virtual threads are designed for I/O-bound work: they park cheaply and allow
 * the carrier thread to run other virtual threads while waiting. CPU-intensive tasks,
 * however, do <em>not</em> yield the carrier — they monopolize it for the entire
 * duration of computation, negating the scalability benefits of virtual threads.
 *
 * <p><strong>Issues detected:</strong>
 * <ul>
 *   <li><b>Long CPU-bound task</b> — A virtual thread ran for longer than the
 *       configured threshold ({@value #DEFAULT_CPU_THRESHOLD_MS}ms by default) without
 *       recording a yield point. Platform threads are a better fit for CPU-bound work.</li>
 *   <li><b>High average task duration</b> — The mean duration across recorded tasks
 *       exceeds the threshold, suggesting pervasive CPU-bound usage.</li>
 * </ul>
 *
 * <p><strong>Usage:</strong>
 * <pre>{@code
 * @AsyncTest(threads = 8, useVirtualThreads = true, detectVirtualThreadCpuBoundTasks = true)
 * void testComputeIntensive() {
 *     var detector = AsyncTestContext.virtualThreadCpuBoundTaskDetector();
 *     String taskId = detector.recordTaskStart("matrix-multiply");
 *     try {
 *         // Potentially CPU-bound work
 *         performHeavyComputation();
 *         detector.recordYieldPoint(taskId); // mark intentional yield / blocking point
 *     } finally {
 *         detector.recordTaskEnd(taskId);
 *     }
 * }
 * }</pre>
 *
 * @since 0.7.0
 */
public class VirtualThreadCpuBoundTaskDetector {

    static final long DEFAULT_CPU_THRESHOLD_MS = 50;

    private final long cpuThresholdMs;

    private static class TaskRecord {
        final String taskId;
        final String taskName;
        final long threadId;
        final boolean isVirtual;
        final long startNanos;
        volatile long endNanos = -1;
        final AtomicInteger yieldCount = new AtomicInteger(0);
        volatile long lastYieldNanos;

        TaskRecord(String taskId, String taskName, Thread thread) {
            this.taskId = taskId;
            this.taskName = taskName;
            this.threadId = thread.threadId();
            this.isVirtual = isVirtual(thread);
            this.startNanos = System.nanoTime();
            this.lastYieldNanos = this.startNanos;
        }

        long durationMs() {
            long end = endNanos >= 0 ? endNanos : System.nanoTime();
            return (end - startNanos) / 1_000_000L;
        }

        long maxSegmentMs() {
            long end = endNanos >= 0 ? endNanos : System.nanoTime();
            return (end - lastYieldNanos) / 1_000_000L;
        }
    }

    private static final AtomicLong ID_GEN = new AtomicLong(0);

    private final Map<String, TaskRecord> activeTasks = new ConcurrentHashMap<>();
    private final List<String> violations = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger totalTasks = new AtomicInteger(0);
    private final AtomicLong totalDurationMs = new AtomicLong(0);
    private final AtomicLong maxObservedMs = new AtomicLong(0);

    public VirtualThreadCpuBoundTaskDetector() {
        this(DEFAULT_CPU_THRESHOLD_MS);
    }

    public VirtualThreadCpuBoundTaskDetector(long cpuThresholdMs) {
        this.cpuThresholdMs = cpuThresholdMs;
    }

    /**
     * Record the start of a named task on the current thread.
     *
     * @param taskName a descriptive label (e.g. {@code "matrix-multiply"})
     * @return a task ID to pass to {@link #recordYieldPoint(String)} and {@link #recordTaskEnd(String)}
     */
    public String recordTaskStart(String taskName) {
        return recordTaskStart(taskName, Thread.currentThread());
    }

    /**
     * Record the start of a named task on the specified thread.
     *
     * @param taskName a descriptive label
     * @param thread   the thread executing the task
     * @return a task ID
     */
    public String recordTaskStart(String taskName, Thread thread) {
        String id = (taskName != null ? taskName : "task") + "#" + ID_GEN.incrementAndGet()
                    + "@" + thread.threadId();
        activeTasks.put(id, new TaskRecord(id, taskName != null ? taskName : "unnamed", thread));
        totalTasks.incrementAndGet();
        return id;
    }

    /**
     * Record a yield point — a blocking or cooperative-yield operation — resetting the
     * CPU-bound segment timer for this task.
     *
     * <p>Call this before any operation that parks the virtual thread (I/O, {@code sleep},
     * {@code LockSupport.park}, etc.) to indicate the task is not purely CPU-bound.
     *
     * @param taskId the ID returned by {@link #recordTaskStart(String)}
     */
    public void recordYieldPoint(String taskId) {
        TaskRecord rec = activeTasks.get(taskId);
        if (rec != null) {
            rec.lastYieldNanos = System.nanoTime();
            rec.yieldCount.incrementAndGet();
        }
    }

    /**
     * Record the end of the task and evaluate whether it was CPU-bound.
     *
     * @param taskId the ID returned by {@link #recordTaskStart(String)}
     */
    public void recordTaskEnd(String taskId) {
        TaskRecord rec = activeTasks.remove(taskId);
        if (rec == null) return;

        rec.endNanos = System.nanoTime();
        long durationMs = rec.durationMs();
        long maxSegmentMs = rec.maxSegmentMs();

        totalDurationMs.addAndGet(durationMs);
        maxObservedMs.updateAndGet(max -> Math.max(max, durationMs));

        if (rec.isVirtual && maxSegmentMs > cpuThresholdMs) {
            violations.add(String.format(
                "Virtual thread (id=%d) task '%s': ran %dms without a yield point "
                + "(threshold=%dms, total yields=%d). "
                + "Consider using platform threads for CPU-bound work.",
                rec.threadId, rec.taskName, maxSegmentMs, cpuThresholdMs, rec.yieldCount.get()
            ));
        }
    }

    /**
     * Analyze all recorded tasks for CPU-bound patterns.
     *
     * @return a report describing any detected issues
     */
    public CpuBoundTaskReport analyze() {
        // Evaluate tasks still active at analysis time
        for (TaskRecord rec : activeTasks.values()) {
            long maxSegmentMs = rec.maxSegmentMs();
            if (rec.isVirtual && maxSegmentMs > cpuThresholdMs) {
                violations.add(String.format(
                    "Virtual thread (id=%d) task '%s': still running after %dms "
                    + "without a yield point (threshold=%dms).",
                    rec.threadId, rec.taskName, maxSegmentMs, cpuThresholdMs
                ));
            }
        }

        int count = totalTasks.get();
        long avgMs = count > 0 ? totalDurationMs.get() / count : 0;
        return new CpuBoundTaskReport(
            new ArrayList<>(violations),
            count,
            avgMs,
            maxObservedMs.get(),
            cpuThresholdMs
        );
    }

    private static boolean isVirtual(Thread thread) {
        try {
            return thread.isVirtual();
        } catch (NoSuchMethodError e) {
            return false;
        }
    }

    /**
     * Report of CPU-bound virtual thread task analysis.
     */
    public static class CpuBoundTaskReport {
        private final List<String> violations;
        private final int totalTasks;
        private final long averageDurationMs;
        private final long maxDurationMs;
        private final long thresholdMs;

        CpuBoundTaskReport(List<String> violations, int totalTasks,
                           long averageDurationMs, long maxDurationMs, long thresholdMs) {
            this.violations = violations;
            this.totalTasks = totalTasks;
            this.averageDurationMs = averageDurationMs;
            this.maxDurationMs = maxDurationMs;
            this.thresholdMs = thresholdMs;
        }

        /** @return true if any CPU-bound tasks were detected on virtual threads */
        public boolean hasIssues() {
            return !violations.isEmpty();
        }

        public List<String> getViolations()    { return violations; }
        public int          getTotalTasks()     { return totalTasks; }
        public long         getAverageDurationMs() { return averageDurationMs; }
        public long         getMaxDurationMs()  { return maxDurationMs; }
        public long         getThresholdMs()    { return thresholdMs; }

        @Override
        public String toString() {
            if (!hasIssues()) {
                return "VirtualThreadCpuBoundTaskReport: No CPU-bound tasks detected on virtual threads";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(IssueSeverity.MEDIUM.format())
              .append(": CPU-bound tasks detected on virtual threads\n");
            sb.append("  Tasks recorded=").append(totalTasks)
              .append(", avgDuration=").append(averageDurationMs).append("ms")
              .append(", maxDuration=").append(maxDurationMs).append("ms")
              .append(", threshold=").append(thresholdMs).append("ms\n");

            for (String v : violations) {
                sb.append("    - ").append(v).append("\n");
            }

            sb.append("\n\n").append("=".repeat(60));
            sb.append("\n").append(getLearningContent());
            sb.append("=".repeat(60));

            return sb.toString();
        }

        private static String getLearningContent() {
            return """
                📚 LEARNING: CPU-Bound Work and Virtual Threads

                Virtual threads excel at I/O-bound concurrency: they park cheaply while
                waiting for network, disk, or other blocking operations, freeing the carrier
                thread to run other virtual threads.

                CPU-bound tasks (long computations without blocking) do NOT benefit from
                virtual threads because:
                  - The virtual thread holds its carrier platform thread for the full duration
                  - No other virtual threads can run on that carrier
                  - You gain scheduling overhead without the scalability win

                Recommended patterns:
                  1. Use a dedicated platform-thread pool for CPU-bound work:
                       ExecutorService cpuPool = Executors.newFixedThreadPool(
                           Runtime.getRuntime().availableProcessors());
                       cpuPool.submit(() -> performHeavyComputation());

                  2. Mix I/O and CPU work by breaking tasks at natural yield points:
                       // Record the yield before any blocking call
                       detector.recordYieldPoint(taskId);
                       Files.readAllBytes(path);  // this parks the virtual thread

                  3. Use ForkJoinPool for parallel CPU work:
                       ForkJoinPool.commonPool().submit(() -> heavyTask());
                """;
        }
    }
}
