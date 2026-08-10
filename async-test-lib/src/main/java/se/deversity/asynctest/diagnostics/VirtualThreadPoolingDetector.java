package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects virtual threads being pooled or reused across tasks — the central anti-pattern
 * JEP 444 warns about: a virtual thread is a cheap, single-task object and must never be pooled.
 *
 * <p>Two signals are reported:
 * <ul>
 *   <li><b>Pooled executor manufacturing virtual threads</b> — a {@link ThreadPoolExecutor}
 *       (including {@code ScheduledThreadPoolExecutor} and the {@code Executors.newFixedThreadPool} /
 *       {@code newCachedThreadPool} / {@code newSingleThreadExecutor} wrappers) whose
 *       {@link ThreadFactory} produces virtual threads. Concurrency is then capped at the pool
 *       size, and the pooled "workers" are virtual threads that never terminate — the cost model
 *       of a pool with none of its benefit. Identified by probing the factory with one
 *       <em>unstarted</em> thread, which is discarded without side effects.</li>
 *   <li><b>Observed reuse</b> — the same virtual thread executing more than one recorded task.
 *       Reuse carries {@code ThreadLocal} state from one task into the next and implies a pool or
 *       hand-rolled recycling upstream.</li>
 * </ul>
 *
 * <p>Custom pooled executors that are not {@code ThreadPoolExecutor} subclasses cannot be probed
 * and are caught only by the reuse signal — a false negative is acceptable here, a false positive
 * is not. {@code Executors.newVirtualThreadPerTaskExecutor()} is the correct pattern and is never
 * flagged.
 *
 * <p>Usage:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectVirtualThreadPooling = true)
 * void testExecutorChoice() {
 *     var d = AsyncTestContext.virtualThreadPoolingDetector();
 *     d.registerExecutor(executor, "request-pool");
 *     executor.submit(() -> {
 *         d.recordTaskExecution("request-pool");
 *         // ... task body ...
 *     });
 * }
 * }</pre>
 *
 * @since 1.8.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER,
    note = "Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; "
        + "thread-id map values use AtomicInteger counters and ConcurrentHashMap.newKeySet().")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/VirtualThreadPoolingDetectorTest.java"
)
public final class VirtualThreadPoolingDetector {

    private static final class ExecutorInfo {
        final String name;
        final String executorClass;
        final int maximumPoolSize;
        final boolean poolsVirtualThreads;

        ExecutorInfo(String name, String executorClass, int maximumPoolSize, boolean poolsVirtualThreads) {
            this.name = name;
            this.executorClass = executorClass;
            this.maximumPoolSize = maximumPoolSize;
            this.poolsVirtualThreads = poolsVirtualThreads;
        }
    }

    private static final class ThreadTasks {
        final String threadName;
        final AtomicInteger taskCount = new AtomicInteger();
        final Set<String> executorNames = ConcurrentHashMap.newKeySet();

        ThreadTasks(String threadName) { this.threadName = threadName; }
    }

    private final Map<Integer, ExecutorInfo> executors = new ConcurrentHashMap<>();
    private final Map<Long, ThreadTasks> tasksPerVirtualThread = new ConcurrentHashMap<>();

    /**
     * Register an executor for inspection. Only {@link ThreadPoolExecutor} subclasses carry a
     * probe-able factory and a pool to misuse; other executor types are ignored (per-task
     * executors have nothing to pool, custom pools are caught by the reuse signal).
     *
     * @param executor the executor the test uses (null-safe)
     * @param name     human-readable label for triage (may be {@code null})
     */
    public void registerExecutor(ExecutorService executor, String name) {
        if (!(executor instanceof ThreadPoolExecutor pool)) {
            return;
        }
        int id = System.identityHashCode(executor);
        if (executors.containsKey(id)) {
            return;
        }
        String label = name != null ? name : executor.getClass().getSimpleName() + "@" + id;
        executors.computeIfAbsent(id, k -> new ExecutorInfo(
                label,
                executor.getClass().getName(),
                pool.getMaximumPoolSize(),
                manufacturesVirtualThreads(pool.getThreadFactory())));
    }

