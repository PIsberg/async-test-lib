package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * Detects non-atomic compound updates on {@link AtomicInteger}, {@link AtomicLong},
 * {@link AtomicReference}, and similar: using {@code get()} then {@code set()} instead of
 * {@code compareAndSet()}, silently losing concurrent updates.
 *
 * <p>The Atomic* classes guarantee per-operation atomicity, but a {@code get}/{@code compute}/
 * {@code set} sequence is still a non-atomic read-modify-write that races with other threads.
 * The correct pattern is a CAS loop, or the built-in {@code updateAndGet}/{@code getAndUpdate}.
 *
 * <p>Usage inside {@code @AsyncTest(includes = DetectorType.ATOMIC_NON_ATOMIC_UPDATE)}:
 * <pre>{@code
 * var mon = AsyncTestContext.atomicNonAtomicUpdateDetector();
 * int v = counter.get();
 * mon.recordGet(counter, "counter", Thread.currentThread());
 * counter.set(v + 1);   // BUG: concurrent updates from other threads are silently lost
 * mon.recordSet(counter, "counter", Thread.currentThread());
 * }</pre>
 */
public class AtomicNonAtomicUpdateDetector extends AbstractInstanceDetector<AtomicNonAtomicUpdateDetector.AtomicState> {

    /** How many per-thread lines one atomic's woven findings keep; the count stays exact. */
    private static final int MAX_WOVEN_DETAILS = 8;

    static final class AtomicState extends SelfGuard.TrackedInstance {
        final String name;
        final Map<Long, Integer> pendingGetByThread = new ConcurrentHashMap<>();
        final AtomicInteger      nonAtomicUpdates   = new AtomicInteger();
        /** Read-then-store pairs the agent saw two threads of a round make (#1157). */
        final AtomicInteger      wovenUpdates       = new AtomicInteger();
        final List<String>       details            = new CopyOnWriteArrayList<>();
        /** Threads that read then stored through woven calls in the round in progress (#1157). */
        final Set<Long>          roundUpdaters      = ConcurrentHashMap.newKeySet();
        /** Per thread, the locks held at its last woven read that no store has spent yet. */
        final Map<Long, int[]>   wovenReads         = new ConcurrentHashMap<>();
        /** The locks every read-then-store pair of this round held throughout; null before one. */
        final AtomicReference<int @Nullable []> roundPairLocks = new AtomicReference<>();

        AtomicState(String name) { this.name = name; }
    }

    @Override
    AtomicState newState(Object instance, String label) {
        return new AtomicState(label);
    }

    /**
     * Call after {@code atomic.get()}.
     *
     * @param atomic the atomic being recorded, tracked by identity
     * @param name a label identifying the atomic in the report
     * @param thread the thread performing the operation
     */
    public void recordGet(Object atomic, String name, Thread thread) {
        if (atomic == null || thread == null) return;
        AtomicState s = stateFor(atomic, name, "Atomic");
        s.noteAccess(atomic, false);
        s.pendingGetByThread.put(thread.threadId(), 1);
    }

    /**
     * Call after {@code atomic.set()} (a non-CAS write).
     * If the same thread previously called {@link #recordGet} without an intervening CAS,
     * the sequence is flagged as a lost-update race.
     *
     * @param atomic the atomic being recorded, tracked by identity
     * @param name a label identifying the atomic in the report
     * @param thread the thread performing the operation
     */
    public void recordSet(Object atomic, String name, Thread thread) {
        if (atomic == null || thread == null) return;
        AtomicState s = stateFor(atomic, name, "Atomic");
        s.noteAccess(atomic, true);
        Integer pending = s.pendingGetByThread.remove(thread.threadId());
        if (pending != null) {
            s.nonAtomicUpdates.incrementAndGet();
            s.details.add(String.format(
                "Thread '%s' read %s then set it without compareAndSet — update is lost under concurrency",
                thread.getName(), s.name));
        }
    }

    /**
     * Call after a successful {@code atomic.compareAndSet()} — clears the pending-get flag.
     *
     * @param atomic the atomic being recorded, tracked by identity
     * @param name a label identifying the atomic in the report
     * @param thread the thread performing the operation
     */
    public void recordCas(Object atomic, String name, Thread thread) {
        if (atomic == null || thread == null) return;
        stateFor(atomic, name, "Atomic").pendingGetByThread.remove(thread.threadId());
    }

    /**
     * Records a read the agent saw at a call site (#1157).
     *
     * @param atomic the atomic the call site read, tracked by identity
     * @since 1.14.1
     */
    public void agentRead(Object atomic) {
        if (atomic == null) return;
        AtomicState s = stateFor(atomic, null, "Atomic");
        s.noteAccess(atomic, false);
        s.wovenReads.put(Thread.currentThread().threadId(), HeldLocks.intersect(null, atomic, true));
    }

