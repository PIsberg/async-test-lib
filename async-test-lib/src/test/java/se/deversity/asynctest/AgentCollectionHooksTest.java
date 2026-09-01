package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.AbstractQueue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hooks the agent's {@code collections=true} mode weaves into.
 *
 * <p>Two properties matter here and neither needs an agent to check. The hook must perform the
 * operation it replaced, exactly, or instrumented code computes something different from
 * uninstrumented code. And it must be silent when there is no {@code @AsyncTest} in progress,
 * because woven third-party code runs in plenty of places where there is not.
 */
class AgentCollectionHooksTest {

    @Test
    @DisplayName("every hook performs the operation it replaced, with no context installed")
    void hooksDelegateOutsideAnAsyncTest() {
        Map<Object, Object> map = new HashMap<>();
        assertNull(AgentCollectionHooks.mapPut(map, "k", "v"), "put returns the previous value");
        assertEquals("v", AgentCollectionHooks.mapGet(map, "k"), "get returns what put stored");
        assertTrue(AgentCollectionHooks.mapContainsKey(map, "k"), "containsKey sees the entry");
        assertEquals("v", AgentCollectionHooks.mapRemove(map, "k"), "remove returns the old value");
        AgentCollectionHooks.mapPut(map, "k", "v");
        assertFalse(AgentCollectionHooks.mapRemove(map, "k", "other"),
                "the conditional remove leaves a mapping whose value does not match");
        assertTrue(AgentCollectionHooks.mapRemove(map, "k", "v"),
                "and removes the one that does, returning a boolean rather than the value");
        assertTrue(map.isEmpty(), "remove actually removed");

        List<Object> list = new ArrayList<>();
        assertTrue(AgentCollectionHooks.collectionAdd(list, "a"), "add reports the change");
        assertTrue(AgentCollectionHooks.collectionContains(list, "a"), "contains sees the element");
        assertEquals("a", AgentCollectionHooks.listGet(list, 0), "get returns the element");
        assertEquals("a", AgentCollectionHooks.listSet(list, 0, "b"), "set returns the previous element");
        assertTrue(AgentCollectionHooks.collectionRemove(list, "b"), "remove reports the change");
        AgentCollectionHooks.collectionClear(list);
        assertTrue(list.isEmpty(), "clear actually cleared");

        Queue<Object> queue = new LinkedList<>();
        assertTrue(AgentCollectionHooks.queueOffer(queue, "q"), "offer accepts the element");
        assertEquals("q", AgentCollectionHooks.queuePeek(queue), "peek returns the head");
        assertEquals("q", AgentCollectionHooks.queuePoll(queue), "poll removes and returns the head");
        assertTrue(queue.isEmpty(), "poll actually removed");
    }

    @Test
    @DisplayName("a type that answers for its own thread safety is delegated but never recorded")
    void threadSafeTypesAreDelegatedWithoutRecording() {
        // No context is installed here, so nothing can be recorded either way; what this pins is
        // that the suppressed path still performs the operation. The recording side of the same
        // rule is measured end to end by the agent module's CollectionWeavingEndToEndTest, and on
        // real libraries by corpus-eval, where a ConcurrentHashMap-backed subject stays silent.
        Map<Object, Object> concurrent = new ConcurrentHashMap<>();
        AgentCollectionHooks.mapPut(concurrent, "k", "v");
        assertEquals("v", AgentCollectionHooks.mapGet(concurrent, "k"),
                "a suppressed receiver must still have its operation performed");

        Map<Object, Object> synchronizedMap = Collections.synchronizedMap(new HashMap<>());
        AgentCollectionHooks.mapPut(synchronizedMap, "k", "v");
        assertEquals("v", AgentCollectionHooks.mapGet(synchronizedMap, "k"),
                "a synchronized wrapper must still have its operation performed");
    }

    /**
     * A queue with no state anywhere: the shape Guava's cache hands to every lock-free read.
     * Writing to it from any number of threads corrupts nothing, because there is nothing.
     */
    private static final class StatelessQueue extends AbstractQueue<Object> {
        @Override
        public boolean offer(Object element) {
            return true;
        }

        @Override
        public Object poll() {
            return null;
        }

        @Override
        public Object peek() {
            return null;
        }

        @Override
        public Iterator<Object> iterator() {
            return Collections.emptyIterator();
        }

        @Override
        public int size() {
            return 0;
        }
    }

    @Test
    @DisplayName("a receiver with no state inside java.util is delegated but never recorded")
    void receiversWithoutUnweavableStateAreNotRecorded() throws InterruptedException {
        // The hook stands in for fields the weaver cannot see. A receiver whose state lives in
        // woven classes, or nowhere, is already covered field by field, and recording it here
        // reported Guava's stateless discarding queue as a data-corruption risk on a class its
        // javadoc calls safe for concurrent use.
        assertFalse(reportsTwoUnguardedWriters(new StatelessQueue()),
                "a stateless AbstractQueue subclass has nothing the hook can speak for");
        assertFalse(reportsTwoUnguardedWriters(new Hashtable<>()),
                "Hashtable synchronizes every method inside java.util, where no monitor is woven");

        assertTrue(reportsTwoUnguardedWriters(new ArrayDeque<>()),
                "an ArrayDeque keeps its array inside java.util, which only this hook can see");
        assertTrue(reportsTwoUnguardedWriters(new ArrayDeque<>() { }),
                "a subclass inherits that array, so it stays recorded");
    }

    /** Writes to {@code receiver} through the hook from two threads and asks the detector. */
    private static boolean reportsTwoUnguardedWriters(Object receiver) throws InterruptedException {
        AsyncTestConfig cfg = AsyncTestConfig.builder().detectSharedCollections(true).build();
        AsyncTestContext ctx = new AsyncTestContext(cfg);
        for (int i = 0; i < 2; i++) {
            Thread writer = new Thread(() -> {
                AsyncTestContext.install(ctx);
                try {
                    if (receiver instanceof Map<?, ?>) {
                        @SuppressWarnings("unchecked")
                        Map<Object, Object> map = (Map<Object, Object>) receiver;
                        AgentCollectionHooks.mapPut(map, "k", "v");
                    } else {
                        @SuppressWarnings("unchecked")
                        Queue<Object> queue = (Queue<Object>) receiver;
                        AgentCollectionHooks.queueOffer(queue, "x");
                    }
                } finally {
                    AsyncTestContext.uninstall();
                }
            });
            writer.start();
            writer.join();
        }
        return ctx.sharedCollectionDetector.analyze().hasIssues();
    }
}
