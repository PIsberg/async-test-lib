package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Detects results of a {@code StructuredTaskScope} outliving the scope that produced them - the
 * hazard JEP 525 made easy to reach by returning collections instead of streams.
 *
 * <p>In JDK 25 the built-in joiners handed back a {@code Stream<Subtask<T>>}. A stream is lazy and
 * single-use, so keeping one past {@code close()} tended to fail loudly and early. JDK 26 changed
 * {@code allSuccessfulOrThrow()} and {@code allUntil(...)} to return a {@code List}, which is the
 * ergonomic improvement everyone wanted and also a handle that looks perfectly safe to store in a
 * field, hand to another thread, or read after the try-with-resources block has ended:
 *
 * <pre>{@code
 * List<Subtask<Order>> results;
 * try (var scope = StructuredTaskScope.open(Joiner.<Order>allSuccessfulOrThrow())) {
 *     scope.fork(this::fetchA);
 *     results = scope.join();
 * }
 * return results.get(0).get();   // the scope is gone; this is outside the structure
 * }</pre>
 *
 * <p>The point of structured concurrency is that a subtask does not outlive its scope. A result
 * handle read after {@code close()} has no happens-before edge to anything the scope did on the
 * way out, and a handle read by a thread that never owned the scope has none to the subtasks that
 * filled it either - {@code join()} on the owner thread is where that edge is established.
 *
 * <p>{@link StructuredTaskScopeMisuseDetector} covers reading a result <em>too early</em>: before
 * {@code join()}, or after a timeout cancelled the subtask. This detector covers reading one
 * <em>too late</em>, or on the wrong thread. The two are disjoint by construction: nothing here
 * fires until {@code join()} has completed or the scope has closed.
 *
 * <p><strong>Issues detected:</strong>
 * <ul>
 *   <li><b>Read after close</b> - a result list or subtask read after its scope closed. The
 *       structure that guaranteed the value is complete no longer exists.</li>
 *   <li><b>Read off the owner thread</b> - a result handle read by a thread other than the one
 *       that opened the scope and called {@code join()}. That thread has no happens-before edge to
 *       the subtask writes the list points at.</li>
 *   <li><b>Published before join</b> - the handle stored into a field or shared collection before
 *       {@code join()} completed, which lets another thread reach it while subtasks are still
 *       running.</li>
 *   <li><b>Mutation attempted</b> - a write to the returned list. It is unmodifiable, so the write
 *       throws; reaching for it at all means the caller believed it owned a private copy.</li>
 * </ul>
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var d = AsyncTestContext.scopeResultEscapeDetector();
 * d.recordScopeOpened("scope-1", Thread.currentThread());
 * ...
 * d.recordJoinCompleted("scope-1");
 * d.recordResultHandle(results, "orderResults", "scope-1");
 * d.recordScopeClosed("scope-1");
 *
 * d.recordHandleRead(results, Thread.currentThread());   // after close: reported
 * }</pre>
 *
 * @since 1.9.7
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER,
        note = "One state object per scope id and per result-handle identity, both in ConcurrentHashMaps. "
             + "Close and read are ordered by a single AtomicLong sequence rather than the wall clock, so "
             + "'read after close' is decided by program order and never by clock granularity. Reader thread "
             + "ids accumulate in a concurrent set and are read once in analyze().")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/ScopeResultEscapeDetectorTest.java"
)
public final class ScopeResultEscapeDetector {

    private static final class ScopeState {
        final String     scopeId;
        volatile long    ownerThreadId;
        final AtomicBoolean joined  = new AtomicBoolean(false);
        /** Sequence at which the scope closed; MAX_VALUE while it is still open. */
        volatile long    closeSeq   = Long.MAX_VALUE;

        ScopeState(String scopeId, long ownerThreadId) {
            this.scopeId      = scopeId;
            this.ownerThreadId = ownerThreadId;
        }
    }

    private static final class HandleState {
        final String        label;
        final ScopeState    scope;
        final AtomicInteger readsAfterClose   = new AtomicInteger();
        final Set<String>   offOwnerReaders   = ConcurrentHashMap.newKeySet();
        final AtomicInteger publishedBeforeJoin = new AtomicInteger();
        final AtomicInteger mutations         = new AtomicInteger();
        final AtomicInteger reads             = new AtomicInteger();

