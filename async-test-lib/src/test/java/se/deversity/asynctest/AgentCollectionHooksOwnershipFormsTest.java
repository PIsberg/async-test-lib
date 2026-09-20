package se.deversity.asynctest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The entry and removal hooks added for #692 must make the call they replaced.
 *
 * <p>What they publish to the ownership model is asserted from woven bytecode, in the agent
 * module's {@code OwnershipOfferWeavingTest}. None of that fails if a hook records and then puts
 * the element at the wrong end, returns the wrong element, or swallows the exception an empty
 * deque throws, and each hook stands in for the caller's own instruction.
 */
class AgentCollectionHooksOwnershipFormsTest {

    @Test
    @DisplayName("the deque offers put the element at the end they name")
    void dequeOffersKeepTheirEnd() {
        Deque<Object> deque = new ArrayDeque<>();
        assertTrue(AgentCollectionHooks.dequeOfferLast(deque, "b"));
        assertTrue(AgentCollectionHooks.dequeOfferFirst(deque, "a"));
        AgentCollectionHooks.dequeAddLast(deque, "c");
        AgentCollectionHooks.dequeAddFirst(deque, "0");
        AgentCollectionHooks.dequePush(deque, "top");

        assertEquals(List.of("top", "0", "a", "b", "c"), new ArrayList<>(deque));
    }

    @Test
    @DisplayName("the deque takes return the element at the end they name")
    void dequeTakesKeepTheirEnd() {
        Deque<Object> deque = new ArrayDeque<>(List.of("a", "b", "c", "d", "e"));

        assertEquals("a", AgentCollectionHooks.dequePollFirst(deque));
        assertEquals("e", AgentCollectionHooks.dequePollLast(deque));
        assertEquals("b", AgentCollectionHooks.dequeRemoveFirst(deque));
        assertEquals("d", AgentCollectionHooks.dequeRemoveLast(deque));
        assertEquals("c", AgentCollectionHooks.dequePop(deque));
        assertNull(AgentCollectionHooks.dequePollFirst(deque));
        assertNull(AgentCollectionHooks.dequePollLast(deque));
    }

    @Test
    @DisplayName("a take from an empty structure throws what the original call throws")
    void emptyTakesThrow() {
        Deque<Object> deque = new ArrayDeque<>();
        Queue<Object> queue = new ConcurrentLinkedQueue<>();

        assertThrows(NoSuchElementException.class, () -> AgentCollectionHooks.dequeRemoveFirst(deque));
        assertThrows(NoSuchElementException.class, () -> AgentCollectionHooks.dequeRemoveLast(deque));
        assertThrows(NoSuchElementException.class, () -> AgentCollectionHooks.dequePop(deque));
        assertThrows(NoSuchElementException.class, () -> AgentCollectionHooks.queueRemove(queue));
    }

    @Test
    @DisplayName("addAll, remove(), remove(Object) and removeIf do what the queue's own methods do")
    void bulkAndRemovalFormsDelegate() {
        Queue<Object> queue = new ConcurrentLinkedQueue<>();

        assertTrue(AgentCollectionHooks.collectionAddAll(queue, List.of("a", "b", "c", "d")));
        assertFalse(AgentCollectionHooks.collectionAddAll(queue, List.of()));
        assertEquals("a", AgentCollectionHooks.queueRemove(queue));
        assertTrue(AgentCollectionHooks.collectionRemove(queue, "c"));
        assertFalse(AgentCollectionHooks.collectionRemove(queue, "absent"));
        assertTrue(AgentCollectionHooks.collectionRemoveIf(queue, "b"::equals));
        assertFalse(AgentCollectionHooks.collectionRemoveIf(queue, "b"::equals));

        assertEquals(List.of("d"), new ArrayList<>(queue));
    }

    @Test
    @DisplayName("addAll with a null source throws from the call, not from the recording")
    void addAllWithANullSourceThrowsTheCallsOwnException() {
        Queue<Object> queue = new ConcurrentLinkedQueue<>();

        assertThrows(NullPointerException.class, () -> AgentCollectionHooks.collectionAddAll(queue, null));
    }
}
