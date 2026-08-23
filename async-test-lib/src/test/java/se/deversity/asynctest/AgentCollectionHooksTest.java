package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
