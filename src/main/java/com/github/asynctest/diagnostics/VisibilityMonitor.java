package com.github.asynctest.diagnostics;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monitors field access patterns to detect visibility issues (stale memory).
 * A visibility issue occurs when a field is updated by one thread but other threads
 * don't see the update because it's not marked volatile and synchronization is missing.
 * 
 * This monitor tracks field values across invocation rounds to detect inconsistencies
 * that suggest missing volatile keywords or synchronization.
 */
public class VisibilityMonitor {
    
    private static final class FieldSnapshot {
        final long invocationId;
        final Object value;
        final Thread thread;
        final StackTraceElement[] stackTrace;
        
        FieldSnapshot(long invocationId, Object value, Thread thread) {
            this.invocationId = invocationId;
            this.value = value;
            this.thread = thread;
            this.stackTrace = thread.getStackTrace();
        }
    }
    
    private final Map<String, List<FieldSnapshot>> fieldSnapshots = new ConcurrentHashMap<>();
    private final AtomicLong invocationCounter = new AtomicLong(0);
    private final Map<String, Set<Object>> seenValues = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    
    /**
     * Record a field access. Call this from test code to track when a field is read/written.
     * Format: className.fieldName
     */
    public void recordFieldAccess(String fieldIdentifier, Object value) {
        if (!enabled) return;
        
        long invId = invocationCounter.get();
        Thread currentThread = Thread.currentThread();
        FieldSnapshot snapshot = new FieldSnapshot(invId, value, currentThread);
        
        fieldSnapshots.computeIfAbsent(fieldIdentifier, k -> 
            Collections.synchronizedList(new ArrayList<>())
        ).add(snapshot);
        
        // Track distinct values seen
        seenValues.computeIfAbsent(fieldIdentifier, k -> 
            ConcurrentHashMap.newKeySet()
        ).add(value);
    }
    
    /**
     * Mark the start of a new invocation round.
     */
    public void markInvocationStart() {
        invocationCounter.incrementAndGet();
    }
    
    /**
     * Analyze visibility issues. Returns a report of suspected visibility issues.
     */
    public VisibilityReport analyzeVisibility() {
        VisibilityReport report = new VisibilityReport();
        
        for (Map.Entry<String, List<FieldSnapshot>> entry : fieldSnapshots.entrySet()) {
            String fieldId = entry.getKey();
            List<FieldSnapshot> snapshots = entry.getValue();
            
            // Check for field value changing across invocations
            Map<Long, Set<Object>> valuesByInvocation = new HashMap<>();
            for (FieldSnapshot snapshot : snapshots) {
                valuesByInvocation.computeIfAbsent(snapshot.invocationId, k -> new HashSet<>())
                    .add(snapshot.value);
            }
            
            // If a field has different values in different invocations, it might be visibility issue
            if (valuesByInvocation.size() > 1) {
                Set<Object> allValues = seenValues.get(fieldId);
                if (allValues != null && allValues.size() > 1) {
                    // Field had different values across invocations
                    report.suspectedFields.add(fieldId);
                    report.fieldValueVariations.put(fieldId, valuesByInvocation);
                }
            }
        }
        
        return report;
    }
    
    public void reset() {
        fieldSnapshots.clear();
        seenValues.clear();
        invocationCounter.set(0);
    }
    
    public void disable() {
        enabled = false;
    }
    
    public void enable() {
        enabled = true;
    }
    
    public static class VisibilityReport {
        public final Set<String> suspectedFields = new HashSet<>();
        public final Map<String, Map<Long, Set<Object>>> fieldValueVariations = new HashMap<>();
        
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
