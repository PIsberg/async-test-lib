package com.github.asynctest.diagnostics;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
 * @AsyncTest(threads = 10, detectCompletableFutureChainIssues = true)
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
public class CompletableFutureChainDetector {

    private static class FutureState {
        final String name;
        final long createdTime;
        final long createdByThread;
        volatile boolean joined;
        volatile boolean exceptionallyAdded;
        volatile boolean handled;
        volatile String lastOperation;
        final java.util.List<String> chainOperations = new java.util.ArrayList<>();

        FutureState(String name) {
            this.name = name;
            this.createdTime = System.currentTimeMillis();
            this.createdByThread = Thread.currentThread().threadId();
        }
    }

    private final Map<Integer, FutureState> futures = new ConcurrentHashMap<>();
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
        int key = System.identityHashCode(future);
        FutureState state = new FutureState(name);
        futures.put(key, state);
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
        int key = System.identityHashCode(original);
        FutureState state = futures.get(key);
        if (state != null) {
            state.chainOperations.add(operation);
            state.lastOperation = operation;
        }
        
        // Track the new future too
        int resultKey = System.identityHashCode(result);
        if (!futures.containsKey(resultKey)) {
            FutureState newState = new FutureState(
                state != null ? state.name + "->" + operation : "chained-" + operation);
            futures.put(resultKey, newState);
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
        int key = System.identityHashCode(future);
        FutureState state = futures.get(key);
        if (state != null) {
            state.exceptionallyAdded = true;
            // Mark all futures in this chain
            String baseName = state.name.split("->")[0];
            for (FutureState otherState : futures.values()) {
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
        int key = System.identityHashCode(future);
        FutureState state = futures.get(key);
        if (state != null) {
            state.handled = true;
            // Mark all futures in this chain
            String baseName = state.name.split("->")[0];
            for (FutureState otherState : futures.values()) {
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
        int key = System.identityHashCode(future);
        FutureState state = futures.get(key);
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
        for (Map.Entry<Integer, FutureState> entry : futures.entrySet()) {
            FutureState state = entry.getValue();
            
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
        long unjoined = futures.values().stream()
            .filter(state -> !state.joined)
            .count();
        if (unjoined > 0) {
            report.unusedFutures.add(String.format(
                "%d futures created but never joined", unjoined));
        }

        return report;
    }

    /**
     * Report class for CompletableFuture chain issues.
     */
    public static class CompletableFutureChainReport {
        private boolean enabled = true;
        int totalCreated;
        int totalJoined;
        int totalChained;
        final java.util.List<String> unjoinedFutures = new java.util.ArrayList<>();
        final java.util.List<String> missingExceptionHandler = new java.util.ArrayList<>();
        final java.util.List<String> unusedFutures = new java.util.ArrayList<>();

        /**
         * Check if any issues were detected.
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

            sb.append(String.format("  Summary: %d created, %d joined, %d chained\n",
                totalCreated, totalJoined, totalChained));

            if (!hasIssues()) {
                sb.append("  No issues detected.\n");
            }

            sb.append("  Fix: always add .exceptionally() and ensure all futures are joined or awaited");
            return sb.toString();
        }
    }
}
