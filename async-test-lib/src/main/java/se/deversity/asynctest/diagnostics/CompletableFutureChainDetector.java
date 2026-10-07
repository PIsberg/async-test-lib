package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects improper CompletableFuture chain usage in concurrent code.
 *
 * Common CompletableFuture chain issues detected:
 * - Missing .exceptionally() or .handle() in async chains
 * - CompletableFuture created but never joined/awaited
 * - Chained operations without proper exception handling
 * - Blocking calls (.join(), .get()) on the same thread pool
 * - CompletableFuture chains not properly propagated
 *
 * Usage:
 * <pre>{@code
 * @AsyncTest(threads = 10, includes = DetectorType.COMPLETABLEFUTURE_CHAIN)
 * void testCompletableFutureChain() {
 *     CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> "result");
 *     AsyncTestContext.cfChainDetector()
 *         .recordFutureCreated(future, "async-operation");
 *     
 *     CompletableFuture<String> chained = future.thenApply(s -> s.toUpperCase());
 *     AsyncTestContext.cfChainDetector()
 *         .recordChainOperation(future, chained, "thenApply");
 *     
 *     String result = chained.join();
 *     AsyncTestContext.cfChainDetector()
 *         .recordFutureJoined(chained, "async-operation");
 * }
 * }</pre>
 */
public class CompletableFutureChainDetector extends AbstractInstanceDetector<CompletableFutureChainDetector.FutureState> {

    private static final java.util.regex.Pattern ARROW = java.util.regex.Pattern.compile("->");

    static final class FutureState {
        final String name;
        final long createdTime;
        final long createdByThread;
        volatile boolean joined;
        volatile boolean exceptionallyAdded;
        volatile boolean handled;
        final List<String> chainOperations = new java.util.concurrent.CopyOnWriteArrayList<>();

        FutureState(String name) {
            this.name = name;
            this.createdTime = System.currentTimeMillis();
            this.createdByThread = Thread.currentThread().threadId();
        }
    }

    @Override
    FutureState newState(Object instance, String label) {
        return new FutureState(label);
    }

    private final AtomicInteger totalCreated = new AtomicInteger(0);
    private final AtomicInteger totalJoined = new AtomicInteger(0);
    private final AtomicInteger totalChained = new AtomicInteger(0);
    private volatile boolean enabled = true;

    /**
     * Disable this detector.
     */
    public void disable() {
        enabled = false;
    }

    /**
     * Enable this detector.
     */
    public void enable() {
        enabled = true;
    }

    /**
     * Record a CompletableFuture being created.
     *
     * @param future the future instance
     * @param name a descriptive name for tracking
     */
    public void recordFutureCreated(CompletableFuture<?> future, String name) {
        if (!enabled || future == null) {
            return;
        }
        // The first record wins: a put() here replaced the state, so recording a future again
        // forgot that it had been joined and reported it as never joined.
        stateFor(future, name, "CompletableFuture");
        totalCreated.incrementAndGet();
    }

    /**
     * Record a chain operation on a CompletableFuture.
     *
     * @param original the original future
     * @param result the resulting future from the chain operation
     * @param operation the operation name (e.g., "thenApply", "thenCompose")
     */
    public void recordChainOperation(CompletableFuture<?> original, 
                                     CompletableFuture<?> result,
                                     String operation) {
        if (!enabled || original == null || result == null) {
            return;
        }
        FutureState state = trackedState(original);
        if (state != null) {
            state.chainOperations.add(operation);
        }
        
        // Track the new future too
        if (trackedState(result) == null) {
            stateFor(result, state != null ? state.name + "->" + operation : "chained-" + operation);
        }
        
        totalChained.incrementAndGet();
    }

    /**
     * Record .exceptionally() being added to a CompletableFuture.
     *
     * @param future the future instance
     */
    public void recordExceptionally(CompletableFuture<?> future) {
        if (!enabled || future == null) {
            return;
        }
        FutureState state = trackedState(future);
        if (state != null) {
            state.exceptionallyAdded = true;
            // Mark all futures in this chain
            String baseName = ARROW.split(state.name, -1)[0];
            for (FutureState otherState : states()) {
                if (otherState.name.startsWith(baseName)) {
                    otherState.exceptionallyAdded = true;
                    otherState.joined = true;
                }
            }
        }
    }

    /**
     * Record .handle() being added to a CompletableFuture.
     *
     * @param future the future instance
     */
    public void recordHandle(CompletableFuture<?> future) {
        if (!enabled || future == null) {
            return;
        }
        FutureState state = trackedState(future);
        if (state != null) {
            state.handled = true;
            // Mark all futures in this chain
            String baseName = ARROW.split(state.name, -1)[0];
            for (FutureState otherState : states()) {
                if (otherState.name.startsWith(baseName)) {
                    otherState.handled = true;
                    otherState.joined = true;
                }
            }
        }
    }

