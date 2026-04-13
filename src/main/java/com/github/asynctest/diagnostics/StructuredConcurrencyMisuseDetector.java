package com.github.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Detects misuse of Java 21+ Structured Concurrency ({@code StructuredTaskScope}).
 *
 * <p>Structured concurrency enforces a discipline: subtasks must not outlive their scope.
 * The canonical pattern is:
 * <pre>{@code
 * try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
 *     var subtask = scope.fork(() -> computeSomething());
 *     scope.join();           // Wait for all subtasks
 *     scope.throwIfFailed();  // Propagate errors
 *     return subtask.get();   // Safe to call after join()
 * }
 * }</pre>
 *
 * <p><strong>Issues detected:</strong>
 * <ul>
 *   <li><b>Scope not closed</b> — {@code StructuredTaskScope} opened but {@code close()} never
 *       called (resource leak, subtasks may outlive caller)</li>
 *   <li><b>Join skipped</b> — Subtask result accessed via {@code get()} before {@code join()},
 *       which returns stale or incorrect data and defeats structured concurrency guarantees</li>
 *   <li><b>Excessive scope nesting</b> — Deeply nested scopes creating complex lifetime
 *       dependencies that are hard to reason about</li>
 *   <li><b>Zero-subtask scope</b> — Scope opened but no subtasks forked (dead code / overhead)</li>
 * </ul>
 *
 * <p><strong>Usage:</strong>
 * <pre>{@code
 * @AsyncTest(threads = 4, useVirtualThreads = true, detectStructuredConcurrencyIssues = true)
 * void testStructuredConcurrency() {
 *     var detector = AsyncTestContext.structuredConcurrencyMisuseDetector();
 *     String scopeId = detector.recordScopeOpened("ShutdownOnFailure");
 *     try {
 *         detector.recordSubtaskForked(scopeId);
 *         // ... do work ...
 *         detector.recordJoinCalled(scopeId);
 *         detector.recordResultAccessed(scopeId);
 *         detector.recordScopeClosed(scopeId);
 *     } catch (Exception e) {
 *         detector.recordScopeClosed(scopeId);
 *         throw e;
 *     }
 * }
 * }</pre>
 *
 * @since 0.7.0
 */
public class StructuredConcurrencyMisuseDetector {

    private static final AtomicLong SCOPE_ID_GEN = new AtomicLong(0);

    private static class ScopeRecord {
        final String id;
        final String scopeType;
        final long openedAtNanos;
        final long openedByThread;
        volatile boolean joined = false;
        volatile boolean closed = false;
        final AtomicInteger subtaskCount = new AtomicInteger(0);
        final AtomicInteger resultAccessBeforeJoin = new AtomicInteger(0);
        volatile int nestingDepth = 0;
        final StackTraceElement[] openedStack;

        ScopeRecord(String id, String scopeType, StackTraceElement[] stack) {
            this.id = id;
            this.scopeType = scopeType;
            this.openedAtNanos = System.nanoTime();
            this.openedByThread = Thread.currentThread().threadId();
            this.openedStack = stack;
        }
    }

    private final Map<String, ScopeRecord> openScopes = new ConcurrentHashMap<>();
    private final List<String> closedWithoutJoin = Collections.synchronizedList(new ArrayList<>());
    private final List<String> resultAccessedBeforeJoin = Collections.synchronizedList(new ArrayList<>());
    private final List<String> unclosedScopes = Collections.synchronizedList(new ArrayList<>());
    private final List<String> emptyScopes = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger maxNestingDepth = new AtomicInteger(0);
    // Track nesting depth per thread
    private final Map<Long, AtomicInteger> threadNesting = new ConcurrentHashMap<>();

    /**
     * Record that a {@code StructuredTaskScope} has been opened.
     *
     * @param scopeType descriptive name, e.g. {@code "ShutdownOnFailure"} or {@code "ShutdownOnSuccess"}
     * @return a scope ID to pass to all subsequent calls for this scope
     */
    public String recordScopeOpened(String scopeType) {
        String id = scopeType + "#" + SCOPE_ID_GEN.incrementAndGet()
                    + "@" + Thread.currentThread().threadId();
        StackTraceElement[] stack = captureStack();
        ScopeRecord rec = new ScopeRecord(id, scopeType != null ? scopeType : "StructuredTaskScope", stack);

        // Track nesting depth for this thread
        long tid = Thread.currentThread().threadId();
        AtomicInteger depth = threadNesting.computeIfAbsent(tid, k -> new AtomicInteger(0));
        int d = depth.incrementAndGet();
        rec.nestingDepth = d;
        maxNestingDepth.updateAndGet(max -> Math.max(max, d));

        openScopes.put(id, rec);
        return id;
    }

    /**
     * Record that a subtask has been forked inside the scope.
     *
     * @param scopeId the ID returned by {@link #recordScopeOpened(String)}
     */
    public void recordSubtaskForked(String scopeId) {
        ScopeRecord rec = openScopes.get(scopeId);
        if (rec != null) {
            rec.subtaskCount.incrementAndGet();
        }
    }

    /**
     * Record that {@code scope.join()} was called.
     *
     * @param scopeId the ID returned by {@link #recordScopeOpened(String)}
     */
    public void recordJoinCalled(String scopeId) {
        ScopeRecord rec = openScopes.get(scopeId);
        if (rec != null) {
            rec.joined = true;
        }
    }

    /**
     * Record that a subtask result was accessed (via {@code Subtask.get()}).
     * If called before {@link #recordJoinCalled(String)}, this is flagged as a violation.
     *
     * @param scopeId the ID returned by {@link #recordScopeOpened(String)}
     */
    public void recordResultAccessed(String scopeId) {
        ScopeRecord rec = openScopes.get(scopeId);
        if (rec != null && !rec.joined) {
            rec.resultAccessBeforeJoin.incrementAndGet();
            resultAccessedBeforeJoin.add(
                "Scope " + rec.scopeType + " (id=" + rec.id + "): "
                + "subtask result accessed before join() — returned value may be incomplete"
            );
        }
    }

