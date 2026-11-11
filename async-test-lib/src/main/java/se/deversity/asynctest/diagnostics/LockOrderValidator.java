package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects lock ordering violations that can cause deadlocks.
 *
 * <p>Problem: if different threads acquire locks in different orders, deadlock can occur:
 * thread A takes {@code L1} then {@code L2}, thread B takes {@code L2} then {@code L1}.
 *
 * <p>Each thread's nesting edges are recorded at acquisition time, while what it holds is still
 * known. An edge {@code held -> taken} is kept with the thread that made it and the other locks
 * that thread held at the time, its guard set. A pair is reported only when its two orders come
 * from different threads under disjoint guard sets, and a longer cycle only when its edges come
 * from pairwise different threads under pairwise disjoint guard sets (#1242). That is the
 * Goodlock rule: two nestings made under one common outer lock, a gate, cannot be in progress at
 * once, and one thread cannot deadlock with itself.
 *
 * <p>Only an acquisition that can block makes an edge into the lock it takes. A successful
 * {@code tryLock} never waits, so it can never be the waiting side of a cycle; it still counts as
 * held, so a blocking acquisition made inside it is an edge out of it. Re-entering a lock the
 * thread already holds cannot block either and makes no edge. Holds are counted, so the first
 * release of a reentrant hold leaves the lock held (#1244).
 *
 * <p>With the agent, {@code synchronized} blocks and methods are delivered too (#1243). A
 * synchronized method's exit is not woven on an exception, so a method monitor's hold is
 * re-confirmed with {@link Thread#holdsLock} before it is allowed to nest anything, and
 * {@code Object.wait} hands every hold of its monitor back and takes them again after.
 *
 * <p>What it cannot see, and therefore may still report: two threads whose opposite nestings are
 * ordered by something other than a common lock, a join, a latch, a hand-off. Those cannot
 * deadlock either. The cycle search is bounded in length and work, which can only drop a finding,
 * never add one.
 */
public class LockOrderValidator {

    /** Labels for objects the test gave no name, numbered per kind within this detector (#860). */
    private final UnnamedLabels unnamedLabels = new UnnamedLabels();

    /**
     * The most distinct guard sets one thread keeps for one edge. Past it the thread's edge is
     * recorded as unguarded, which can add a finding but never hide one.
     */
    static final int MAX_GUARDS_PER_EDGE = 4;

    /** The longest cycle searched for, in locks. */
    private static final int MAX_CYCLE_LENGTH = 8;

    /** Edge records the cycle search may visit in one analysis before it stops looking. */
    private static final int CYCLE_SEARCH_BUDGET = 200_000;

    private static final IdentityKey[] NO_GUARD = new IdentityKey[0];

    /**
     * One lock acquired while another was already held: {@code from} nests {@code to}.
     *
     * <p>Locks are {@link IdentityKey}s, not labels. The label was class name plus identity hash,
     * and two live locks share an identity hash often enough to matter: keyed by that label, one
     * thread nesting {@code X1} inside {@code A} and another nesting {@code A} inside {@code X2}
     * read as one pair taken both ways round, an inversion and a deadlock cycle that three
     * distinct locks cannot form.
     */
    private record LockEdge(IdentityKey from, IdentityKey to) {
        LockEdge reversed() {
            return new LockEdge(to, from);
        }
    }

    /** One thread's record of one edge: which thread made it, holding what else. */
    private static final class EdgeRecord {
        final long threadId;
        final IdentityKey[] guard;

        EdgeRecord(long threadId, IdentityKey[] guard) {
            this.threadId = threadId;
            this.guard = guard;
        }

        /** Whether this edge and {@code other} could be in progress at once. */
        boolean concurrentWith(EdgeRecord other) {
            return threadId != other.threadId && disjoint(guard, other.guard);
        }
    }

    /** The guard sets one thread made one edge under. Guarded by the owning sequence's monitor. */
    private static final class EdgeGuards {
        final IdentityKey to;
        final List<IdentityKey[]> guards = new ArrayList<>(2);

        EdgeGuards(IdentityKey to) {
            this.to = to;
        }
    }

    /**
     * One thread's holds and edges. Written only by its own thread, under its own monitor, which
     * analysis also takes, so the arrays need nothing stronger.
     */
    private static final class LockSequence {
        final long threadId;
        /** Locks this thread holds right now, oldest first. The only sound basis for an edge. */
        private Object[] heldLocks = new Object[8];
        /** A key per held lock, made the first time an edge needs one. */
        private @Nullable IdentityKey[] heldKeys = new IdentityKey[8];
        private int[] holdCounts = new int[8];
        /** Whether the hold must be re-confirmed with {@link Thread#holdsLock} before use. */
        private boolean[] confirm = new boolean[8];
        private int size;
        private int confirmed;
        /**
         * Edges observed on this thread, by outer then inner lock: recorded at acquisition time,
         * when we can still see what was held. Deriving them afterwards from a flat acquisition
         * history cannot work: consecutive entries are not necessarily nested, and a released
         * lock leaves no trace.
         */
        final Map<IdentityKey, Map<IdentityKey, EdgeGuards>> edges = new ConcurrentHashMap<>();

        LockSequence(long threadId) {
            this.threadId = threadId;
        }

        @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"}) // locks are tracked by identity
        int indexOf(Object lock) {
            for (int i = size - 1; i >= 0; i--) {
                if (heldLocks[i] == lock) {
                    return i;
                }
            }
            return -1;
        }

        IdentityKey keyAt(int index) {
            IdentityKey key = heldKeys[index];
            if (key == null) {
                key = new IdentityKey(heldLocks[index]);
                heldKeys[index] = key;
            }
            return key;
        }

        /** Drops the method-monitor holds this thread no longer has: an exception left the method. */
        void dropStaleHolds() {
            if (confirmed == 0) {
                return;
            }
            for (int i = size - 1; i >= 0; i--) {
                if (confirm[i] && !Thread.holdsLock(heldLocks[i])) {
                    removeAt(i);
                }
            }
        }

        void acquire(Object lock, boolean blocking, boolean confirmHold) {
            dropStaleHolds();
            int index = indexOf(lock);
            if (index >= 0) {
                // Re-entering a held lock cannot block, so nothing is nested by it.
                holdCounts[index]++;
                markConfirm(index, confirmHold);
                return;
            }
            IdentityKey key = null;
            if (blocking) {
                // Every lock still held is nested by the one we are taking now: "while holding
                // `held`, this thread wants `lock`", the relation that deadlocks when another
                // thread does the reverse. Locks already released impose no ordering.
                for (int held = 0; held < size; held++) {
                    key = recordEdge(held, lock, key);
                }
            }
            push(lock, key, confirmHold);
        }

        private IdentityKey recordEdge(int held, Object lock, @Nullable IdentityKey known) {
            IdentityKey from = keyAt(held);
            Map<IdentityKey, EdgeGuards> out = edges.get(from);
            if (out == null) {
                out = edges.computeIfAbsent(from, k -> new ConcurrentHashMap<>(4));
            }
            EdgeGuards guards = out.get(known != null ? known : IdentityKey.lookup(lock));
            IdentityKey key = known;
            if (guards == null) {
                IdentityKey made = key != null ? key : new IdentityKey(lock);
                guards = out.computeIfAbsent(made, EdgeGuards::new);
                key = guards.to;
            } else if (key == null) {
                key = guards.to;
            }
            recordGuard(guards, held);
            return key;
        }

        /** Adds the locks held now, other than {@code skip}, as a guard set of the edge. */
        private void recordGuard(EdgeGuards edge, int skip) {
            List<IdentityKey[]> guards = edge.guards;
            for (int i = 0; i < guards.size(); i++) { // NOPMD ForLoopCanBeForeach - no Iterator on the record path
                if (isGuardNow(guards.get(i), skip)) {
                    return;
                }
            }
            if (guards.size() < MAX_GUARDS_PER_EDGE) {
                guards.add(guardNow(skip));
            } else if (!guards.contains(NO_GUARD)) {
                guards.add(NO_GUARD); // too many to keep: unguarded can only add a finding
            }
        }

        @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"}) // locks are tracked by identity
        private boolean isGuardNow(IdentityKey[] guard, int skip) {
            if (guard.length != size - 1) {
                return false;
            }
            for (int i = 0; i < size; i++) {
                if (i != skip && !containsReferent(guard, heldLocks[i])) {
                    return false;
                }
            }
            return true;
        }

        private IdentityKey[] guardNow(int skip) {
            if (size == 1) {
                return NO_GUARD;
            }
            IdentityKey[] guard = new IdentityKey[size - 1];
            int at = 0;
            for (int i = 0; i < size; i++) {
                if (i != skip) {
                    guard[at] = keyAt(i);
                    at++;
                }
            }
            return guard;
        }

        private void push(Object lock, @Nullable IdentityKey key, boolean confirmHold) {
            if (size == heldLocks.length) {
                int grown = size * 2;
                heldLocks = java.util.Arrays.copyOf(heldLocks, grown);
                heldKeys = java.util.Arrays.copyOf(heldKeys, grown);
                holdCounts = java.util.Arrays.copyOf(holdCounts, grown);
                confirm = java.util.Arrays.copyOf(confirm, grown);
            }
            heldLocks[size] = lock;
            heldKeys[size] = key;
            holdCounts[size] = 1;
            confirm[size] = false;
            size++;
            markConfirm(size - 1, confirmHold);
        }

        private void markConfirm(int index, boolean confirmHold) {
            if (confirmHold && !confirm[index]) {
                confirm[index] = true;
                confirmed++;
            }
        }

        void release(Object lock) {
            int index = indexOf(lock);
            if (index < 0) {
                return;
            }
            holdCounts[index]--;
            if (holdCounts[index] == 0) {
                removeAt(index);
            }
        }

        int releaseAll(Object lock) {
            int index = indexOf(lock);
            if (index < 0) {
                return 0;
            }
            int holds = holdCounts[index];
            removeAt(index);
            return holds;
        }

        void setHolds(Object lock, int holds) {
            int index = indexOf(lock);
            if (index >= 0) {
                holdCounts[index] = holds;
            }
        }

        private void removeAt(int index) {
            if (confirm[index]) {
                confirmed--;
            }
            int tail = size - index - 1;
            System.arraycopy(heldLocks, index + 1, heldLocks, index, tail);
            System.arraycopy(heldKeys, index + 1, heldKeys, index, tail);
            System.arraycopy(holdCounts, index + 1, holdCounts, index, tail);
            System.arraycopy(confirm, index + 1, confirm, index, tail);
            size--;
            heldLocks[size] = null;
            heldKeys[size] = null;
            confirm[size] = false;
        }
    }

    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"}) // locks are tracked by identity
    private static boolean containsReferent(IdentityKey[] keys, Object lock) {
        for (IdentityKey key : keys) {
            if (key.referent() == lock) {
                return true;
            }
        }
        return false;
    }

    private static boolean disjoint(IdentityKey[] first, IdentityKey[] second) {
        for (IdentityKey key : first) {
            if (containsReferent(second, key.referent())) {
                return false;
            }
        }
        return true;
    }

    /**
     * How the report names each lock it has printed: its class and a number, assigned by the first
     * report that names the lock and kept for every later one. Filled only by analysis, so the
     * record path never touches it.
     */
    private final Map<IdentityKey, String> labels = new ConcurrentHashMap<>();

    /**
     * {@return how the report names {@code lock}}, such as {@code ReentrantLock@4}. Not the identity
     * hash it was: two locks share one often enough that a report printed two inversions as one line
     * and two locks on a cycle as one (#854).
     */
    private String label(IdentityKey lock) {
        return labels.computeIfAbsent(lock, k -> unnamedLabels.next(k.referent().getClass().getSimpleName()));
    }

    private final Map<Long, LockSequence> threadLockOrders = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

    /** {@return the calling thread's sequence, or {@code null} when it has recorded nothing}. */
    private @Nullable LockSequence existingSequence() {
        return threadLockOrders.get(Thread.currentThread().threadId());
    }

    /** {@return the calling thread's sequence, made on its first acquisition}. */
    private LockSequence sequence() {
        LockSequence sequence = existingSequence();
        return sequence != null ? sequence
                : threadLockOrders.computeIfAbsent(Thread.currentThread().threadId(), LockSequence::new);
    }

    /**
     * Record a lock acquisition that may have blocked, such as {@code lock()} or entering a
     * {@code synchronized} block. Same as {@code recordLockAcquisition(lock, true)}.
     *
     * @param lock the lock being recorded, tracked by identity rather than equality
     */
    public void recordLockAcquisition(Object lock) {
        recordLockAcquisition(lock, true);
    }

    /**
     * Record a lock acquisition.
     *
     * <p>Pass {@code blocking = false} for an acquisition that cannot wait, a successful
     * {@code tryLock()} or timed {@code tryLock}: the lock counts as held, so a blocking
     * acquisition inside it is nested by it, but no edge is recorded into it, because it can
     * never be the waiting side of a deadlock.
     *
     * @param lock     the lock being recorded, tracked by identity rather than equality
     * @param blocking whether the acquisition could have waited for another thread
     * @since 1.14.3
     */
    public void recordLockAcquisition(Object lock, boolean blocking) {
        if (!enabled || lock == null) return;
        LockSequence sequence = sequence();
        synchronized (sequence) {
            sequence.acquire(lock, blocking, false);
        }
    }

    /**
     * Record that the calling thread has entered a {@code synchronized} method holding
     * {@code monitor}. The exit of such a method is not seen when an exception leaves it, so this
     * hold is re-confirmed with {@link Thread#holdsLock} before it nests anything.
     *
     * @param monitor the monitor the method holds
     * @since 1.14.3
     */
    public void recordMethodMonitorAcquisition(Object monitor) {
        if (!enabled || monitor == null) return;
        LockSequence sequence = sequence();
        synchronized (sequence) {
            sequence.acquire(monitor, true, true);
        }
    }

    /**
     * Record lock release. One release undoes one acquisition: a lock acquired twice is held
     * until it has been released twice.
     *
     * @param lock the lock being recorded, tracked by identity rather than equality
     */
    public void recordLockRelease(Object lock) {
        if (!enabled || lock == null) return;
        LockSequence sequence = existingSequence();
        if (sequence != null) {
            synchronized (sequence) {
                sequence.release(lock);
            }
        }
    }

    /**
     * Record that the calling thread is about to wait on {@code monitor}, which lets every hold of
     * it go until the wait returns.
     *
     * @param monitor the monitor being waited on
     * @return the holds let go, to pass to {@link #recordReacquireAfterWait}
     * @since 1.14.3
     */
    public int recordReleaseForWait(Object monitor) {
        if (!enabled || monitor == null) return 0;
        LockSequence sequence = existingSequence();
        if (sequence == null) {
            return 0;
        }
        synchronized (sequence) {
            return sequence.releaseAll(monitor);
        }
    }

    /**
     * Record that a wait on {@code monitor} has returned and taken its holds back. Taking the
     * monitor back can block, so it is nested by whatever else the thread still holds.
     *
     * @param monitor the monitor waited on
     * @param holds   what {@link #recordReleaseForWait} returned
     * @since 1.14.3
     */
    public void recordReacquireAfterWait(Object monitor, int holds) {
        if (!enabled || monitor == null || holds <= 0) return;
        LockSequence sequence = sequence();
        synchronized (sequence) {
            sequence.acquire(monitor, true, true);
            sequence.setHolds(monitor, holds);
        }
    }

    /** Every thread's records of every edge, collected for analysis. */
    private Map<LockEdge, List<EdgeRecord>> edgeRecords() {
        Map<LockEdge, List<EdgeRecord>> records = new HashMap<>();
        for (LockSequence sequence : threadLockOrders.values()) {
            synchronized (sequence) {
                for (Map.Entry<IdentityKey, Map<IdentityKey, EdgeGuards>> out : sequence.edges.entrySet()) {
                    for (EdgeGuards edge : out.getValue().values()) {
                        List<EdgeRecord> list = records.computeIfAbsent(
                                new LockEdge(out.getKey(), edge.to), k -> new ArrayList<>());
                        for (IdentityKey[] guard : edge.guards) {
                            list.add(new EdgeRecord(sequence.threadId, guard));
                        }
                    }
                }
            }
        }
        return records;
    }

    /**
     * Validate lock ordering consistency.
     *
     * @return the findings this detector collected during the run
     */
    public LockOrderReport validateLockOrder() {
        LockOrderReport report = new LockOrderReport();
        Map<LockEdge, List<EdgeRecord>> records = edgeRecords();

        // A pair is inconsistently ordered when it was nested both ways round, by two different
        // threads holding no lock in common. Only real nesting edges count, and a pair is two
        // lock instances, never two labels that happen to read alike.
        Set<LockEdge> reported = new HashSet<>();
        for (Map.Entry<LockEdge, List<EdgeRecord>> entry : records.entrySet()) {
            LockEdge edge = entry.getKey();
            LockEdge reverse = edge.reversed();
            List<EdgeRecord> back = records.get(reverse);
            if (back != null && anyConcurrent(entry.getValue(), back)
                    && reported.add(edge) && reported.add(reverse)) {
                String a = label(edge.from());
                String b = label(edge.to());
                String first = a.compareTo(b) <= 0 ? a : b;
                String second = first.equals(a) ? b : a;
                report.inconsistentOrderings.add(String.format(Locale.ROOT,
                    "Lock pair {%s, %s} acquired in different orders: [%s -> %s, %s -> %s]",
                    first, second, first, second, second, first
                ));
            }
        }

        detectDeadlockCycles(records, report);

        if (report.hasIssues()) {
            // The severity the failOn gate read from this text before #801: a marker in it,
            // else the value DetectorDefaultSeverity declared for the detector.
            IssueSeverity severity = IssueSeverity.markedIn(report.toString())
                    .orElse(IssueSeverity.CRITICAL);
            for (String finding : report.inconsistentOrderings) {
                report.structuredViolations.add(new Violation("LockOrderValidator", severity,
                        finding, List.of(), Map.of(), Instant.now()));
            }
            for (String finding : report.potentialDeadlockCycles) {
                report.structuredViolations.add(new Violation("LockOrderValidator", severity,
                        finding, List.of(), Map.of(), Instant.now()));
            }
        }
        return DetectorFailurePolicy.checkedReport(this, report);
    }

    private static boolean anyConcurrent(List<EdgeRecord> forward, List<EdgeRecord> back) {
        for (EdgeRecord one : forward) {
            for (EdgeRecord other : back) {
                if (one.concurrentWith(other)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Marks every lock on a cycle whose edges could all be in progress at once: each made by a
     * different thread, under guard sets no two of which share a lock.
     */
    private void detectDeadlockCycles(Map<LockEdge, List<EdgeRecord>> records, LockOrderReport report) {
        Map<IdentityKey, List<LockEdge>> graph = new HashMap<>();
        for (LockEdge edge : records.keySet()) {
            graph.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge);
        }
        Set<IdentityKey> onCycle = new HashSet<>();
        int[] budget = {CYCLE_SEARCH_BUDGET};
        List<IdentityKey> path = new ArrayList<>();
        List<EdgeRecord> taken = new ArrayList<>();
        for (IdentityKey start : graph.keySet()) {
            if (onCycle.contains(start)) {
                continue;
            }
            path.add(start);
            searchCycle(start, start, graph, records, path, taken, onCycle, budget);
            path.clear();
            taken.clear();
        }
        for (IdentityKey lock : onCycle) {
            report.potentialDeadlockCycles.add(label(lock));
        }
    }

    @SuppressWarnings("checkstyle:ParameterNumber") // the search state, kept off the heap per step
    private static boolean searchCycle(IdentityKey start, IdentityKey node,
                                       Map<IdentityKey, List<LockEdge>> graph,
                                       Map<LockEdge, List<EdgeRecord>> records,
                                       List<IdentityKey> path, List<EdgeRecord> taken,
                                       Set<IdentityKey> onCycle, int[] budget) {
        for (LockEdge edge : graph.getOrDefault(node, List.of())) {
            IdentityKey to = edge.to();
            boolean closes = to.equals(start);
            if (!closes && (path.contains(to) || path.size() >= MAX_CYCLE_LENGTH)) {
                continue;
            }
            for (EdgeRecord record : records.getOrDefault(edge, List.of())) {
                budget[0]--;
                if (budget[0] < 0) {
                    return false;
                }
                if (!concurrentWithAll(record, taken)) {
                    continue;
                }
                if (closes) {
                    onCycle.addAll(path);
                    return true;
                }
                path.add(to);
                taken.add(record);
                boolean found = searchCycle(start, to, graph, records, path, taken, onCycle, budget);
                path.remove(path.size() - 1);
                taken.remove(taken.size() - 1);
                if (found) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean concurrentWithAll(EdgeRecord record, List<EdgeRecord> taken) {
        for (EdgeRecord other : taken) {
            if (!record.concurrentWith(other)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Clears recorded the observation so this instance can be reused for the next run.
     */
    public void reset() {
        threadLockOrders.clear();
        labels.clear();
    }
    /**
     * Disable.
     */
    public void disable() {
        enabled = false;
    }
    /**
     * Enable.
     */
    public void enable() {
        enabled = true;
    }
    
    public static class LockOrderReport {
        /** Lock pairs acquired in one order by one thread and the reverse by another. */
        public final Set<String> inconsistentOrderings = new HashSet<>();
        /** The findings as Violations, at the severity the text resolved to (#801). */
        public final List<Violation> structuredViolations = new ArrayList<>();
        /** Cycles in the observed lock-acquisition graph. */
        public final Set<String> potentialDeadlockCycles = new HashSet<>();
        
        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return !inconsistentOrderings.isEmpty() || !potentialDeadlockCycles.isEmpty();
        }
        
        @Override
        public String toString() {
            if (!hasIssues()) {
                return "No lock ordering violations detected.";
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("LOCK ORDERING VIOLATIONS DETECTED:\n");
            
            if (!inconsistentOrderings.isEmpty()) {
                sb.append("\nInconsistent lock acquisition orders:\n");
                for (String ordering : inconsistentOrderings) {
                    sb.append("  - ").append(ordering).append("\n");
                }
                sb.append("\nFix: Establish global lock ordering and enforce it everywhere\n");
            }
            
            if (!potentialDeadlockCycles.isEmpty()) {
                sb.append("\nPotential deadlock cycles in lock graph:\n");
                for (String cycle : potentialDeadlockCycles) {
                    sb.append("  - ").append(cycle).append("\n");
                }
                sb.append("\nFix: Restructure lock acquisition to prevent cycles\n");
            }
            
            return sb.toString();
        }
    }
}
