package se.deversity.asynctest.agent;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AgentCollectionHooks;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The weave table, and the receiver capture it exists to perform. */
class CollectionAccessWeaverTest {

    public static final ConcurrentLinkedQueue<Object> SEEN = new ConcurrentLinkedQueue<>();

    /** Stands in for {@link AgentCollectionHooks} so the assertion is about capture, not recording. */
    public static class Hooks {
        public static Object put(Map<Object, Object> receiver, Object key, Object value) {
            SEEN.add(receiver);
            return receiver.put(key, value);
        }
    }

    public static class Sample {
        public final Map<String, String> viaInterface = new HashMap<>();
        public final HashMap<String, String> viaConcreteType = new HashMap<>();

        public void store(String key, String value) {
            viaInterface.put(key, value);
            viaConcreteType.put(key, value);
        }
    }

    @Test
    @DisplayName("every table entry resolves to a hook with the matching erased signature")
    void tableAndHooksAgree() {
        List<AsmVisitorWrapper> substitutions =
                CollectionAccessWeaver.substitutions(AgentCollectionHooks.class);

        assertFalse(substitutions.isEmpty(), "the weave table must not be empty");
        assertEquals(AgentCollectionHooks.class.getName(), CollectionAccessWeaver.hooksClassName(),
                "the weaver names the hook class by string, because the agent module must not "
                        + "depend on the library. If these drift, weaving fails at install time "
                        + "with a ClassNotFoundException instead of here.");
        // substitutions() throws IllegalStateException for an entry with no matching hook, so
        // reaching this line is the assertion: every entry found its method.
        assertTrue(substitutions.size() >= 13,
                "each entry in the table becomes one substitution; got " + substitutions.size());
    }

    @Test
    @DisplayName("the receiver is captured through both an interface call and a concrete-type call")
    void capturesReceiverForBothCallShapes() throws Exception {
        SEEN.clear();
        Class<?> woven = new ByteBuddy()
                .redefine(Sample.class)
                .visit(net.bytebuddy.asm.MemberSubstitution.relaxed()
                        .method(net.bytebuddy.matcher.ElementMatchers
                                .isDeclaredBy(net.bytebuddy.matcher.ElementMatchers.isSubTypeOf(Map.class))
                                .and(net.bytebuddy.matcher.ElementMatchers.named("put"))
                                .and(net.bytebuddy.matcher.ElementMatchers.takesArguments(2)))
                        .onVirtualCall()
                        .replaceWith(Hooks.class.getMethod("put", Map.class, Object.class, Object.class))
                        .on(net.bytebuddy.matcher.ElementMatchers.any()))
                .make()
                .load(getClass().getClassLoader(), ClassLoadingStrategy.Default.CHILD_FIRST)
                .getLoaded();

        Object sample = woven.getDeclaredConstructor().newInstance();
        woven.getMethod("store", String.class, String.class).invoke(sample, "k", "v");

        Object viaInterface = woven.getField("viaInterface").get(sample);
        Object viaConcreteType = woven.getField("viaConcreteType").get(sample);

        assertEquals(1, ((Map<?, ?>) viaInterface).size(), "the original interface call must still happen");
        assertEquals(1, ((Map<?, ?>) viaConcreteType).size(), "the original concrete call must still happen");
        assertEquals(2, SEEN.size(), "both call shapes must be observed");
        assertSame(viaInterface, SEEN.poll(), "first recorded object is the interface-typed receiver");
        assertSame(viaConcreteType, SEEN.poll(), "second recorded object is the concrete-typed receiver");
    }
}
