package com.github.asynctest.diagnostics;

import java.util.*;
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
    
    private static class LockSequence {
        final long threadId;
        final List<String> lockOrder = Collections.synchronizedList(new ArrayList<>());
        final Set<String> acquiredLocks = ConcurrentHashMap.newKeySet();
        volatile long lastAcquisitionTime = 0;
        
        LockSequence(long tid) {
            this.threadId = tid;
        }
    }
    
    private final Map<Long, LockSequence> threadLockOrders = new ConcurrentHashMap<>();
    private final Map<String, Set<List<String>>> observedLockOrders = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    
    /**
     * Record a lock acquisition.
     */
    public void recordLockAcquisition(Object lock) {
        if (!enabled) return;
        
        long threadId = Thread.currentThread().getId();
        String lockId = lock.getClass().getSimpleName() + "@" + System.identityHashCode(lock);
        
        LockSequence sequence = threadLockOrders.computeIfAbsent(threadId, LockSequence::new);
        
        synchronized (sequence) {
            sequence.lockOrder.add(lockId);
            sequence.acquiredLocks.add(lockId);
            sequence.lastAcquisitionTime = System.nanoTime();
        }
    }
    
    /**
     * Record lock release.
     */
    public void recordLockRelease(Object lock) {
        if (!enabled) return;
        
        long threadId = Thread.currentThread().getId();
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
        
        // Collect all observed lock orders
        Map<String, List<Integer>> lockOrderPatterns = new HashMap<>();
        
        for (LockSequence sequence : threadLockOrders.values()) {
            if (sequence.lockOrder.size() < 2) continue;
            
            // Extract lock pairs to build ordering rules
            for (int i = 0; i < sequence.lockOrder.size() - 1; i++) {
                String lock1 = sequence.lockOrder.get(i);
                String lock2 = sequence.lockOrder.get(i + 1);
                
                String pair = lock1 + " -> " + lock2;
                lockOrderPatterns.computeIfAbsent(pair, k -> new ArrayList<>())
                    .add((int)sequence.threadId);
            }
        }
        
        // Detect conflicting orderings
        Map<String, Set<String>> lockPairOrderings = new HashMap<>();
        for (LockSequence sequence : threadLockOrders.values()) {
            for (int i = 0; i < sequence.lockOrder.size() - 1; i++) {
                String lock1 = sequence.lockOrder.get(i);
                String lock2 = sequence.lockOrder.get(i + 1);
                
                String pair = normalizeUnorderedPair(lock1, lock2);
                String order = lock1.compareTo(lock2) < 0 ? 
                    lock1 + " -> " + lock2 : lock2 + " -> " + lock1;
                
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
            for (int i = 0; i < sequence.lockOrder.size() - 1; i++) {
                String from = sequence.lockOrder.get(i);
                String to = sequence.lockOrder.get(i + 1);
                lockGraph.computeIfAbsent(from, k -> new HashSet<>()).add(to);
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
