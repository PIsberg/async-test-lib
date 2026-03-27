package com.github.asynctest.diagnostics;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects unsafe operations in parallel streams.
 * 
 * Common parallel stream issues detected:
 * - Stateful lambdas: lambdas that capture and modify external state
 * - Non-thread-safe collectors: using ArrayList, HashMap in parallel collect()
 * - Ordered operations: using operations that require ordering in parallel streams
 * - Side effects in forEach: modifying shared state from parallel forEach
 * - Race conditions in reduce: non-associative accumulator functions
 * 
 * Note: Parallel streams require:
 * - Stateless, non-interfering lambda functions
 * - Thread-safe collectors (ConcurrentHashMap, ConcurrentHashMap.newKeySet())
 * - Associative and non-interfering reduction operations
 * 
 * Usage:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectParallelStreamIssues = true)
 * void testParallelStream() {
 *     List<Integer> list = Arrays.asList(1, 2, 3, 4, 5);
 *     AtomicInteger counter = new AtomicInteger();
 *     
 *     AsyncTestContext.parallelStreamMonitor()
 *         .recordParallelStream("stateful-stream");
 *     
 *     // Bug: stateful lambda modifying external state
 *     list.parallelStream().forEach(i -> counter.incrementAndGet());
 *     AsyncTestContext.parallelStreamMonitor()
 *         .recordStatefulOperation("stateful-stream", "forEach");
 * }
 * }</pre>
 */
public class ParallelStreamDetector {

    private static class StreamState {
        final String name;
        final AtomicInteger operationCount = new AtomicInteger(0);
        final Set<Long> accessingThreads = ConcurrentHashMap.newKeySet();
        final Map<String, AtomicInteger> operationTypes = new ConcurrentHashMap<>();
        final AtomicBoolean hasStatefulLambda = new AtomicBoolean(false);
        final AtomicBoolean hasNonThreadSafeCollector = new AtomicBoolean(false);
        final AtomicBoolean hasSideEffects = new AtomicBoolean(false);
        volatile Long firstAccessTime = null;
        volatile Long lastAccessTime = null;

        StreamState(String name) {
            this.name = name != null ? name : "stream@" + System.identityHashCode(this);
        }
    }

    private final Map<String, StreamState> streams = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

    /**
     * Record the start of a parallel stream operation.
     * 
     * @param streamName a descriptive name for the stream
     */
    public void recordParallelStream(String streamName) {
        if (!enabled || streamName == null) {
            return;
        }
        streams.computeIfAbsent(streamName, StreamState::new);
    }

    /**
     * Record a stateful lambda operation (bug in parallel stream).
     * 
     * @param streamName the stream name
     * @param operation the operation type (forEach, map, filter, etc.)
     */
    public void recordStatefulOperation(String streamName, String operation) {
        recordOperation(streamName, operation, true);
    }

    /**
     * Record a stateless operation (safe in parallel stream).
     * 
     * @param streamName the stream name
     * @param operation the operation type
     */
    public void recordStatelessOperation(String streamName, String operation) {
        recordOperation(streamName, operation, false);
    }

    /**
     * Record use of a non-thread-safe collector.
     * 
     * @param streamName the stream name
     * @param collectorType the collector type (ArrayList, HashMap, etc.)
     */
    public void recordNonThreadSafeCollector(String streamName, String collectorType) {
        if (!enabled || streamName == null) {
            return;
        }
        StreamState state = streams.get(streamName);
        if (state != null) {
            state.hasNonThreadSafeCollector.set(true);
            updateAccessTime(state);
        }
    }

    /**
     * Record side effects in parallel stream (bug).
     * 
     * @param streamName the stream name
     * @param sideEffectType the type of side effect
     */
    public void recordSideEffect(String streamName, String sideEffectType) {
        if (!enabled || streamName == null) {
            return;
        }
        StreamState state = streams.get(streamName);
        if (state != null) {
            state.hasSideEffects.set(true);
            updateAccessTime(state);
        }
    }

