package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
    
    /** One lock acquired while another was already held: {@code from} nests {@code to}. */
    private record LockEdge(String from, String to) { }

    private static class LockSequence {
        final long threadId;
        /** Locks this thread holds right now. The only sound basis for a nesting edge. */
        final Set<String> acquiredLocks = ConcurrentHashMap.newKeySet();
        /**
         * Edges observed on this thread: recorded at acquisition time, when we can still see
         * what was held. Deriving them afterwards from a flat acquisition history cannot work —
         * consecutive entries are not necessarily nested, and a released lock leaves no trace.
         */
        final Set<LockEdge> nestingEdges = ConcurrentHashMap.newKeySet();
        LockSequence(long tid) {
            this.threadId = tid;
        }
    }
    
    private final Map<Long, LockSequence> threadLockOrders = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    
    /**
     * Record a lock acquisition.
     */
    public void recordLockAcquisition(Object lock) {
        if (!enabled || lock == null) return;

        long threadId = Thread.currentThread().threadId();
        String lockId = lock.getClass().getSimpleName() + "@" + System.identityHashCode(lock);
        
        LockSequence sequence = threadLockOrders.computeIfAbsent(threadId, LockSequence::new);
        
        synchronized (sequence) {
            // Every lock still held by this thread is being nested by the one we are taking
            // now. This is the edge that matters: it says "while holding `held`, this thread
            // wants `lockId`" — the exact relation that deadlocks when another thread does the
            // reverse. Locks already released impose no ordering and contribute nothing.
            for (String held : sequence.acquiredLocks) {
                if (!held.equals(lockId)) {
                    sequence.nestingEdges.add(new LockEdge(held, lockId));
                }
            }
            sequence.acquiredLocks.add(lockId);
        }
    }
    
    /**
     * Record lock release.
     */
    public void recordLockRelease(Object lock) {
        if (!enabled || lock == null) return;

        long threadId = Thread.currentThread().threadId();
        String lockId = lock.getClass().getSimpleName() + "@" + System.identityHashCode(lock);
        
        LockSequence sequence = threadLockOrders.get(threadId);
        if (sequence != null) {
            synchronized (sequence) {
                sequence.acquiredLocks.remove(lockId);
                // Note: We keep the full order for analysis
            }
        }
    }
    
    /**
     * Validate lock ordering consistency.
     */
    public LockOrderReport validateLockOrder() {
        LockOrderReport report = new LockOrderReport();

        // A pair is inconsistently ordered when it was nested both ways round — A inside B
        // somewhere, B inside A somewhere else. Only real nesting edges count.
        Map<String, Set<String>> lockPairOrderings = new HashMap<>();
        for (LockSequence sequence : threadLockOrders.values()) {
            for (LockEdge edge : sequence.nestingEdges) {
                String pair = normalizeUnorderedPair(edge.from(), edge.to());
                String order = edge.from() + " -> " + edge.to();

                lockPairOrderings.computeIfAbsent(pair, k -> new HashSet<>()).add(order);
            }
        }
        
        // Find pairs with conflicting orders
        for (Map.Entry<String, Set<String>> entry : lockPairOrderings.entrySet()) {
            if (entry.getValue().size() > 1) {
                report.inconsistentOrderings.add(String.format(
                    "Lock pair %s acquired in different orders: %s",
                    entry.getKey(), entry.getValue()
                ));
            }
        }
        
        // Detect potential deadlock cycles
        detectDeadlockCycles(threadLockOrders.values(), report);
        
        return report;
    }
    
    private String normalizeUnorderedPair(String lock1, String lock2) {
        if (lock1.compareTo(lock2) < 0) {
            return "{" + lock1 + ", " + lock2 + "}";
        } else {
            return "{" + lock2 + ", " + lock1 + "}";
        }
    }
    
    private void detectDeadlockCycles(Collection<LockSequence> sequences, LockOrderReport report) {
        // Build a directed graph of lock acquisitions
        Map<String, Set<String>> lockGraph = new HashMap<>();
        
        for (LockSequence sequence : sequences) {
            for (LockEdge edge : sequence.nestingEdges) {
                lockGraph.computeIfAbsent(edge.from(), k -> new HashSet<>()).add(edge.to());
            }
        }
        
        // Detect cycles using DFS
        for (String lock : lockGraph.keySet()) {
            if (hasCycle(lock, lockGraph, new HashSet<>(), new HashSet<>())) {
                report.potentialDeadlockCycles.add(lock);
            }
        }
    }
    
    private boolean hasCycle(String node, Map<String, Set<String>> graph, 
                            Set<String> visited, Set<String> recursionStack) {
        visited.add(node);
        recursionStack.add(node);
        
        Set<String> neighbors = graph.getOrDefault(node, new HashSet<>());
        for (String neighbor : neighbors) {
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
    
    public void reset() {
        threadLockOrders.clear();
    }
    
    public void disable() {
        enabled = false;
    }
    
    public void enable() {
        enabled = true;
    }
    
    public static class LockOrderReport {
        public final Set<String> inconsistentOrderings = new HashSet<>();
        public final Set<String> potentialDeadlockCycles = new HashSet<>();
        
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