    /**
     * Record a CompletableFuture being joined/awaited.
     *
     * @param future the future instance
     * @param name should match the creation name
     */
    public void recordFutureJoined(CompletableFuture<?> future, String name) {
        if (!enabled || future == null) {
            return;
        }
        FutureState state = trackedState(future);
        if (state != null) {
            state.joined = true;
        }
        totalJoined.incrementAndGet();
    }

    /**
     * Analyze CompletableFuture chain usage for issues.
     *
     * @return a report of detected issues
     */
    public CompletableFutureChainReport analyze() {
        CompletableFutureChainReport report = new CompletableFutureChainReport();
        report.enabled = enabled;
        
        report.totalCreated = totalCreated.get();
        report.totalJoined = totalJoined.get();
        report.totalChained = totalChained.get();

        // Check for unjoined futures
        for (FutureState state : states()) {
            
            if (!state.joined) {
                long waitTime = System.currentTimeMillis() - state.createdTime;
                report.unjoinedFutures.add(String.format(
                    "%s: created by thread %d, never joined (age: %d ms)",
                    state.name, state.createdByThread, waitTime));
            }

            // Check for missing exception handling
            if (!state.exceptionallyAdded && !state.handled && !state.chainOperations.isEmpty()) {
                report.missingExceptionHandler.add(String.format(
                    "%s: chain operations (%s) without .exceptionally() or .handle()",
                    state.name, String.join(", ", state.chainOperations)));
            }
        }

        // Check for futures created but never used
        long unjoined = states().stream()
            .filter(state -> !state.joined)
            .count();
        if (unjoined > 0) {
            report.unusedFutures.add(String.format(
                "%d futures created but never joined", unjoined));
        }

        if (report.hasIssues()) {
            // The severity the failOn gate read from this text before #801: a marker in it,
            // else the value DetectorDefaultSeverity declared for the detector.
            IssueSeverity severity = IssueSeverity.markedIn(report.toString())
                    .orElse(IssueSeverity.HIGH);
            for (String finding : report.unjoinedFutures) {
                report.structuredViolations.add(new Violation("CompletableFutureChain", severity,
                        finding, List.of(), Map.of(), Instant.now()));
            }
            for (String finding : report.missingExceptionHandler) {
                report.structuredViolations.add(new Violation("CompletableFutureChain", severity,
                        finding, List.of(), Map.of(), Instant.now()));
            }
            for (String finding : report.unusedFutures) {
                report.structuredViolations.add(new Violation("CompletableFutureChain", severity,
                        finding, List.of(), Map.of(), Instant.now()));
            }
        }
        return DetectorFailurePolicy.checkedReport(this, report);
    }

    /**
     * Report class for CompletableFuture chain issues.
     */
    public static class CompletableFutureChainReport {
        private boolean enabled = true;
        int totalCreated;
        int totalJoined;
        int totalChained;
        final List<String> unjoinedFutures = new ArrayList<>();
        /** The findings as Violations, at the severity the text resolved to (#801). */
        public final List<Violation> structuredViolations = new ArrayList<>();
        final List<String> missingExceptionHandler = new ArrayList<>();
        final List<String> unusedFutures = new ArrayList<>();

        /**
         * Check if any issues were detected.
         *
         * @return {@code true} when this detector recorded something worth reporting
         */
        public boolean hasIssues() {
            return !unjoinedFutures.isEmpty() || 
                   !missingExceptionHandler.isEmpty() || 
                   !unusedFutures.isEmpty();
        }

        @Override
        public String toString() {
            if (!enabled) {
                return "CompletableFutureChainReport: disabled";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("COMPLETABLEFUTURE CHAIN ISSUES DETECTED:\n");

            if (!unjoinedFutures.isEmpty()) {
                sb.append("  Unjoined Futures:\n");
                for (String issue : unjoinedFutures) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!missingExceptionHandler.isEmpty()) {
                sb.append("  Missing Exception Handlers:\n");
                for (String issue : missingExceptionHandler) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!unusedFutures.isEmpty()) {
                sb.append("  Unused Futures:\n");
                for (String issue : unusedFutures) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            sb.append(String.format("  Summary: %d created, %d joined, %d chained%n",
                totalCreated, totalJoined, totalChained));

            if (!hasIssues()) {
                sb.append("  No issues detected.\n");
            }

            sb.append("""
  Why: An unjoined future whose exception is never handled silently swallows the error — the calling
       thread never sees the failure and proceeds as if the operation succeeded, producing wrong results
       or leaving the system in an inconsistent state. A future that is created but never awaited can
       also leak resources held by its computation.
  Fix:
    - Always add .exceptionally(ex -> { log(ex); return fallback; }) or .handle((r, ex) -> ...) to every chain
    - Call join() or get() on every future you submit, or compose with thenApply/thenCompose so the result propagates
    - Use CompletableFuture.allOf(futures).join() to await a collection of futures at once\
""");
            return sb.toString();
        }
    }
}
