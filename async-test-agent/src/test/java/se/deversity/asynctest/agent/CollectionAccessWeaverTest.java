package se.deversity.asynctest.agent;

import com.example.agentfixture.CollectionCallSample;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AgentCollectionHooks;
import se.deversity.asynctest.AgentLockHooks;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The weave table, and the receiver capture it exists to perform. */
class CollectionAccessWeaverTest {

    public static final ConcurrentLinkedQueue<Object> SEEN = new ConcurrentLinkedQueue<>();

    /**
     * Stands in for {@link AgentCollectionHooks} so the assertion is about capture, not recording.
     * One method per table entry: the weaver resolves the whole table against this class, which is
     * itself the check that the table and the hook signatures agree.
     */
    public static class StubHooks {
        public static Object mapPut(Map<Object, Object> receiver, Object key, Object value) {
            SEEN.add(receiver);
            return receiver.put(key, value);
        }

        public static Object mapGet(Map<Object, Object> receiver, Object key) {
            SEEN.add(receiver);
            return receiver.get(key);
        }

        public static Object mapRemove(Map<Object, Object> receiver, Object key) {
            return receiver.remove(key);
        }

        public static boolean mapRemove(Map<Object, Object> receiver, Object key, Object value) {
            return receiver.remove(key, value);
        }

        public static boolean mapContainsKey(Map<Object, Object> receiver, Object key) {
            return receiver.containsKey(key);
        }

        public static boolean collectionAdd(java.util.Collection<Object> receiver, Object element) {
            return receiver.add(element);
        }

        public static boolean collectionRemove(java.util.Collection<Object> receiver, Object element) {
            return receiver.remove(element);
        }

        public static boolean collectionContains(java.util.Collection<Object> receiver, Object element) {
            return receiver.contains(element);
        }

        public static void collectionClear(java.util.Collection<Object> receiver) {
            receiver.clear();
        }

        public static Object listGet(List<Object> receiver, int index) {
            return receiver.get(index);
        }

        public static Object listSet(List<Object> receiver, int index, Object element) {
            return receiver.set(index, element);
        }

        public static boolean queueOffer(Queue<Object> receiver, Object element) {
            return receiver.offer(element);
        }

        public static Object queuePoll(Queue<Object> receiver) {
            return receiver.poll();
        }

        public static Object queuePeek(Queue<Object> receiver) {
            return receiver.peek();
        }
    }

    @Test
    @DisplayName("every table entry resolves to a hook with the matching erased signature")
    void tableAndHooksAgree() {
        List<AsmVisitorWrapper> substitutions =
                CollectionAccessWeaver.substitutions(AgentCollectionHooks.class);
        List<AsmVisitorWrapper> lockSubstitutions =
                CollectionAccessWeaver.lockSubstitutions(AgentLockHooks.class);

        assertEquals(AgentCollectionHooks.class.getName(), CollectionAccessWeaver.hooksClassName(),
                "the weaver names the hook class by string, because the agent module must not "
                        + "depend on the library. If these drift, weaving fails at install time "
                        + "with a ClassNotFoundException instead of here.");
        assertEquals(AgentLockHooks.class.getName(), CollectionAccessWeaver.lockHooksClassName(),
                "same contract for the lock hooks");
        // targets() throws IllegalStateException for an entry with no matching hook, so reaching
        // these lines is the assertion: every entry of both tables found its method. The whole
        // table travels in one visitor since the MemberSubstitution replacement.
        assertEquals(1, substitutions.size(), "one visitor carries the whole collection table");
        assertEquals(1, lockSubstitutions.size(), "one visitor carries the whole lock table");
    }

    @Test
    @DisplayName("the receiver is captured through both an interface call and a concrete-type call")
    void capturesReceiverForBothCallShapes() throws Exception {
        SEEN.clear();
        Class<?> woven = new ByteBuddy()
                .redefine(CollectionCallSample.class)
                .visit(CollectionAccessWeaver.substitutions(StubHooks.class).get(0))
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
