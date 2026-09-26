package se.deversity.asynctest.diagnostics;

import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * Notes {@link java.util.Random} instances used from more than one thread, as a performance
 * advisory.
 *
 * <p>{@code java.util.Random} is thread-safe: its javadoc says instances are safe for use by
 * multiple threads, and that concurrent use of one instance may see contention and poor
 * performance. Every call advances one {@code AtomicLong} seed with a compare-and-set, so a shared
 * instance is correct code whose callers retry on each other. A finding therefore says nothing
 * about correctness, is marked {@link IssueSeverity#LOW} in the report text, and belongs to
 * {@link TrustTier#ADVISORY}: {@code ThreadLocalRandom.current()} gives each thread its own seed and
 * is faster under contention. The note is about contention, so an external lock does not silence
 * it; a lock serializes the callers a second time.
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
        final AtomicInteger accessCount = new AtomicInteger(0);
        final Set<Long> accessingThreads = ConcurrentHashMap.newKeySet();
        final Map<String, AtomicInteger> methodCounts = new ConcurrentHashMap<>();
        volatile @Nullable Long firstAccessTime = null;
        volatile @Nullable Long lastAccessTime = null;

        RandomState(Random random, String name) {
            this.name = name != null ? name : "random@" + System.identityHashCode(random);
        }
    }

    private final Map<IdentityKey, RandomState> randoms = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

    /**
     * Register a Random instance for monitoring.
     * 
     * @param random the Random to monitor
     * @param name a descriptive name for reporting
     */
    public void registerRandom(Random random, String name) {
        // First registration wins: re-registering a subject must not discard what has
        // been observed about it. An @AsyncTest body runs once per thread, so a consumer
        // registering inside it registers once per worker.
        if (!enabled || random == null) {
            return;
        }
        randoms.putIfAbsent(new IdentityKey(random), new RandomState(random, name));
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
        IdentityKey key = new IdentityKey(random);
        RandomState state = randoms.get(key);
        if (state == null) {
            // Auto-register. computeIfAbsent, not get-then-put: two threads racing here both
            // saw null, both built a state and the second put discarded the first, so each
            // thread counted itself alone and analyze()'s "> 1 thread" test never tripped. A
            // detector whose whole job is spotting concurrent sharing went silent under
            // exactly the contention it exists to find.
            final String label = name != null ? name : "random@" + key.hashCode();
            state = randoms.computeIfAbsent(key, k -> new RandomState(random, label));
        }
        
        long now = System.currentTimeMillis();
        state.accessCount.incrementAndGet();
        state.accessingThreads.add(Thread.currentThread().threadId());
        
        if (state.firstAccessTime == null) {
            state.firstAccessTime = now;
        }
        state.lastAccessTime = now;
        
        if (methodName != null) {
            state.methodCounts.computeIfAbsent(methodName, k -> new AtomicInteger(0))
                .incrementAndGet();
        }
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
                report.sharedRandoms.add(String.format(Locale.ROOT,
                    "%s: one Random used by %d threads (%d total accesses); correct, since"
                        + " Random is thread-safe, but the callers contend on its one seed",
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
                        report.highContention.add(String.format(Locale.ROOT,
                            "%s: high contention detected (%.0f accesses/sec)",
                            state.name, accessesPerSecond));
                    }
                }
            }

            // Track activity
            if (state.accessCount.get() > 0) {
                report.randomActivity.put(state.name, String.format(Locale.ROOT,
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
         *
         * @return {@code true} when this detector recorded something worth reporting
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
            sb.append("SHARED RANDOM CONTENTION ADVISORY (").append(IssueSeverity.LOW.getLabel())
                    .append("):\n");

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

            sb.append("""
  Why: java.util.Random is thread-safe, so this is a performance note and not a bug. Every call
       advances one AtomicLong seed with a compare-and-set, and concurrent callers retry on each
       other, so throughput falls as threads are added.
  If the contention matters:
    - Use ThreadLocalRandom.current().nextInt(...): each thread has its own seed, no contention
    - For a reproducible sequence per task: SplittableRandom, split() once per task\
""");
            return sb.toString();
        }
    }
}
