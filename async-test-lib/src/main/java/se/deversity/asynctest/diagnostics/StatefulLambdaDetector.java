package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;
import org.jspecify.annotations.Nullable;

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
 * <p>Two kinds of mutation are not the race and are not reported. Mutation of state that is
 * thread-safe by type, when the caller names the captured object through
 * {@link #recordCapturedMutation(Object, String, Object, Thread)}, and mutation that one lock
 * covered every time. The lock is judged per captured object, so two captures each guarded by
 * its own lock are both covered. The lock the detector can see is the captured object's own
 * monitor (or the lambda's, when no object is named), a lock declared with
 * {@code AsyncTestContext.holdingLock(...)}, or one the agent wove; a lock it never saw leaves
 * the finding standing.
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

    /**
     * Per-lambda bookkeeping. The lockset is kept per captured object rather than per lambda, so
     * two captures each guarded by a different lock are two consistently locked captures, not one
     * capture with no common lock (#769). A mutation that names no object is tracked against the
     * lambda itself, so all of a lambda's unnamed captures still share one lockset.
     */
    private static class LambdaState {
        final String      name;
        final Set<Long>   executingThreadIds   = ConcurrentHashMap.newKeySet();
        final Set<String> executingThreadNames = ConcurrentHashMap.newKeySet();
        final List<String> mutationEvents      = new CopyOnWriteArrayList<>();
        final Map<IdentityKey, CaptureGuard> captures = new ConcurrentHashMap<>();

        LambdaState(String name) { this.name = name; }

        boolean sawUnguardedSharing() {
            for (CaptureGuard c : captures.values()) {
                if (c.sawUnguardedSharing()) return true;
            }
            return false;
        }
    }

    /** The lockset and round verdict for one captured object of one lambda. */
    private static final class CaptureGuard extends SelfGuard.TrackedInstance { }

    private final Map<IdentityKey, LambdaState> lambdas = new ConcurrentHashMap<>();

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
        // The fallback label is built only when the instance is first seen.
        LambdaState s = lambdas.computeIfAbsent(
                new IdentityKey(lambda), id -> new LambdaState(name != null ? name
                        : lambda.getClass().getSimpleName() + "@" + System.identityHashCode(lambda)));
        s.executingThreadIds.add(thread.threadId());
        s.executingThreadNames.add(thread.getName());
    }

    /**
     * Record that the lambda is mutating a captured variable.
     * Call this whenever the lambda writes to a captured mutable container.
     *
     * <p>Without the captured object the detector cannot tell thread-safe state from a plain
     * container; prefer {@link #recordCapturedMutation(Object, String, Object, Thread)}.
     *
     * @param lambda        the lambda, Runnable, or Callable instance
     * @param capturedName  name of the captured variable being mutated
     * @param thread        the mutating thread
     */
    public void recordCapturedMutation(Object lambda, String capturedName, Thread thread) {
        recordCapturedMutation(lambda, capturedName, null, thread);
    }

    /**
     * Record that the lambda is mutating a captured variable, naming the captured object.
     *
     * <p>The object is what lets the detector tell correct sharing from the race. State that is
     * thread-safe by type - anything in {@code java.util.concurrent} or its {@code atomic}
     * package, such as {@code LongAdder}, {@code AtomicLong} or {@code ConcurrentHashMap}, and the
     * {@code Collections.synchronizedXxx} wrappers - is not reported: its mutation is taken to be
     * one of the type's own atomic operations. A get-then-set on an {@code Atomic*} is still a
     * lost update, and is {@code AtomicNonAtomicUpdateDetector}'s finding, not this one's. Other
     * state is reported only when no one lock covered every such mutation; the captured object's
     * own monitor counts without a declaration, so {@code synchronized (counter)} is recognised.
     *
     * @param lambda        the lambda, Runnable, or Callable instance
     * @param capturedName  name of the captured variable being mutated
     * @param capturedState the captured object being mutated, or {@code null} when not known
     * @param thread        the mutating thread
     * @since 1.12.3
     */
    @API(status = Status.EXPERIMENTAL)
    public void recordCapturedMutation(Object lambda, String capturedName,
                                       @Nullable Object capturedState, Thread thread) {
        if (lambda == null || thread == null) return;
        if (capturedState != null && isThreadSafeByType(capturedState)) return;
        String label = capturedName != null ? capturedName : "capturedState";
        LambdaState s = lambdas.computeIfAbsent(
                new IdentityKey(lambda),
                id -> new LambdaState(lambda.getClass().getSimpleName()
                        + "@" + System.identityHashCode(lambda)));
        Object tracked = capturedState != null ? capturedState : lambda;
        CaptureGuard guard = s.captures.computeIfAbsent(
                new IdentityKey(tracked), k -> new CaptureGuard());
        // Probed on the mutating thread while it is still inside whatever region guards it.
        guard.noteAccess(tracked, true, thread.threadId());
        s.mutationEvents.add(thread.getName() + " → " + label);
    }

    private static boolean isThreadSafeByType(Object state) {
        String type = state.getClass().getName();
        return type.startsWith("java.util.concurrent.")
                || type.startsWith("java.util.Collections$Synchronized");
    }

    /**
     * {@return report of lambdas with concurrent captured-state mutations}
     */
    public StatefulLambdaReport analyze() {
        StatefulLambdaReport r = new StatefulLambdaReport();
        for (LambdaState s : lambdas.values()) {
            if (s.executingThreadIds.size() > 1 && !s.mutationEvents.isEmpty()
                    && s.sawUnguardedSharing()) {
                r.violations.add(String.format(
                        "'%s' executed on %d threads (%s) with concurrent captured-state mutations: [%s]"
                                + SelfGuard.REPORT_NOTE,
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
            sb.append("""
  Why: A lambda that captures a mutable variable or field shares that state with every thread that
       executes the lambda concurrently. Without synchronization, two threads can read the same value,
       both modify it, and one update is silently lost — a classic lost-update race condition inside
       what looks like a simple closure.
  Fix:
    - Use AtomicInteger/AtomicLong/LongAdder for captured numeric counters (lock-free, correct)
    - Capture only effectively-final, immutable values and pass mutable state via method parameters
    - Create a new lambda (or a new capturing context) per task so each thread gets its own state\
""");
            return sb.toString();
        }
    }
}
