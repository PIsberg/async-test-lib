package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Which maps get a per-key token: those whose atomic per-key updates are the JDK's own (#1245,
 * #1259). A token on a map whose update is not atomic would hide a real race, so every class that
 * might run the function outside the map's lock must get none.
 */
class AtomicMapUpdateTokensTest {

    static class Plain<K, V> extends ConcurrentHashMap<K, V> {
        private static final long serialVersionUID = 1L;
    }

    static final class PlainGrandchild<K, V> extends Plain<K, V> {
        private static final long serialVersionUID = 1L;
    }

    static class OverridesCompute<K, V> extends ConcurrentHashMap<K, V> {
        private static final long serialVersionUID = 1L;

        @Override
        public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remapping) {
            return super.compute(key, remapping);
        }
    }

    static final class BelowOverride<K, V> extends OverridesCompute<K, V> {
        private static final long serialVersionUID = 1L;
    }

    static final class OverridesComputeIfPresent<K, V> extends ConcurrentHashMap<K, V> {
        private static final long serialVersionUID = 1L;

        @Override
        public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remapping) {
            return super.computeIfPresent(key, remapping);
        }
    }

    static final class OverridesComputeIfAbsent<K, V> extends ConcurrentHashMap<K, V> {
        private static final long serialVersionUID = 1L;

        @Override
        public V computeIfAbsent(K key, Function<? super K, ? extends V> mapping) {
            return super.computeIfAbsent(key, mapping);
        }
    }

    static final class OverridesMerge<K, V> extends ConcurrentHashMap<K, V> {
        private static final long serialVersionUID = 1L;

        @Override
        public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remapping) {
            return super.merge(key, value, remapping);
        }
    }

    /** Overrides an unrelated method, which does not change how the updates run. */
    static final class OverridesGet<K, V> extends ConcurrentHashMap<K, V> {
        private static final long serialVersionUID = 1L;

        @Override
        public V get(Object key) {
            return super.get(key);
        }
    }

    @Test
    void aConcurrentHashMapGetsOneTokenPerKey() {
        Map<String, Integer> map = new ConcurrentHashMap<>();
        Object a = AtomicMapUpdateTokens.tokenFor(map, "a");
        assertNotNull(a);
        assertSame(a, AtomicMapUpdateTokens.tokenFor(map, "a"), "one key, one token");
        assertNotSame(a, AtomicMapUpdateTokens.tokenFor(map, "b"), "two keys update concurrently");
    }

    @Test
    void aSubclassThatOverridesNoUpdateGetsATokenAtAnyDepth() {
        assertNotNull(AtomicMapUpdateTokens.tokenFor(new Plain<>(), "k"));
        assertNotNull(AtomicMapUpdateTokens.tokenFor(new PlainGrandchild<>(), "k"));
        assertNotNull(AtomicMapUpdateTokens.tokenFor(new OverridesGet<>(), "k"),
                "get is not one of the four updates");
    }

    @Test
    void aSubclassThatOverridesAnyUpdateGetsNone() {
        assertNull(AtomicMapUpdateTokens.tokenFor(new OverridesCompute<>(), "k"));
        assertNull(AtomicMapUpdateTokens.tokenFor(new OverridesComputeIfPresent<>(), "k"));
        assertNull(AtomicMapUpdateTokens.tokenFor(new OverridesComputeIfAbsent<>(), "k"));
        assertNull(AtomicMapUpdateTokens.tokenFor(new OverridesMerge<>(), "k"));
        assertNull(AtomicMapUpdateTokens.tokenFor(new BelowOverride<>(), "k"),
                "the override is inherited from a level above");
    }

    @Test
    void aMapThatIsNotAConcurrentHashMapGetsNone() {
        assertNull(AtomicMapUpdateTokens.tokenFor(new ConcurrentSkipListMap<>(), "k"));
        assertNull(AtomicMapUpdateTokens.tokenFor(new HashMap<>(), "k"));
        assertNull(AtomicMapUpdateTokens.tokenFor(null, "k"));
        assertNull(AtomicMapUpdateTokens.tokenFor(new ConcurrentHashMap<>(), null));
    }
}