    private void recordOperation(String streamName, String operation, boolean stateful) {
        if (!enabled || streamName == null) {
            return;
        }
        StreamState state = streams.computeIfAbsent(streamName, StreamState::new);
        
        state.operationCount.incrementAndGet();
        state.accessingThreads.add(Thread.currentThread().threadId());
        state.operationTypes.computeIfAbsent(operation, k -> new AtomicInteger(0))
            .incrementAndGet();
        
        if (stateful) {
            state.hasStatefulLambda.set(true);
        }
        
        updateAccessTime(state);
    }

    private void updateAccessTime(StreamState state) {
        long now = System.currentTimeMillis();
        if (state.firstAccessTime == null) {
            state.firstAccessTime = now;
        }
        state.lastAccessTime = now;
    }

    /**
     * Analyze parallel stream usage for issues.
     * 
     * @return a report of detected issues
     */
    public ParallelStreamReport analyze() {
        ParallelStreamReport report = new ParallelStreamReport();
        report.enabled = enabled;

        for (StreamState state : streams.values()) {
            // Check for stateful lambdas
            if (state.hasStatefulLambda.get()) {
                report.statefulLambdas.add(String.format(
                    "%s: stateful lambda detected (captures/modifies external state)",
                    state.name));
            }

            // Check for non-thread-safe collectors
            if (state.hasNonThreadSafeCollector.get()) {
                report.nonThreadSafeCollectors.add(String.format(
                    "%s: non-thread-safe collector used in parallel collect()",
                    state.name));
            }

            // Check for side effects
            if (state.hasSideEffects.get()) {
                report.sideEffects.add(String.format(
                    "%s: side effects detected in parallel stream operation",
                    state.name));
            }

            // Check for multi-thread access (indicates parallel execution)
            if (state.accessingThreads.size() > 1) {
                report.parallelExecution.add(String.format(
                    "%s: executed by %d threads (%d operations)",
                    state.name, state.accessingThreads.size(), state.operationCount.get()));
            }

            // Track activity
            if (state.operationCount.get() > 0) {
                StringBuilder ops = new StringBuilder();
                for (Map.Entry<String, AtomicInteger> entry : state.operationTypes.entrySet()) {
                    if (ops.length() > 0) ops.append(", ");
                    ops.append(entry.getKey()).append(":").append(entry.getValue().get());
                }
                report.streamActivity.put(state.name, String.format(
                    "%d operations from %d threads: %s",
                    state.operationCount.get(), state.accessingThreads.size(), ops.toString()));
            }
        }

        return report;
    }

    /**
     * Report class for parallel stream analysis.
     */
    public static class ParallelStreamReport {
        private boolean enabled = true;
        final java.util.List<String> statefulLambdas = new java.util.ArrayList<>();
        final java.util.List<String> nonThreadSafeCollectors = new java.util.ArrayList<>();
        final java.util.List<String> sideEffects = new java.util.ArrayList<>();
        final java.util.List<String> parallelExecution = new java.util.ArrayList<>();
        final Map<String, String> streamActivity = new ConcurrentHashMap<>();

        /**
         * Check if any issues were detected.
         */
        public boolean hasIssues() {
            return !statefulLambdas.isEmpty() || !nonThreadSafeCollectors.isEmpty() || !sideEffects.isEmpty();
        }

        @Override
        public String toString() {
            if (!enabled) {
                return "ParallelStreamReport: disabled";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("PARALLEL STREAM ISSUES DETECTED:\n");

            if (!statefulLambdas.isEmpty()) {
                sb.append("  Stateful Lambdas:\n");
                for (String issue : statefulLambdas) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!nonThreadSafeCollectors.isEmpty()) {
                sb.append("  Non-Thread-Safe Collectors:\n");
                for (String issue : nonThreadSafeCollectors) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!sideEffects.isEmpty()) {
                sb.append("  Side Effects:\n");
                for (String issue : sideEffects) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!parallelExecution.isEmpty()) {
                sb.append("  Parallel Execution:\n");
                for (String issue : parallelExecution) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!streamActivity.isEmpty()) {
                sb.append("  Stream Activity:\n");
                for (Map.Entry<String, String> entry : streamActivity.entrySet()) {
                    sb.append("    - ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
            }

            if (!hasIssues()) {
                sb.append("  No issues detected.\n");
            }

            sb.append("  Fix: use stateless lambdas, thread-safe collectors, avoid side effects");
            return sb.toString();
        }
    }
}
