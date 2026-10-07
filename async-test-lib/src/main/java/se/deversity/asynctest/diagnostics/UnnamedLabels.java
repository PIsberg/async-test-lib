package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Labels for objects a test gave no name, {@code kind@n}, numbered per kind and per detector:
 * {@code queue@1}, {@code queue@2}, {@code timer@1} (#860).
 *
 * <p>An identity hash is not a name: two live objects share one often enough that a report keyed or
 * grouped by such a label prints them as one (#854). A number handed out once cannot collide. Each
 * detector holds its own instance, and a detector lives for one {@code @AsyncTest} invocation, so the
 * numbers start at 1 in every run and repeat from one run to the next instead of depending on what ran
 * earlier in the JVM. The shape stays {@code kind@digits}, so a baseline fingerprint, which reads
 * {@code @} and digits as {@code @#}, matches a finding recorded under an older label.
 *
 * <p>Both maps are created on first use, so a detector that never meets an unnamed object pays one
 * small object and nothing else.
 *
 * <p>Objects are keyed weakly ({@link IdentityKey.Weak}): a label does not keep its object alive
 * (#929). It used to hold a strong key, so every detector using it kept every object a test
 * recorded without a name for the whole run, even where the detector's own state was weak. A
 * collected object's entry stays, its key now equal only to itself, at the cost of a key and a
 * label string, the same choice {@link AbstractInstanceDetector} makes for its states.
 */
final class UnnamedLabels {

    private volatile @Nullable ConcurrentMap<String, AtomicInteger> counters;
    private volatile @Nullable ConcurrentMap<Object, String> byObject;

    /**
     * {@return a label no earlier call on this instance returned, {@code kind@n}}
     *
     * <p>For a label taken once per object, where its state is created. A caller that may meet the
     * same object again before keeping the label uses {@link #of(Object, String)} instead.
     *
     * @param kind what the report calls such an object, such as {@code "queue"}; printed as given
     */
    String next(String kind) {
        return kind + "@" + counters().computeIfAbsent(kind, k -> new AtomicInteger()).incrementAndGet();
    }

    /**
     * {@return the label of {@code object}: the one handed out the first time it was asked about,
     * otherwise a new one from {@link #next(String)}}
     *
     * <p>For a path that computes the label on every call. A repeat costs a lookup by the calling
     * thread's reused key and allocates nothing.
     *
     * @param object the unnamed object
     * @param kind   what the report calls it the first time
     */
    String of(Object object, String kind) {
        ConcurrentMap<Object, String> labels = byObject();
        String label = labels.get(IdentityKey.lookup(object));
        return label != null ? label : labels.computeIfAbsent(new IdentityKey.Weak(object, null), k -> next(kind));
    }

    /**
     * As {@link #of(Object, String)}, for a caller that already holds the object's key, which
     * looks the label up without allocating; what is stored is a weak key, never this one.
     *
     * @param key  the unnamed object's key
     * @param kind what the report calls it the first time
     * @return the object's label
     */
    String of(IdentityKey key, String kind) {
        ConcurrentMap<Object, String> labels = byObject();
        String label = labels.get(key);
        return label != null ? label
                : labels.computeIfAbsent(new IdentityKey.Weak(key.referent(), null), k -> next(kind));
    }

    private ConcurrentMap<String, AtomicInteger> counters() {
        ConcurrentMap<String, AtomicInteger> map = counters;
        if (map == null) {
            synchronized (this) {
                map = counters;
                if (map == null) {
                    map = new ConcurrentHashMap<>();
                    counters = map;
                }
            }
        }
        return map;
    }

    private ConcurrentMap<Object, String> byObject() {
        ConcurrentMap<Object, String> map = byObject;
        if (map == null) {
            synchronized (this) {
                map = byObject;
                if (map == null) {
                    map = new ConcurrentHashMap<>();
                    byObject = map;
                }
            }
        }
        return map;
    }
}
