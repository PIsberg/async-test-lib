package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;

/**
 * Lock awareness, shared by the detectors that watch a non-thread-safe instance.
 *
 * <p>Most of the {@code Shared*} family reduces its input to "how many distinct threads
 * touched this instance". A correctly guarded twin - the same code with every access inside
 * {@code synchronized (instance)} - records an identical event stream, so those detectors
 * reported the fix as loudly as the bug. {@code SharedTypeAccuracyEvalTest} measured that
 * directly: 19 of 19 fired on unguarded sharing, and 17 of 19 also fired on the guarded twin.
 *
 * <p>What closes that gap is the Eraser lockset, in {@link TrackedInstance}: per instance, the
 * intersection of the locks held at every recorded access. Non-empty means some lock protects
 * the instance consistently; empty means none does. The instance's own monitor is one member of
 * that set rather than a special case, so {@code synchronized (theInstance)} needs no
 * declaration, and {@link Thread#holdsLock(Object)} stays the cheap intrinsic that answers for
 * it - it takes no monitor, so asking cannot serialize the threads being observed.
 *
 * <h4>What it does not see</h4>
 *
 * <p>A lock the test never declared. {@code synchronized} on any object other than the tracked
 * instance emits no callback the library can observe, and the agent weaves field access rather
 * than monitor instructions, so a {@code ReentrantLock} or a private lock object enters the set
 * only through {@link HeldLocks}. An undeclared lock is invisible and the finding stands, which
 * is the safe direction to be wrong in; the detector wording says so, so such a finding stays a
 * prompt to verify synchronization rather than a verdict.
 *
 * <p>The probe reflects the thread that calls the record method. Detectors whose recording API
 * takes an explicit {@code Thread} parameter for attribution still probe the caller, which is
 * the thread actually inside (or outside) the guarded region.
 */
final class SelfGuard {

    /**
     * The wording every lock-aware detector appends to a finding, so the report says exactly
     * which synchronization the detector can and cannot see.
     */
    static final String REPORT_NOTE =
            " (accesses under the instance's own monitor count as guarded, as do accesses under a"
            + " lock declared with AsyncTestContext.holdingLock(...); a lock that was never"
            + " declared is not observed - verify external synchronization or use a per-thread"
            + " instance)";

    private SelfGuard() {
    }

    /**
     * {@return whether the calling thread holds {@code instance}'s own monitor}
     *
     * @param instance the shared instance being accessed; {@code null} counts as unguarded
     */
    static boolean heldOn(@Nullable Object instance) {
        return instance != null && Thread.holdsLock(instance);
    }

    /**
     * Per-instance lock bookkeeping, extended by a detector's own state class.
     *
     * <p>Holds the Eraser candidate set: the locks that were held at <em>every</em> recorded
     * access to this instance, as an intersection that only ever shrinks. While it is non-empty
     * some lock consistently protects the instance and there is nothing to report. Once it is
     * empty no lock does, and the accesses were racing.
     *
     * <p>The instance's own monitor is one member of that set rather than a special case, so
     * {@code synchronized (theInstance)} keeps working with no declaration at all, and a
     * {@code ReentrantLock} or a private lock object works once the test declares it through
     * {@link HeldLocks}.
     */
    abstract static class TrackedInstance {

        /**
         * Locks common to every access so far. {@code null} until the first access; empty once
         * the intersection has collapsed, which is the reportable state.
         *
         * <p>An {@link java.util.concurrent.atomic.AtomicReference} rather than a monitor because
         * this runs on the record path of detectors whose whole job is observing contention:
         * taking a lock here could serialize the very threads being watched and hide the race.
         * The update is a CAS loop over a value that only shrinks, so it terminates.
         */
        private final java.util.concurrent.atomic.AtomicReference<int[]> candidateLocks =
                new java.util.concurrent.atomic.AtomicReference<>();

        /**
         * Records one access to {@code instance}, intersecting the candidate set with the locks
         * the calling thread holds right now.
         *
         * <p>Call this from the record path, on the accessing thread, before any analysis
         * bookkeeping - the probe is only meaningful while the caller is still inside the region
         * it is being asked about.
         *
         * @param instance the shared instance being accessed
         */
        final void noteAccess(@Nullable Object instance) {
            if (instance == null) {
                candidateLocks.set(HeldLocks.NONE);
                return;
            }
            int[] current = candidateLocks.get();
            // Fast path: already collapsed, and it can never grow again, so nothing to compute.
            if (current != null && current.length == 0) {
                return;
            }
            while (true) {
                // The compare-and-set is unconditional even when nothing dropped out: the write
                // is then the same reference back, which costs one uncontended CAS and saves
                // having to signal "unchanged" out of band.
                int[] next = HeldLocks.intersect(current, instance);
                if (candidateLocks.compareAndSet(current, next)) {
                    return;
                }
                current = candidateLocks.get();
                if (current != null && current.length == 0) {
                    return;
                }
            }
        }

        /**
         * {@return whether no single lock was held across every recorded access}
         *
         * <p>An instance that has never been accessed reads as guarded, which is correct: with
         * no access there is no hazard to report. The name is unchanged from when this was a
         * guard-on-self boolean, because that is still exactly what it answers for callers - the
         * question just got a better model behind it.
         */
        final boolean sawUnguardedAccess() {
            int[] current = candidateLocks.get();
            return current != null && current.length == 0;
        }

        /**
         * {@return how many locks were held across every recorded access}
         *
         * <p>For reports and tests that want to say which side of the line an instance fell on.
         */
        final int commonLockCount() {
            int[] current = candidateLocks.get();
            return current == null ? 0 : current.length;
        }
    }
}
