package se.deversity.asynctest.diagnostics;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
        final String className;
        /**
         * The thread that called {@code recordConstructionStart}. An access from any other
         * thread before construction completes is unsafe publication — which is only decidable
         * if we remember who was constructing.
         */
        final long constructingThreadId;
        volatile boolean constructionComplete = false;
        final AtomicInteger threadsThatAccessedDuringConstruction = new AtomicInteger(0);
        final Set<Long> accessingThreadIds = ConcurrentHashMap.newKeySet();
        final Map<String, FieldAccessInfo> fieldAccesses = new ConcurrentHashMap<>();

        ObjectState(String className, long constructingThreadId) {
            this.className = className;
            this.constructingThreadId = constructingThreadId;
        }
    }

    private static final class FieldAccessInfo {
        final AtomicInteger accessCount = new AtomicInteger(0);
        final Set<Long> accessingThreadIds = ConcurrentHashMap.newKeySet();
    }
    
    private final Map<Integer, ObjectState> objects = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    
    /**
     * Mark the start of object construction.
     *
     * @param object the object the access is on, tracked by identity
     */
    public void recordConstructionStart(Object object) {
        if (!enabled || object == null) return;

        int id = System.identityHashCode(object);
        objects.putIfAbsent(id, new ObjectState(object.getClass().getSimpleName(),
                                                Thread.currentThread().threadId()));
    }
    
    /**
     * Mark the end of object construction.
     *
     * @param object the object the access is on, tracked by identity
     */
    public void recordConstructionEnd(Object object) {
        if (!enabled) return;
        
        int id = System.identityHashCode(object);
        ObjectState state = objects.get(id);
        if (state != null) {
            state.constructionComplete = true;
        }
    }
    
    /**
     * Record a field access to a partially constructed object.
     *
     * @param object the object the access is on, tracked by identity
     * @param fieldName the field involved, as it should appear in the report
     * @param timestamp when the event happened, in nanoseconds
     */
    public void recordFieldAccess(Object object, String fieldName, long timestamp) {
        if (!enabled) return;
        
        int objectId = System.identityHashCode(object);
        ObjectState state = objects.get(objectId);
        if (state == null) return;
        
        long threadId = Thread.currentThread().threadId();
        
        FieldAccessInfo fieldInfo = state.fieldAccesses.computeIfAbsent(fieldName,
            k -> new FieldAccessInfo()
        );
        
        fieldInfo.accessCount.incrementAndGet();
        fieldInfo.accessingThreadIds.add(threadId);
        state.accessingThreadIds.add(threadId);
        
        if (!state.constructionComplete && threadId != state.constructingThreadId) {
            // A thread other than the one still running the constructor can see this object:
            // unsafe publication. Comparing against the *constructing* thread is the whole
            // point — the previous check compared threadId to Thread.currentThread().threadId(),
            // the expression it had just been assigned from, so it was always false and this
            // counter never moved.
            state.threadsThatAccessedDuringConstruction.incrementAndGet();
        }
    }
    
    /**
     * Validate constructor safety.
     *
     * @return the findings this detector collected during the run
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
            
            // Started and never completed. There used to be a second case here: a
            // construction that finished in under a microsecond was reported as
            // "possibly incomplete". It was backwards. Elapsed time cannot distinguish a
            // completed construction from an incomplete one, the branch only ran when
            // constructionComplete was already true so the construction demonstrably did
            // complete, and a constructor that assigns three fields takes tens of
            // nanoseconds — so every ordinary constructor a caller instrumented produced a
            // finding, burying the real one (unsafeObjects) next to noise. See issue #357.
            if (!state.constructionComplete) {
                report.possiblyIncompleteConstructions.add(
                    state.className + " (construction started but never completed)"
                );
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
    /**
     * Clears recorded the observation so this instance can be reused for the next run.
     */
    public void reset() {
        objects.clear();
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
    
    public static class ConstructorSafetyReport {
        /** Objects whose reference escaped their constructor. */
        public final Set<String> unsafeObjects = new HashSet<>();
        /**
         * Objects whose construction was recorded as started and never recorded as finished.
         * Not part of {@link #hasIssues()}: a caller that instruments the start and forgets the
         * end produces this without a defect, so it is context for a finding rather than one.
         */
        public final Set<String> possiblyIncompleteConstructions = new HashSet<>();
        /** Fields read by another thread before the constructor returned. */
        public final Set<String> fieldsAccessedDuringConstruction = new HashSet<>();
        
        /**
         * {@return whether there are issues}
         */
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
                sb.append("\nConstructions that started and never completed:\n");
                for (String cons : possiblyIncompleteConstructions) {
                    sb.append("  - ").append(cons).append("\n");
                }
            }
            
            return sb.toString();
        }
    }
}
