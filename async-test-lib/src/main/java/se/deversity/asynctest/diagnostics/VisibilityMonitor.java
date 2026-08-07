package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monitors field access patterns to detect visibility issues (stale memory).
 * A visibility issue occurs when a field is updated by one thread but other threads
 * don't see the update because it's not marked volatile and synchronization is missing.
 *
 * <p>Each recorded access carries the observing thread and the current invocation round.
 * A field is reported only when two threads observed different values <em>within the same
 * round</em> — the stale-read signature the barrier-aligned collision is engineered to
 * expose. Value changes across rounds are ordinary program behavior (counters, per-round
 * fixture state) and are ordered by the harness's own round happens-before edges, so they
 * are never reported.
 */
public class VisibilityMonitor {

    private static final class FieldSnapshot {
        final long invocationId;
        final long threadId;
        final Object value;

        FieldSnapshot(long invocationId, long threadId, Object value) {
            this.invocationId = invocationId;
            this.threadId = threadId;
            this.value = value;
        }
    }

    private final Map<String, List<FieldSnapshot>> fieldSnapshots = new ConcurrentHashMap<>();
    private final AtomicLong invocationCounter = new AtomicLong(0);
    private volatile boolean enabled = true;

    /**
     * Stand-in stored for recorded {@code null} values, so the public report sets never
     * carry a bare {@code null} and it still counts as one distinct observed value.
     * Prints as {@code "null"} in reports.
     */
    private static final Object NULL_VALUE = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    /**
     * Record a field access. Call this from test code to track when a field is read/written.
     * Format: className.fieldName
     *
     * @param fieldIdentifier the {@code Type.field} the access was on
     * @param value the value read or written
     */
    public void recordFieldAccess(String fieldIdentifier, Object value) {
        if (!enabled) return;

        // Map null to the sentinel: a stale null read is precisely the observation a
        // visibility monitor exists to accept.
        Object tracked = (value != null) ? value : NULL_VALUE;
        FieldSnapshot snapshot = new FieldSnapshot(
            invocationCounter.get(), Thread.currentThread().threadId(), tracked);

        fieldSnapshots.computeIfAbsent(fieldIdentifier, k -> 
            Collections.synchronizedList(new ArrayList<>())
        ).add(snapshot);
    }
    
    /**
     * Mark the start of a new invocation round.
     */
    public void markInvocationStart() {
        invocationCounter.incrementAndGet();
    }
    
    /**
     * Analyze visibility issues. Returns a report of suspected visibility issues.
     *
     * @return the findings this detector collected during the run
     */
    public VisibilityReport analyzeVisibility() {
        VisibilityReport report = new VisibilityReport();

        for (Map.Entry<String, List<FieldSnapshot>> entry : fieldSnapshots.entrySet()) {
            String fieldId = entry.getKey();
            List<FieldSnapshot> snapshots = entry.getValue();

            // Copy under the list's own lock: this is a Collections.synchronizedList, and
            // iterating it unguarded races a concurrent recordFieldAccess (the runner's
            // timeout path can analyze while a cancelled worker is still unwinding), which
            // throws ConcurrentModificationException and costs the whole report.
            List<FieldSnapshot> copy;
            synchronized (snapshots) {
                copy = new ArrayList<>(snapshots);
            }

            // Group per invocation round, tracking both the values seen and the threads
            // that observed them.
            Map<Long, Set<Object>> valuesByInvocation = new HashMap<>();
            Map<Long, Set<Long>> threadsByInvocation = new HashMap<>();
            for (FieldSnapshot snapshot : copy) {
                valuesByInvocation.computeIfAbsent(snapshot.invocationId, k -> new HashSet<>())
                    .add(snapshot.value);
                threadsByInvocation.computeIfAbsent(snapshot.invocationId, k -> new HashSet<>())
                    .add(snapshot.threadId);
            }

            // A field is suspect only when at least two threads observed at least two
            // distinct values WITHIN one invocation round: divergence at the same
            // barrier-aligned collision point is the stale-read signature this monitor
            // hunts. The previous heuristic flagged any value change ACROSS rounds, which
            // is ordinary program behavior (counters, per-round fixture state) ordered by
            // the harness's own round happens-before edges — a false-positive machine —
            // while missing the true signal, because same-round divergence never spans
            // two invocation ids.
            boolean divergentRound = false;
            for (Map.Entry<Long, Set<Object>> invocation : valuesByInvocation.entrySet()) {
                Set<Long> observers = threadsByInvocation.get(invocation.getKey());
                if (invocation.getValue().size() > 1 && observers != null && observers.size() > 1) {
                    divergentRound = true;
                    break;
                }
            }
            if (divergentRound) {
                report.suspectedFields.add(fieldId);
                report.fieldValueVariations.put(fieldId, valuesByInvocation);
            }
        }

        return report;
    }

    /**
     * Standardized alias for {@link #analyzeVisibility()}.
     *
     * @return the findings this detector collected during the run
     */
    public VisibilityReport analyze() {
        return analyzeVisibility();
    }
    /**
     * Clears recorded the observation so this instance can be reused for the next run.
     */
    public void reset() {
        fieldSnapshots.clear();
        invocationCounter.set(0);
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
    
    public static class VisibilityReport {
        /** Fields where two threads observed different values at the same time. */
        public final Set<String> suspectedFields = new HashSet<>();
        /** Values each thread observed per field, used to spot stale reads. */
        public final Map<String, Map<Long, Set<Object>>> fieldValueVariations = new HashMap<>();
        
        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return !suspectedFields.isEmpty();
        }
        
        @Override
        public String toString() {
            if (suspectedFields.isEmpty()) {
                return "No visibility issues detected.";
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("POTENTIAL VISIBILITY ISSUES DETECTED:\n");
            for (String field : suspectedFields) {
                sb.append("  - ").append(field).append("\n");
                Map<Long, Set<Object>> variations = fieldValueVariations.get(field);
                if (variations != null) {
                    for (Map.Entry<Long, Set<Object>> var : variations.entrySet()) {
                        sb.append("      Invocation ").append(var.getKey()).append(": ");
                        sb.append(var.getValue()).append("\n");
                    }
                }
            }
            sb.append("\nSuspect: Missing 'volatile' keyword or insufficient synchronization.\n");
            return sb.toString();
        }
    }
}
