package se.deversity.asynctest.diagnostics;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
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
 *
 * <p><strong>What counts as construction.</strong> The object's constructor running, which is
 * where the hazard lives, bounded by {@link #recordConstructionStart(Object)} and
 * {@link #recordConstructionEnd(Object)} recorded from inside it. The records are checked
 * against the stack rather than taken at their word:
 * <ul>
 *   <li>A start recorded with no constructor of the object's class on the recording thread's
 *       stack is not a construction. The reference already exists outside its constructor, so
 *       the object is built, and publishing it through a volatile, a concurrent collection, a
 *       lock or a plain field happens after construction ended in program order. Nothing is
 *       tracked.</li>
 *   <li>A read by another thread before the end is recorded is checked against the
 *       constructing thread's stack at that moment. If no constructor of the object's class is
 *       running there, the constructor has returned and the read is after construction, which
 *       covers an end recorded late (after publishing) or never. Only a read made while the
 *       constructor is still on the constructing thread's stack is a finding: the reference
 *       escaped it.</li>
 *   <li>A start recorded on the constructing thread at the same stack depth as an earlier
 *       construction, or shallower, closes that earlier one: its constructor frame was popped
 *       for the new one to run there. A pooled thread that goes on to build another instance
 *       of the same class therefore stops reading as still building the first (#778), while a
 *       construction nested inside a running constructor starts deeper and closes nothing.
 *       What the stack cannot tell apart is the window between the later constructor starting
 *       and its start being recorded, or a later constructor of the class that records no
 *       start: a read made there still counts against the earlier instance.</li>
 * </ul>
 */
public class ConstructorSafetyValidator {

    /** Walks the recording thread's stack for a constructor frame of the object's class. */
    private static final StackWalker WALKER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    /**
     * The depth, counted from the bottom of the recording thread's stack, of the innermost
     * constructor frame of the object's class, or {@code -1} when no such frame is running.
     */
    private static int constructorDepthOf(Object object) {
        Class<?> type = object.getClass();
        return WALKER.walk(frames -> {
            List<StackWalker.StackFrame> stack = frames.toList();
            for (int i = 0; i < stack.size(); i++) {
                StackWalker.StackFrame frame = stack.get(i);
                if ("<init>".equals(frame.getMethodName())
                        && frame.getDeclaringClass() != Object.class
                        && frame.getDeclaringClass().isAssignableFrom(type)) {
                    return stack.size() - i;
                }
            }
            return -1;
        });
    }

    /** Names of the object's class and its superclasses below {@code Object}. */
    private static Set<String> constructorOwners(Class<?> type) {
        Set<String> owners = new HashSet<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            owners.add(c.getName());
        }
        return owners;
    }
    
    private static class ObjectState {
        final String className;
        /**
         * The thread that called {@code recordConstructionStart}. An access from any other
         * thread before construction completes is unsafe publication — which is only decidable
         * if we remember who was constructing.
         */
        final long constructingThreadId;
        /** The constructing thread itself, weakly, so its stack can be read at an access. */
        final WeakReference<Thread> constructingThread;
        /** Classes whose {@code <init>} frame on that stack means the constructor is running. */
        final Set<String> constructorOwners;
        /** Where the constructor frame sat on that stack, counted from the bottom, at the start. */
        final int constructorDepth;
        volatile boolean constructionComplete = false;
        /** Accesses made before construction finished, by a thread other than the constructor's. */
        final AtomicInteger accessesDuringConstruction = new AtomicInteger(0);
        /**
         * The distinct threads behind {@link #accessesDuringConstruction}. Kept apart because the
         * report speaks of threads: one thread reading the half-built object ten times is one
         * escape, and counting the accesses as threads overstated every finding (#501).
         */
        final Set<Long> threadsAccessingDuringConstruction = ConcurrentHashMap.newKeySet();
        final Set<Long> accessingThreadIds = ConcurrentHashMap.newKeySet();
        final Map<String, FieldAccessInfo> fieldAccesses = new ConcurrentHashMap<>();

        ObjectState(Object object, Thread constructing, int constructorDepth) {
            this.className = object.getClass().getSimpleName();
            this.constructingThreadId = constructing.threadId();
            this.constructingThread = new WeakReference<>(constructing);
            this.constructorOwners = constructorOwners(object.getClass());
            this.constructorDepth = constructorDepth;
        }

        /** Whether a constructor of the object's class is on the constructing thread's stack now. */
        boolean constructorStillRunning() {
            Thread constructing = constructingThread.get();
            if (constructing == null || !constructing.isAlive()) {
                return false;
            }
            for (StackTraceElement frame : constructing.getStackTrace()) {
                if ("<init>".equals(frame.getMethodName())
                        && constructorOwners.contains(frame.getClassName())) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class FieldAccessInfo {
        final AtomicInteger accessCount = new AtomicInteger(0);
        final Set<Long> accessingThreadIds = ConcurrentHashMap.newKeySet();
        /** Set once a thread other than the constructor's read this field inside the window. */
        volatile boolean accessedByAnotherThreadDuringConstruction = false;
    }
    
    private final Map<IdentityKey, ObjectState> objects = new ConcurrentHashMap<>();
    /**
     * Per constructing thread, its recorded constructions not yet seen to end, innermost on top.
     * Each deque is touched only by the thread it belongs to.
     */
    private final Map<Long, Deque<ObjectState>> openConstructions = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    
    /**
     * Mark the start of object construction, from inside the object's constructor.
     *
     * @param object the object the access is on, tracked by identity
     */
    public void recordConstructionStart(Object object) {
        if (!enabled || object == null) return;

        int depth = constructorDepthOf(object);
        if (depth < 0) {
            // Recorded outside any constructor of the object: it is already built.
            return;
        }
        Thread current = Thread.currentThread();
        ObjectState state = new ObjectState(object, current, depth);
        if (objects.putIfAbsent(new IdentityKey(object), state) != null) {
            return; // a superclass or subclass constructor of the same object recorded it first
        }
        // A construction this thread started at the same depth or deeper has returned: the frame
        // it ran in was popped for this one to sit there. Without this, a pooled thread inside a
        // later instance's constructor of the same class read as still building the earlier one
        // whose end was never recorded (#778). A nested construction starts deeper and closes
        // nothing, so the outer one stays open.
        Deque<ObjectState> open = openConstructions.computeIfAbsent(
                current.threadId(), k -> new ArrayDeque<>());
        while (!open.isEmpty() && open.peek().constructorDepth >= depth) {
            open.pop().constructionComplete = true;
        }
        open.push(state);
    }
    
    /**
     * Mark the end of object construction.
     *
     * @param object the object the access is on, tracked by identity
     */
    public void recordConstructionEnd(Object object) {
        if (!enabled || object == null) return;
        
        IdentityKey id = new IdentityKey(object);
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
        if (!enabled || object == null) return;
        
        IdentityKey objectId = new IdentityKey(object);
        ObjectState state = objects.get(objectId);
        if (state == null) return;
        
        long threadId = Thread.currentThread().threadId();
        
        FieldAccessInfo fieldInfo = state.fieldAccesses.computeIfAbsent(fieldName,
            k -> new FieldAccessInfo()
        );
        
        fieldInfo.accessCount.incrementAndGet();
        fieldInfo.accessingThreadIds.add(threadId);
        state.accessingThreadIds.add(threadId);
        
        if (!state.constructionComplete && threadId != state.constructingThreadId
                && !constructorReturned(state)) {
            // A thread other than the one still running the constructor can see this object:
            // unsafe publication. Comparing against the *constructing* thread is the whole
            // point — the previous check compared threadId to Thread.currentThread().threadId(),
            // the expression it had just been assigned from, so it was always false and this
            // counter never moved.
            state.accessesDuringConstruction.incrementAndGet();
            state.threadsAccessingDuringConstruction.add(threadId);
            fieldInfo.accessedByAnotherThreadDuringConstruction = true;
        }
    }
    
    /**
     * Whether the constructor has returned although no end was recorded: the constructing
     * thread's stack no longer holds a constructor of the object's class. Once seen, the
     * construction is closed, so later reads skip the stack walk.
     */
    private static boolean constructorReturned(ObjectState state) {
        if (state.constructorStillRunning()) {
            return false;
        }
        state.constructionComplete = true;
        return true;
    }

    /**
     * Validate constructor safety.
     *
     * @return the findings this detector collected during the run
     */
    public ConstructorSafetyReport validateConstructorSafety() {
        ConstructorSafetyReport report = new ConstructorSafetyReport();
        
        for (ObjectState state : objects.values()) {
            if (state.accessesDuringConstruction.get() > 0) {
                // Object accessed by another thread before construction finished
                report.unsafeObjects.add(String.format(
                    "%s: Accessed by %d thread(s) during construction, %d access(es) in total",
                    state.className,
                    state.threadsAccessingDuringConstruction.size(),
                    state.accessesDuringConstruction.get()
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

            // Fields another thread read inside the window. This used to be "the field was
            // touched by two threads at any time, and the construction is not closed now",
            // which counted reads made after a safe publication whenever the end was recorded
            // late or never; the access itself has to fall inside the window.
            for (Map.Entry<String, FieldAccessInfo> entry : state.fieldAccesses.entrySet()) {
                if (entry.getValue().accessedByAnotherThreadDuringConstruction) {
                    report.fieldsAccessedDuringConstruction.add(
                        state.className + "." + entry.getKey()
                    );
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
        openConstructions.clear();
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
            sb.append(IssueSeverity.HIGH.format()).append(": CONSTRUCTOR SAFETY ISSUES DETECTED:\n");
            
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
