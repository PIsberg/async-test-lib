package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects misuse of the {@code StructuredTaskScope.Joiner} contract - the extension point JEP 525
 * (Structured Concurrency, sixth preview in JDK 26) puts in the application's hands.
 *
 * <p>{@link StructuredTaskScopeMisuseDetector} models the <em>scope</em> lifecycle: fork, join,
 * read, close, and who owns them. This detector models the <em>joiner</em>, and the two do not
 * overlap. A joiner is not a callback the owner thread runs; it is a small piece of concurrent
 * code the runtime calls from threads the application never named:
 *
 * <pre>{@code
 * try (var scope = StructuredTaskScope.open(
 *         new CollectingJoiner<Order>(),
 *         cfg -> cfg.withTimeout(Duration.ofSeconds(3)))) {
 *     scope.fork(this::fetchA);
 *     scope.fork(this::fetchB);
 *     return scope.join();
 * }
 * }</pre>
 *
 * <p>The contract that makes this dangerous is in the invocation threads. {@code onComplete} runs
 * <strong>on the thread that finished the subtask</strong>, so two subtasks finishing together
 * call it concurrently on the same joiner instance. {@code result()} and the JDK 26 addition
 * {@code onTimeout()} run on the <strong>owner</strong> thread. A joiner that accumulates into a
 * plain {@code ArrayList} therefore has a data race that no amount of correct scope usage removes,
 * and {@code onTimeout()} reads that accumulator while the cancelled subtasks are still writing to
 * it.
 *
 * <p>{@code onTimeout()} is what makes this worth its own detector rather than a note on the scope
 * one. Before JDK 26 a timeout simply threw, so a half-built accumulator was never read. JEP 525
 * makes returning a partial result the recommended pattern, which turns a latent race into the
 * value the caller gets back.
 *
 * <p><strong>Issues detected:</strong>
 * <ul>
 *   <li><b>Joiner reused across scopes</b> - one joiner instance passed to more than one
 *       {@code StructuredTaskScope.open(...)}. A joiner holds the run's accumulated state; the
 *       second scope starts with the first scope's results already in it.</li>
 *   <li><b>Racy accumulation</b> - two or more threads were inside {@code onComplete} at the same
 *       moment and wrote to the joiner's state, on a joiner not declared thread-safe. Reported
 *       only with both facts present: overlap alone is normal, and a write alone is not a race.</li>
 *   <li><b>Timeout read a partial accumulation</b> - {@code onTimeout()} began on the owner thread
 *       while at least one {@code onComplete} was still running. The fallback value is built from
 *       state another thread is mid-write on.</li>
 *   <li><b>Joiner method off the owner thread</b> - {@code result()} or {@code onTimeout()} called
 *       from a thread other than the one that opened the scope. Both are owner-confined.</li>
 *   <li><b>Fork after short-circuit</b> - {@code onComplete} returned {@code true} to cancel the
 *       scope, and the owner forked again anyway. The new subtask is born cancelled.</li>
 * </ul>
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var d = AsyncTestContext.scopeJoinerMisuseDetector();
 * d.recordJoinerBound(joiner, "orderJoiner", "scope-1", Thread.currentThread());
 *
 * // inside the joiner's onComplete, on the subtask's thread:
 * d.recordOnCompleteEnter(joiner, Thread.currentThread());
 * d.recordAccumulate(joiner, Thread.currentThread());   // touching joiner-owned state
 * d.recordOnCompleteExit(joiner, Thread.currentThread(), false);
 *
 * // inside onTimeout / result, on the owner thread:
 * d.recordOnTimeout(joiner, Thread.currentThread());
 * }</pre>
 *
 * <p>A joiner whose state is genuinely concurrent - an accumulator built on a
 * {@code ConcurrentLinkedQueue}, or one guarded by a lock the joiner owns - should say so once
 * with {@link #declareThreadSafe(Object)}; the racy-accumulation finding is then suppressed for
 * that instance and the other four still apply.
 *
 * @since 1.9.7
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER,
        note = "One state object per joiner identity in a ConcurrentHashMap. The in-flight onComplete count "
             + "is an atomic counter whose peak is raised with a CAS retry loop, so a peak observed under "
             + "contention is never lower than the true peak. Findings are recorded as flags set from the "
             + "recording threads and read once in analyze(), after the run has quiesced.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/ScopeJoinerMisuseDetectorTest.java"
)
public final class ScopeJoinerMisuseDetector {

