package se.deversity.asynctest.diagnostics;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    @DisplayName("a lookup key finds the instance's state, and only that instance's (#812)")
    void lookupFindsTheSameInstanceOnly() {
        String one = new String("pool");
        String two = new String("pool");
        Map<IdentityKey, String> state = new ConcurrentHashMap<>();
        state.put(new IdentityKey(one), "one");

        assertEquals("one", state.get(IdentityKey.lookup(one)));
        assertEquals("one", state.get(IdentityKey.lookup(one)), "the reused key finds it again");
        assertNull(state.get(IdentityKey.lookup(two)), "an equal instance right after is another key");
        assertEquals("one", state.get(IdentityKey.lookup(one)));
        IdentityKey.forgetLookup();
    }

    @Test
    @DisplayName("a lookup key finds a weakly keyed instance, and only that instance (#807)")
    void lookupFindsAWeaklyKeyedInstance() {
        String one = new String("map");
        String two = new String("map");
        Map<IdentityKey.Weak, String> state = new ConcurrentHashMap<>();
        state.put(new IdentityKey.Weak(one, null), "one");

        assertEquals("one", state.get(IdentityKey.lookup(one)),
                "the strong lookup key names the weak key's referent");
        assertNull(state.get(IdentityKey.lookup(two)), "an equal instance is another key");
        assertEquals(new IdentityKey.Weak(one, null), new IdentityKey(one), "equal both ways");
        assertEquals(new IdentityKey(one), new IdentityKey.Weak(one, null), "equal both ways");
        assertNotEquals(new IdentityKey(two), new IdentityKey.Weak(one, null));
        IdentityKey.forgetLookup();
    }

    @Test
    @DisplayName("a worker leaving its run keeps no instance it looked up (#812)")
    void unbindingReleasesTheLookedUpInstance() throws InterruptedException {
        WeakReference<Object> instance = lookedUpAndDropped();

        SelfGuard.Scope.unbind();

        for (int i = 0; i < 50 && instance.get() != null; i++) {
            System.gc();
            Thread.sleep(10);
        }
        assertNull(instance.get(), "the thread's cached lookup key still holds the instance");
    }

    private static WeakReference<Object> lookedUpAndDropped() {
        Object instance = new Object();
        IdentityKey.lookup(instance);
        return new WeakReference<>(instance);
    }
}
