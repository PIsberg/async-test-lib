package se.deversity.asynctest.agent;

import com.example.agentfixture.OrderedMapConstructionSample;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The construction hooks (#807): every shape a class builds an ordered {@code LinkedHashMap} in
 * still verifies once woven, still builds the map in the order it asked for, and hands each map to
 * the hook once with that order.
 *
 * <p>Loading a woven class verifies it, so a {@code DUP} or {@code ALOAD 0} inserted where the
 * stack or local 0 did not hold the new map fails here with a {@code VerifyError} rather than inside
 * somebody's test run.
 */
class ConstructionWeaverTest {

    /** A map the woven code built, with the order the hook saw it built in. */
    record Built(LinkedHashMap<?, ?> map, boolean accessOrder) {
    }

    /** What the woven code reported, in order. */
    public static final List<Built> BUILT = new CopyOnWriteArrayList<>();

    /** Stands in for the library hooks, recording instead of reporting. */
    public static class Recorder {

        private static boolean pending;

        /**
         * @param accessOrder the order argument of the constructor call about to be made
         * @return {@code accessOrder}, unchanged
         */
        public static boolean linkedHashMapAccessOrder(boolean accessOrder) {
            pending = accessOrder;
            return accessOrder;
        }

        /**
         * @param map the map the woven code just built
         */
        public static void linkedHashMapConstructed(LinkedHashMap<?, ?> map) {
            BUILT.add(new Built(map, pending));
        }
    }

    @BeforeEach
    void clear() {
        BUILT.clear();
    }

    private static Class<?> woven(Class<?> type) {
        return new ByteBuddy()
                .redefine(type)
                .visit(ConstructionWeaver.of(Recorder.class))
                .make()
                .load(ConstructionWeaverTest.class.getClassLoader(),
                        ClassLoadingStrategy.Default.CHILD_FIRST)
                .getLoaded();
    }

    /** {@return whether {@code map} moves a key it reads to the end, which only access order does} */
    @SuppressWarnings("unchecked")
    private static boolean relinksOnGet(Map<?, ?> map) {
        Map<String, String> strings = (Map<String, String>) map;
        strings.put("a", "1");
        strings.put("b", "2");
        strings.get("a");
        boolean moved = "b".equals(strings.keySet().iterator().next());
        strings.clear();
        return moved;
    }

    /** Asserts the woven code reported exactly {@code map}, built in {@code accessOrder}. */
    private static void assertOnly(Object map, boolean accessOrder, String what) {
        assertEquals(1, BUILT.size(), what + ": one report, got " + BUILT);
        assertSame(map, BUILT.get(0).map(), what + ": the map itself");
        assertEquals(accessOrder, BUILT.get(0).accessOrder(), what + ": its order");
    }

    @Test
    @DisplayName("every new LinkedHashMap(int, float, boolean) is reported once, with its order")
    void everyOrderedMapIsReportedOnce() throws Exception {
        Class<?> sample = woven(OrderedMapConstructionSample.class);

        Map<?, ?> lru = (Map<?, ?>) sample.getMethod("accessOrdered").invoke(null);
        assertOnly(lru, true, "a constant");
        assertEquals(true, relinksOnGet(lru), "the order argument reached the constructor intact");

        BUILT.clear();
        Map<?, ?> insertion = (Map<?, ?>) sample.getMethod("ordered", boolean.class)
                .invoke(null, false);
        assertOnly(insertion, false, "a value");
        assertEquals(false, relinksOnGet(insertion));

        BUILT.clear();
        sample.getMethod("discarded").invoke(null);
        assertEquals(1, BUILT.size(), "a dropped map was still built");

        BUILT.clear();
        Map<?, ?> outer = (Map<?, ?>) sample.getMethod("nested").invoke(null);
        assertEquals(2, BUILT.size(), "both nested maps, the inner one first");
        assertEquals(true, BUILT.get(0).accessOrder(), "the inner map is access-ordered");
        assertSame(outer, BUILT.get(1).map(), "the outer map completes last");
        assertEquals(false, BUILT.get(1).accessOrder(), "with its own order, not the inner one's");

        BUILT.clear();
        List<?> pair = (List<?>) sample.getMethod("besideAnUnorderedOne").invoke(null);
        assertOnly(pair.get(1), true,
                "a constructor that cannot set the order reports nothing and keeps the count");

        BUILT.clear();
        Map<?, ?> branched = (Map<?, ?>) sample.getMethod("withABranchInItsOrder",
                boolean.class, boolean.class).invoke(null, true, true);
        assertOnly(branched, true, "a branch inside the order argument");
    }

    @Test
    @DisplayName("a subclass reports this after its superclass call, once through a delegation")
    void aSubclassReportsItselfAfterItsSuperclassCall() throws Exception {
        Class<?> lru = woven(OrderedMapConstructionSample.LruSample.class);

        Map<?, ?> map = (Map<?, ?>) lru.getConstructor(boolean.class).newInstance(true);
        assertOnly(map, true, "this, once the superclass call initialised it");
        assertEquals(true, relinksOnGet(map));

        BUILT.clear();
        Map<?, ?> delegated = (Map<?, ?>) lru.getConstructor().newInstance();
        assertOnly(delegated, true,
                "a this(...) delegation reports nothing itself; the constructor it calls does");
    }

    @Test
    @DisplayName("a map built in a superclass call's arguments is reported before the subclass")
    void aMapInTheSuperclassCallsArgumentsIsReportedFirst() throws Exception {
        Class<?> sized = woven(OrderedMapConstructionSample.SizedFromAnother.class);

        Map<?, ?> map = (Map<?, ?>) sized.getConstructor().newInstance();

        assertEquals(2, BUILT.size(), "the argument's map, then the subclass instance");
        assertEquals(true, BUILT.get(0).accessOrder());
        assertSame(map, BUILT.get(1).map());
        assertEquals(false, BUILT.get(1).accessOrder());
    }

    @Test
    @DisplayName("a hooks class without the construction hooks is refused when the visitor is built")
    void aMissingHookIsRefused() {
        assertThrows(IllegalStateException.class, () -> ConstructionWeaver.of(Object.class),
                "a library without the hooks is a version skew, and weaving a call to a method "
                        + "that is not there would fail inside user code");
    }
}
