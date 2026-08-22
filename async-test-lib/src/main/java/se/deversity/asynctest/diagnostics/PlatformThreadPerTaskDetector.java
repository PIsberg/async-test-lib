package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects thread-per-task execution on <em>platform</em> threads — one OS thread per task, the
 * pattern virtual threads (JEP 444) exist to replace.
 *
 * <p>Two signals are reported:
 * <ul>
 *   <li><b>Per-task executor with a platform factory</b> — an executor created by
 *       {@code Executors.newThreadPerTaskExecutor(...)} whose factory produces platform threads.
 *       Registering such an executor runs <em>one no-op probe task</em> on it to learn the thread
 *       kind (bounded 200 ms wait; rejection or timeout means no finding).</li>
 *   <li><b>Platform-thread churn</b> — at least {@link #DEFAULT_CHURN_THRESHOLD} recorded
 *       platform-thread creations of which at least half have already terminated by analysis
 *       time. Short-lived per-task threads die with their task; long-lived pool workers recorded
 *       at startup stay alive and do not trip this signal.</li>
 * </ul>
 *
 * <p>Each platform thread costs an OS thread: a ~1 MB default stack reservation, kernel scheduler
 * load, and a hard system-wide ceiling. Thread-per-task on platform threads works in a unit test
 * and collapses under production load — the classic defect this library exists to surface before
 * production does.
 *
 * <p>Usage:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectPlatformThreadPerTask = true)
 * void testThreadCreation() {
 *     var d = AsyncTestContext.platformThreadPerTaskDetector();
 *     Thread worker = new Thread(task);          // per-task platform thread
 *     d.recordThreadCreated(worker);
 *     worker.start();
 * }
 * }</pre>
 *
 * @since 1.8.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER,
    note = "Created threads accumulate in a ConcurrentLinkedQueue; probe bookkeeping in "
        + "ConcurrentHashMap; counters are AtomicInteger. analyze() reads a moment-in-time "
        + "snapshot and is safe to call concurrently with recording.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/PlatformThreadPerTaskDetectorTest.java"
)
public final class PlatformThreadPerTaskDetector {

    /** Platform-thread creations per run before the churn signal may fire. */
    public static final int DEFAULT_CHURN_THRESHOLD = 16;

    private static final String THREAD_PER_TASK_EXECUTOR = "java.util.concurrent.ThreadPerTaskExecutor";
    private static final long PROBE_TIMEOUT_MS = 200;

    private volatile int churnThreshold = DEFAULT_CHURN_THRESHOLD;

    private final Queue<Thread> platformThreadsCreated = new ConcurrentLinkedQueue<>();
    private final AtomicInteger virtualThreadsCreated = new AtomicInteger();
    private final Map<Integer, Boolean> probedExecutors = new ConcurrentHashMap<>();
    private final Map<Integer, String> perTaskPlatformExecutors = new ConcurrentHashMap<>();

    /**
     * Adjust the churn threshold (defaults to {@link #DEFAULT_CHURN_THRESHOLD}).
     * Values below 1 are ignored.
     *
     * @param threshold minimum platform-thread creations before the churn signal may fire
     */
    public void setChurnThreshold(int threshold) {
        if (threshold >= 1) {
            churnThreshold = threshold;
        }
    }

    /**
     * Record a thread the test created for a task — call once per created thread, e.g. from a
     * {@link java.util.concurrent.ThreadFactory} or next to each {@code new Thread(...)} site.
     * Virtual threads count only toward the informational balance; platform threads feed the
     * churn signal.
     *
     * @param thread the freshly created thread (null-safe)
     */
    public void recordThreadCreated(Thread thread) {
        if (thread == null) {
            return;
        }
        if (thread.isVirtual()) {
            virtualThreadsCreated.incrementAndGet();
            return;
        }
        platformThreadsCreated.add(thread);
    }

