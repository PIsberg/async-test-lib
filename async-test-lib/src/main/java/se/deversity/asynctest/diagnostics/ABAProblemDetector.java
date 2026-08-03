package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
        /**
         * Guards {@link #changes}. A dedicated private lock rather than the list itself: this
         * class is extensible, so its fields are reachable by subclasses, and a lock a subclass
         * can also acquire is not a lock.
         */
        private final Object changesLock = new Object();
        /** Guarded by {@link #changesLock} — never touch it outside that monitor. */
        final List<ValueChange> changes = new ArrayList<>();
        final Map<Long, CASAttempt> casAttempts = new ConcurrentHashMap<>();
        final AtomicLong cycleCount = new AtomicLong(0);
        
        AtomicValueHistory(String name) {
            this.varName = name;
        }
    }
    
    private static class ValueChange {
        final Object oldValue;
        final Object newValue;

        ValueChange(Object old, Object neu) {
            this.oldValue = old;
            this.newValue = neu;
        }
        
        @SuppressWarnings("PMD.CompareObjectsWithEquals") // identity equality intentional for atomic value tracking
        boolean isSameValue(Object v1, Object v2) {
            if (v1 == null && v2 == null) return true;
            if (v1 == null || v2 == null) return false;
            return v1.equals(v2) || v1 == v2;
        }
    }
    
    private static class CASAttempt {
        final Object expectedValue;
        final Object newValue;
        volatile boolean wasABA = false;

        CASAttempt(Object expected, Object neu) {
            this.expectedValue = expected;
            this.newValue = neu;
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
        
        ValueChange change = new ValueChange(oldValue, newValue);
        synchronized (history.changesLock) {
            history.changes.add(change);
        }
        
        // Detect cycles (A -> B -> A pattern)
        detectCycles(history);
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
        
        CASAttempt attempt = new CASAttempt(expectedValue, newValue);
        
        // Detect ABA: Value changed but came back to expected
        if (succeeded && detectABA(history, attempt)) {
            attempt.wasABA = true;
        }
        
        history.casAttempts.put((long) System.identityHashCode(attempt), attempt);
    }
    
    /**
     * An ABA is a value coming back to what it just was: a change {@code A -> B} immediately
     * followed by {@code B -> A}.
     *
     * <p>Two changes are all it takes to see that, and two is all the canonical case produces —
     * a lock-free stack head sitting at A, swung to B, swung back to A. There is no {@code ? -> A}
     * change, because A is the value the variable <em>started</em> with, never one it was written
     * to. The previous implementation required three changes and matched a {@code ? -> A},
     * {@code A -> B}, {@code B -> A} window, so the minimal cycle fell straight through its
     * {@code size() < 3} guard and was never counted.
     *
     * <p>Only the newest pair is examined: each pair is therefore checked
     * exactly once, as it is formed, instead of the whole history being rescanned on every
     * change (which inflated the cycle count quadratically).
     */
    private void detectCycles(AtomicValueHistory history) {
        List<ValueChange> changes = history.changes;

        // Reading two elements consistently, while other threads append, needs the lock held
        // across both reads.
        synchronized (history.changesLock) {
            int size = changes.size();
            if (size < 2) return;

            ValueChange previous = changes.get(size - 2);   // A -> B
            ValueChange latest = changes.get(size - 1);     // B -> A ?

            boolean contiguous = latest.isSameValue(previous.newValue, latest.oldValue);
            boolean returnedToStart = latest.isSameValue(previous.oldValue, latest.newValue);
            boolean actuallyMoved = !latest.isSameValue(previous.oldValue, previous.newValue);

            if (contiguous && returnedToStart && actuallyMoved) {
                history.cycleCount.incrementAndGet();
            }
        }
    }
    
    private boolean detectABA(AtomicValueHistory history, CASAttempt attempt) {
        // Snapshot under the list's own lock: iterating a synchronizedList while other threads
        // append throws ConcurrentModificationException, which would lose the ABA finding.
        List<ValueChange> changes = history.changes;
        List<ValueChange> snapshot;
        synchronized (history.changesLock) {
            snapshot = new ArrayList<>(changes);
        }

        boolean foundExpectedBefore = false;
        boolean foundDifferentAfter = false;

        for (ValueChange change : snapshot) {
            if (!foundExpectedBefore) {
                if (change.isSameValue(change.newValue, attempt.expectedValue)) {
                    foundExpectedBefore = true;
                }
            } else if (!foundDifferentAfter) {
                if (!change.isSameValue(change.newValue, attempt.expectedValue)) {
                    foundDifferentAfter = true;
                }
            } else {
                if (change.isSameValue(change.newValue, attempt.expectedValue)) {
                    return true; // A -> B -> A confirmed
                }
            }
        }

        return false;
    }
    
    /**
     * Analyze for ABA problems.
     */
    public ABAReport analyzeABA() {
        ABAReport report = new ABAReport();
        
        for (AtomicValueHistory history : trackedVariables.values()) {
            long cycles = history.cycleCount.get();
            if (cycles > 0) {
                report.variablesWithCycles.put(history.varName, (int) cycles);
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

    /**
     * Standardized alias for {@link #analyzeABA()}.
     */
    public ABAReport analyze() {
        return analyzeABA();
    }
    /**
     * Clears recorded the observation so this instance can be reused for the next run.
     */

    public void reset() {
        trackedVariables.clear();
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
    
    public static class ABAReport {
        /** The variables with cycles. */
        public final Map<String, Integer> variablesWithCycles = new HashMap<>();
        /** The successful ABA cases. */
        public final Set<String> successfulABACases = new HashSet<>();
        
        /** {@return whether there are issues} */
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
                    sb.append(String.format("  - %s: %d cycles detected%n",
                        entry.getKey(), entry.getValue()));
                }
            }
            
            if (!successfulABACases.isEmpty()) {
                sb.append("\nCAS operations that succeeded despite ABA:\n");
                for (String cas : successfulABACases) {
                    sb.append("  - ").append(cas).append("\n");
                }
                sb.append("""

                          Why: An ABA race occurs when a location holds value A, is changed to B, then changed back to A
                               before a competing CAS reads it. The CAS sees A (as expected) and succeeds — but the underlying
                               object may have been destroyed and recreated, or a linked list node may have been freed and
                               reallocated, leaving the data structure in a corrupt state that the CAS cannot detect.
                          """);
                sb.append("""

                          Fix: Use AtomicStampedReference<V> (pairs value with an integer version stamp) or
                               AtomicMarkableReference<V> (pairs value with a boolean mark) so the CAS compares both
                               the value and the stamp/mark — an A→B→A cycle changes the stamp and the CAS correctly fails
                          """);
            }
            
            sb.append("\nWarning: ABA problems are subtle and can cause:\n");
            sb.append("  - Data structure corruption\n");
            sb.append("  - Lost updates in lock-free structures\n");
            sb.append("  - Incorrect synchronization guarantees\n");
            
            return sb.toString();
        }
    }
}
