package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import se.deversity.vibetags.annotations.AITestDriven;

/**
 * Detects blocking operations ({@link Thread#sleep}, {@link Object#wait}, blocking I/O,
 * {@code Future.get()}) running inside {@link CompletableFuture} stages that were submitted
 * to the common {@link ForkJoinPool} — i.e. created without a custom {@link java.util.concurrent.Executor}.
 *
 * <p>The common pool is shared by parallel streams and all {@code CompletableFuture} stages
 * submitted without an explicit executor. Blocking tasks starve the pool for all other JVM
 * callers. The fix is to always supply a dedicated {@code Executor} for I/O-bound or otherwise
 * blocking async work (e.g. {@code Executors.newVirtualThreadPerTaskExecutor()}).
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var mon = AsyncTestContext.completableFutureCommonPoolBlockingMonitor();
 * CompletableFuture<String> cf = CompletableFuture.supplyAsync(this::fetchData);
 * mon.recordCommonPoolSubmission(cf, Thread.currentThread(), "fetchData");
 * // inside the task body:
 * mon.recordBlockingCall(cf, Thread.currentThread(), "InputStream.read");
 * }</pre>
 */
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/CompletableFutureCommonPoolBlockingDetectorTest.java"
)
public class CompletableFutureCommonPoolBlockingDetector {

    private final Set<Integer>         commonPoolFutures = ConcurrentHashMap.newKeySet();
    private final Map<Integer, String> futureNames       = new ConcurrentHashMap<>();
    private final List<String>         violations        = new CopyOnWriteArrayList<>();

    /**
     * Record a {@code CompletableFuture} submitted to the common {@link ForkJoinPool}
     * (created via {@code supplyAsync}/{@code runAsync} without an explicit executor).
     *
     * @param future   the CompletableFuture (null-safe)
     * @param thread   the submitting thread (null-safe)
     * @param taskName descriptive name for reports
     */
    public void recordCommonPoolSubmission(Object future, Thread thread, String taskName) {
        if (future == null) return;
        int id = System.identityHashCode(future);
        commonPoolFutures.add(id);
        futureNames.put(id, taskName != null ? taskName : "task@" + id);
    }

    /**
     * Record a blocking call made inside a CompletableFuture stage.
     * Calls on futures not registered via {@link #recordCommonPoolSubmission} are ignored.
     *
     * @param future   the enclosing CompletableFuture (null-safe)
     * @param thread   the calling thread (null-safe)
     * @param callType human-readable description, e.g. "Thread.sleep", "JDBC query"
     */
    public void recordBlockingCall(Object future, Thread thread, String callType) {
        if (future == null || thread == null) return;
        int id = System.identityHashCode(future);
        if (!commonPoolFutures.contains(id)) return;
        String name = futureNames.getOrDefault(id, "future@" + id);
        String type = callType != null ? callType : "blocking call";
        violations.add(String.format(
            "Thread '%s' made blocking call (%s) inside CompletableFuture '%s' "
            + "running on the common ForkJoinPool — starves the pool for parallel streams "
            + "and all other common-pool users in this JVM",
            thread.getName(), type, name));
    }

    /** @return report of blocking calls in common-pool futures */
    public CompletableFutureCommonPoolBlockingReport analyze() {
        CompletableFutureCommonPoolBlockingReport r = new CompletableFutureCommonPoolBlockingReport();
        r.violations.addAll(violations);
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static class CompletableFutureCommonPoolBlockingReport {
        final List<String> violations = new ArrayList<>();

        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("COMPLETABLEFUTURE COMMON POOL BLOCKING DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append("\n");
            sb.append("  Fix: supply a dedicated Executor to supplyAsync/runAsync for blocking tasks, "
                    + "e.g. CompletableFuture.supplyAsync(task, Executors.newVirtualThreadPerTaskExecutor())");
            return sb.toString();
        }
    }
}