    /**
     * Records a plain store the agent saw at a call site (#1157). Unlike {@link #recordSet}, where
     * the test says it wrote a compound update, this sees every store in the code under test, so
     * two things that are correct must stay silent. A single writer doing {@code set(get() + 1)}
     * while others read loses nothing, so a read then store counts only once a second thread of the
     * round has done the same. And a lock-free read elsewhere says nothing about an update made
     * under a lock, which is how Spring's {@code ConcurrentReferenceHashMap} and Jackson's
     * {@code PrivateMaxEntriesMap} keep their counts: so each pair is judged by the locks it held
     * from its read to its store, not by every access the atomic ever saw.
     *
     * @param atomic the atomic the call site wrote, tracked by identity
     * @since 1.14.1
     */
    public void agentStore(Object atomic) {
        if (atomic == null) return;
        AtomicState s = stateFor(atomic, null, "Atomic");
        s.noteAccess(atomic, true);
        Thread thread = Thread.currentThread();
        int[] atRead = s.wovenReads.remove(thread.threadId());
        if (atRead == null) {
            return;
        }
        int[] heldThroughout = HeldLocks.intersect(atRead, atomic, true);
        int[] commonToPairs = s.roundPairLocks.updateAndGet(common -> common == null
                ? heldThroughout : Lockset.intersect(common, heldThroughout));
        boolean firstThisRound = s.roundUpdaters.add(thread.threadId());
        if (s.roundUpdaters.size() < 2 || commonToPairs.length > 0) {
            return;
        }
        s.wovenUpdates.incrementAndGet();
        if (firstThisRound && s.details.size() < MAX_WOVEN_DETAILS) {
            s.details.add(String.format(
                "Thread '%s' read %s then set it, as another thread did in the same round, "
                    + "without compareAndSet", thread.getName(), s.name));
        }
    }

    /**
     * Records an atomic update the agent saw at a call site, which ends a pending read (#1157).
     *
     * @param atomic the atomic the call site updated, tracked by identity
     * @since 1.14.1
     */
    public void agentSwap(Object atomic) {
        if (atomic == null) return;
        stateFor(atomic, null, "Atomic").wovenReads.remove(Thread.currentThread().threadId());
    }

    /**
     * Closes the round in progress: a get left pending on a pool thread must not pair with a
     * set that the same thread makes in the next round, which the runner orders after it.
     *
     * @since 1.11.2
     */
    public void markInvocationStart() {
        for (AtomicState s : states()) {
            s.pendingGetByThread.clear();
            s.wovenReads.clear();
            s.roundUpdaters.clear();
            s.roundPairLocks.set(null);
        }
    }

    /**
     * {@return report of non-atomic compound updates}
     */
    public AtomicNonAtomicUpdateReport analyze() {
        AtomicNonAtomicUpdateReport r = new AtomicNonAtomicUpdateReport();
        for (AtomicState s : states()) {
            // A get+set pair inside a critical section every access of its round shares is
            // excluded from the interleaving this detector names, so the lockset decides, as for
            // the Shared* family. Judged per round: the runner orders rounds, so a different lock
            // in each round loses no update. A single thread's get+set still counts, as it always
            // has: the pattern is the finding, and the next thread only has to arrive.
            if (s.nonAtomicUpdates.get() > 0 && s.sawUnguardedRound()) {
                String finding = String.format("%s: %d non-atomic get+set sequence(s) detected" + SelfGuard.REPORT_NOTE,
                    s.name, s.nonAtomicUpdates.get());
                r.violations.add(finding);
                r.structuredViolations.add(new Violation("AtomicNonAtomicUpdate", IssueSeverity.HIGH,
                        finding, List.of(), Map.of(), Instant.now()));
                r.grades.add(new GradedFindings.Grade(IssueSeverity.HIGH, TrustTier.VERDICT, finding,
                        DetectorTrust.Evidence.CONTEXTUAL));
                r.details.addAll(s.details);
            }
            // The agent sees calls and not values, so it cannot tell set(get() + 1) from a thread
            // that reads and then stores something unrelated. That is a prompt to look, not a
            // verdict: the test's own record call is what says an update was compound.
            if (s.wovenUpdates.get() > 0 && s.sawUnguardedRound()) {
                String finding = String.format("%s: %d get+set sequence(s) from two threads of a round, "
                    + "with no lock held across both" + SelfGuard.REPORT_NOTE,
                    s.name, s.wovenUpdates.get());
                r.violations.add(finding);
                r.structuredViolations.add(new Violation("AtomicNonAtomicUpdate", IssueSeverity.HIGH,
                        finding, List.of(), Map.of(), Instant.now()));
                r.grades.add(new GradedFindings.Grade(IssueSeverity.HIGH, TrustTier.PROMPT, finding,
                        DetectorTrust.Evidence.HEURISTIC));
                r.details.addAll(s.details);
            }
        }
        return DetectorFailurePolicy.checkedReport(this, r);
    }

    /** Report produced by {@link #analyze()}. */
    public static class AtomicNonAtomicUpdateReport implements GradedFindings {
        final List<String> violations = new ArrayList<>();
        final List<GradedFindings.Grade> grades = new ArrayList<>();
        /** The findings as Violations, at the severity the text resolved to (#801). */
        public final List<Violation> structuredViolations = new ArrayList<>();
        final List<String> details    = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        /**
         * A recorded get and set is the test saying an update was compound, so it stays a verdict.
         * A woven pair is a call shape, so it is a prompt, on {@code HEURISTIC} evidence (#1157).
         */
        @Override
        public List<GradedFindings.Grade> grades() { return List.copyOf(grades); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("ATOMIC NON-ATOMIC UPDATE DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append("\n");
            for (String d : details)    sb.append("    * ").append(d).append("\n");
            sb.append("  Why: A get() followed by set() on an AtomicXxx is not atomic as a compound operation — another thread\n")
              .append("       can write a new value between the get() and the set(), and that write is silently overwritten by\n")
              .append("       the set(). The result appears correct per-thread but loses the other thread's update entirely.\n")
              .append("  Fix:\n")
              .append("    - Use ref.updateAndGet(v -> v + delta) for read-modify-write on a single field\n")
              .append("    - Use compareAndSet() in a retry loop when multiple fields must change together atomically:\n")
              .append("        do { old = ref.get(); newVal = compute(old); } while (!ref.compareAndSet(old, newVal));\n")
              .append("    - For pure counters, LongAdder gives better throughput under high contention");
            return sb.toString();
        }
    }
}
