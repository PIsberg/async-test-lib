package se.deversity.asynctest.diagnostics;

import java.util.Arrays;

import org.jspecify.annotations.Nullable;

/**
 * The locks common to every recorded access, as an intersection: the Eraser lockset.
 *
 * <p>Shared rather than per-detector on purpose. This began inside {@code AtomicityValidator},
 * and every other detector that wants to stop reporting correctly guarded code needs the same
 * algorithm; a second copy would be a twin kept in agreement by convention, which is the drift
 * this repository gates against elsewhere. One implementation, one set of tests.
 *
 * <p>The model fails in one direction on purpose. Intersecting to empty means <em>no single
 * lock covered every access</em>, which is the finding; a surviving lock means one did. A
 * caller holding a lock the library was never told about - not declared through
 * {@code AsyncTestContext.holdingLock} and not observed by the agent - contributes no members,
 * so the intersection collapses and the detector still reports. An invisible lock cannot buy
 * silence, which is the safe way round.
 *
 * <p>An event carries a fingerprint, which {@link HeldLocks#members(long)} turns back into
 * the locks it stands for, plus up to two monitors the fingerprint could not hold: the
 * receiver's own, when the access happened inside one of its {@code synchronized}
 * methods or under {@code synchronized (this)} further up the stack, and the monitor of
 * the enclosing {@code synchronized} method. Intersecting is what the owner-aware path
 * always did; the agent path compared digests for equality, which reported a field
 * guarded by {@code A} as soon as one path touched it under {@code {A, B}}.
 *
 * <p>A fingerprint with no registered members is treated as one opaque lock, so a caller
 * that only ever passed a digest keeps the equality model it had: the same digest twice
 * is consistent, two different digests collapse.
 */
final class Lockset {

    /**
     * {@code null} before the first access; empty once no lock survived. Guarded by this
     * object's monitor, readers included: the producer is the single drain thread and the
     * readers run after analysis quiesces, so the monitor is never contended.
     */
    private int @Nullable [] common;

    synchronized void note(long fingerprint, int ownMonitor, int methodMonitor) {
        int[] current = common;
        if (current != null && current.length == 0) {
            return;
        }
        int[] members = HeldLocks.members(fingerprint);
        if (members == null) {
            members = new int[] {opaque(fingerprint)};
        }
        if (current == null) {
            common = union(members, ownMonitor, methodMonitor);
            return;
        }
        int kept = 0;
        int[] survivors = null;
        for (int hash : current) {
            boolean held = (hash == ownMonitor && ownMonitor != 0)
                    || (hash == methodMonitor && methodMonitor != 0)
                    || contains(members, hash);
            if (held) {
                if (survivors != null) {
                    survivors[kept] = hash;
                }
                kept++;
            } else if (survivors == null) {
                survivors = new int[current.length - 1];
                System.arraycopy(current, 0, survivors, 0, kept);
            }
        }
        if (survivors == null) {
            return;
        }
        common = kept == 0 ? HeldLocks.NONE : Arrays.copyOf(survivors, kept);
    }

    /** {@return whether some access was recorded and no lock covered all of them} */
    synchronized boolean collapsed() {
        return common != null && common.length == 0;
    }

    /** {@return whether at least one access was recorded and some lock covered all of them} */
    synchronized boolean guarded() {
        return common != null && common.length > 0;
    }

    /** {@return a copy of the surviving intersection; empty before any note or after collapse} */
    synchronized int[] survivors() {
        return common == null || common.length == 0 ? HeldLocks.NONE : common.clone();
    }

    static int[] union(int[] members, int ownMonitor, int methodMonitor) {
        int extra = (ownMonitor != 0 && !contains(members, ownMonitor) ? 1 : 0)
                + (methodMonitor != 0 && methodMonitor != ownMonitor
                        && !contains(members, methodMonitor) ? 1 : 0);
        if (members.length + extra == 0) {
            return HeldLocks.NONE;
        }
        int[] out = Arrays.copyOf(members, members.length + extra);
        int at = members.length;
        if (ownMonitor != 0 && !contains(members, ownMonitor)) {
            out[at] = ownMonitor;
            at++;
        }
        if (methodMonitor != 0 && methodMonitor != ownMonitor
                && !contains(members, methodMonitor)) {
            out[at] = methodMonitor;
        }
        return out;
    }

    static boolean contains(int[] hashes, int hash) {
        for (int candidate : hashes) {
            if (candidate == hash) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@return a lock id for a digest nobody registered, in a range no identity hash uses}
     *
     * <p>Identity hashes are non-negative, so the sign bit marks an opaque id and the two
     * can never be confused for one another.
     */
    static int opaque(long fingerprint) {
        return (int) (fingerprint ^ (fingerprint >>> 32)) | Integer.MIN_VALUE;
    }
}
