package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects lock ordering violations that can cause deadlocks.
 * 
 * Problem: If different threads acquire locks in different orders, deadlock can occur:
 * - Thread A: lock(L1) -> lock(L2)
 * - Thread B: lock(L2) -> lock(L1)
 * 
 * This detector tracks the order in which locks are acquired by each thread
 * and identifies inconsistencies.
 */
public class LockOrderValidator {
    
    /**
     * One lock acquired while another was already held: {@code from} nests {@code to}.
     *
     * <p>Locks are {@link IdentityKey}s, not labels. The label is class name plus identity hash,
     * and two live locks share an identity hash often enough to matter: keyed by the label, one
     * thread nesting {@code X1} inside {@code A} and another nesting {@code A} inside {@code X2}
     * read as one pair taken both ways round, an inversion and a deadlock cycle that three
     * distinct locks cannot form.
     */
    private record LockEdge(IdentityKey from, IdentityKey to) {
        LockEdge reversed() {
            return new LockEdge(to, from);
        }
    }

    private static final class LockSequence {
        /** Locks this thread holds right now. The only sound basis for a nesting edge. */
        final Set<IdentityKey> acquiredLocks = ConcurrentHashMap.newKeySet();
        /**
         * Edges observed on this thread: recorded at acquisition time, when we can still see
         * what was held. Deriving them afterwards from a flat acquisition history cannot work —
         * consecutive entries are not necessarily nested, and a released lock leaves no trace.
         */
        final Set<LockEdge> nestingEdges = ConcurrentHashMap.newKeySet();

        /**
         * One-entry memo of the last lock's key. A release nearly always follows the acquisition
         * of the same lock, and the agent calls both on every woven lock operation, so this keeps
         * the common pair from allocating the same key twice. Guarded by this sequence's monitor.
         */
        private @Nullable IdentityKey lastLock;

        @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"}) // locks are tracked by identity
        IdentityKey keyOf(Object lock) {
            IdentityKey last = lastLock;
            if (last == null || last.referent() != lock) {
                last = new IdentityKey(lock);
                lastLock = last;
            }
            return last;
        }
    }

    /** {@return how a lock is named in the report: its class and identity hash} */
    private static String label(IdentityKey lock) {
        return lock.referent().getClass().getSimpleName() + "@" + lock.hashCode();
    }
    
    private final Map<Long, LockSequence> threadLockOrders = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    
    /**
     * Record a lock acquisition.
     *
     * @param lock the lock being recorded, tracked by identity rather than equality
     */
    public void recordLockAcquisition(Object lock) {
        if (!enabled || lock == null) return;

        long threadId = Thread.currentThread().threadId();
        LockSequence sequence = threadLockOrders.computeIfAbsent(threadId, id -> new LockSequence());
        
        synchronized (sequence) {
            IdentityKey lockId = sequence.keyOf(lock);
            // Every lock still held by this thread is being nested by the one we are taking
            // now. This is the edge that matters: it says "while holding `held`, this thread
            // wants `lockId`" — the exact relation that deadlocks when another thread does the
            // reverse. Locks already released impose no ordering and contribute nothing.
            for (IdentityKey held : sequence.acquiredLocks) {
                if (!held.equals(lockId)) {
                    sequence.nestingEdges.add(new LockEdge(held, lockId));
                }
            }
            sequence.acquiredLocks.add(lockId);
        }
    }
    
    /**
     * Record lock release.
     *
     * @param lock the lock being recorded, tracked by identity rather than equality
     */
    public void recordLockRelease(Object lock) {
        if (!enabled || lock == null) return;

        long threadId = Thread.currentThread().threadId();
        LockSequence sequence = threadLockOrders.get(threadId);
        if (sequence != null) {
            synchronized (sequence) {
                sequence.acquiredLocks.remove(sequence.keyOf(lock));
                // Note: We keep the full order for analysis
            }
        }
    }
    
    /**
     * Validate lock ordering consistency.
     *
     * @return the findings this detector collected during the run
     */
    public LockOrderReport validateLockOrder() {
        LockOrderReport report = new LockOrderReport();

        // A pair is inconsistently ordered when it was nested both ways round — A inside B
        // somewhere, B inside A somewhere else. Only real nesting edges count, and a pair is two
        // lock instances, never two labels that happen to read alike.
        Set<LockEdge> edges = new HashSet<>();
        for (LockSequence sequence : threadLockOrders.values()) {
            edges.addAll(sequence.nestingEdges);
        }
        Set<LockEdge> reported = new HashSet<>();
        for (LockEdge edge : edges) {
            LockEdge reverse = edge.reversed();
            if (edges.contains(reverse) && reported.add(edge) && reported.add(reverse)) {
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
        
        // Detect potential deadlock cycles
        detectDeadlockCycles(threadLockOrders.values(), report);
        
        return report;
    }
    
    private void detectDeadlockCycles(Collection<LockSequence> sequences, LockOrderReport report) {
        // Build a directed graph of lock acquisitions
        Map<IdentityKey, Set<IdentityKey>> lockGraph = new HashMap<>();
        
        for (LockSequence sequence : sequences) {
            for (LockEdge edge : sequence.nestingEdges) {
                lockGraph.computeIfAbsent(edge.from(), k -> new HashSet<>()).add(edge.to());
            }
        }
        
        // Detect cycles using DFS
        for (IdentityKey lock : lockGraph.keySet()) {
            if (hasCycle(lock, lockGraph, new HashSet<>(), new HashSet<>())) {
                report.potentialDeadlockCycles.add(label(lock));
            }
        }
    }
    
    private boolean hasCycle(IdentityKey node, Map<IdentityKey, Set<IdentityKey>> graph,
                            Set<IdentityKey> visited, Set<IdentityKey> recursionStack) {
        visited.add(node);
        recursionStack.add(node);
        
        Set<IdentityKey> neighbors = graph.getOrDefault(node, new HashSet<>());
        for (IdentityKey neighbor : neighbors) {
            if (!visited.contains(neighbor)) {
                if (hasCycle(neighbor, graph, visited, recursionStack)) {
                    return true;
                }
            } else if (recursionStack.contains(neighbor)) {
                return true;
            }
        }
        
        recursionStack.remove(node);
        return false;
    }
    /**
     * Clears recorded the observation so this instance can be reused for the next run.
     */
    public void reset() {
        threadLockOrders.clear();
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
