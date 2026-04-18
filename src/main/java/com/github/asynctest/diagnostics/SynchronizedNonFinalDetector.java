package com.github.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects the anti-pattern of synchronizing on a non-final, reassignable
 * object reference.
 *
 * <p>When a field used as a lock is not declared {@code final}, a different
 * thread may reassign the field between invocations.  Two threads may then
 * synchronize on <em>different</em> object instances, providing no mutual
 * exclusion at all:
 *
 * <pre>{@code
 * // BUG: lock is not final — can be reassigned
 * private Object lock = new Object();
 *
 * void doWork() {
 *     synchronized (lock) { ... }  // each thread may hold a different lock!
 * }
 * }</pre>
 *
 * <p>This detector tracks the identity hash codes of objects used for a given
 * named lock slot across invocations.  If more than one distinct identity hash
 * code is observed, the reference was reassigned and is flagged.
 *
 * <p>Usage:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectSynchronizedNonFinal = true)
 * void testReassignableLock() {
 *     AsyncTestContext.synchronizedNonFinalDetector()
 *         .recordLockObject(lock, "MyClass.lock", MyClass.class);
 *     synchronized (lock) {
 *         // critical section
 *     }
 * }
 * }</pre>
 */
public class SynchronizedNonFinalDetector {

    private static final class LockSlot {
        final String fieldId;
        final Set<Integer> identityHashes = ConcurrentHashMap.newKeySet();

        LockSlot(String fieldId) {
            this.fieldId = fieldId;
        }
    }

    private final Map<String, LockSlot> slots = new ConcurrentHashMap<>();

    // ---- Public API --------------------------------------------------------

    /**
     * Records that {@code lockObject} was used as the monitor for the lock
     * slot identified by {@code fieldId}.
     *
     * <p>Call this immediately before each {@code synchronized (lockObject)} block.
     *
     * @param lockObject the object used as the monitor
     * @param fieldId    a stable identifier for the field, e.g. {@code "MyService.lock"}
     * @param ownerClass the class that declares the field (used in reports)
     */
    public void recordLockObject(Object lockObject, String fieldId, Class<?> ownerClass) {
        if (lockObject == null || fieldId == null) return;
        String key = (ownerClass != null) ? ownerClass.getSimpleName() + "." + fieldId : fieldId;
        LockSlot slot = slots.computeIfAbsent(key, LockSlot::new);
        slot.identityHashes.add(System.identityHashCode(lockObject));
    }

    // ---- Analysis ----------------------------------------------------------

    /**
     * Analyses recorded lock objects and returns a report of slots where the
     * monitor reference changed across invocations.
     */
    public SynchronizedNonFinalReport analyze() {
        SynchronizedNonFinalReport report = new SynchronizedNonFinalReport();

        for (LockSlot slot : slots.values()) {
            if (slot.identityHashes.size() > 1) {
                report.violations.add(String.format(
                        "%s: synchronized on %d different object instances — lock reference is NOT FINAL, mutual exclusion is broken!",
                        slot.fieldId, slot.identityHashes.size()));
            }
        }

        return report;
    }

    // ---- Report ------------------------------------------------------------

    /**
     * Report produced by {@link #analyze()}.
     */
    public static class SynchronizedNonFinalReport {

        final List<String> violations = new ArrayList<>();

        /** Returns {@code true} when any reassignable-lock violation was detected. */
        public boolean hasIssues() {
            return !violations.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("SYNCHRONIZED-ON-NON-FINAL ISSUES DETECTED:\n");

            if (!violations.isEmpty()) {
                sb.append("  Reassignable Lock Violations:\n");
                for (String v : violations) {
                    sb.append("    - ").append(v).append("\n");
                }
            } else {
                sb.append("  No violations detected.\n");
            }

            sb.append("  Fix: declare the lock field as 'final', or replace with a dedicated")
              .append(" java.util.concurrent.locks.ReentrantLock that is never reassigned.");
            return sb.toString();
        }
    }
}
