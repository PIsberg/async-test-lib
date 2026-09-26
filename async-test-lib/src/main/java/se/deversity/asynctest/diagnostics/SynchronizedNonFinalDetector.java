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
 * <p>This detector tracks the objects used as the monitor for a given lock slot on a given
 * instance. If one instance is seen synchronizing on more than one object, the reference was
 * reassigned and is flagged.
 *
 * <p>The instance matters. Recorded without it, a slot is only a class and a field name, and a
 * reassigned field looks exactly like several instances each holding their own final lock, which
 * is correct code: a holder per thread or per invocation does it every time. Those recordings
 * are listed in the report text as undecided and are not findings; pass the owner to
 * {@link #recordLockObject(Object, String, Class, Object)} to have them decided.
 *
 * <p>Usage:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectSynchronizedNonFinal = true)
 * void testReassignableLock() {
 *     AsyncTestContext.synchronizedNonFinalDetector()
 *         .recordLockObject(lock, "MyClass.lock", MyClass.class, this);
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
        final Set<IdentityKey> identityHashes = ConcurrentHashMap.newKeySet();

        LockSlot(String fieldId, boolean ownerKnown) {
            this.fieldId = fieldId;
            this.ownerKnown = ownerKnown;
        }
    }

    /**
     * Keyed by the declaring class and field id, or by the field id and its owner compared by
     * identity. The owner used to be folded into a string as its identity hash, so two owners
     * whose hashes collided shared a slot and each one's single monitor read as the field
     * changing its lock (#564).
     */
    private final Map<Object, LockSlot> slots = new ConcurrentHashMap<>();

    private record OwnedSlot(String fieldId, IdentityKey owner) {
    }

    /**
     * A slot recorded without its owner, keyed by the declaring class itself rather than its
     * simple name, so two classes that share a simple name in different packages or enclosing
     * types are two slots.
     */
    private record ClassSlot(@Nullable Class<?> ownerClass, String fieldId) {
    }

    // ---- Public API --------------------------------------------------------

    /**
     * Records that {@code lockObject} was used as the monitor for the lock
     * slot identified by {@code fieldId}.
     *
     * <p>Call this immediately before each {@code synchronized (lockObject)} block.
     *
     * <p>Without the owner a monitor that changes is undecidable, so it is listed as a note and
     * never reported; prefer {@link #recordLockObject(Object, String, Class, Object)}.
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
        Object slotKey = owner != null ? new OwnedSlot(key, new IdentityKey(owner))
                : new ClassSlot(ownerClass, fieldId);
        LockSlot slot = slots.computeIfAbsent(slotKey, k -> new LockSlot(key, ownerKnown));
        slot.identityHashes.add(new IdentityKey(lockObject));
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
            if (slot.identityHashes.size() <= 1) {
                continue;
            }
            if (slot.ownerKnown) {
                report.violations.add(String.format(
                        "%s: one instance synchronized on %d different objects — lock reference is "
                            + "NOT FINAL, mutual exclusion is broken!",
                        slot.fieldId, slot.identityHashes.size()));
            } else {
                // Not a finding. Without the owner a reassigned field and N instances each with
                // their own final lock record the same thing, and one of those is correct code.
                report.unattributed.add(String.format(
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
        /** Monitor changes recorded without an owner: undecidable, so notes rather than findings. */
        final List<String> unattributed = new ArrayList<>();

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
            if (!unattributed.isEmpty()) {
                sb.append("  Undecided without an owner (not reported as findings):\n");
                for (String note : unattributed) {
                    sb.append("    - ").append(note).append("\n");
                }
            }

            sb.append("  Fix: declare the lock field as 'final', or replace with a dedicated")
              .append(" java.util.concurrent.locks.ReentrantLock that is never reassigned.");
            return sb.toString();
        }
    }
}
