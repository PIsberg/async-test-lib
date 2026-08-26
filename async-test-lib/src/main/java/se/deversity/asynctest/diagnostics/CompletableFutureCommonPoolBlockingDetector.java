package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

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
public class CompletableFutureCommonPoolBlockingDetector {

    /**
     * The most distinct findings this detector will keep. A finding is one (thread, call type,
     * future) triple, so the cap is only reached by a subject that really does block on the
     * common pool from that many distinct places; everything past it is dropped and counted.
     *
     * <p>Checked without a lock, so a burst of threads all reaching an unseen finding at once
     * can seat a few more than this. It is a bound on the report's size, not an exact quota.
     */
    static final int MAX_DISTINCT_FINDINGS = 200;

    private final Set<Integer>         commonPoolFutures = ConcurrentHashMap.newKeySet();
    private final Map<Integer, String> futureNames       = new ConcurrentHashMap<>();

    /**
     * Finding text to the number of times it was recorded.
     *
     * <p>This was a {@code CopyOnWriteArrayList} appended to unconditionally, which made the
     * report one line per blocking call: a {@code threads = 8, invocations = 50} run printed the
     * same sentence 400 times with a different worker number. It also made recording quadratic -
     * every append copied the whole array, on the very threads the detector is watching - with
     * nothing capping it. Collapsing on the text keeps the report readable and the recording
     * path O(1). See issue #351.
     */
    private final Map<String, AtomicInteger> occurrences = new ConcurrentHashMap<>();
    private final AtomicInteger droppedDistinctFindings = new AtomicInteger();

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
        String finding = String.format(
            "Thread '%s' made blocking call (%s) inside CompletableFuture '%s' "
            + "running on the common ForkJoinPool — starves the pool for parallel streams "
            + "and all other common-pool users in this JVM",
            thread.getName(), type, name);

        AtomicInteger seen = occurrences.get(finding);
        if (seen != null) {
            seen.incrementAndGet();
            return;
        }
        if (occurrences.size() >= MAX_DISTINCT_FINDINGS) {
            droppedDistinctFindings.incrementAndGet();
            return;
        }
        // A racing put of the same key is why this is merge and not put: two threads reaching
        // an unseen finding at once must count two occurrences, not one.
        occurrences.merge(finding, new AtomicInteger(1), (existing, ignored) -> {
            existing.incrementAndGet();
            return existing;
        });
    }

    /**
     * {@return report of blocking calls in common-pool futures}
     */
    public CompletableFutureCommonPoolBlockingReport analyze() {
        CompletableFutureCommonPoolBlockingReport r = new CompletableFutureCommonPoolBlockingReport();
        for (Map.Entry<String, AtomicInteger> e : occurrences.entrySet()) {
            int count = e.getValue().get();
            r.violations.add(count > 1 ? e.getKey() + " (x" + count + ")" : e.getKey());
        }
        r.violations.sort(null);
        int dropped = droppedDistinctFindings.get();
        if (dropped > 0) {
            r.droppedDistinctFindings = dropped;
        }
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static class CompletableFutureCommonPoolBlockingReport {
        final List<String> violations = new ArrayList<>();
        /** Distinct findings refused because {@link #MAX_DISTINCT_FINDINGS} was already reached. */
        int droppedDistinctFindings;

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("COMPLETABLEFUTURE COMMON POOL BLOCKING DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append("\n");
            if (droppedDistinctFindings > 0) {
                sb.append("  - ").append(droppedDistinctFindings)
                  .append(" further distinct finding(s) not shown; the detector keeps about ")
                  .append(MAX_DISTINCT_FINDINGS).append(".\n");
            }
            sb.append("  Fix: supply a dedicated Executor to supplyAsync/runAsync for blocking tasks, "
                    + "e.g. CompletableFuture.supplyAsync(task, Executors.newVirtualThreadPerTaskExecutor())");
            return sb.toString();
        }
    }
}
