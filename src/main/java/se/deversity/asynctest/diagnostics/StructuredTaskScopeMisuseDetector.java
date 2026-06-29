package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import se.deversity.vibetags.annotations.AITestDriven;

/**
 * Detects misuse of the Java 25 {@code StructuredTaskScope} API (JEP 505 —
 * Structured Concurrency, fifth preview in JDK 25, on track to finalize in
 * JDK 26).
 *
 * <p>The JDK 25 API reshapes structured concurrency around a single factory and
 * a pluggable {@code Joiner}:
 * <pre>{@code
 * try (var scope = StructuredTaskScope.open(Joiner.<String>allSuccessfulOrThrow())) {
 *     Subtask<String> a = scope.fork(() -> fetchA());
 *     Subtask<String> b = scope.fork(() -> fetchB());
 *     scope.join();                 // wait for the policy to be satisfied
 *     return combine(a.get(), b.get());
 * }
 * }</pre>
 *
 * <p>The structure enforces a strict lifecycle. Breaking it does not merely
 * produce a bad result — it throws, leaks subtasks, or silently returns a value
 * read from an incomplete subtask. This detector models the lifecycle as a small
 * state machine per scope and flags the transitions that the runtime rejects:
 *
 * <p><strong>Issues detected:</strong>
 * <ul>
 *   <li><b>Fork after join</b> — {@code fork(...)} called after {@code join()} on
 *       the same scope. The runtime throws {@code IllegalStateException}; the scope
 *       is no longer accepting work.</li>
 *   <li><b>Result before join</b> — {@code Subtask.get()} called before {@code join()}
 *       completes, or while the subtask state is not {@code SUCCESS}. Throws
 *       {@code IllegalStateException} and, if it didn't, would read an
 *       unpublished / partial result.</li>
 *   <li><b>Owner-confinement violation</b> — {@code fork()} / {@code join()} invoked
 *       from a thread other than the one that opened the scope. The runtime throws
 *       {@code WrongThreadException} (or {@code StructureViolationException}); a scope
 *       is confined to its owning thread.</li>
 *   <li><b>Missing join</b> — the scope is closed without {@code join()} having been
 *       called after at least one {@code fork()}. Closing cancels the still-running
 *       subtasks; their work (and any side effects) is abandoned.</li>
 * </ul>
 *
 * <p><strong>Usage:</strong>
 * <pre>{@code
 * @AsyncTest(threads = 8, useVirtualThreads = true)
 * void testStructuredFanOut() {
 *     var detector = AsyncTestContext.structuredTaskScopeMisuseDetector();
 *     String scopeId = "fanout";
 *     Thread owner = Thread.currentThread();
 *
 *     detector.recordScopeOpened(scopeId, owner);
 *     detector.recordFork(scopeId, "a", owner);
 *     detector.recordJoin(scopeId, owner);
 *     detector.recordResultRead(scopeId, "a", owner);
 *     detector.recordScopeClosed(scopeId, owner);
 * }
 * }</pre>
 *
 * @since 1.7.0
 */
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/StructuredTaskScopeMisuseDetectorTest.java"
)
public class StructuredTaskScopeMisuseDetector {

    private static final class ScopeState {
        final long ownerThreadId;
        final String ownerThreadName;
        volatile boolean joined = false;
        final AtomicInteger forkCount = new AtomicInteger(0);

        ScopeState(long ownerThreadId, String ownerThreadName) {
            this.ownerThreadId = ownerThreadId;
            this.ownerThreadName = ownerThreadName;
        }
    }

    // Per-scope lifecycle state, keyed by a caller-supplied scope id.
    private final Map<String, ScopeState> scopes = new ConcurrentHashMap<>();

    private final List<String> forkAfterJoinReports   = Collections.synchronizedList(new ArrayList<>());
    private final List<String> resultBeforeJoinReports = Collections.synchronizedList(new ArrayList<>());
    private final List<String> confinementReports      = Collections.synchronizedList(new ArrayList<>());
    private final List<String> missingJoinReports      = Collections.synchronizedList(new ArrayList<>());

    private final AtomicInteger totalForks = new AtomicInteger(0);
    private final AtomicInteger totalScopes = new AtomicInteger(0);

