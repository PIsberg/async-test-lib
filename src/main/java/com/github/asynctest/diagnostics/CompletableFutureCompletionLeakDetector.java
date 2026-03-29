package com.github.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects CompletableFuture instances that are created but never completed.
 *
 * <p>Uncompleted CompletableFutures are a common source of hangs and resource leaks:
 * <ul>
 *   <li>Created with {@code new CompletableFuture<>()} but never completed</li>
 *   <li>Returned from methods without completion guarantee</li>
 *   <li>Left pending when exception paths skip completion</li>
 *   <li>Completion called on wrong code path (e.g., only on success, not failure)</li>
 * </ul>
 *
 * <p><strong>Usage:</strong>
 * <pre>{@code
 * @AsyncTest(threads = 4, detectCompletableFutureCompletionLeaks = true)
 * void testCompletableFuture() {
 *     CompletableFuture<String> future = new CompletableFuture<>();
 *
 *     // Track creation
 *     AsyncTestContext.completableFutureCompletionLeakDetector()
 *         .recordFutureCreated(future, "my-future");
 *
 *     // Track completion (call in all code paths!)
 *     try {
 *         String result = doWork();
 *         future.complete(result);
 *         AsyncTestContext.completableFutureCompletionLeakDetector()
 *             .recordFutureCompleted(future, "my-future");
 *     } catch (Exception e) {
 *         future.completeExceptionally(e);
 *         AsyncTestContext.completableFutureCompletionLeakDetector()
 *             .recordFutureCompleted(future, "my-future");
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Report:</strong> Lists all uncompleted futures with:
 * <ul>
 *   <li>Name/identity hash</li>
 *   <li>Creation time</li>
 *   <li>Creating thread ID</li>
 *   <li>Stack trace at creation point</li>
 * </ul>
 *
 * @see CompletableFutureExceptionDetector
 */
public class CompletableFutureCompletionLeakDetector {

    private static class FutureState {
        final CompletableFuture<?> future;
        final String name;
        final long createdTimeNanos = System.nanoTime();
        final long creatorThreadId = Thread.currentThread().threadId();
        final StackTraceElement[] creationStackTrace = Thread.currentThread().getStackTrace();
        volatile boolean completed = false;
        volatile Long completedTimeNanos = null;
        volatile String completionType = null; // "complete", "completeExceptionally", "cancel"
        final AtomicInteger completionAttempts = new AtomicInteger(0);

        FutureState(CompletableFuture<?> future, String name) {
            this.future = future;
            this.name = name != null ? name : "future@" + System.identityHashCode(future);
        }
    }

    private final Map<Integer, FutureState> futures = new ConcurrentHashMap<>();
    private final AtomicInteger leakCount = new AtomicInteger(0);
    private volatile boolean enabled = true;

    /**
     * Register a CompletableFuture for completion leak monitoring.
     *
     * @param future the CompletableFuture to monitor
     * @param name a descriptive name for reporting (e.g., "user-lookup-future")
     */
    public void recordFutureCreated(CompletableFuture<?> future, String name) {
        if (!enabled || future == null) {
            return;
        }
        int identity = System.identityHashCode(future);
        FutureState state = new FutureState(future, name);
        futures.put(identity, state);
        leakCount.incrementAndGet();

        // Attach a completion listener to auto-track completion
        future.whenComplete((result, ex) -> {
            if (!state.completed) {
                // Mark as completed via whenComplete (not explicit call)
                state.completed = true;
                state.completedTimeNanos = System.nanoTime();
                state.completionType = "whenComplete";
                leakCount.decrementAndGet();
            }
        });
    }

    /**
     * Explicitly mark a CompletableFuture as completed.
     *
     * <p>Call this after {@code future.complete()}, {@code future.completeExceptionally()},
     * or {@code future.cancel()} to ensure accurate tracking.
     *
     * @param future the CompletableFuture that was completed
     * @param name the same name used in {@link #recordFutureCreated}
     */
    public void recordFutureCompleted(CompletableFuture<?> future, String name) {
        recordFutureCompleted(future, name, "explicit");
    }

    /**
     * Explicitly mark a CompletableFuture as completed with completion type.
     *
     * @param future the CompletableFuture that was completed
     * @param name the same name used in {@link #recordFutureCreated}
     * @param completionType type of completion ("complete", "completeExceptionally", "cancel")
     */
    public void recordFutureCompleted(CompletableFuture<?> future, String name, String completionType) {
        if (!enabled || future == null) {
            return;
        }
        int identity = System.identityHashCode(future);
        FutureState state = futures.get(identity);
        if (state != null && !state.completed) {
            state.completed = true;
            state.completedTimeNanos = System.nanoTime();
            state.completionType = completionType;
            state.completionAttempts.incrementAndGet();
            leakCount.decrementAndGet();
        }
    }

    /**
     * Analyze for uncompleted CompletableFuture instances.
     *
     * @return report containing all leaked futures
     */
    public CompletionLeakReport analyze() {
        if (!enabled) {
            return new CompletionLeakReport(Collections.emptyList());
        }

        List<LeakedFuture> leaked = new ArrayList<>();
        for (FutureState state : futures.values()) {
            if (!state.completed) {
                long ageMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - state.createdTimeNanos);
                leaked.add(new LeakedFuture(
                    state.name,
                    state.creatorThreadId,
                    ageMillis,
                    state.creationStackTrace
                ));
            }
        }

        return new CompletionLeakReport(leaked);
    }

    /**
     * Check if there are any completion leaks.
     *
     * @return true if uncompleted futures were detected
     */
    public boolean hasLeaks() {
        return enabled && leakCount.get() > 0;
    }

    /**
     * Get the count of uncompleted futures.
     *
     * @return number of leaked futures
     */
    public int getLeakCount() {
        return leakCount.get();
    }

    /**
     * Enable or disable monitoring.
     *
     * @param enabled true to enable, false to disable
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Clear all tracked futures.
     *
     * <p>Useful for test cleanup between test methods.
     */
    public void clear() {
        futures.clear();
        leakCount.set(0);
    }

    /**
     * Report of CompletableFuture completion leaks.
     */
    public static class CompletionLeakReport {
        private final List<LeakedFuture> leakedFutures;

        CompletionLeakReport(List<LeakedFuture> leakedFutures) {
            this.leakedFutures = leakedFutures;
        }

        /**
         * @return list of leaked futures
         */
        public List<LeakedFuture> getLeakedFutures() {
            return leakedFutures;
        }

        /**
         * @return true if any leaks were detected
         */
        public boolean hasLeaks() {
            return !leakedFutures.isEmpty();
        }

        /**
         * @return number of leaked futures
         */
        public int getLeakCount() {
            return leakedFutures.size();
        }

        @Override
        public String toString() {
            if (leakedFutures.isEmpty()) {
                return "CompletableFutureCompletionLeakReport: No leaks detected";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("CompletableFutureCompletionLeakReport: ")
              .append(leakedFutures.size())
              .append(" uncompleted CompletableFuture(s) detected:\n");

            for (int i = 0; i < leakedFutures.size(); i++) {
                LeakedFuture lf = leakedFutures.get(i);
                sb.append("\n  [").append(i + 1).append("] ")
                  .append(lf.name)
                  .append("\n      Created by thread #").append(lf.creatorThreadId)
                  .append(" ").append(lf.ageMillis).append("ms ago");

                // Show top 5 stack frames from creation point
                if (lf.creationStackTrace != null && lf.creationStackTrace.length > 3) {
                    sb.append("\n      Creation stack trace:");
                    int framesToShow = Math.min(5, lf.creationStackTrace.length - 2);
                    for (int j = 2; j < 2 + framesToShow; j++) {
                        sb.append("\n        at ").append(lf.creationStackTrace[j]);
                    }
                }
            }

            sb.append("\n\n  Possible causes:");
            sb.append("\n    - CompletableFuture created but never completed");
            sb.append("\n    - Exception path skipped completion (missing completeExceptionally)");
            sb.append("\n    - Completion called on wrong object instance");
            sb.append("\n    - Race condition: completion happens after test timeout");

            return sb.toString();
        }
    }

    /**
     * Represents a single leaked CompletableFuture.
     */
    public static class LeakedFuture {
        private final String name;
        private final long creatorThreadId;
        private final long ageMillis;
        private final StackTraceElement[] creationStackTrace;

        LeakedFuture(String name, long creatorThreadId, long ageMillis, StackTraceElement[] stackTrace) {
            this.name = name;
            this.creatorThreadId = creatorThreadId;
            this.ageMillis = ageMillis;
            this.creationStackTrace = stackTrace;
        }

        /**
         * @return the descriptive name of the future
         */
        public String getName() {
            return name;
        }

        /**
         * @return thread ID that created the future
         */
        public long getCreatorThreadId() {
            return creatorThreadId;
        }

        /**
         * @return age in milliseconds since creation
         */
        public long getAgeMillis() {
            return ageMillis;
        }

        /**
         * @return stack trace at creation point
         */
        public StackTraceElement[] getCreationStackTrace() {
            return creationStackTrace;
        }

        @Override
        public String toString() {
            return String.format("LeakedFuture{name='%s', threadId=%d, age=%dms}",
                name, creatorThreadId, ageMillis);
        }
    }
}