    /**
     * Register an executor for inspection. Only thread-per-task executors are probed; anything
     * else is ignored. Probing submits one no-op task (see class Javadoc) — registering an
     * executor is consent to inspect it.
     *
     * @param executor the executor the test uses (null-safe)
     * @param name     human-readable label for triage (may be {@code null})
     */
    public void registerExecutor(ExecutorService executor, String name) {
        if (executor == null || !THREAD_PER_TASK_EXECUTOR.equals(executor.getClass().getName())) {
            return;
        }
        int id = System.identityHashCode(executor);
        if (probedExecutors.putIfAbsent(id, Boolean.TRUE) != null) {
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean probeWasVirtual = new AtomicBoolean(true);
        try {
            executor.execute(() -> {
                probeWasVirtual.set(Thread.currentThread().isVirtual());
                done.countDown();
            });
            if (!done.await(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return; // unknown — no finding rather than a guessed one
            }
        } catch (RejectedExecutionException e) {
            return; // shut down or saturated — not this detector's finding
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!probeWasVirtual.get()) {
            perTaskPlatformExecutors.put(id, name != null ? name : "executor@" + id);
        }
    }

    /**
     * Evaluate the observed state and produce a report. Must be idempotent on quiescent state;
     * note the churn signal reads thread liveness at call time, so it stabilises once the
     * recorded threads have terminated.
     */
    public Report analyze() {
        Report r = new Report();
        for (String label : perTaskPlatformExecutors.values()) {
            String msg = String.format(
                    "'%s' is a thread-per-task executor backed by platform threads — every submit costs an"
                            + " OS thread with no upper bound; this is the workload virtual threads exist"
                            + " for (JEP 444)",
                    label);
            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                    "PlatformThreadPerTask",
                    IssueSeverity.HIGH,
                    msg,
                    List.of(),
                    Map.of("label", label),
                    Instant.now()));
        }
        List<Thread> created = new ArrayList<>(platformThreadsCreated);
        int terminated = 0;
        for (Thread t : created) {
            if (t.getState() == Thread.State.TERMINATED) {
                terminated++;
            }
        }
        int threshold = churnThreshold;
        if (created.size() >= threshold && terminated * 2 >= created.size()) {
            String msg = String.format(
                    "%d platform threads created this run (%d already terminated, %d virtual threads"
                            + " created) — short-lived one-task platform threads are churn; virtual threads"
                            + " make thread-per-task the cheap default",
                    created.size(), terminated, virtualThreadsCreated.get());
            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                    "PlatformThreadPerTask",
                    IssueSeverity.MEDIUM,
                    msg,
                    List.of(),
                    Map.of(
                            "platformThreadsCreated", created.size(),
                            "terminated", terminated,
                            "virtualThreadsCreated", virtualThreadsCreated.get()),
                    Instant.now()));
        }
        return r;
    }

    /** Report produced by {@link #analyze()}. {@code hasIssues()} drives the SPI sweep. */
    public static final class Report implements GradedFindings {
        public final List<String> violations = new ArrayList<>();
        public final List<Violation> structuredViolations = new ArrayList<>();

        public boolean hasIssues() { return !violations.isEmpty(); }

        /**
         * One grade per finding, so a verdict-grade finding is not held back by a weaker one from
         * the same detector.
         *
         * <p>The executor finding is a verdict: a probe task reports the thread kind it actually ran on, so
         * "this executor gave every task its own platform thread" is observed rather than inferred.
         * The churn finding is a threshold over platform-thread creations and says nothing about
         * correctness, which is what {@link TrustTier#ADVISORY} means.
         */
        @Override
        public List<GradedFindings.Grade> grades() {
            return structuredViolations.stream()
                    .map(v -> new GradedFindings.Grade(v.severity(), tierOf(v.severity()), v.message()))
                    .toList();
        }

        private static TrustTier tierOf(IssueSeverity severity) {
            return switch (severity) {
            case HIGH -> TrustTier.VERDICT;
            case MEDIUM -> TrustTier.ADVISORY;
            default -> TrustTier.PROMPT;
            };
        }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "PlatformThreadPerTask — clean";
            StringBuilder sb = new StringBuilder("PLATFORM THREAD-PER-TASK DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("""
                      Why: each platform thread reserves an OS thread and ~1 MB of stack and adds kernel
                           scheduler load, with a hard system-wide ceiling. One-thread-per-task on platform
                           threads survives the unit test and collapses under production load.
                      Fix:
                        - Executors.newVirtualThreadPerTaskExecutor() instead of newThreadPerTaskExecutor
                          with a platform factory
                        - Thread.startVirtualThread(task) instead of new Thread(task).start() per task
                        - for CPU-bound work, a pool bounded to the core count is the right tool instead
                      """);
            return sb.toString();
        }
    }
}