        HandleState(String label, ScopeState scope) {
            this.label = label;
            this.scope = scope;
        }
    }

    private final Map<String, ScopeState>   scopes   = new ConcurrentHashMap<>();
    private final Map<Integer, HandleState> handles  = new ConcurrentHashMap<>();
    private final AtomicLong                sequence = new AtomicLong();
    private volatile boolean                enabled  = true;

    /** Creates a detector with no recorded scopes. */
    public ScopeResultEscapeDetector() { }

    /**
     * Record a scope opening and who owns it.
     *
     * @param scopeId the scope's identifier, unique per open
     * @param owner   the thread opening the scope
     */
    public void recordScopeOpened(String scopeId, Thread owner) {
        if (!enabled || scopeId == null || owner == null) return;
        long ownerId = owner.threadId();
        scopes.computeIfAbsent(scopeId, k -> new ScopeState(scopeId, ownerId));
    }

    /**
     * Record that {@code join()} returned, which is where the results become readable.
     *
     * @param scopeId the scope's identifier
     */
    public void recordJoinCompleted(String scopeId) {
        ScopeState s = scope(scopeId);
        if (s != null) s.joined.set(true);
    }

    /**
     * Record a handle onto a scope's results - the {@code List<Subtask<T>>} a joiner returned, or a
     * single {@code Subtask}.
     *
     * @param handle  the list or subtask, tracked by identity
     * @param label   a label identifying it in the report
     * @param scopeId the scope that produced it
     */
    public void recordResultHandle(Object handle, String label, String scopeId) {
        if (!enabled || handle == null) return;
        ScopeState s = scope(scopeId);
        if (s == null) return;
        int id = System.identityHashCode(handle);
        String name = label != null ? label : "results@" + id;
        handles.computeIfAbsent(id, k -> new HandleState(name, s));
    }

    /**
     * Record a read of a result handle - iterating the list, or calling {@code Subtask.get()}.
     *
     * @param handle the list or subtask, tracked by identity
     * @param thread the reading thread
     */
    public void recordHandleRead(Object handle, Thread thread) {
        HandleState h = handle(handle);
        if (h == null || thread == null) return;
        h.reads.incrementAndGet();
        if (sequence.incrementAndGet() > h.scope.closeSeq) h.readsAfterClose.incrementAndGet();
        if (thread.threadId() != h.scope.ownerThreadId) h.offOwnerReaders.add(thread.getName());
    }

    /**
     * Record the handle being stored somewhere another thread can reach it.
     *
     * @param handle the list or subtask, tracked by identity
     * @param thread the publishing thread
     */
    public void recordHandlePublished(Object handle, Thread thread) {
        HandleState h = handle(handle);
        if (h == null || thread == null) return;
        if (!h.scope.joined.get()) h.publishedBeforeJoin.incrementAndGet();
    }

    /**
     * Record an attempt to modify the returned result list.
     *
     * @param handle the list, tracked by identity
     * @param thread the mutating thread
     */
    public void recordHandleMutation(Object handle, Thread thread) {
        HandleState h = handle(handle);
        if (h == null || thread == null) return;
        h.mutations.incrementAndGet();
    }

    /**
     * Record a scope closing, which ends the window in which its results are valid.
     *
     * @param scopeId the scope's identifier
     */
    public void recordScopeClosed(String scopeId) {
        ScopeState s = scope(scopeId);
        if (s != null) s.closeSeq = sequence.incrementAndGet();
    }

    private @Nullable ScopeState scope(String scopeId) {
        if (!enabled || scopeId == null) return null;
        return scopes.get(scopeId);
    }

    private @Nullable HandleState handle(Object handle) {
        if (!enabled || handle == null) return null;
        return handles.get(System.identityHashCode(handle));
    }

    /** Turn recording off; already-recorded state is kept. */
    public void disable() { enabled = false; }

    /** Turn recording back on. */
    public void enable() { enabled = true; }

    /**
     * Analyses the recorded result handles and builds the report.
     *
     * @return the findings this detector collected during the run
     */
    public Report analyze() {
        Report r = new Report();
        for (HandleState h : handles.values()) {
            readAfterClose(r, h);
            readOffOwner(r, h);
            publishedBeforeJoin(r, h);
            mutated(r, h);
        }
        return r;
    }

