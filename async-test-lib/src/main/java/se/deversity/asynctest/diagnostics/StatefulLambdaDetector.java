package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Detects lambda / {@link Runnable} / {@link java.util.concurrent.Callable} instances
 * whose captured mutable state is mutated concurrently from multiple threads.
 *
 * <p>Java lambdas that capture mutable containers (e.g. {@code int[]}, {@code AtomicInteger}
 * used via {@code get+set} instead of {@code compareAndSet}, or an outer field) and are then
 * shared across multiple threads introduce a shared-mutable-state race. The JVM enforces
 * <em>effectively-final</em> for captured variables, but captured <em>containers</em>
 * (arrays, wrapper objects) are mutable — a common source of data races.
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * int[] counter = {0};
 * Runnable task = () -> {
 *     var d = AsyncTestContext.statefulLambdaDetector();
 *     d.recordExecution(task, "task", Thread.currentThread());       // this lambda is running
 *     d.recordCapturedMutation(task, "counter", Thread.currentThread()); // mutating capture
 *     counter[0]++;
 * };
 * }</pre>
 *
 * @since 0.9.0
 */
public class StatefulLambdaDetector {

    private static class LambdaState {
        final String      name;
        final Set<Long>   executingThreadIds   = ConcurrentHashMap.newKeySet();
        final Set<String> executingThreadNames = ConcurrentHashMap.newKeySet();
        final List<String> mutationEvents      = new CopyOnWriteArrayList<>();

        LambdaState(String name) { this.name = name; }
    }

    private final Map<Integer, LambdaState> lambdas = new ConcurrentHashMap<>();

    /**
     * Record that a lambda instance is executing on the calling thread.
     * Call this at the beginning of the lambda body to track multi-thread sharing.
     *
     * @param lambda the lambda, Runnable, or Callable instance
     * @param name   descriptive label for reports
     * @param thread the executing thread
     */
    public void recordExecution(Object lambda, String name, Thread thread) {
        if (lambda == null || thread == null) return;
        String label = name != null ? name
                : lambda.getClass().getSimpleName() + "@" + System.identityHashCode(lambda);
        LambdaState s = lambdas.computeIfAbsent(
                System.identityHashCode(lambda), id -> new LambdaState(label));
        s.executingThreadIds.add(thread.threadId());
        s.executingThreadNames.add(thread.getName());
    }

    /**
     * Record that the lambda is mutating a captured variable.
     * Call this whenever the lambda writes to a captured mutable container.
     *
     * @param lambda        the lambda, Runnable, or Callable instance
     * @param capturedName  name of the captured variable being mutated
     * @param thread        the mutating thread
     */
    public void recordCapturedMutation(Object lambda, String capturedName, Thread thread) {
        if (lambda == null || thread == null) return;
        String label = capturedName != null ? capturedName : "capturedState";
        LambdaState s = lambdas.computeIfAbsent(
                System.identityHashCode(lambda),
                id -> new LambdaState(lambda.getClass().getSimpleName()
                        + "@" + System.identityHashCode(lambda)));
        s.mutationEvents.add(thread.getName() + " → " + label);
    }

    /**
     * {@return report of lambdas with concurrent captured-state mutations}
     */
    public StatefulLambdaReport analyze() {
        StatefulLambdaReport r = new StatefulLambdaReport();
        for (LambdaState s : lambdas.values()) {
            if (s.executingThreadIds.size() > 1 && !s.mutationEvents.isEmpty()) {
                r.violations.add(String.format(
                        "'%s' executed on %d threads (%s) with concurrent captured-state mutations: [%s]",
                        s.name, s.executingThreadIds.size(),
                        String.join(", ", s.executingThreadNames),
                        String.join("; ", s.mutationEvents)));
            }
        }
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static class StatefulLambdaReport {
        final List<String> violations = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("STATEFUL LAMBDA SHARED ACROSS THREADS DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append("\n");
            sb.append("  Why: A lambda that captures a mutable variable or field shares that state with every thread that\n"
                    + "       executes the lambda concurrently. Without synchronization, two threads can read the same value,\n"
                    + "       both modify it, and one update is silently lost — a classic lost-update race condition inside\n"
                    + "       what looks like a simple closure.\n"
                    + "  Fix:\n"
                    + "    - Use AtomicInteger/AtomicLong/LongAdder for captured numeric counters (lock-free, correct)\n"
                    + "    - Capture only effectively-final, immutable values and pass mutable state via method parameters\n"
                    + "    - Create a new lambda (or a new capturing context) per task so each thread gets its own state");
            return sb.toString();
        }
    }
}
