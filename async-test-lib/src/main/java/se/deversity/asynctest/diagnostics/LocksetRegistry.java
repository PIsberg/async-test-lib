package se.deversity.asynctest.diagnostics;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jspecify.annotations.Nullable;

/**
 * The locks behind a fingerprint, so a consumer that only ever sees the digest can intersect.
 *
 * <p>The agent's telemetry path captures a lockset on the worker thread as one {@code long} and
 * replays it on a drain thread, because the ring buffer between them is allocation-free and a set
 * cannot travel through it. Comparing those digests for equality was the only question the drain
 * side could ask, and it is the wrong question for a field guarded by {@code A} that one path also
 * touches under {@code B}: the sets {@code {A}} and {@code {A, B}} differ, so the field read as
 * unguarded. Registering each distinct set once, the first time a thread computes its digest, is
 * what lets the consumer ask the right question. Guava's cache writes an entry under its segment
 * lock and, on the load path, under the entry's monitor as well; that one shape is why this exists.
 *
 * <p>Fixed capacity and lock-free to read: an access records a fingerprint without touching this
 * table, and a thread registers a set only when its lockset changed since it last recorded. Sets
 * are few, they are bounded by the code's lock nesting rather than by its objects, which is why an
 * instance's own monitor is carried separately by the event instead of being folded in here. Once
 * the table is full, new sets simply go unregistered and a consumer falls back to treating their
 * digest as one opaque lock, which is the equality model this replaces. The table is cleared when
 * the telemetry registry starts a run, because every consumer resolves members as events arrive
 * and nothing needs them afterwards.
 */
final class LocksetRegistry {

    /** A power of two; room for more distinct locksets than a test run has lock-nesting shapes. */
    private static final int CAPACITY = 16_384;

    private static final int MASK = CAPACITY - 1;

    /** Stop inserting before probe chains grow long; the remainder stays unregistered. */
    private static final int FILL_LIMIT = CAPACITY - CAPACITY / 8;

    private static final AtomicLongArray KEYS = new AtomicLongArray(CAPACITY);

    private static final AtomicReferenceArray<int[]> MEMBERS = new AtomicReferenceArray<>(CAPACITY);

    private static final Object INSERT = new Object();

    private static int size;

    /** Bumped by {@link #clear()}, so a thread that cached a registration re-registers after it. */
    private static volatile int generation;

    private LocksetRegistry() {
    }

    /** {@return the generation a registration belongs to; compare with a cached value to re-register} */
    static int generation() {
        return generation;
    }

    /** {@return whether {@code fingerprint} has members on record} */
    static boolean isRegistered(long fingerprint) {
        return members(fingerprint) != null;
    }

    /**
     * {@return the identity hashes registered for {@code fingerprint}, or {@code null} if none
     * were; the caller must not modify the array}
     *
     * <p>{@code null} and empty mean different things here and the distinction is the point:
     * empty is a fingerprint of "held nothing", {@code null} is a digest nobody registered, which
     * a consumer treats as one opaque lock rather than as no lock.
     */
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    @SuppressFBWarnings(value = "PZLA_PREFER_ZERO_LENGTH_ARRAYS",
            justification = "null and empty differ on purpose: empty is a fingerprint of nothing"
                    + " held, null is an unregistered digest the consumer treats as opaque")
    static int @Nullable [] members(long fingerprint) {
        if (fingerprint == 0L) {
            return HeldLocks.NONE;
        }
        int index = slot(fingerprint);
        for (int probe = 0; probe < CAPACITY; probe++) {
            long key = KEYS.get(index);
            if (key == fingerprint) {
                return MEMBERS.get(index);
            }
            if (key == 0L) {
                return null;
            }
            index = (index + 1) & MASK;
        }
        return null;
    }

    /**
     * Records the members behind {@code fingerprint}, once. Members are published before the key,
     * so a reader that matches the key sees them.
     *
     * @param fingerprint the digest, as produced by {@code HeldLocks}
     * @param members     the identity hashes it stands for; owned by the registry from here on
     */
    static void register(long fingerprint, int[] members) {
        if (fingerprint == 0L) {
            return;
        }
        synchronized (INSERT) {
            if (size >= FILL_LIMIT) {
                return;
            }
            int index = slot(fingerprint);
            for (;;) {
                long key = KEYS.get(index);
                if (key == fingerprint) {
                    return;
                }
                if (key == 0L) {
                    MEMBERS.set(index, members);
                    KEYS.set(index, fingerprint);
                    size++;
                    return;
                }
                index = (index + 1) & MASK;
            }
        }
    }

    /** Forgets every registration; called at the start of a telemetry run. */
    static void clear() {
        synchronized (INSERT) {
            for (int i = 0; i < CAPACITY; i++) {
                KEYS.set(i, 0L);
                MEMBERS.set(i, null);
            }
            size = 0;
            generation++;
        }
    }

    private static int slot(long fingerprint) {
        long mixed = fingerprint * 0x9E3779B97F4A7C15L;
        return (int) (mixed >>> 40) & MASK;
    }
}