    /**
     * Record that a {@code StructuredTaskScope.open(...)} returned a new scope,
     * confined to the opening (owner) thread.
     */
    public void recordScopeOpened(String scopeId, Thread owner) {
        if (scopeId == null || owner == null) return;
        totalScopes.incrementAndGet();
        scopes.put(scopeId, new ScopeState(owner.threadId(), owner.getName()));
    }

    /**
     * Record a {@code scope.fork(task)} call. Flags fork-after-join and
     * owner-confinement violations.
     */
    public void recordFork(String scopeId, String subtaskId, Thread thread) {
        if (scopeId == null || subtaskId == null || thread == null) return;
        totalForks.incrementAndGet();
        ScopeState s = scopes.get(scopeId);
        if (s == null) return;

        if (thread.threadId() != s.ownerThreadId) {
            confinementReports.add(
                "Thread " + thread.getName() + " (id=" + thread.threadId() + "): "
                + "fork() on scope '" + scopeId + "' from a non-owner thread (owner='"
                + s.ownerThreadName + "', id=" + s.ownerThreadId + "). A StructuredTaskScope "
                + "is confined to its owner; this throws WrongThreadException."
            );
        }
        if (s.joined) {
            forkAfterJoinReports.add(
                "Thread " + thread.getName() + " (id=" + thread.threadId() + "): "
                + "fork() of subtask '" + subtaskId + "' on scope '" + scopeId + "' after join() "
                + "had already returned. The scope no longer accepts work; this throws "
                + "IllegalStateException."
            );
        }
        s.forkCount.incrementAndGet();
    }

    /**
     * Record a {@code scope.join()} call. Flags owner-confinement violations and
     * marks the scope as joined.
     */
    public void recordJoin(String scopeId, Thread thread) {
        if (scopeId == null || thread == null) return;
        ScopeState s = scopes.get(scopeId);
        if (s == null) return;

        if (thread.threadId() != s.ownerThreadId) {
            confinementReports.add(
                "Thread " + thread.getName() + " (id=" + thread.threadId() + "): "
                + "join() on scope '" + scopeId + "' from a non-owner thread (owner='"
                + s.ownerThreadName + "', id=" + s.ownerThreadId + "). join() must be called "
                + "by the owning thread; this throws WrongThreadException."
            );
        }
        s.joined = true;
    }

    /**
     * Record a {@code Subtask.get()} call. Flags reads that happen before the
     * scope has been joined.
     */
    public void recordResultRead(String scopeId, String subtaskId, Thread thread) {
        if (scopeId == null || subtaskId == null || thread == null) return;
        ScopeState s = scopes.get(scopeId);
        if (s == null) return;

        if (!s.joined) {
            resultBeforeJoinReports.add(
                "Thread " + thread.getName() + " (id=" + thread.threadId() + "): "
                + "Subtask.get() for '" + subtaskId + "' on scope '" + scopeId + "' before join() "
                + "completed. The subtask may not have finished; this throws IllegalStateException "
                + "rather than returning a partial result."
            );
        }
    }

    /**
     * Record that the scope was closed (the try-with-resources block ended).
     * Flags a scope that forked subtasks but was never joined.
     */
    public void recordScopeClosed(String scopeId, Thread thread) {
        if (scopeId == null || thread == null) return;
        ScopeState s = scopes.get(scopeId);
        if (s == null) return;

        if (s.forkCount.get() > 0 && !s.joined) {
            missingJoinReports.add(
                "Scope '" + scopeId + "' (owner='" + s.ownerThreadName + "') was closed after "
                + s.forkCount.get() + " fork(s) without ever calling join(). Closing cancels the "
                + "still-running subtasks and discards their work — call join() before leaving the "
                + "try-with-resources block."
            );
        }
    }

    /**
     * Analyze all recorded StructuredTaskScope events for misuse patterns.
     *
     * @return a report describing detected issues
     */
    public StructuredTaskScopeMisuseReport analyze() {
        return new StructuredTaskScopeMisuseReport(
            new ArrayList<>(forkAfterJoinReports),
            new ArrayList<>(resultBeforeJoinReports),
            new ArrayList<>(confinementReports),
            new ArrayList<>(missingJoinReports),
            totalScopes.get(),
            totalForks.get()
        );
    }

