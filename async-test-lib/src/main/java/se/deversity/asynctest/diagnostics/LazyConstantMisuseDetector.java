package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;
import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

/**
 * Detects misuse of Java 26+ {@code LazyConstant} (Lazy Constants, second preview
 * in JDK 26 — the renamed and radically simplified successor of the JDK 25
 * {@code StableValue} preview, JEP 502).
 *
 * <p>{@code LazyConstant<T>} is created with {@code LazyConstant.of(supplier)} and
 * computed on first {@code get()}: the supplier runs <strong>at most once</strong>,
 * every later {@code get()} returns the cached result, and the JVM may then
 * constant-fold the value like a {@code final} field. The low-level
 * {@code trySet()} / {@code setOrThrow()} / {@code orElseSet()} methods of the old
 * {@code StableValue} API were removed; lazy collections moved to
 * {@code List.ofLazy(size, fn)} and {@code Map.ofLazy(keys, fn)}. Null values are
 * no longer permitted — a null-producing supplier throws
 * {@code NullPointerException}.
 *
 * <p>The at-most-once guarantee only protects code that goes through the real
 * {@code LazyConstant}; the classic mistakes migrate into the supplier itself.
 * This detector flags:
 *
 * <p><strong>Issues detected:</strong>
 * <ul>
 *   <li><b>Reentrant computation</b> — the supplier reads the <em>same</em>
 *       constant (directly or transitively) while it is still computing. The JDK
 *       throws {@code IllegalStateException} to break the cycle; a hand-rolled
 *       holder recurses forever.</li>
 *   <li><b>Null-producing supplier</b> — the supplier completed with {@code null}.
 *       JDK 26 {@code LazyConstant} throws {@code NullPointerException} here
 *       (unlike the JDK 25 {@code StableValue} preview, which allowed null).</li>
 *   <li><b>Supplier ran more than once</b> — the computation completed multiple
 *       times for one constant. The real API guarantees at-most-once, so this
 *       indicates a hand-rolled "lazy" holder that lost the race.</li>
 *   <li><b>Non-deterministic supplier</b> — two completed computations produced
 *       values that are not {@code equals()}. Which value the program sees then
 *       depends on thread timing ({@code Map.ofLazy}/{@code List.ofLazy} mapping
 *       functions must be deterministic).</li>
 *   <li><b>Compute convoy</b> (warning) — many distinct threads piled up in
 *       {@code get()} while the first computation was still running. Correct but
 *       a latency smell: every caller blocks on one slow supplier.</li>
 * </ul>
 *
 * <p><strong>Usage:</strong>
 * <pre>{@code
 * private static final LazyConstant<Config> CONFIG =
 *         LazyConstant.of(() -> loadConfig());
 *
 * @AsyncTest(threads = 16)
 * void testLazyConfig() {
 *     var detector = AsyncTestContext.lazyConstantMisuseDetector();
 *     String name = "CONFIG";
 *
 *     detector.recordGet(name, Thread.currentThread());
 *     detector.recordComputeStart(name, Thread.currentThread());
 *     Config c = loadConfig();                       // the supplier body
 *     detector.recordComputeEnd(name, Thread.currentThread(), c);
 *     use(c);
 * }
 * }</pre>
 *
 * <p><strong>Naming the constant.</strong> Each record method has an overload that also takes the
 * {@code LazyConstant} itself, and that one is the more precise: a name only labels a constant, so
 * two constants under one name are judged as one and one constant under two names as two. Keyed
 * by the name, a constant's state lasts one round, since a name reused by a fresh constant each
 * round would otherwise read as computed once per round, and so a static constant computed in each
 * of two rounds is missed. Keyed by the constant, its state lasts the run: that second computation
 * is reported, while a constant built per round is a new constant, and the convoy is still judged
 * per round. Use one form or the other for any one constant; the two are tracked apart.
 *
 * @since 1.7.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "Per-constant state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id sets are ConcurrentHashMap.newKeySet(); reports are synchronized lists.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/LazyConstantMisuseDetectorTest.java"
)
public class LazyConstantMisuseDetector {

    /** Above this many distinct threads blocked behind one in-flight computation, we warn. */
    private static final int CONVOY_THRESHOLD = 4;

    /** Sentinel so a legitimate first result of {@code null} is distinguishable from "no result yet". */
    private static final Object NO_RESULT = new Object();

    private static final class State {
        final AtomicBoolean computed = new AtomicBoolean(false);
        final AtomicInteger completedComputes = new AtomicInteger(0);
        final AtomicInteger activeComputes = new AtomicInteger(0);
        final Set<Long> computingThreadIds = ConcurrentHashMap.newKeySet();
        final Set<Long> convoyThreadIds = ConcurrentHashMap.newKeySet();
        final AtomicBoolean convoyReported = new AtomicBoolean(false);
        // First completed result, for the determinism check. Guarded by synchronized(this)
        // in recordComputeEnd — completions are rare (at most once when used correctly).
        @Nullable Object firstResult = NO_RESULT;
    }

    /**
     * Per-constant state, keyed by the caller-supplied name for the current round, or by the
     * constant's {@link IdentityKey} for the whole run when the caller names the constant.
     */
    private final Map<Object, State> states = new ConcurrentHashMap<>();

    /** Names or constants already warned about for a convoy, so the warning is made once each. */
    private final Set<Object> convoyWarned = ConcurrentHashMap.newKeySet();

    // Per-thread set of constant keys (names or IdentityKeys) whose computation is running.
    private final Map<Long, Set<Object>> activeComputations = new ConcurrentHashMap<>();

    private final List<String> reentrantIssues        = Collections.synchronizedList(new ArrayList<>());
    private final List<String> nullValueIssues        = Collections.synchronizedList(new ArrayList<>());
    private final List<String> multipleComputeIssues  = Collections.synchronizedList(new ArrayList<>());
    private final List<String> nonDeterministicIssues = Collections.synchronizedList(new ArrayList<>());
    private final List<String> convoyWarnings         = Collections.synchronizedList(new ArrayList<>());

    private final AtomicInteger totalGets     = new AtomicInteger(0);
    private final AtomicInteger totalComputes = new AtomicInteger(0);

    /**
     * Closes the previous invocation round: clears the per-thread in-flight state it left
     * over, and the per-holder state a name reused in the next round would otherwise carry.
     *
     * <p>Called by {@code ConcurrencyRunner} before each round, after the previous round's
     * workers have all finished, so nothing is legitimately in flight when it runs.
     *
     * <p>The record methods are paired, and a {@code recordComputeStart} whose matching
     * {@code recordComputeEnd} never runs - because the caller's supplier threw and the caller had no
     * {@code finally} - leaves an entry behind. Platform worker threads are pooled, so the same
     * thread comes back for the next round and that stale entry made the next round's first
     * computation look like a supplier re-entering itself. Clearing here bounds the damage to the round that
     * produced it (#498).
     *
     * @since 1.11.2
     */
    public void markInvocationStart() {
        activeComputations.clear();
        // The constant's state is per object, and a name is all the detector has. A test that
        // builds its LazyConstant per round reuses the name for a new, uncomputed constant, and
        // carrying the old state over counted each round's one computation as a repeat and each
        // round's value as a disagreement. Every finding is already in its report list, so the
        // state can go; a repeat computation is judged within a round. A constant the caller
        // named is the same constant next round, so what it computed stays; what was in flight,
        // and who queued behind it, belonged to the round that ended.
        states.entrySet().removeIf(e -> {
            if (!(e.getKey() instanceof IdentityKey)) {
                return true;
            }
            State s = e.getValue();
            s.activeComputes.set(0);
            s.computingThreadIds.clear();
            s.convoyThreadIds.clear();
            return false;
        });
    }

    private State stateFor(Object key) {
        State s = states.get(key);
        if (s == null) {
            s = states.computeIfAbsent(key, k -> new State());
        }
        return s;
    }

    private static String labelOf(IdentityKey key, @Nullable String name) {
        return name != null ? name : key.toString();
    }

    /**
     * Record a {@code get()} on the constant. Gets that arrive while another
     * thread's computation is still in flight are counted toward the convoy
     * warning.
     *
     * @param name   a descriptive name for the LazyConstant (e.g. field name)
     * @param thread the current thread
     */
    public void recordGet(String name, Thread thread) {
        if (name == null || thread == null) return;
        noteGet(name, name, thread);
    }

    /**
     * Record a {@code get()} on {@code constant}. Gets that arrive while another thread's
     * computation of that constant is in flight, in this round, count toward the convoy warning.
     *
     * @param constant the {@code LazyConstant} being read, which identifies it;
     *                 {@code null} records by {@code name} alone, as
     *                 {@link #recordGet(String, Thread)} does
     * @param name     the label the report prints for the constant, or {@code null} to print
     *                 its class and identity hash
     * @param thread   the current thread
     * @since 1.12.3
     */
    @API(status = Status.EXPERIMENTAL)
    public void recordGet(@Nullable Object constant, @Nullable String name, Thread thread) {
        if (constant == null) {
            if (name != null) recordGet(name, thread);
        } else if (thread != null) {
            IdentityKey key = IdentityKey.lookup(constant);
            noteGet(key, labelOf(key, name), thread);
        }
    }

    private void noteGet(Object key, String name, Thread thread) {
        totalGets.incrementAndGet();
        State s = stateFor(key);
        if (!s.computed.get() && s.activeComputes.get() > 0
                && !s.computingThreadIds.contains(thread.threadId())) {
            s.convoyThreadIds.add(thread.threadId());
            if (s.convoyThreadIds.size() >= CONVOY_THRESHOLD
                    && s.convoyReported.compareAndSet(false, true)
                    && convoyWarned.add(key)) {
                convoyWarnings.add(
                    "LazyConstant '" + name + "': " + s.convoyThreadIds.size()
                    + " distinct threads blocked in get() behind one in-flight computation. "
                    + "Correct, but every caller pays for the slow supplier — consider "
                    + "computing eagerly at startup or trimming the supplier."
                );
            }
        }
    }

    /**
     * Record the start of the constant's supplier computation. If the same
     * constant's supplier is already running on this thread, the supplier is
     * reading the value it is meant to produce (reentrant computation).
     *
     * <p>Pair this with {@code recordComputeEnd} in a {@code finally}. A supplier or mapping
     * function that throws past an unpaired start leaves the entry in flight, and every later
     * record on this thread then reads as re-entrancy. {@link #markInvocationStart()} bounds
     * that to the round it happened in; only a {@code finally} prevents it inside one.
     *
     * @param name   a descriptive name for the LazyConstant
     * @param thread the current thread
     */
    public void recordComputeStart(String name, Thread thread) {
        if (name == null || thread == null) return;
        noteComputeStart(name, name, thread);
    }

    /**
     * Record the start of {@code constant}'s supplier computation. It is reentrant when that
     * constant's supplier is already running on this thread, under any name; another constant's
     * supplier under the same name is not. Pair it with
     * {@link #recordComputeEnd(Object, String, Thread, Object)} in a {@code finally}, for the
     * reason {@link #recordComputeStart(String, Thread)} gives.
     *
     * @param constant the {@code LazyConstant} whose supplier starts, which identifies it;
     *                 {@code null} records by {@code name} alone, as
     *                 {@link #recordComputeStart(String, Thread)} does
     * @param name     the label the report prints for the constant, or {@code null} to print
     *                 its class and identity hash
     * @param thread   the current thread
     * @since 1.12.3
     */
    @API(status = Status.EXPERIMENTAL)
    public void recordComputeStart(@Nullable Object constant, @Nullable String name, Thread thread) {
        if (constant == null) {
            if (name != null) recordComputeStart(name, thread);
        } else if (thread != null) {
            IdentityKey key = IdentityKey.lookup(constant);
            noteComputeStart(key, labelOf(key, name), thread);
        }
    }

    private void noteComputeStart(Object key, String name, Thread thread) {
        long tid = thread.threadId();
        State s = stateFor(key);
        s.activeComputes.incrementAndGet();
        s.computingThreadIds.add(tid);
        Set<Object> running = activeComputations.computeIfAbsent(tid, k -> ConcurrentHashMap.newKeySet());
        if (!running.add(key)) {
            reentrantIssues.add(
                "Thread " + thread.getName() + " (id=" + tid + "): "
                + "LazyConstant '" + name + "' supplier re-entered the same constant while it "
                + "was still computing. The JDK throws IllegalStateException to break the "
                + "cycle; a hand-rolled holder would recurse infinitely. Remove the "
                + "self-reference from the supplier."
            );
        }
    }

    /**
     * Record the completion of the constant's supplier computation.
     *
     * @param name   a descriptive name for the LazyConstant
     * @param thread the current thread
     * @param result the value the supplier produced (may be {@code null}, which is
     *               itself reported: JDK 26 {@code LazyConstant} rejects null)
     */
    public void recordComputeEnd(String name, Thread thread, Object result) {
        if (name == null || thread == null) return;
        noteComputeEnd(name, name, thread, result);
    }

    /**
     * Record the completion of {@code constant}'s supplier computation. A second completion for
     * the same constant, in any round and under any name, is a repeat computation, and one whose
     * value is not {@code equals()} to the first is non-deterministic.
     *
     * @param constant the {@code LazyConstant} whose supplier completed, which identifies it;
     *                 {@code null} records by {@code name} alone, as
     *                 {@link #recordComputeEnd(String, Thread, Object)} does
     * @param name     the label the report prints for the constant, or {@code null} to print
     *                 its class and identity hash
     * @param thread   the current thread
     * @param result   the value the supplier produced (may be {@code null}, which is itself
     *                 reported: JDK 26 {@code LazyConstant} rejects null)
     * @since 1.12.3
     */
    @API(status = Status.EXPERIMENTAL)
    public void recordComputeEnd(@Nullable Object constant, @Nullable String name, Thread thread,
                                 @Nullable Object result) {
        if (constant == null) {
            if (name != null && thread != null) noteComputeEnd(name, name, thread, result);
        } else if (thread != null) {
            IdentityKey key = IdentityKey.lookup(constant);
            noteComputeEnd(key, labelOf(key, name), thread, result);
        }
    }

    @SuppressWarnings("ReferenceEquality") // NO_RESULT is a unique sentinel; identity is the point, not value equality
    private void noteComputeEnd(Object key, String name, Thread thread, @Nullable Object result) {
        totalComputes.incrementAndGet();
        long tid = thread.threadId();
        Set<Object> running = activeComputations.get(tid);
        if (running != null) {
            running.remove(key);
        }
        State s = stateFor(key);
        s.activeComputes.decrementAndGet();

        if (result == null) {
            nullValueIssues.add(
                "Thread " + thread.getName() + " (id=" + tid + "): "
                + "LazyConstant '" + name + "' supplier produced null — JDK 26 LazyConstant "
                + "throws NullPointerException here (the JDK 25 StableValue preview allowed "
                + "null; the replacement does not). Return a non-null sentinel or use an "
                + "Optional-valued constant."
            );
        }

        int completions = s.completedComputes.incrementAndGet();
        if (completions > 1) {
            multipleComputeIssues.add(
                "Thread " + thread.getName() + " (id=" + tid + "): "
                + "LazyConstant '" + name + "' computation completed " + completions
                + " times. The real LazyConstant.of(supplier) guarantees at-most-once "
                + "execution — multiple completions indicate a hand-rolled lazy holder "
                + "whose threads raced past each other. Use LazyConstant (or a proper "
                + "double-checked idiom) instead."
            );
        }

        synchronized (s) {
            if (s.firstResult == NO_RESULT) {
                s.firstResult = result;
            } else if (!Objects.equals(s.firstResult, result)) {
                nonDeterministicIssues.add(
                    "LazyConstant '" + name + "': two computations produced different values ("
                    + summarize(s.firstResult) + " vs " + summarize(result) + "). The stored "
                    + "value now depends on thread timing — suppliers and List.ofLazy/"
                    + "Map.ofLazy mapping functions must be deterministic."
                );
            }
        }

        s.computed.set(true);
    }


    private static String summarize(@Nullable Object o) {
        if (o == null) return "null";
        String str = String.valueOf(o);
        return str.length() > 40 ? str.substring(0, 37) + "..." : str;
    }

    /**
     * Analyze all recorded LazyConstant events for misuse patterns.
     *
     * @return a report describing detected issues
     */
    public LazyConstantMisuseReport analyze() {
        return new LazyConstantMisuseReport(
            new ArrayList<>(reentrantIssues),
            new ArrayList<>(nullValueIssues),
            new ArrayList<>(multipleComputeIssues),
            new ArrayList<>(nonDeterministicIssues),
            new ArrayList<>(convoyWarnings),
            totalGets.get(),
            totalComputes.get()
        );
    }

    /**
     * Report of LazyConstant misuse analysis.
     */
    public static class LazyConstantMisuseReport {
        private final List<String> reentrantIssues;
        private final List<String> nullValueIssues;
        private final List<String> multipleComputeIssues;
        private final List<String> nonDeterministicIssues;
        private final List<String> convoyWarnings;
        private final int totalGets;
        private final int totalComputes;

        LazyConstantMisuseReport(
                List<String> reentrantIssues,
                List<String> nullValueIssues,
                List<String> multipleComputeIssues,
                List<String> nonDeterministicIssues,
                List<String> convoyWarnings,
                int totalGets,
                int totalComputes) {
            this.reentrantIssues = reentrantIssues;
            this.nullValueIssues = nullValueIssues;
            this.multipleComputeIssues = multipleComputeIssues;
            this.nonDeterministicIssues = nonDeterministicIssues;
            this.convoyWarnings = convoyWarnings;
            this.totalGets = totalGets;
            this.totalComputes = totalComputes;
        }

        /**
         * {@return true if any correctness-affecting LazyConstant misuse was detected}
         */
        public boolean hasIssues() {
            return !reentrantIssues.isEmpty()
                || !nullValueIssues.isEmpty()
                || !multipleComputeIssues.isEmpty()
                || !nonDeterministicIssues.isEmpty();
        }

        /**
         * {@return the reentrant issues}
         */
        public List<String> getReentrantIssues()        { return Collections.unmodifiableList(reentrantIssues); }
        /**
         * {@return the null value issues}
         */
        public List<String> getNullValueIssues()        { return Collections.unmodifiableList(nullValueIssues); }
        /**
         * {@return the multiple compute issues}
         */
        public List<String> getMultipleComputeIssues()  { return Collections.unmodifiableList(multipleComputeIssues); }
        /**
         * {@return the non deterministic issues}
         */
        public List<String> getNonDeterministicIssues() { return Collections.unmodifiableList(nonDeterministicIssues); }
        /**
         * {@return the convoy warnings}
         */
        public List<String> getConvoyWarnings()         { return Collections.unmodifiableList(convoyWarnings); }
        /**
         * {@return the total gets}
         */
        public int          getTotalGets()              { return totalGets; }
        /**
         * {@return the total computes}
         */
        public int          getTotalComputes()          { return totalComputes; }

        @Override
        public String toString() {
            if (!hasIssues() && convoyWarnings.isEmpty()) {
                return "LazyConstantMisuseReport: No LazyConstant misuse detected";
            }

            StringBuilder sb = new StringBuilder();

            if (!reentrantIssues.isEmpty()) {
                sb.append(IssueSeverity.CRITICAL.format())
                  .append(": LazyConstant supplier re-entered itself (IllegalStateException / infinite recursion)\n");
            } else if (!nullValueIssues.isEmpty() || !multipleComputeIssues.isEmpty()
                    || !nonDeterministicIssues.isEmpty()) {
                sb.append(IssueSeverity.HIGH.format())
                  .append(": LazyConstant supplier contract violated (null value / repeat computation / non-determinism)\n");
            } else {
                sb.append(IssueSeverity.LOW.format())
                  .append(": LazyConstant usage warnings\n");
            }

            sb.append("  Gets=").append(totalGets)
              .append(", Computes=").append(totalComputes).append("\n");

            ReportSections.appendSection(sb, "Reentrant computation (IllegalStateException / infinite recursion)", reentrantIssues);
            ReportSections.appendSection(sb, "Null-producing supplier (NullPointerException on JDK 26)", nullValueIssues);
            ReportSections.appendSection(sb, "Computation ran more than once (at-most-once contract broken)", multipleComputeIssues);
            ReportSections.appendSection(sb, "Non-deterministic supplier (stored value depends on timing)", nonDeterministicIssues);
            ReportSections.appendSection(sb, "Compute convoy (callers blocked behind a slow supplier)", convoyWarnings);

            sb.append("\n\n").append("=".repeat(60));
            sb.append("\n").append(getLearningContent());
            sb.append("=".repeat(60));

            return sb.toString();
        }

        private static String getLearningContent() {
            return """
                📚 LEARNING: LazyConstant (Java 26+, second preview — successor of StableValue)

                LazyConstant<T> holds a value computed on first get(): the supplier runs
                at most once, later gets return the cached result, and the JVM may
                constant-fold it like a final field. The StableValue low-level methods
                (trySet / setOrThrow / orElseSet) were removed; lazy collections moved to
                List.ofLazy(size, fn) and Map.ofLazy(keys, fn); null values now throw NPE.

                Correct usage:
                  static final LazyConstant<Config> CONFIG =
                          LazyConstant.of(() -> loadConfig());

                  Config c = CONFIG.get();   // computes once, cached forever

                Common mistakes:
                  ✗ Supplier that reads the same constant → IllegalStateException / recursion
                  ✗ Supplier that returns null → NullPointerException (allowed in the old
                    StableValue preview, rejected by LazyConstant)
                  ✗ Hand-rolled lazy holders that run the computation more than once
                  ✗ Non-deterministic suppliers / ofLazy mapping functions — the stored
                    value then depends on which thread computed it
                  ✗ A slow supplier convoys every get()-caller behind the first thread

                Rule of thumb:
                  • Keep the supplier pure, deterministic, non-null and fast.
                  • Let LazyConstant do the laziness — do not roll your own holder.
                """;
        }
    }
}
