package com.github.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Detects misuse of Java 21+ {@code ScopedValue}.
 *
 * <p>{@code ScopedValue} is the preferred alternative to {@code ThreadLocal} for
 * virtual thread workloads. It provides immutable, inheritable, scope-bounded context
 * that works correctly with structured concurrency. Its API is safe by design —
 * a value is only accessible inside a {@code ScopedValue.where(sv, val).run(task)} block.
 *
 * <p><strong>Issues detected:</strong>
 * <ul>
 *   <li><b>Mutation attempt</b> — Attempting to re-bind an already-bound {@code ScopedValue}
 *       via nested {@code where().run()} with conflicting values (not always an error, but often
 *       unintentional in frameworks)</li>
 *   <li><b>Cross-scope leak</b> — A {@code ScopedValue} accessed in a scope where it was not
 *       bound; this throws {@code NoSuchElementException} at runtime if not guarded with
 *       {@code isBound()}</li>
 *   <li><b>Excessive bindings per task</b> — More than a configurable threshold of distinct
 *       ScopedValues bound in a single call chain (design smell)</li>
 *   <li><b>Unbound get() call</b> — {@code get()} called without a prior binding on this
 *       thread / scope, which will throw {@code NoSuchElementException}</li>
 * </ul>
 *
 * <p><strong>Usage:</strong>
 * <pre>{@code
 * private static final ScopedValue<String> USER_ID = ScopedValue.newInstance();
 *
 * @AsyncTest(threads = 10, useVirtualThreads = true, detectScopedValueMisuse = true)
 * void testScopedValueUsage() {
 *     var detector = AsyncTestContext.scopedValueMisuseDetector();
 *     String svName = "USER_ID";
 *
 *     detector.recordBindingEntered(svName, Thread.currentThread());
 *     ScopedValue.where(USER_ID, "user-" + Thread.currentThread().threadId()).run(() -> {
 *         detector.recordGetCalled(svName, Thread.currentThread());
 *         String userId = USER_ID.get(); // safe — we're inside the binding
 *         processRequest(userId);
 *         detector.recordBindingExited(svName, Thread.currentThread());
 *     });
 * }
 * }</pre>
 *
 * @since 0.7.0
 */
public class ScopedValueMisuseDetector {

    private static final int HIGH_BINDING_COUNT_THRESHOLD = 8;

    private static class BindingRecord {
        final String variableName;
        final long threadId;
        final long enteredAtNanos;
        volatile boolean exited = false;

        BindingRecord(String variableName, long threadId) {
            this.variableName = variableName;
            this.threadId = threadId;
            this.enteredAtNanos = System.nanoTime();
        }
    }

    private static final AtomicLong SEQ = new AtomicLong(0);

    // Per-thread active binding set: threadId → Set<variableName>
    private final Map<Long, Set<String>> activeBindings = new ConcurrentHashMap<>();

    private final List<String> unboundGetReports  = Collections.synchronizedList(new ArrayList<>());
    private final List<String> rebindReports       = Collections.synchronizedList(new ArrayList<>());
    private final List<String> highBindingWarnings = Collections.synchronizedList(new ArrayList<>());

    private final AtomicInteger totalBindings  = new AtomicInteger(0);
    private final AtomicInteger totalGetCalls  = new AtomicInteger(0);
    private final AtomicInteger unboundGetCount = new AtomicInteger(0);

    /**
     * Record that a {@code ScopedValue.where(sv, val).run()} binding has been entered
     * on the current thread.
     *
     * @param variableName a descriptive name for the ScopedValue (e.g. field name)
     * @param thread       the current thread
     */
    public void recordBindingEntered(String variableName, Thread thread) {
        if (thread == null || variableName == null) return;
        long tid = thread.threadId();
        Set<String> bound = activeBindings.computeIfAbsent(tid, k -> ConcurrentHashMap.newKeySet());

        // Detect re-binding (nested where().run() with the same variable)
        if (bound.contains(variableName)) {
            rebindReports.add(
                "Thread " + thread.getName() + " (id=" + tid + "): "
                + "ScopedValue '" + variableName + "' is being re-bound in a nested scope. "
                + "The inner binding shadows the outer one — ensure this is intentional."
            );
        }

        bound.add(variableName);
        int count = bound.size();
        totalBindings.incrementAndGet();

        if (count > HIGH_BINDING_COUNT_THRESHOLD) {
            highBindingWarnings.add(
                "Thread " + thread.getName() + " (id=" + tid + "): "
                + count + " distinct ScopedValues bound simultaneously (threshold="
                + HIGH_BINDING_COUNT_THRESHOLD + "). "
                + "Consider consolidating into a context record."
            );
        }
    }

    /**
     * Record that a binding scope has been exited (the {@code run()} lambda returned).
     *
     * @param variableName a descriptive name for the ScopedValue
     * @param thread       the current thread
     */
    public void recordBindingExited(String variableName, Thread thread) {
        if (thread == null || variableName == null) return;
        long tid = thread.threadId();
        Set<String> bound = activeBindings.get(tid);
        if (bound != null) {
            bound.remove(variableName);
        }
    }

