package com.github.asynctest.diagnostics;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Detects the ABA Problem in atomic operations.
 * 
 * ABA Problem: 
 * 1. Thread A reads value X as A
 * 2. Thread B changes A -> B -> A (value is back to A)
 * 3. Thread A's CAS(X, A, C) succeeds, but X was modified!
 * 
 * This is a subtle bug in lock-free code that can cause:
 * - Data structure corruption
 * - Lost updates
 * - Incorrect synchronization
 */
public class ABAProblemDetector {
    
    private static class AtomicValueHistory {
        final String varName;
        final List<ValueChange> changes = Collections.synchronizedList(new ArrayList<>());
        final Map<Long, CASAttempt> casAttempts = new ConcurrentHashMap<>();
        volatile int cycleCount = 0;
        
        AtomicValueHistory(String name) {
            this.varName = name;
        }
    }
    
    private static class ValueChange {
        final long timestamp;
        final long threadId;
        final Object oldValue;
        final Object newValue;
        
        ValueChange(long threadId, Object old, Object neu) {
            this.timestamp = System.nanoTime();
            this.threadId = threadId;
            this.oldValue = old;
            this.newValue = neu;
        }
        
        boolean isSameValue(Object v1, Object v2) {
            if (v1 == null && v2 == null) return true;
            if (v1 == null || v2 == null) return false;
            return v1.equals(v2) || v1 == v2;
        }
    }
    
    private static class CASAttempt {
        final long threadId;
        final long timestamp;
        final Object expectedValue;
        final Object newValue;
        final boolean succeeded;
        volatile boolean wasABA = false;
        
        CASAttempt(long threadId, Object expected, Object neu, boolean success) {
            this.threadId = threadId;
            this.timestamp = System.nanoTime();
            this.expectedValue = expected;
            this.newValue = neu;
            this.succeeded = success;
        }
    }
    
    private final Map<String, AtomicValueHistory> trackedVariables = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    
    /**
     * Record a value change in an atomic variable.
     */
    public void recordValueChange(String variableName, Object oldValue, Object newValue) {
        if (!enabled) return;
        
        AtomicValueHistory history = trackedVariables.computeIfAbsent(variableName,
            AtomicValueHistory::new
        );
        
        ValueChange change = new ValueChange(Thread.currentThread().getId(), oldValue, newValue);
        history.changes.add(change);
        
        // Detect cycles (A -> B -> A pattern)
        detectCycles(history, change);
    }
    
    /**
     * Record a CAS (Compare-And-Swap) attempt.
     */
    public void recordCASAttempt(String variableName, Object expectedValue, Object newValue, 
                                 boolean succeeded, Object actualCurrentValue) {
        if (!enabled) return;
        
        AtomicValueHistory history = trackedVariables.computeIfAbsent(variableName,
            AtomicValueHistory::new
        );
        
        CASAttempt attempt = new CASAttempt(Thread.currentThread().getId(), 
                                            expectedValue, newValue, succeeded);
        
        // Detect ABA: Value changed but came back to expected
        if (succeeded && detectABA(history, attempt)) {
            attempt.wasABA = true;
        }
        
        history.casAttempts.put((long) System.identityHashCode(attempt), attempt);
    }
    
    private void detectCycles(AtomicValueHistory history, ValueChange newChange) {
        List<ValueChange> changes = history.changes;
        if (changes.size() < 3) return;
        
        // Look for A -> B -> A pattern
        for (int i = 0; i < changes.size() - 2; i++) {
            ValueChange c1 = changes.get(i);
            ValueChange c2 = changes.get(i + 1);
            ValueChange c3 = changes.get(i + 2);
            
            // c1: something -> A, c2: A -> B, c3: B -> A
            if (c1.isSameValue(c1.newValue, c3.newValue) && 
                c1.isSameValue(c2.oldValue, c1.newValue) &&
                !c1.isSameValue(c2.newValue, c3.newValue)) {
                history.cycleCount++;
            }
        }
    }
    
    private boolean detectABA(AtomicValueHistory history, CASAttempt attempt) {
        List<ValueChange> changes = history.changes;
        
        // Find if there was a cycle after the last time this expected value was current
        boolean foundExpectedBefore = false;
        boolean foundDifferentAfter = false;
        boolean foundExpectedAgain = false;
        
        for (ValueChange change : changes) {
            if (change.isSameValue(change.newValue, attempt.expectedValue)) {
                foundExpectedBefore = true;
                foundDifferentAfter = false;
            } else if (foundExpectedBefore) {
                foundDifferentAfter = true;
            }
            
            if (foundDifferentAfter && change.isSameValue(change.newValue, attempt.expectedValue)) {
                foundExpectedAgain = true;
                break;
            }
        }
        
        return foundExpectedBefore && foundDifferentAfter && foundExpectedAgain;
    }
    
    /**
     * Analyze for ABA problems.
     */
    public ABAReport analyzeABA() {
        ABAReport report = new ABAReport();
        
        for (AtomicValueHistory history : trackedVariables.values()) {
            if (history.cycleCount > 0) {
                report.variablesWithCycles.put(history.varName, history.cycleCount);
            }
            
            // Check for CAS attempts that succeeded despite ABA
            for (CASAttempt attempt : history.casAttempts.values()) {
                if (attempt.wasABA) {
                    report.successfulABACases.add(String.format(
                        "%s: CAS succeeded despite ABA (expected %s, set to %s)",
                        history.varName, attempt.expectedValue, attempt.newValue
                    ));
                }
            }
        }
        
        return report;
    }
    
    public void reset() {
        trackedVariables.clear();
    }
    
    public void disable() {
        enabled = false;
    }
    
    public void enable() {
        enabled = true;
    }
    
    public static class ABAReport {
        public final Map<String, Integer> variablesWithCycles = new HashMap<>();
        public final Set<String> successfulABACases = new HashSet<>();
        
        public boolean hasIssues() {
            return !variablesWithCycles.isEmpty() || !successfulABACases.isEmpty();
        }
        
        @Override
        public String toString() {
            if (!hasIssues()) {
                return "No ABA problems detected.";
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("ABA PROBLEM DETECTED:\n");
            
            if (!variablesWithCycles.isEmpty()) {
                sb.append("\nVariables with A->B->A cycles:\n");
                for (Map.Entry<String, Integer> entry : variablesWithCycles.entrySet()) {
                    sb.append(String.format("  - %s: %d cycles detected\n", 
                        entry.getKey(), entry.getValue()));
                }
            }
            
            if (!successfulABACases.isEmpty()) {
                sb.append("\nCAS operations that succeeded despite ABA:\n");
                for (String cas : successfulABACases) {
                    sb.append("  - ").append(cas).append("\n");
                }
                sb.append("\nFix: Use AtomicStampedReference or AtomicMarkableReference\n");
            }
            
            sb.append("\nWarning: ABA problems are subtle and can cause:\n");
            sb.append("  - Data structure corruption\n");
            sb.append("  - Lost updates in lock-free structures\n");
            sb.append("  - Incorrect synchronization guarantees\n");
            
            return sb.toString();
        }
    }
}
