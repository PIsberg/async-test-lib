package se.deversity.asynctest.diagnostics;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins what {@link IdentityKey} means as a map key (#564).
 *
 * <p>A real identity-hash collision cannot be arranged on demand, so the merge the key prevents is
 * shown through the property that prevents it: two objects are one key only when they are one
 * object, whatever their {@code equals} and {@code hashCode} say.
 */
class IdentityKeyTest {

    @Test
    @DisplayName("the same instance is the same key")
    void sameInstanceIsOneKey() {
        Object instance = new Object();
        Map<IdentityKey, String> state = new ConcurrentHashMap<>();

        state.put(new IdentityKey(instance), "first");
        state.merge(new IdentityKey(instance), "second", (a, b) -> a + "+" + b);

        assertEquals(1, state.size());
        assertEquals("first+second", state.get(new IdentityKey(instance)));
    }

    @Test
    @DisplayName("equal but distinct instances are distinct keys")
    void equalInstancesAreDistinctKeys() {
        String one = new String("pool");
        String two = new String("pool");
        Map<IdentityKey, String> state = new HashMap<>();

        state.put(new IdentityKey(one), "one");
        state.put(new IdentityKey(two), "two");

        assertEquals(2, state.size(), "value equality must not merge two tracked instances");
        assertNotEquals(new IdentityKey(one), new IdentityKey(two));
    }

    @Test
    @DisplayName("the hash is the identity hash, so hashing costs what the bare-hash keys cost")
    void hashIsTheIdentityHash() {
        Object instance = new Object();
        assertEquals(System.identityHashCode(instance), new IdentityKey(instance).hashCode());
    }

    @Test
    @DisplayName("a null referent is refused")
    void nullIsRefused() {
        assertThrows(NullPointerException.class, () -> new IdentityKey(null));
    }

}