    private static void readAfterClose(Report r, HandleState h) {
        int late = h.readsAfterClose.get();
        if (late < 1) return;
        String msg = String.format(
                "Result handle '%s' from scope %s was read %d time(s) after the scope closed. The structure "
                + "that guaranteed those subtasks were finished is gone by then, and the read carries no "
                + "happens-before edge to the scope's shutdown - the list escaped the try-with-resources it "
                + "was produced in.",
                h.label, h.scope.scopeId, late);
        r.add("ScopeResultEscape", IssueSeverity.CRITICAL, msg,
                Map.of("handle", h.label, "scope", h.scope.scopeId,
                       "issue", "resultReadAfterScopeClose",
                       "readsAfterClose", late, "reads", h.reads.get()));
    }

    private static void readOffOwner(Report r, HandleState h) {
        if (h.offOwnerReaders.isEmpty()) return;
        String readers = String.join(", ", new TreeSet<>(h.offOwnerReaders));
        String msg = String.format(
                "Result handle '%s' from scope %s was read by %d thread(s) that did not own the scope: %s. "
                + "join() on the owner thread is what publishes the subtasks' writes; a reader that never "
                + "called it has no edge to them and may see the results incomplete.",
                h.label, h.scope.scopeId, h.offOwnerReaders.size(), readers);
        r.add("ScopeResultEscape", IssueSeverity.HIGH, msg,
                Map.of("handle", h.label, "scope", h.scope.scopeId,
                       "issue", "resultReadOffOwnerThread",
                       "readers", readers, "readerCount", h.offOwnerReaders.size()));
    }

    private static void publishedBeforeJoin(Report r, HandleState h) {
        int published = h.publishedBeforeJoin.get();
        if (published < 1) return;
        String msg = String.format(
                "Result handle '%s' from scope %s was published to shared state %d time(s) before join() "
                + "completed. Another thread can reach it while the subtasks behind it are still running, so "
                + "what that thread reads is whatever had been filled in so far.",
                h.label, h.scope.scopeId, published);
        r.add("ScopeResultEscape", IssueSeverity.HIGH, msg,
                Map.of("handle", h.label, "scope", h.scope.scopeId,
                       "issue", "resultPublishedBeforeJoin", "publications", published));
    }

    private static void mutated(Report r, HandleState h) {
        int mutations = h.mutations.get();
        if (mutations < 1) return;
        String msg = String.format(
                "Result handle '%s' from scope %s was modified %d time(s). The list a JDK 26 joiner returns is "
                + "unmodifiable, so the write throws; reaching for it means the caller took the list for a "
                + "private copy it could sort, filter in place, or add to.",
                h.label, h.scope.scopeId, mutations);
        r.add("ScopeResultEscape", IssueSeverity.MEDIUM, msg,
                Map.of("handle", h.label, "scope", h.scope.scopeId,
                       "issue", "resultListMutated", "mutations", mutations));
    }

    /** Report produced by {@link #analyze()}. */
    public static final class Report {
        /** Findings as human-readable lines, for the text report. */
        public final List<String> violations = new ArrayList<>();
        /** The same findings as {@link Violation} objects, for machine-readable reports. */
        public final List<Violation> structuredViolations = new ArrayList<>();

        void add(String detector, IssueSeverity severity, String message, Map<String, Object> context) {
            violations.add(message);
            structuredViolations.add(
                    new Violation(detector, severity, message, List.of(), context, Instant.now()));
        }

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "SCOPE RESULT ESCAPE - clean";
            StringBuilder sb = new StringBuilder("SCOPE RESULT ESCAPE DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Why: JDK 26 joiners return a List where JDK 25 returned a Stream. A stream is lazy and\n")
              .append("       single-use, so holding one past close() failed early and loudly; a List looks like\n")
              .append("       an ordinary value and stores happily in a field. Structured concurrency's whole\n")
              .append("       guarantee is that subtasks do not outlive their scope, and the handle does.\n")
              .append("  Fix:\n")
              .append("    - Extract the values you need inside the try-with-resources and return those, not the\n")
              .append("      list of Subtask handles\n")
              .append("    - Do the reading on the thread that opened the scope and called join()\n")
              .append("    - Copy into your own collection if you need to sort or filter; the returned list is\n")
              .append("      unmodifiable\n")
              .append("    - Assign to shared state after join() returns, never before\n");
            return sb.toString();
        }
    }
}