    private static final class JoinerState {
        final String        label;
        volatile long       ownerThreadId  = -1L;
        final Set<String>   scopes         = ConcurrentHashMap.newKeySet();
        final AtomicBoolean threadSafe     = new AtomicBoolean(false);

        /** Threads between onComplete entry and exit, and the most at once. */
        final AtomicInteger inFlight       = new AtomicInteger();
        final AtomicInteger peakInFlight   = new AtomicInteger();
        final AtomicInteger onCompletes    = new AtomicInteger();

        /** Threads that wrote joiner-owned state while another onComplete overlapped them. */
        final Set<Long>     racingWriters  = ConcurrentHashMap.newKeySet();

        final AtomicBoolean shortCircuited = new AtomicBoolean(false);
        final AtomicInteger forksAfterCut  = new AtomicInteger();

        /** onTimeout entered while this many onCompletes were still running; -1 = never entered. */
        final AtomicInteger timeoutOverlap = new AtomicInteger(-1);

        /** Owner-confined methods seen on some other thread, as "method@thread" lines. */
        private final Set<String> offOwnerCalls = new LinkedHashSet<>();
        /** Guards {@link #offOwnerCalls}. A private lock, so nothing outside can hold it. */
        private final Object offOwnerLock       = new Object();

        void recordOffOwnerCall(String call) {
            synchronized (offOwnerLock) { offOwnerCalls.add(call); }
        }

        /** {@return a snapshot of the off-owner calls, empty if there were none} */
        List<String> offOwnerCalls() {
            synchronized (offOwnerLock) { return List.copyOf(offOwnerCalls); }
        }

        JoinerState(String label) { this.label = label; }
    }

    private final Map<Integer, JoinerState> joiners = new ConcurrentHashMap<>();
    private volatile boolean                enabled = true;

    /** Creates a detector with no recorded joiners. */
    public ScopeJoinerMisuseDetector() {
        // Nothing to set up: every joiner gets its state the first time it is bound.
    }

    /**
     * Record that a joiner instance was handed to {@code StructuredTaskScope.open(...)}.
     *
     * <p>Call this once per {@code open}, with the scope's own identifier. Seeing one joiner bound
     * to two scope identifiers is what produces the reuse finding, so the identifiers must be
     * distinct per scope.
     *
     * @param joiner  the joiner instance, tracked by identity
     * @param label   a label identifying it in the report
     * @param scopeId the identifier of the scope it was passed to
     * @param owner   the thread that opened the scope
     */
    public void recordJoinerBound(Object joiner, String label, String scopeId, Thread owner) {
        if (!enabled || joiner == null || scopeId == null) return;
        JoinerState s = stateOrCreate(joiner, label);
        s.scopes.add(scopeId);
        if (owner != null) s.ownerThreadId = owner.threadId();
    }

    /**
     * Declare that this joiner's own state is safe for concurrent {@code onComplete} calls.
     *
     * <p>Suppresses only the racy-accumulation finding for this instance. Use it when the
     * accumulator is a concurrent collection, an atomic, or is guarded by a lock the joiner holds.
     *
     * @param joiner the joiner instance, tracked by identity
     */
    public void declareThreadSafe(Object joiner) {
        JoinerState s = state(joiner);
        if (s != null) s.threadSafe.set(true);
    }

    /**
     * Record entry into {@code onComplete}, on the thread the runtime called it from.
     *
     * @param joiner the joiner instance, tracked by identity
     * @param thread the completing subtask's thread
     */
    public void recordOnCompleteEnter(Object joiner, Thread thread) {
        JoinerState s = state(joiner);
        if (s == null || thread == null) return;
        raise(s.peakInFlight, s.inFlight.incrementAndGet());
        s.onCompletes.incrementAndGet();
    }

