package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;

/**
 * Guard-on-self synchronization awareness, shared by the detectors that watch a
 * non-thread-safe instance.
 *
 * <p>Most of the {@code Shared*} family reduces its input to "how many distinct threads
 * touched this instance". A correctly guarded twin - the same code with every access inside
 * {@code synchronized (instance)} - records an identical event stream, so those detectors
 * reported the fix as loudly as the bug. {@code SharedTypeAccuracyEvalTest} measured that
 * directly: 19 of 19 fired on unguarded sharing, and 17 of 19 also fired on the guarded twin.
 *
 * <p>The probe here is what closes that gap. {@link Thread#holdsLock(Object)} is an intrinsic
 * over the calling thread's own lock records: it takes no monitor, so asking the question
 * cannot serialize the threads being observed, and it is cheap enough for a record-time hot
 * path. An instance whose every recorded access held its own monitor produces no finding.
 *
 * <h4>What it does not see</h4>
 *
 * <p>Only the instance's own monitor. A {@code ReentrantLock}, a private lock object, or any
 * other external guard is invisible and still produces a finding, because nothing in the
 * recording path names the lock the caller chose. Detector wording says so, so a finding stays
 * a prompt to verify synchronization rather than a verdict. Closing that direction needs a
 * per-thread held-lock set rather than a single-object probe.
 *
 * <p>The probe reflects the thread that calls the record method. Detectors whose recording API
 * takes an explicit {@code Thread} parameter for attribution still probe the caller, which is
 * the thread actually inside (or outside) the guarded region.
 */
final class SelfGuard {

    /**
     * The wording every guard-aware detector appends to a finding, so the report says exactly
     * which synchronization the detector can and cannot see.
     */
    static final String REPORT_NOTE =
            " (accesses under the instance's own monitor count as guarded; other locks are not"
            + " observed - verify external synchronization or use a per-thread instance)";

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
     * Per-instance guard bookkeeping, extended by a detector's own state class.
     *
     * <p>The flag is one-way on purpose. A single access that held no lock on the instance is
     * enough to make the sharing unsafe, and no later guarded access can undo it, so the state
     * only ever moves from guarded to unguarded and never needs a lock of its own.
     */
    abstract static class TrackedInstance {

        private volatile boolean sawUnguardedAccess;

        /**
         * Records one access to {@code instance}, probing the guard on the calling thread.
         *
         * <p>Call this from the record path, on the accessing thread, before any analysis
         * bookkeeping - the probe is only meaningful while the caller is still inside the
         * region it is being asked about.
         *
         * @param instance the shared instance being accessed
         */
        final void noteAccess(@Nullable Object instance) {
            if (!heldOn(instance)) {
                sawUnguardedAccess = true;
            }
        }

        /**
         * {@return whether at least one recorded access did not hold the instance's own monitor}
         *
         * <p>An instance that has never been accessed reads as guarded, which is correct: with
         * no access there is no hazard to report.
         */
        final boolean sawUnguardedAccess() {
            return sawUnguardedAccess;
        }
    }
}
