package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

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

    /** Labels for objects the test gave no name, numbered per kind within this detector (#860). */
    private final UnnamedLabels unnamedLabels = new UnnamedLabels();

    /** How old, in milliseconds, an incomplete future without a handler must be to be reported (#756). */
    private static final long MISSING_HANDLER_AGE_THRESHOLD_MS = 100;

    private static class FutureState {
        final String name;
        final long createdTime = System.nanoTime();
        final long creatorThreadId = Thread.currentThread().threadId();
        volatile boolean exceptionHandlerRegistered = false;
        volatile boolean completed = false;
        volatile boolean completedExceptionally = false;
        volatile @Nullable Exception lastException = null;
        final AtomicInteger getJoinCalls = new AtomicInteger(0);

        FutureState(String name, UnnamedLabels labels) {
            this.name = name != null ? name : labels.next("future");
        }
    }

    private final Map<IdentityKey, FutureState> futures = new ConcurrentHashMap<>();
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
        // computeIfAbsent, not put: re-declaring a future already tracked would discard whether
        // a handler had been registered on it, which is the whole finding.
        futures.computeIfAbsent(new IdentityKey(future), k -> new FutureState(name, unnamedLabels));
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
        FutureState state = futures.get(new IdentityKey(future));
        if (state != null) {
            state.exceptionHandlerRegistered = true;
            state.lastException = exception instanceof Exception e ? e : new Exception(exception);
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
        FutureState state = futures.get(new IdentityKey(future));
        if (state != null) {
            state.completed = true;
            state.completedExceptionally = !success;
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
        FutureState state = futures.get(new IdentityKey(future));
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
                if (ageMs > MISSING_HANDLER_AGE_THRESHOLD_MS) {
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

        if (report.hasIssues()) {
            // The severity the failOn gate read from this text before #801: a marker in it,
            // else the value DetectorDefaultSeverity declared for the detector.
            IssueSeverity severity = IssueSeverity.markedIn(report.toString())
                    .orElse(IssueSeverity.HIGH);
            for (String finding : report.unhandledExceptions) {
                report.structuredViolations.add(new Violation("CompletableFutureException", severity,
                        finding, List.of(), Map.of(), Instant.now()));
            }
            for (String finding : report.missingHandlers) {
                report.structuredViolations.add(new Violation("CompletableFutureException", severity,
                        finding, List.of(), Map.of(), Instant.now()));
            }
            for (String finding : report.swallowedExceptions) {
                report.structuredViolations.add(new Violation("CompletableFutureException", severity,
                        finding, List.of(), Map.of(), Instant.now()));
            }
        }
        return DetectorFailurePolicy.checkedReport(this, report);
    }

    /**
     * Report class for CompletableFuture exception analysis.
     */
    public static class CompletableFutureExceptionReport {
        private boolean enabled = true;
        final List<String> unhandledExceptions = new ArrayList<>();
        /** The findings as Violations, at the severity the text resolved to (#801). */
        public final List<Violation> structuredViolations = new ArrayList<>();
        final List<String> missingHandlers = new ArrayList<>();
        final List<String> swallowedExceptions = new ArrayList<>();
        final Map<String, String> completionStatus = new ConcurrentHashMap<>();

        /**
         * Check if any issues were detected.
         *
         * @return {@code true} when this detector recorded something worth reporting
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

            sb.append("""
  Why: An unhandled exception in a CompletableFuture stage causes the future to complete exceptionally.
       Without .exceptionally() or .handle(), calling get()/join() will throw a wrapping ExecutionException,
       and any downstream thenApply/thenCompose stages are silently skipped, leaving the chain in an
       incomplete state with no visible error.
  Fix:
    - Add .exceptionally(ex -> fallbackValue) to recover from errors in the chain
    - Use .handle((result, ex) -> ...) when you need to inspect both success and failure in one place
    - Call get() inside a try/catch ExecutionException so failures surface immediately\
""");
            return sb.toString();
        }
    }
}