    /**
     * Record a write to joiner-owned state from inside {@code onComplete}.
     *
     * <p>The write is only evidence of a race if another {@code onComplete} was running at the
     * same moment, so this records the writer and the overlap together; a write with nothing else
     * in flight is ordinary and produces no finding.
     *
     * @param joiner the joiner instance, tracked by identity
     * @param thread the writing thread
     */
    public void recordAccumulate(Object joiner, Thread thread) {
        JoinerState s = state(joiner);
        if (s == null || thread == null) return;
        if (s.inFlight.get() > 1) s.racingWriters.add(thread.threadId());
    }

    /**
     * Record the return from {@code onComplete}.
     *
     * @param joiner          the joiner instance, tracked by identity
     * @param thread          the completing subtask's thread
     * @param requestedCancel what {@code onComplete} returned - {@code true} short-circuits the scope
     */
    public void recordOnCompleteExit(Object joiner, Thread thread, boolean requestedCancel) {
        JoinerState s = state(joiner);
        if (s == null || thread == null) return;
        s.inFlight.decrementAndGet();
        if (requestedCancel) s.shortCircuited.set(true);
    }

    /**
     * Record a {@code fork(...)} against the scope this joiner is driving.
     *
     * @param joiner the joiner instance, tracked by identity
     * @param thread the forking thread
     */
    public void recordFork(Object joiner, Thread thread) {
        JoinerState s = state(joiner);
        if (s == null || thread == null) return;
        if (s.shortCircuited.get()) s.forksAfterCut.incrementAndGet();
    }

    /**
     * Record entry into the JDK 26 {@code onTimeout()} hook, on the owner thread.
     *
     * @param joiner the joiner instance, tracked by identity
     * @param thread the thread the hook ran on
     */
    public void recordOnTimeout(Object joiner, Thread thread) {
        JoinerState s = state(joiner);
        if (s == null || thread == null) return;
        s.timeoutOverlap.set(s.inFlight.get());
        confineToOwner(s, "onTimeout()", thread);
    }

    /**
     * Record a call to {@code result()}, on the owner thread.
     *
     * @param joiner the joiner instance, tracked by identity
     * @param thread the thread the call ran on
     */
    public void recordResult(Object joiner, Thread thread) {
        JoinerState s = state(joiner);
        if (s == null || thread == null) return;
        confineToOwner(s, "result()", thread);
    }

    private static void confineToOwner(JoinerState s, String method, Thread thread) {
        long owner = s.ownerThreadId;
        if (owner < 0 || owner == thread.threadId()) return;
        s.recordOffOwnerCall(method + "@" + thread.getName());
    }

    private JoinerState stateOrCreate(Object joiner, String label) {
        int id = System.identityHashCode(joiner);
        String name = label != null ? label : "joiner@" + id;
        return joiners.computeIfAbsent(id, k -> new JoinerState(name));
    }

    private @Nullable JoinerState state(Object joiner) {
        if (!enabled || joiner == null) return null;
        return joiners.get(System.identityHashCode(joiner));
    }

    /** Raises {@code peak} to {@code observed} if it is higher, retrying against concurrent raisers. */
    private static void raise(AtomicInteger peak, int observed) {
        int current = peak.get();
        while (observed > current && !peak.compareAndSet(current, observed)) {
            current = peak.get();
        }
    }

    /** Turn recording off; already-recorded state is kept. */
    public void disable() { enabled = false; }

    /** Turn recording back on. */
    public void enable() { enabled = true; }

    /**
     * Analyses the recorded joiner traffic and builds the report.
     *
     * @return the findings this detector collected during the run
     */
    public Report analyze() {
        Report r = new Report();
        for (JoinerState s : joiners.values()) {
            reuse(r, s);
            racyAccumulation(r, s);
            partialTimeoutRead(r, s);
            offOwner(r, s);
            forkAfterShortCircuit(r, s);
        }
        return r;
    }

    private static void reuse(Report r, JoinerState s) {
        if (s.scopes.size() < 2) return;
        String msg = String.format(
                "Joiner '%s' was passed to %d scopes (%s). A joiner carries the run's accumulated state, "
                + "so the second scope began with the first scope's results already in it; joiners are "
                + "single-use and a fresh instance belongs at every open().",
                s.label, s.scopes.size(), String.join(", ", new TreeSet<>(s.scopes)));
        r.add("ScopeJoinerMisuse", IssueSeverity.CRITICAL, msg,
                Map.of("joiner", s.label, "issue", "joinerReusedAcrossScopes", "scopes", s.scopes.size()));
    }

