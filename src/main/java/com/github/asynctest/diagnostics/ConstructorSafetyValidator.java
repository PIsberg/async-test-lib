package com.github.asynctest.diagnostics;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Validates that objects are fully constructed before being shared across threads.
 * 
 * Problem: If an object is published to other threads before its constructor finishes,
 * those threads may see partially initialized fields due to:
 * - Compiler reordering of writes
 * - CPU memory ordering
 * - Lack of visibility barriers
 * 
 * This detector tracks object construction and access across threads.
 */
public class ConstructorSafetyValidator {
    
    private static class ObjectState {
        final int objectId;
        final String className;
        volatile boolean constructionComplete = false;
        final AtomicInteger threadsThatAccessedDuringConstruction = new AtomicInteger(0);
        final Set<Long> accessingThreadIds = ConcurrentHashMap.newKeySet();
        volatile long constructionStartTime = 0;
        volatile long constructionEndTime = 0;
        final Map<String, FieldAccessInfo> fieldAccesses = new ConcurrentHashMap<>();
        
        ObjectState(int id, String className) {
            this.objectId = id;
            this.className = className;
            this.constructionStartTime = System.nanoTime();
        }
    }
    
    private static class FieldAccessInfo {
        volatile long firstAccessTime = 0;
        volatile int accessCount = 0;
        final Set<Long> accessingThreadIds = ConcurrentHashMap.newKeySet();
    }
    
    private final Map<Integer, ObjectState> objects = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    
    /**
     * Mark the start of object construction.
     */
    public void recordConstructionStart(Object object) {
        if (!enabled) return;
        
        int id = System.identityHashCode(object);
        objects.putIfAbsent(id, new ObjectState(id, object.getClass().getSimpleName()));
    }
    
    /**
     * Mark the end of object construction.
     */
    public void recordConstructionEnd(Object object) {
        if (!enabled) return;
        
        int id = System.identityHashCode(object);
        ObjectState state = objects.get(id);
        if (state != null) {
            state.constructionComplete = true;
            state.constructionEndTime = System.nanoTime();
        }
    }
    
    /**
     * Record a field access to a partially constructed object.
     */
    public void recordFieldAccess(Object object, String fieldName, long timestamp) {
        if (!enabled) return;
        
        int objectId = System.identityHashCode(object);
        ObjectState state = objects.get(objectId);
        if (state == null) return;
        
        long threadId = Thread.currentThread().getId();
        
        FieldAccessInfo fieldInfo = state.fieldAccesses.computeIfAbsent(fieldName,
            k -> new FieldAccessInfo()
        );
        
        fieldInfo.accessCount++;
        fieldInfo.accessingThreadIds.add(threadId);
        state.accessingThreadIds.add(threadId);
        
        if (!state.constructionComplete && state.constructionStartTime > 0) {
            fieldInfo.firstAccessTime = timestamp;
            // Different thread accessing object during construction
            if (threadId != Thread.currentThread().getId()) {
                state.threadsThatAccessedDuringConstruction.incrementAndGet();
            }
        }
    }
    
    /**
     * Validate constructor safety.
     */
    public ConstructorSafetyReport validateConstructorSafety() {
        ConstructorSafetyReport report = new ConstructorSafetyReport();
        
        for (ObjectState state : objects.values()) {
            if (state.threadsThatAccessedDuringConstruction.get() > 0) {
                // Object accessed by multiple threads before construction finished
                report.unsafeObjects.add(String.format(
                    "%s: Accessed by %d threads during construction",
                    state.className,
                    state.threadsThatAccessedDuringConstruction.get()
                ));
            }
            
            if (state.constructionComplete) {
                long constructionTime = state.constructionEndTime - state.constructionStartTime;
                
                // Check if construction was very fast (suspicious, might not complete properly)
                if (constructionTime < 1000) { // < 1 microsecond
                    report.possiblyIncompleteConstructions.add(
                        state.className + " (completed in " + constructionTime + "ns)"
                    );
                }
            }
            
            // Check for field races during construction
            for (Map.Entry<String, FieldAccessInfo> entry : state.fieldAccesses.entrySet()) {
                FieldAccessInfo fieldInfo = entry.getValue();
                if (fieldInfo.accessingThreadIds.size() > 1) {
                    // Multiple threads accessing same field
                    if (!state.constructionComplete) {
                        report.fieldsAccessedDuringConstruction.add(
                            state.className + "." + entry.getKey()
                        );
                    }
                }
            }
        }
        
        return report;
    }
    
    public void reset() {
        objects.clear();
    }
    
    public void disable() {
        enabled = false;
    }
    
    public void enable() {
        enabled = true;
    }
    
    public static class ConstructorSafetyReport {
        public final Set<String> unsafeObjects = new HashSet<>();
        public final Set<String> possiblyIncompleteConstructions = new HashSet<>();
        public final Set<String> fieldsAccessedDuringConstruction = new HashSet<>();
        
        public boolean hasIssues() {
            return !unsafeObjects.isEmpty() || !fieldsAccessedDuringConstruction.isEmpty();
        }
        
        @Override
        public String toString() {
            if (!hasIssues()) {
                return "No constructor safety issues detected.";
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("CONSTRUCTOR SAFETY ISSUES DETECTED:\n");
            
            if (!unsafeObjects.isEmpty()) {
                sb.append("\nObjects accessed by multiple threads during construction:\n");
                for (String issue : unsafeObjects) {
                    sb.append("  - ").append(issue).append("\n");
                }
                sb.append("  Fix: Don't share object reference until constructor completes\n");
            }
            
            if (!fieldsAccessedDuringConstruction.isEmpty()) {
                sb.append("\nFields accessed by multiple threads during construction:\n");
                for (String field : fieldsAccessedDuringConstruction) {
                    sb.append("  - ").append(field).append("\n");
                }
                sb.append("  Fix: Use final fields and proper initialization order\n");
            }
            
            if (!possiblyIncompleteConstructions.isEmpty()) {
                sb.append("\nConstructions completed suspiciously fast (may be incomplete):\n");
                for (String cons : possiblyIncompleteConstructions) {
                    sb.append("  - ").append(cons).append("\n");
                }
            }
            
            return sb.toString();
        }
    }
}
