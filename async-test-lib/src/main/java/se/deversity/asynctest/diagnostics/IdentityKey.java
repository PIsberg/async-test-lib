package se.deversity.asynctest.diagnostics;

import java.util.Objects;

/**
 * A map key that identifies an object by reference, for detector state tracked per instance.
 *
 * <p><strong>Why not {@code System.identityHashCode} as the key.</strong> An identity hash is not
 * unique. Two live objects share one about half the time once some 54,000 are tracked, by the
 * birthday bound, and far sooner on a JVM whose hash has fewer significant bits. A map keyed by the
 * bare hash then merges two objects into one entry, and the detector attributes one's events to the
 * other: a pool created per body inherits an earlier pool's shutdown, one thread-local's cleanup
 * covers another's leak, two per-thread instances read as one shared instance. Every one of those
 * is silent. #564 counted 79 detector classes keyed that way.
 *
 * <p>This key caches the identity hash, so hashing costs what it did, and compares referents with
 * {@code ==}, so a collision costs a bucket neighbour and never a merge. It is also not
 * {@link Object#equals}: two distinct {@code String}s with the same characters are two keys, which
 * is the point for a detector that asks whether one instance is shared.
 *
 * <p>The reference is strong. Detector state is scoped to one run and released by the detector's
 * reset, and a detector that must not hold what it tracks already says so and keeps its own weak
 * structure.
 */
final class IdentityKey {

    private final Object referent;
    private final int identityHash;

    /**
     * @param referent the tracked instance
     * @throws NullPointerException if {@code referent} is null; detectors skip null records before
     *                              they reach a map, and a null key would hide that they did not
     */
    IdentityKey(Object referent) {
        this.referent = Objects.requireNonNull(referent, "referent");
        this.identityHash = System.identityHashCode(referent);
    }

    /** {@return the tracked instance} */
    Object referent() {
        return referent;
    }

    @Override
    @SuppressWarnings("ReferenceEquality") // referent identity is the point; see the class javadoc
    public boolean equals(Object other) {
        return other instanceof IdentityKey that && that.referent == this.referent;
    }

    @Override
    public int hashCode() {
        return identityHash;
    }

    @Override
    public String toString() {
        return referent.getClass().getSimpleName() + "@" + Integer.toHexString(identityHash);
    }

    /**
     * The same identity comparison, holding its referent weakly.
     *
     * <p>For state that must not keep what it describes alive: an object the agent saw once and the
     * code under test then dropped should not survive the run because a detector remembered it.
     * Once the referent is collected the key equals only itself, which is what lets a map remove it
     * after its {@link java.lang.ref.ReferenceQueue} reports it.
     */
    static final class Weak extends java.lang.ref.WeakReference<Object> {

        private final int identityHash;

        /**
         * @param referent the tracked instance
         * @param queue    where the key is enqueued once the referent is collected, or {@code null}
         *                 for a lookup key that is never stored
         */
        Weak(Object referent,
             java.lang.ref.@org.jspecify.annotations.Nullable ReferenceQueue<Object> queue) {
            super(Objects.requireNonNull(referent, "referent"), queue);
            this.identityHash = System.identityHashCode(referent);
        }

        @Override
        @SuppressWarnings("ReferenceEquality") // referent identity is the point; see IdentityKey
        public boolean equals(Object other) {
            if (other == this) {
                return true;
            }
            if (!(other instanceof Weak that)) {
                return false;
            }
            Object mine = get();
            return mine != null && mine == that.get();
        }

        @Override
        public int hashCode() {
            return identityHash;
        }
    }
}
