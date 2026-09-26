package se.deversity.asynctest.diagnostics;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Finds two distinct live objects that share one {@code System.identityHashCode}, so a test can
 * show that a detector keeps them apart.
 *
 * <p>A collision cannot be requested, but it can be searched for. The identity hash has 31 bits on
 * a 64-bit HotSpot, so by the birthday bound a pair turns up after about 58,000 allocations. The
 * search holds every candidate strongly until then, so no hash is recycled under it.
 */
final class IdentityCollisions {

    /** Far past the birthday bound for 31 bits; reaching it means the hash is wider than assumed. */
    private static final int MAX_CANDIDATES = 4_000_000;

    private IdentityCollisions() {
    }

    /**
     * {@return two distinct instances from {@code factory} with the same identity hash, the
     * earlier one first}
     *
     * @param factory makes a fresh instance on every call
     * @param <T> the instance type
     */
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"}) // identity is the point
    static <T> List<T> pair(Supplier<? extends T> factory) {
        Map<Integer, T> seen = new HashMap<>();
        for (int i = 0; i < MAX_CANDIDATES; i++) {
            T candidate = factory.get();
            T earlier = seen.putIfAbsent(System.identityHashCode(candidate), candidate);
            if (earlier == candidate) {
                throw new IllegalArgumentException("the factory returned the same instance twice; "
                        + "a non-capturing lambda is one shared object, so make a fresh one");
            }
            if (earlier != null) {
                return List.of(earlier, candidate);
            }
        }
        throw new AssertionError("no identity-hash collision in " + MAX_CANDIDATES
                + " allocations; this JVM's identity hash is wider than 31 bits");
    }
}
