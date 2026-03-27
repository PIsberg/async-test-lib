package com.github.asynctest.diagnostics;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects exception handling issues in CompletableFuture chains.
 * 
 * Common CompletableFuture exception issues detected:
 * - Unhandled exceptions: CompletableFuture completes exceptionally without exception handler
 * - Swallowed exceptions: Exceptions caught but not propagated or logged
 * - Missing .exceptionally() or .handle() in async chains
 * - get()/join() called without proper exception handling
 * 
 * Usage:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectCompletableFutureExceptions = true)
 * void testCompletableFuture() {
 *     CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
 *         AsyncTestContext.completableFutureMonitor()
 *             .recordFutureCreated(future, "async-task");
 *         return "result";
 *     });
 *     
 *     // Register exception handler
 *     future.exceptionally(ex -> {
 *         AsyncTestContext.completableFutureMonitor()
 *             .recordExceptionHandled(future, "async-task", ex);
 *         return "default";
 *     });
 *     
 *     // Or track get/join calls
 *     try {
 *         future.join();
 *         AsyncTestContext.completableFutureMonitor()
 *             .recordFutureCompleted(future, "async-task", true);
 *     } catch (Exception e) {
 *         AsyncTestContext.completableFutureMonitor()
 *             .recordFutureCompleted(future, "async-task", false);
 *     }
 * }
 * }</pre>
 */
public class CompletableFutureExceptionDetector {

    private static class FutureState {
        final String name;
        final CompletableFuture<?> future;
        final long createdTime = System.nanoTime();
        final long creatorThreadId = Thread.currentThread().threadId();
        volatile boolean exceptionHandlerRegistered = false;
        volatile boolean completed = false;
        volatile boolean completedExceptionally = false;
        volatile Exception lastException = null;
        volatile Long completedTime = null;
        final AtomicInteger getJoinCalls = new AtomicInteger(0);

        FutureState(CompletableFuture<?> future, String name) {
            this.future = future;
            this.name = name != null ? name : "future@" + System.identityHashCode(future);
        }
    }

    private final Map<Integer, FutureState> futures = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

    /**
     * Register a CompletableFuture for monitoring.
     * 
     * @param future the CompletableFuture to monitor
     * @param name a descriptive name for reporting
     */
    public void recordFutureCreated(CompletableFuture<?> future, String name) {
        if (!enabled || future == null) {
            return;
        }
        futures.put(System.identityHashCode(future), new FutureState(future, name));
    }

    /**
     * Record that an exception handler was registered for a CompletableFuture.
     * 
     * @param future the CompletableFuture
     * @param name the future name (should match registration)
     * @param exception the exception that was handled
     */
    public void recordExceptionHandled(CompletableFuture<?> future, String name, Throwable exception) {
        if (!enabled || future == null) {
            return;
        }
        FutureState state = futures.get(System.identityHashCode(future));
        if (state != null) {
            state.exceptionHandlerRegistered = true;
            state.lastException = exception instanceof Exception ? (Exception) exception : new Exception(exception);
        }
    }

    /**
     * Record that a CompletableFuture completed (normally or exceptionally).
     * 
     * @param future the CompletableFuture
     * @param name the future name (should match registration)
     * @param success true if completed normally, false if completed exceptionally
     */
    public void recordFutureCompleted(CompletableFuture<?> future, String name, boolean success) {
        if (!enabled || future == null) {
            return;
        }
        FutureState state = futures.get(System.identityHashCode(future));
        if (state != null) {
            state.completed = true;
            state.completedExceptionally = !success;
            state.completedTime = System.nanoTime();
        }
    }

    /**
     * Record a get() or join() call on a CompletableFuture.
     * 
     * @param future the CompletableFuture
     * @param name the future name (should match registration)
     * @param threwException true if get/join threw an exception
     */
    public void recordGetJoinCall(CompletableFuture<?> future, String name, boolean threwException) {
        if (!enabled || future == null) {
            return;
        }
        FutureState state = futures.get(System.identityHashCode(future));
        if (state != null) {
            state.getJoinCalls.incrementAndGet();
            if (threwException) {
                state.completedExceptionally = true;
            }
        }
    }

    /**
     * Analyze CompletableFuture usage for exception handling issues.
     * 
     * @return a report of detected issues
     */
    public CompletableFutureExceptionReport analyze() {
        CompletableFutureExceptionReport report = new CompletableFutureExceptionReport();
        report.enabled = enabled;

        for (FutureState state : futures.values()) {
            // Check for unhandled exceptions
            if (state.completedExceptionally && !state.exceptionHandlerRegistered) {
                report.unhandledExceptions.add(String.format(
                    "%s: completed exceptionally without exception handler",
                    state.name));
            }

            // Check for futures without any exception handler registered
            if (!state.completed && !state.exceptionHandlerRegistered) {
                long ageMs = (System.nanoTime() - state.createdTime) / 1_000_000;
                if (ageMs > 100) { // Only report if future is older than 100ms
                    report.missingHandlers.add(String.format(
                        "%s: no exception handler registered (age: %dms, created by thread %d)",
                        state.name, ageMs, state.creatorThreadId));
                }
            }

            // Check for get/join without proper exception handling
            if (state.getJoinCalls.get() > 0 && state.completedExceptionally && state.lastException == null) {
                report.swallowedExceptions.add(String.format(
                    "%s: get/join called but exception not properly captured",
                    state.name));
            }

            // Track completion status
            if (state.completed) {
                report.completionStatus.put(state.name, 
                    state.completedExceptionally ? "exceptional" : "normal");
            }
        }

        return report;
    }

    /**
     * Report class for CompletableFuture exception analysis.
     */
    public static class CompletableFutureExceptionReport {
        private boolean enabled = true;
        final java.util.List<String> unhandledExceptions = new java.util.ArrayList<>();
        final java.util.List<String> missingHandlers = new java.util.ArrayList<>();
        final java.util.List<String> swallowedExceptions = new java.util.ArrayList<>();
        final Map<String, String> completionStatus = new ConcurrentHashMap<>();

        /**
         * Check if any issues were detected.
         */
        public boolean hasIssues() {
            return !unhandledExceptions.isEmpty() || !missingHandlers.isEmpty() || !swallowedExceptions.isEmpty();
        }

        @Override
        public String toString() {
            if (!enabled) {
                return "CompletableFutureExceptionReport: disabled";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("COMPLETABLEFUTURE EXCEPTION ISSUES DETECTED:\n");

            if (!unhandledExceptions.isEmpty()) {
                sb.append("  Unhandled Exceptions:\n");
                for (String issue : unhandledExceptions) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!missingHandlers.isEmpty()) {
                sb.append("  Missing Exception Handlers:\n");
                for (String issue : missingHandlers) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!swallowedExceptions.isEmpty()) {
                sb.append("  Swallowed Exceptions:\n");
                for (String issue : swallowedExceptions) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!completionStatus.isEmpty()) {
                sb.append("  Completion Status:\n");
                for (Map.Entry<String, String> entry : completionStatus.entrySet()) {
                    sb.append("    - ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
            }

            if (!hasIssues()) {
                sb.append("  No issues detected.\n");
            }

            sb.append("  Fix: always register .exceptionally() or .handle() for async chains");
            return sb.toString();
        }
    }
}