    private static void racyAccumulation(Report r, JoinerState s) {
        if (s.threadSafe.get() || s.racingWriters.size() < 2) return;
        String msg = String.format(
                "Joiner '%s' had %d threads inside onComplete at once and %d of them wrote its state while "
                + "another onComplete was running. onComplete is invoked on the thread that completed the "
                + "subtask, not on the owner, so a plain collection or counter in the joiner is shared "
                + "mutable state with no lock on it.",
                s.label, s.peakInFlight.get(), s.racingWriters.size());
        r.add("ScopeJoinerMisuse", IssueSeverity.HIGH, msg,
                Map.of("joiner", s.label, "issue", "racyAccumulation",
                       "peakConcurrentOnComplete", s.peakInFlight.get(),
                       "racingWriters", s.racingWriters.size(),
                       "onCompletes", s.onCompletes.get()));
    }

    private static void partialTimeoutRead(Report r, JoinerState s) {
        int overlap = s.timeoutOverlap.get();
        if (overlap < 1) return;
        String msg = String.format(
                "Joiner '%s' entered onTimeout() while %d onComplete call(s) were still running. The JDK 26 "
                + "timeout hook builds its fallback from the joiner's state on the owner thread, so the "
                + "partial result was read out from under threads that were still writing to it.",
                s.label, overlap);
        r.add("ScopeJoinerMisuse", IssueSeverity.HIGH, msg,
                Map.of("joiner", s.label, "issue", "timeoutReadPartialAccumulation",
                       "onCompletesInFlight", overlap));
    }

    private static void offOwner(Report r, JoinerState s) {
        List<String> calls = s.offOwnerCalls();
        if (calls.isEmpty()) return;
        String joined = String.join(", ", calls);
        String msg = String.format(
                "Joiner '%s' had owner-confined method(s) called from another thread: %s. result() and "
                + "onTimeout() run on the thread that opened the scope; calling them elsewhere reads the "
                + "accumulated state with no happens-before edge to the subtasks that produced it.",
                s.label, joined);
        r.add("ScopeJoinerMisuse", IssueSeverity.CRITICAL, msg,
                Map.of("joiner", s.label, "issue", "joinerMethodOffOwnerThread", "calls", joined));
    }

    private static void forkAfterShortCircuit(Report r, JoinerState s) {
        int forks = s.forksAfterCut.get();
        if (forks < 1) return;
        String msg = String.format(
                "Joiner '%s' returned true from onComplete to cancel the scope, and %d fork(s) followed. "
                + "A subtask forked after the short-circuit is cancelled before it can produce anything, "
                + "so the work is started and thrown away.",
                s.label, forks);
        r.add("ScopeJoinerMisuse", IssueSeverity.MEDIUM, msg,
                Map.of("joiner", s.label, "issue", "forkAfterShortCircuit", "forksAfterCancel", forks));
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
            if (violations.isEmpty()) return "SCOPE JOINER MISUSE - clean";
            StringBuilder sb = new StringBuilder("SCOPE JOINER MISUSE DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Why: A StructuredTaskScope.Joiner is called from two directions at once. onComplete runs\n")
              .append("       on whichever subtask thread finished, concurrently with its peers; result() and the\n")
              .append("       JDK 26 onTimeout() run on the owner. JEP 525 turned the timeout path into a way to\n")
              .append("       return a partial result, so state that used to be discarded on timeout is now read.\n")
              .append("  Fix:\n")
              .append("    - Accumulate into a concurrent structure (ConcurrentLinkedQueue, an atomic) or guard\n")
              .append("      the joiner's state with a lock the joiner itself owns\n")
              .append("    - Build the value returned by onTimeout()/result() from a snapshot, not from the live\n")
              .append("      accumulator the subtask threads are still appending to\n")
              .append("    - Construct a fresh joiner for every StructuredTaskScope.open(...) call\n")
              .append("    - Stop forking once onComplete has asked for the short-circuit\n");
            return sb.toString();
        }
    }
}
