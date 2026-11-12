package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Stands in for the lock {@link ConcurrentHashMap} takes inside its atomic per-key updates
 * (#1245).
 *
 * <p>{@code compute}, {@code computeIfPresent}, {@code computeIfAbsent} and {@code merge} on a
 * {@code ConcurrentHashMap} run the whole call atomically for the key, under a lock inside the JDK
 * that {@link HeldLocks} cannot see. Without a stand-in, a value mutated inside the function was
 * recorded with an empty lockset on every thread and read exactly like the broken twin that
 * mutates it after a {@code get}. The woven call holds the token this class hands out for the
 * length of the call, so two threads updating one key share a lock in their locksets.
 *
 * <p>The token is per (map, key), not per map: updates of two keys run concurrently, and one value
 * reachable under two keys is a real race that must keep reporting. Only a
 * {@code ConcurrentHashMap} gets one, or a subclass that declares none of the four methods at any
 * level below it, so {@code ConcurrentHashMap}'s own atomic implementation is the one that runs
 * (#1259). A subclass that overrides any of them may run the function outside the map's lock, and
 * {@code ConcurrentSkipListMap.compute} may run it concurrently; neither is atomic, and a plain
 * {@code HashMap} is not thread-safe at all. Each gets no token, which can only add a finding.
 *
 * <p>Maps are held weakly, by identity; keys are held strongly, by the map's own equality, while
 * their map lives. At most {@link #MAX_KEYS_PER_MAP} keys get a token per map; past that a call
 * gets none, which again can only add a finding.
 *
 * @since 1.14.3
 */
public final class AtomicMapUpdateTokens {

    /** The most keys of one map that get a token. */
    static final int MAX_KEYS_PER_MAP = 4096;

    /**
     * Each map's tokens, keyed by an {@link IdentityKey.Weak} of the map and looked up with the
     * calling thread's {@link IdentityKey#lookup} key, which equals it, so a lookup of a map
     * already seen allocates nothing.
     */
    private static final Map<Object, Map<Object, Object>> TOKENS = new ConcurrentHashMap<>();

    /** The four atomic per-key updates, by name and erased parameter types. */
    private static final Class<?>[][] UPDATE_PARAMETERS = {
            {Object.class, BiFunction.class},
            {Object.class, BiFunction.class},
            {Object.class, Function.class},
            {Object.class, Object.class, BiFunction.class},
    };
    private static final String[] UPDATE_NAMES = {"compute", "computeIfPresent", "computeIfAbsent", "merge"};

    /**
     * Whether a {@code ConcurrentHashMap} class runs the JDK's own atomic updates: no level from it
     * up to {@code ConcurrentHashMap} declares any of them. Asked once per class, so the record path
     * pays a lookup, not reflection.
     */
    private static final ClassValue<Boolean> INHERITS_ATOMIC_UPDATES = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                for (Class<?> level = type; level != ConcurrentHashMap.class; level = level.getSuperclass()) {
                    for (Method declared : level.getDeclaredMethods()) {
                        if (isAnUpdate(declared)) {
                            return Boolean.FALSE;
                        }
                    }
                }
            } catch (RuntimeException | LinkageError unreadable) { // NOPMD - cannot tell, so no token
                return Boolean.FALSE;
            }
            return Boolean.TRUE;
        }
    };

    /** {@return whether {@code method} overrides one of the four updates, by name and erased parameters} */
    private static boolean isAnUpdate(Method method) {
        for (int i = 0; i < UPDATE_NAMES.length; i++) {
            if (UPDATE_NAMES[i].equals(method.getName())
                    && Arrays.equals(UPDATE_PARAMETERS[i], method.getParameterTypes())) {
                return true;
            }
        }
        return false;
    }

    /** Where the keys of {@link #TOKENS} go once their map is collected. */
    private static final ReferenceQueue<Object> COLLECTED = new ReferenceQueue<>();

    private AtomicMapUpdateTokens() {
    }

    /**
     * {@return the token to hold while an atomic per-key update of {@code key} in {@code map}
     * runs, or {@code null} when the update is not atomic or the key cannot be looked up}
     *
     * <p>Never throws. Looking the token up calls the key's {@code hashCode} and {@code equals},
     * which the map is about to call anyway; if they throw, this returns {@code null} and the
     * map's own call throws the same exception where the caller expects it.
     *
     * @param map the map being updated
     * @param key the key being updated
     */
    public static @Nullable Object tokenFor(@Nullable Map<?, ?> map, @Nullable Object key) {
        if (key == null || !(map instanceof ConcurrentHashMap)
                || (map.getClass() != ConcurrentHashMap.class
                        && !INHERITS_ATOMIC_UPDATES.get(map.getClass()))) {
            return null;
        }
        Map<Object, Object> tokens = TOKENS.get(IdentityKey.lookup(map));
        if (tokens == null) {
            expungeCollected();
            tokens = TOKENS.computeIfAbsent(new IdentityKey.Weak(map, COLLECTED),
                    k -> new ConcurrentHashMap<>());
        }
        try {
            Object token = tokens.get(key);
            if (token == null) {
                if (tokens.size() >= MAX_KEYS_PER_MAP) {
                    return null;
                }
                token = tokens.computeIfAbsent(key, k -> new Token());
            }
            return token;
        } catch (RuntimeException e) { // NOPMD - the map call rethrows it
            return null;
        }
    }

    private static void expungeCollected() {
        for (Reference<?> gone = COLLECTED.poll(); gone != null; gone = COLLECTED.poll()) {
            TOKENS.remove(gone);
        }
    }

    /** A per-key stand-in lock, named for whoever reads a lockset. */
    private static final class Token {
        @Override
        public String toString() {
            return "ConcurrentHashMap key lock";
        }
    }
}