    /**
     * Record that {@code ScopedValue.get()} (or {@code isBound()}) was called.
     * If the variable is not currently bound on this thread, an unbound-get issue is recorded.
     *
     * @param variableName a descriptive name for the ScopedValue
     * @param thread       the current thread
     */
    public void recordGetCalled(String variableName, Thread thread) {
        if (thread == null || variableName == null) return;
        totalGetCalls.incrementAndGet();
        long tid = thread.threadId();
        Set<String> bound = activeBindings.get(tid);
        boolean isBound = bound != null && bound.contains(variableName);

        if (!isBound) {
            unboundGetCount.incrementAndGet();
            unboundGetReports.add(
                "Thread " + thread.getName() + " (id=" + tid + "): "
                + "ScopedValue '" + variableName + "' accessed via get() but has no active binding "
                + "on this thread — this will throw NoSuchElementException at runtime."
            );
        }
    }

    /**
     * Analyze all recorded ScopedValue events for misuse patterns.
     *
     * @return a report describing detected issues
     */
    public ScopedValueMisuseReport analyze() {
        return new ScopedValueMisuseReport(
            new ArrayList<>(unboundGetReports),
            new ArrayList<>(rebindReports),
            new ArrayList<>(highBindingWarnings),
            totalBindings.get(),
            totalGetCalls.get(),
            unboundGetCount.get()
        );
    }

    /**
     * Report of ScopedValue misuse analysis.
     */
    public static class ScopedValueMisuseReport {
        private final List<String> unboundGetIssues;
        private final List<String> rebindIssues;
        private final List<String> highBindingWarnings;
        private final int totalBindings;
        private final int totalGetCalls;
        private final int unboundGetCount;

        ScopedValueMisuseReport(
                List<String> unboundGetIssues,
                List<String> rebindIssues,
                List<String> highBindingWarnings,
                int totalBindings,
                int totalGetCalls,
                int unboundGetCount) {
            this.unboundGetIssues = unboundGetIssues;
            this.rebindIssues = rebindIssues;
            this.highBindingWarnings = highBindingWarnings;
            this.totalBindings = totalBindings;
            this.totalGetCalls = totalGetCalls;
            this.unboundGetCount = unboundGetCount;
        }

        /** @return true if any ScopedValue misuse issues were detected */
        public boolean hasIssues() {
            return !unboundGetIssues.isEmpty() || !rebindIssues.isEmpty();
        }

        public List<String> getUnboundGetIssues()     { return unboundGetIssues; }
        public List<String> getRebindIssues()          { return rebindIssues; }
        public List<String> getHighBindingWarnings()   { return highBindingWarnings; }
        public int          getTotalBindings()          { return totalBindings; }
        public int          getTotalGetCalls()          { return totalGetCalls; }
        public int          getUnboundGetCount()        { return unboundGetCount; }

        @Override
        public String toString() {
            if (!hasIssues() && highBindingWarnings.isEmpty()) {
                return "ScopedValueMisuseReport: No ScopedValue misuse detected";
            }

            StringBuilder sb = new StringBuilder();

            if (!unboundGetIssues.isEmpty()) {
                sb.append(IssueSeverity.CRITICAL.format())
                  .append(": ScopedValue get() called without active binding (will throw at runtime)\n");
            } else if (!rebindIssues.isEmpty()) {
                sb.append(IssueSeverity.MEDIUM.format())
                  .append(": ScopedValue re-binding detected\n");
            } else {
                sb.append(IssueSeverity.LOW.format())
                  .append(": ScopedValue usage warnings\n");
            }

            sb.append("  Bindings=").append(totalBindings)
              .append(", Gets=").append(totalGetCalls)
              .append(", UnboundGets=").append(unboundGetCount).append("\n");

            appendSection(sb, "Unbound get() calls (NoSuchElementException risk)", unboundGetIssues);
            appendSection(sb, "Re-binding the same ScopedValue in nested scope", rebindIssues);
            appendSection(sb, "High concurrent binding count (design smell)", highBindingWarnings);

            sb.append("\n\n").append("=".repeat(60));
            sb.append("\n").append(getLearningContent());
            sb.append("=".repeat(60));

            return sb.toString();
        }

        private static void appendSection(StringBuilder sb, String title, List<String> items) {
            if (items.isEmpty()) return;
            sb.append("\n  ").append(title).append(":\n");
            for (String item : items) {
                sb.append("    - ").append(item).append("\n");
            }
        }

        private static String getLearningContent() {
            return """
                📚 LEARNING: ScopedValue (Java 21+)

                ScopedValue is the recommended alternative to ThreadLocal for virtual threads.
                It is immutable, inheritable, and automatically scoped to the duration of a call.

                Correct usage:
                  static final ScopedValue<String> USER = ScopedValue.newInstance();

                  // Bind and run
                  ScopedValue.where(USER, "alice").run(() -> {
                      String u = USER.get(); // ← safe, "alice"
                      ScopedValue.where(USER, "bob").run(() -> {
                          String u2 = USER.get(); // ← "bob" in inner scope, "alice" in outer
                      });
                  });
                  // USER.get() here throws NoSuchElementException — binding is gone

                Common mistakes:
                  ✗ Calling get() outside a where().run() block → NoSuchElementException
                  ✗ Assuming a binding set in one task is visible in an unrelated task
                  ✗ Using ScopedValue like a mutable global (it's immutable per scope)

                When to use ScopedValue vs ThreadLocal:
                  • ScopedValue — structured concurrency, virtual threads, read-only context
                  • ThreadLocal — legacy code, mutable per-thread state, non-virtual threads
                """;
        }
    }
}