    /**
     * Record that the scope has been closed (try-with-resources or explicit {@code close()}).
     *
     * @param scopeId the ID returned by {@link #recordScopeOpened(String)}
     */
    public void recordScopeClosed(String scopeId) {
        ScopeRecord rec = openScopes.remove(scopeId);
        if (rec != null) {
            rec.closed = true;

            // Decrement nesting depth for this thread
            long tid = Thread.currentThread().threadId();
            AtomicInteger depth = threadNesting.get(tid);
            if (depth != null) depth.decrementAndGet();

            if (rec.subtaskCount.get() == 0) {
                emptyScopes.add("Scope " + rec.scopeType + " (id=" + rec.id
                    + ") was opened but no subtasks were forked — dead code or missing fork() call");
            }
            if (!rec.joined && rec.subtaskCount.get() > 0) {
                closedWithoutJoin.add("Scope " + rec.scopeType + " (id=" + rec.id
                    + ") closed without calling join() — "
                    + rec.subtaskCount.get() + " subtask(s) may not have completed");
            }
        }
    }

    /**
     * Analyze all recorded structured concurrency events for issues.
     *
     * @return a report summarizing detected misuse patterns
     */
    public StructuredConcurrencyReport analyze() {
        // Any scopes still in openScopes at analysis time are unclosed
        for (ScopeRecord rec : openScopes.values()) {
            unclosedScopes.add("Scope " + rec.scopeType + " (id=" + rec.id
                + ") was never closed — subtasks may outlive their scope (resource leak)");
        }

        return new StructuredConcurrencyReport(
            new ArrayList<>(unclosedScopes),
            new ArrayList<>(closedWithoutJoin),
            new ArrayList<>(resultAccessedBeforeJoin),
            new ArrayList<>(emptyScopes),
            maxNestingDepth.get()
        );
    }

    private static StackTraceElement[] captureStack() {
        StackTraceElement[] full = Thread.currentThread().getStackTrace();
        // Skip getStackTrace + captureStack + recordScopeOpened
        int skip = Math.min(3, full.length);
        StackTraceElement[] trimmed = new StackTraceElement[Math.min(5, full.length - skip)];
        System.arraycopy(full, skip, trimmed, 0, trimmed.length);
        return trimmed;
    }

    /**
     * Report of structured concurrency misuse analysis.
     */
    public static class StructuredConcurrencyReport {
        private final List<String> unclosedScopes;
        private final List<String> closedWithoutJoin;
        private final List<String> resultAccessedBeforeJoin;
        private final List<String> emptyScopes;
        private final int maxNestingDepth;

        StructuredConcurrencyReport(
                List<String> unclosedScopes,
                List<String> closedWithoutJoin,
                List<String> resultAccessedBeforeJoin,
                List<String> emptyScopes,
                int maxNestingDepth) {
            this.unclosedScopes = unclosedScopes;
            this.closedWithoutJoin = closedWithoutJoin;
            this.resultAccessedBeforeJoin = resultAccessedBeforeJoin;
            this.emptyScopes = emptyScopes;
            this.maxNestingDepth = maxNestingDepth;
        }

        /** @return true if any structured concurrency issues were detected */
        public boolean hasIssues() {
            return !unclosedScopes.isEmpty()
                || !closedWithoutJoin.isEmpty()
                || !resultAccessedBeforeJoin.isEmpty()
                || !emptyScopes.isEmpty();
        }

        public List<String> getUnclosedScopes()           { return unclosedScopes; }
        public List<String> getClosedWithoutJoin()        { return closedWithoutJoin; }
        public List<String> getResultAccessedBeforeJoin() { return resultAccessedBeforeJoin; }
        public List<String> getEmptyScopes()              { return emptyScopes; }
        public int          getMaxNestingDepth()           { return maxNestingDepth; }

        @Override
        public String toString() {
            if (!hasIssues()) {
                return "StructuredConcurrencyReport: No structured concurrency issues detected";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(IssueSeverity.HIGH.format())
              .append(": Structured concurrency misuse detected\n");

            appendSection(sb, "Unclosed scopes (resource leak)", unclosedScopes);
            appendSection(sb, "Scopes closed without join() (subtasks may not have completed)", closedWithoutJoin);
            appendSection(sb, "Subtask results accessed before join() (unsafe)", resultAccessedBeforeJoin);
            appendSection(sb, "Empty scopes (no subtasks forked)", emptyScopes);

            if (maxNestingDepth > 3) {
                sb.append("\n  Warning: max scope nesting depth = ").append(maxNestingDepth)
                  .append(" (consider flattening to improve readability)");
            }

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
                📚 LEARNING: Structured Concurrency (Java 21+)

                Structured concurrency ensures that subtasks cannot outlive their enclosing scope.
                The canonical pattern:

                  try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
                      Subtask<String> task1 = scope.fork(() -> fetchData());
                      Subtask<Integer> task2 = scope.fork(() -> computeResult());
                      scope.join();           // ← MUST call before accessing results
                      scope.throwIfFailed();
                      return task1.get() + task2.get(); // ← Safe only after join()
                  }                           // ← close() called automatically

                Common mistakes:
                  ✗ Forgetting scope.join() — subtasks may still be running
                  ✗ Calling task.get() before scope.join() — undefined behavior
                  ✗ Not using try-with-resources — scope never closed, threads leak

                Fix: always use try-with-resources + join() + then access results.
                """;
        }
    }
}
