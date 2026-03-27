package com.github.asynctest.diagnostics;

import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects concurrent use of non-thread-safe Random instances.
 * 
 * Common Random misuse issues detected:
 * - Shared Random instance accessed by multiple threads without synchronization
 * - Thread contention on Random causing performance degradation
 * - Potential data corruption from concurrent nextInt()/nextLong() calls
 * 
 * Note: java.util.Random is thread-safe but uses atomic operations that can cause
 * contention. For high-concurrency scenarios, ThreadLocalRandom should be used instead.
 * 
 * Usage:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectSharedRandom = true)
 * void testRandomUsage() {
 *     Random random = new Random();
 *     AsyncTestContext.sharedRandomMonitor()
 *         .registerRandom(random, "shared-random");
 *     
 *     // This will be detected as shared access
 *     int value = random.nextInt();
 *     AsyncTestContext.sharedRandomMonitor()
 *         .recordRandomAccess(random, "shared-random", "nextInt");
 * }
 * }</pre>
 */
public class SharedRandomDetector {

    private static class RandomState {
        final String name;
        final Random random;
        final AtomicInteger accessCount = new AtomicInteger(0);
        final Set<Long> accessingThreads = ConcurrentHashMap.newKeySet();
        final Map<String, AtomicInteger> methodCounts = new ConcurrentHashMap<>();
        volatile Long firstAccessTime = null;
        volatile Long lastAccessTime = null;

        RandomState(Random random, String name) {
            this.random = random;
            this.name = name != null ? name : "random@" + System.identityHashCode(random);
        }
    }

    private final Map<Integer, RandomState> randoms = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

    /**
     * Register a Random instance for monitoring.
     * 
     * @param random the Random to monitor
     * @param name a descriptive name for reporting
     */
    public void registerRandom(Random random, String name) {
        if (!enabled || random == null) {
            return;
        }
        randoms.put(System.identityHashCode(random), new RandomState(random, name));
    }

    /**
     * Record a Random method access.
     * 
     * @param random the Random instance
     * @param name the random name (should match registration)
     * @param methodName the method called (nextInt, nextLong, nextDouble, etc.)
     */
    public void recordRandomAccess(Random random, String name, String methodName) {
        if (!enabled || random == null) {
            return;
        }
        RandomState state = randoms.get(System.identityHashCode(random));
        if (state == null) {
            // Auto-register
            state = new RandomState(random, name != null ? name : "random@" + System.identityHashCode(random));
            randoms.put(System.identityHashCode(random), state);
        }
        
        long now = System.currentTimeMillis();
        state.accessCount.incrementAndGet();
        state.accessingThreads.add(Thread.currentThread().threadId());
        
        if (state.firstAccessTime == null) {
            state.firstAccessTime = now;
        }
        state.lastAccessTime = now;
        
        state.methodCounts.computeIfAbsent(methodName, k -> new AtomicInteger(0))
            .incrementAndGet();
    }

    /**
     * Analyze Random usage for shared access issues.
     * 
     * @return a report of detected issues
     */
    public SharedRandomReport analyze() {
        SharedRandomReport report = new SharedRandomReport();
        report.enabled = enabled;

        for (RandomState state : randoms.values()) {
            // Check for shared access (multiple threads using same Random)
            if (state.accessingThreads.size() > 1) {
                report.sharedRandoms.add(String.format(
                    "%s: accessed by %d threads (%d total accesses)",
                    state.name, state.accessingThreads.size(), state.accessCount.get()));
                
                // Build method breakdown
                StringBuilder methods = new StringBuilder();
                for (Map.Entry<String, AtomicInteger> entry : state.methodCounts.entrySet()) {
                    if (methods.length() > 0) methods.append(", ");
                    methods.append(entry.getKey()).append(":").append(entry.getValue().get());
                }
                report.methodBreakdown.put(state.name, methods.toString());
            }

            // Check for high contention (many accesses in short time)
            if (state.firstAccessTime != null && state.lastAccessTime != null) {
                long duration = state.lastAccessTime - state.firstAccessTime;
                if (duration > 0 && state.accessCount.get() > 100) {
                    double accessesPerSecond = state.accessCount.get() * 1000.0 / duration;
                    if (accessesPerSecond > 10000) { // More than 10k accesses/second
                        report.highContention.add(String.format(
                            "%s: high contention detected (%.0f accesses/sec)",
                            state.name, accessesPerSecond));
                    }
                }
            }

            // Track activity
            if (state.accessCount.get() > 0) {
                report.randomActivity.put(state.name, String.format(
                    "%d accesses from %d threads",
                    state.accessCount.get(), state.accessingThreads.size()));
            }
        }

        return report;
    }

    /**
     * Report class for shared Random analysis.
     */
    public static class SharedRandomReport {
        private boolean enabled = true;
        final java.util.List<String> sharedRandoms = new java.util.ArrayList<>();
        final java.util.List<String> highContention = new java.util.ArrayList<>();
        final Map<String, String> methodBreakdown = new ConcurrentHashMap<>();
        final Map<String, String> randomActivity = new ConcurrentHashMap<>();

        /**
         * Check if any issues were detected.
         */
        public boolean hasIssues() {
            return !sharedRandoms.isEmpty() || !highContention.isEmpty();
        }

        @Override
        public String toString() {
            if (!enabled) {
                return "SharedRandomReport: disabled";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("SHARED RANDOM ISSUES DETECTED:\n");

            if (!sharedRandoms.isEmpty()) {
                sb.append("  Shared Random Instances:\n");
                for (String issue : sharedRandoms) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!highContention.isEmpty()) {
                sb.append("  High Contention:\n");
                for (String issue : highContention) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!methodBreakdown.isEmpty()) {
                sb.append("  Method Breakdown:\n");
                for (Map.Entry<String, String> entry : methodBreakdown.entrySet()) {
                    sb.append("    - ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
            }

            if (!randomActivity.isEmpty()) {
                sb.append("  Random Activity:\n");
                for (Map.Entry<String, String> entry : randomActivity.entrySet()) {
                    sb.append("    - ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
            }

            if (!hasIssues()) {
                sb.append("  No issues detected.\n");
            }

            sb.append("  Fix: use ThreadLocalRandom.current() for concurrent random number generation");
            return sb.toString();
        }
    }
}