    /** Probes with one unstarted thread, which is discarded — no thread is ever started. */
    private static boolean manufacturesVirtualThreads(ThreadFactory factory) {
        if (factory == null) {
            return false;
        }
        try {
            Thread probe = factory.newThread(() -> { });
            return probe != null && probe.isVirtual();
        } catch (RuntimeException e) {
            return false; // a throwing factory is somebody else's finding
        }
    }

    /**
     * Record that the calling thread executed one task. Call once per task, from inside the task.
     * Platform threads are ignored — reusing those is what pools are for.
     *
     * @param executorName label of the executor the task ran on (may be {@code null})
     */
    public void recordTaskExecution(String executorName) {
        recordTaskExecution(executorName, Thread.currentThread());
    }

    /**
     * Record that {@code thread} executed one task. Overload for callers observing another thread.
     *
     * @param executorName label of the executor the task ran on (may be {@code null})
     * @param thread       the thread that executed the task (null-safe)
     */
    public void recordTaskExecution(String executorName, Thread thread) {
        if (thread == null || !thread.isVirtual()) {
            return;
        }
        long id = thread.threadId();
        ThreadTasks tasks = tasksPerVirtualThread.get(id);
        if (tasks == null) {
            final String threadName = thread.getName();
            tasks = tasksPerVirtualThread.computeIfAbsent(id, k -> new ThreadTasks(threadName));
        }
        tasks.taskCount.incrementAndGet();
        if (executorName != null) {
            tasks.executorNames.add(executorName);
        }
    }

    /**
     * Evaluate the observed state and produce a report. Must be idempotent:
     * calling it N times on quiescent state yields N identical reports.
     */
    public Report analyze() {
        Report r = new Report();
        for (ExecutorInfo info : executors.values()) {
            if (!info.poolsVirtualThreads) {
                continue;
            }
            String msg = String.format(
                    "'%s' (%s) pools virtual threads: concurrency is capped at maximumPoolSize=%d and the"
                            + " pooled workers are virtual threads that never terminate — a virtual thread"
                            + " is per-task and must never be pooled (JEP 444)",
                    info.name, info.executorClass, info.maximumPoolSize);
            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                    "VirtualThreadPooling",
                    IssueSeverity.HIGH,
                    msg,
                    List.of(),
                    Map.of(
                            "label", info.name,
                            "executorClass", info.executorClass,
                            "maximumPoolSize", info.maximumPoolSize),
                    Instant.now()));
        }
        for (Map.Entry<Long, ThreadTasks> entry : tasksPerVirtualThread.entrySet()) {
            ThreadTasks tasks = entry.getValue();
            int count = tasks.taskCount.get();
            if (count < 2) {
                continue;
            }
            String via = tasks.executorNames.isEmpty()
                    ? ""
                    : " via " + String.join(", ", tasks.executorNames);
            String msg = String.format(
                    "virtual thread '%s' (id=%d) executed %d recorded tasks%s — a virtual thread runs one"
                            + " task and terminates; reuse carries ThreadLocal state across tasks and"
                            + " implies a pool upstream",
                    tasks.threadName, entry.getKey(), count, via);
            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                    "VirtualThreadPooling",
                    IssueSeverity.HIGH,
                    msg,
                    List.of(),
                    Map.of(
                            "label", tasks.threadName,
                            "threadId", entry.getKey(),
                            "taskCount", count),
                    Instant.now()));
        }
        return r;
    }

    /** Report produced by {@link #analyze()}. {@code hasIssues()} drives the SPI sweep. */
    public static final class Report {
        public final List<String> violations = new ArrayList<>();
        public final List<Violation> structuredViolations = new ArrayList<>();

        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "VirtualThreadPooling — clean";
            StringBuilder sb = new StringBuilder("VIRTUAL THREAD POOLING DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("""
                      Why: JEP 444 — a virtual thread is a cheap, single-task object. Pooling caps concurrency at
                           the pool size, keeps every pooled worker and its ThreadLocals alive indefinitely, and
                           reintroduces the queueing that virtual threads were designed to remove.
                      Fix:
                        - Executors.newVirtualThreadPerTaskExecutor() — one fresh virtual thread per task
                        - never hand Thread.ofVirtual().factory() to newFixedThreadPool/ThreadPoolExecutor
                        - to limit concurrency, acquire a Semaphore around the guarded operation instead of
                          shrinking a pool
                      """);
            return sb.toString();
        }
    }
}