    /**
     * Report of StructuredTaskScope misuse analysis.
     */
    public static class StructuredTaskScopeMisuseReport {
        private final List<String> forkAfterJoinIssues;
        private final List<String> resultBeforeJoinIssues;
        private final List<String> confinementIssues;
        private final List<String> missingJoinIssues;
        private final int totalScopes;
        private final int totalForks;

        StructuredTaskScopeMisuseReport(
                List<String> forkAfterJoinIssues,
                List<String> resultBeforeJoinIssues,
                List<String> confinementIssues,
                List<String> missingJoinIssues,
                int totalScopes,
                int totalForks) {
            this.forkAfterJoinIssues = forkAfterJoinIssues;
            this.resultBeforeJoinIssues = resultBeforeJoinIssues;
            this.confinementIssues = confinementIssues;
            this.missingJoinIssues = missingJoinIssues;
            this.totalScopes = totalScopes;
            this.totalForks = totalForks;
        }

        /** {@return true if any StructuredTaskScope misuse was detected} */
        public boolean hasIssues() {
            return !forkAfterJoinIssues.isEmpty()
                || !resultBeforeJoinIssues.isEmpty()
                || !confinementIssues.isEmpty()
                || !missingJoinIssues.isEmpty();
        }

        public List<String> getForkAfterJoinIssues()    { return Collections.unmodifiableList(forkAfterJoinIssues); }
        public List<String> getResultBeforeJoinIssues() { return Collections.unmodifiableList(resultBeforeJoinIssues); }
        public List<String> getConfinementIssues()      { return Collections.unmodifiableList(confinementIssues); }
        public List<String> getMissingJoinIssues()      { return Collections.unmodifiableList(missingJoinIssues); }
        public int          getTotalScopes()            { return totalScopes; }
        public int          getTotalForks()             { return totalForks; }

        @Override
        public String toString() {
            if (!hasIssues()) {
                return "StructuredTaskScopeMisuseReport: No StructuredTaskScope misuse detected";
            }

            StringBuilder sb = new StringBuilder();

            if (!confinementIssues.isEmpty()
                || !forkAfterJoinIssues.isEmpty()
                || !resultBeforeJoinIssues.isEmpty()) {
                sb.append(IssueSeverity.CRITICAL.format())
                  .append(": StructuredTaskScope lifecycle violated (will throw at runtime)\n");
            } else {
                sb.append(IssueSeverity.HIGH.format())
                  .append(": StructuredTaskScope subtasks abandoned (missing join)\n");
            }

            sb.append("  Scopes=").append(totalScopes)
              .append(", Forks=").append(totalForks).append("\n");

            appendSection(sb, "Fork after join (IllegalStateException)", forkAfterJoinIssues);
            appendSection(sb, "Subtask.get() before join (IllegalStateException)", resultBeforeJoinIssues);
            appendSection(sb, "Owner-confinement violation (WrongThreadException)", confinementIssues);
            appendSection(sb, "Scope closed without join (subtasks cancelled)", missingJoinIssues);

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
                📚 LEARNING: StructuredTaskScope (Java 25, JEP 505)

                Structured concurrency ties the lifetime of concurrent subtasks to a
                lexical scope, so a fan-out cannot outlive the method that started it.

                Correct usage:
                  try (var scope = StructuredTaskScope.open(Joiner.<T>allSuccessfulOrThrow())) {
                      Subtask<T> a = scope.fork(() -> taskA());
                      Subtask<T> b = scope.fork(() -> taskB());
                      scope.join();              // ← wait first
                      return combine(a.get(), b.get());  // ← then read results
                  }

                Lifecycle rules (all enforced by the runtime):
                  ✗ fork() after join()        → IllegalStateException
                  ✗ Subtask.get() before join() → IllegalStateException (partial result)
                  ✗ fork()/join() off-owner    → WrongThreadException (scope is confined)
                  ✗ close() without join()     → running subtasks are cancelled, work lost

                Order is always: open → fork* → join → get* → close (try-with-resources).
                """;
        }
    }
}
