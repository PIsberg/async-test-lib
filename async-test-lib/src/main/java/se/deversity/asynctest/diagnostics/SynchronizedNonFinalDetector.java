package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;

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
        /** Whether the caller identified the object that declares the field. */
        final boolean ownerKnown;
        final Set<Integer> identityHashes = ConcurrentHashMap.newKeySet();

        LockSlot(String fieldId, boolean ownerKnown) {
            this.fieldId = fieldId;
            this.ownerKnown = ownerKnown;
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
        recordLockObject(lockObject, fieldId, ownerClass, null);
    }

    /**
     * Records a lock object together with the instance that declares the field.
     *
     * <p>The owner is what separates the two things a changing monitor can mean. Without it the
     * slot is keyed by class and field name alone, so N workers each holding their own
     * {@code new Service()} - each with its own {@code private final Object lock} - all record
     * into one slot and look exactly like one field reassigned N times. That is correct code, and
     * the finding used to assert it was not final, which is a fact the detector had no way to
     * know (#501). Pass {@code owner} and each instance gets its own slot, so only a monitor that
     * really changed on one object is reported.
     *
     * @param lockObject the object used as the monitor
     * @param fieldId    a stable identifier for the field, e.g. {@code "MyService.lock"}
     * @param ownerClass the class that declares the field (used in reports)
     * @param owner      the instance that declares the field, or {@code null} when unknown
     * @since 1.11.2
     */
    public void recordLockObject(Object lockObject, String fieldId, Class<?> ownerClass,
                                 @Nullable Object owner) {
        if (lockObject == null || fieldId == null) return;
        String key = (ownerClass != null) ? ownerClass.getSimpleName() + "." + fieldId : fieldId;
        boolean ownerKnown = owner != null;
        String slotKey = ownerKnown ? key + "@" + System.identityHashCode(owner) : key;
        LockSlot slot = slots.computeIfAbsent(slotKey, k -> new LockSlot(key, ownerKnown));
        slot.identityHashes.add(System.identityHashCode(lockObject));
    }

    // ---- Analysis ----------------------------------------------------------

    /**
     * Analyses recorded lock objects and returns a report of slots where the
     * monitor reference changed across invocations.
     *
     * @return the findings this detector collected during the run
     */
    public SynchronizedNonFinalReport analyze() {
        SynchronizedNonFinalReport report = new SynchronizedNonFinalReport();

        for (LockSlot slot : slots.values()) {
            if (slot.identityHashes.size() > 1) {
                report.violations.add(slot.ownerKnown
                    ? String.format(
                        "%s: one instance synchronized on %d different objects — lock reference is "
                            + "NOT FINAL, mutual exclusion is broken!",
                        slot.fieldId, slot.identityHashes.size())
                    : String.format(
                        "%s: synchronized on %d different objects. Either the field was reassigned, "
                            + "in which case mutual exclusion is broken, or each of %d instances "
                            + "has its own final lock, which is correct. This recording did not say "
                            + "which instance each monitor belonged to; pass the owner to "
                            + "recordLockObject to have that decided here.",
                        slot.fieldId, slot.identityHashes.size(), slot.identityHashes.size()));
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

        /**
         * Returns {@code true} when any reassignable-lock violation was detected.
         *
         * @return {@code true} when this detector recorded something worth reporting
         */
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
