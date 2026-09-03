package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every collection hook, one at a time, must reach its detector and answer like the call it
 * replaced.
 *
 * <p>{@code AgentCollectionHooksTest} drives the hooks in families - all the map hooks in one
 * body, all the list hooks in the next - which proves the family records but not that every hook
 * does: a hook whose {@code record} call was dropped rides on its siblings' records and the
 * family test stays green. PIT reported exactly that for twelve of them (#476), and the same run
 * reported eight fabricated return values, because the fixtures only ever produced the answer the
 * mutant already returns: a {@code contains} that is true, a {@code put} with nothing to
 * displace, an {@code offer} that a growable queue always accepts.
 *
 * <p>So each hook here is called alone, on a fresh receiver and a fresh context, and its detector
 * is asked with nothing else recorded; and every predicate is asked once in each direction. The
 * threads run one after another rather than at once: the detector's question is how many distinct
 * threads touched the instance, which sequential threads answer without racing a {@code HashMap}
 * into an infinite loop.
 */
class AgentCollectionHooksEachAloneTest {

    private static AsyncTestContext newContext() {
        return new AsyncTestContext(
                AsyncTestConfig.builder().detectSharedCollections(true).build());
    }

    /** Runs {@code body} on a fresh thread with {@code ctx} installed, and waits for it. */
    private static void onItsOwnThread(AsyncTestContext ctx, Runnable body) {
        Thread thread = new Thread(() -> {
            AsyncTestContext.install(ctx);
            try {
                body.run();
            } finally {
                AsyncTestContext.uninstall();
            }
        });
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Calls {@code write} on two threads, through that hook and nothing else, and
     * {@return whether the shared-collection detector reported the receiver}.
     */
    private static boolean twoWritersReport(Runnable write) {
        AsyncTestContext ctx = newContext();
        onItsOwnThread(ctx, write);
        onItsOwnThread(ctx, write);
        return ctx.sharedCollectionDetector.analyze().hasIssues();
    }

    /**
     * Seeds one write, then reads twice through {@code read} on two further threads, and
     * {@return whether the detector reported the receiver}.
     *
     * <p>The detector's read finding is one writer with more than one reader, so a read hook can
     * only be seen alone by supplying the writer from elsewhere. If the hook under test stops
     * recording, what is left is one writer and no readers, which is silence.
     */
    private static boolean readersAfterAWriteReport(Runnable seedWrite, Runnable read) {
        AsyncTestContext ctx = newContext();
        onItsOwnThread(ctx, seedWrite);
        onItsOwnThread(ctx, read);
        onItsOwnThread(ctx, read);
        return ctx.sharedCollectionDetector.analyze().hasIssues();
    }

    @Test
    @DisplayName("each mutating hook alone, from two threads, reaches the detector")
    void writeHooksEachAloneAreRecorded() {
        Map<Object, Object> put = new HashMap<>();
        assertTrue(twoWritersReport(() -> AgentCollectionHooks.mapPut(put, "k", "v")),
                "put is the write every other case leans on");

        Map<Object, Object> remove = new HashMap<>();
        assertTrue(twoWritersReport(() -> AgentCollectionHooks.mapRemove(remove, "k")),
                "remove records the intent to mutate, whether or not the key was there");

        Map<Object, Object> conditional = new HashMap<>();
        assertTrue(twoWritersReport(() -> AgentCollectionHooks.mapRemove(conditional, "k", "v")),
                "and so does the conditional overload, which had no record of its own");

        List<Object> add = new ArrayList<>();
        assertTrue(twoWritersReport(() -> AgentCollectionHooks.collectionAdd(add, "a")),
                "add is the collection write");

        List<Object> collectionRemove = new ArrayList<>();
        assertTrue(twoWritersReport(() -> AgentCollectionHooks.collectionRemove(collectionRemove, "a")),
                "remove is one too");

        List<Object> clear = new ArrayList<>();
        assertTrue(twoWritersReport(() -> AgentCollectionHooks.collectionClear(clear)),
                "clear discards every element, which is the largest write there is");

        List<Object> set = new ArrayList<>();
        set.add("a"); // seeded off the hooks, so only listSet records
        assertTrue(twoWritersReport(() -> AgentCollectionHooks.listSet(set, 0, "b")),
                "set overwrites in place");

        Queue<Object> offer = new LinkedList<>();
        assertTrue(twoWritersReport(() -> AgentCollectionHooks.queueOffer(offer, "q")),
                "offer appends");

        Queue<Object> poll = new LinkedList<>();
        assertTrue(twoWritersReport(() -> AgentCollectionHooks.queuePoll(poll)),
                "poll removes the head, so it is a write even when the queue is empty");
    }

    @Test
    @DisplayName("each reading hook alone, after one writer, reaches the detector")
    void readHooksEachAloneAreRecorded() {
        Map<Object, Object> get = new HashMap<>();
        assertTrue(readersAfterAWriteReport(() -> AgentCollectionHooks.mapPut(get, "k", "v"),
                        () -> AgentCollectionHooks.mapGet(get, "k")),
                "get is the read every map has");

        Map<Object, Object> containsKey = new HashMap<>();
        assertTrue(readersAfterAWriteReport(
                        () -> AgentCollectionHooks.mapPut(containsKey, "k", "v"),
                        () -> AgentCollectionHooks.mapContainsKey(containsKey, "k")),
                "containsKey reads the same table get does");

        List<Object> contains = new ArrayList<>();
        assertTrue(readersAfterAWriteReport(() -> AgentCollectionHooks.collectionAdd(contains, "a"),
                        () -> AgentCollectionHooks.collectionContains(contains, "a")),
                "contains walks the collection");

        List<Object> listGet = new ArrayList<>();
        assertTrue(readersAfterAWriteReport(() -> AgentCollectionHooks.collectionAdd(listGet, "a"),
                        () -> AgentCollectionHooks.listGet(listGet, 0)),
                "get by index reads the array the writer resized");

        Queue<Object> peek = new LinkedList<>();
        assertTrue(readersAfterAWriteReport(() -> AgentCollectionHooks.queueOffer(peek, "q"),
                        () -> AgentCollectionHooks.queuePeek(peek)),
                "peek reads the head without removing it");
    }

    @Test
    @DisplayName("every predicate hook answers false when the operation changed nothing")
    void predicateHooksReturnFalseWhenNothingHappened() {
        // The existing fixtures only ever produce true, which is exactly what a hook returning a
        // hard-coded true also produces. The false cases are the ones that tell them apart.
        Set<Object> set = new HashSet<>();
        assertTrue(AgentCollectionHooks.collectionAdd(set, "a"), "a new element changes the set");
        assertFalse(AgentCollectionHooks.collectionAdd(set, "a"),
                "adding it again does not, and add must say so");

        assertFalse(AgentCollectionHooks.collectionContains(set, "absent"),
                "contains must answer for the element it was asked about");
        assertFalse(AgentCollectionHooks.collectionRemove(set, "absent"),
                "removing what is not there changes nothing, and remove must say so");

        Map<Object, Object> map = new HashMap<>();
        assertFalse(AgentCollectionHooks.mapContainsKey(map, "k"),
                "an empty map contains no key");
        assertFalse(AgentCollectionHooks.mapRemove(map, "k", "v"),
                "the conditional remove fails on an absent entry");

        Queue<Object> bounded = new ArrayBlockingQueue<>(1);
        assertTrue(AgentCollectionHooks.queueOffer(bounded, "first"), "the queue has room once");
        assertFalse(AgentCollectionHooks.queueOffer(bounded, "second"),
                "and refuses the next one; an offer hard-returning true would tell the caller "
                        + "an element was accepted that the queue dropped");
    }

    @Test
    @DisplayName("put returns the value it displaced, and clear empties a non-empty collection")
    void hooksThatWereOnlyEverAskedTheEasyQuestion() {
        Map<Object, Object> map = new HashMap<>();
        AgentCollectionHooks.mapPut(map, "k", "first");
        assertEquals("first", AgentCollectionHooks.mapPut(map, "k", "second"),
                "put over an existing key returns what it displaced; only the first put returns "
                        + "null, which is what a hook returning a hard null also returns");
        assertEquals("second", map.get("k"), "and the new value is the one stored");

        List<Object> list = new ArrayList<>();
        list.add("a");
        list.add("b");
        AgentCollectionHooks.collectionClear(list);
        assertTrue(list.isEmpty(),
                "clear must reach the collection; clearing an already empty one cannot tell a "
                        + "delegating hook from a hook that dropped the call");
    }

    /** Both directions of the sweep helpers, so a detector that fired on everything would fail. */
    @Test
    @DisplayName("one thread through the same hooks reports nothing")
    void oneThreadIsNotSharing() {
        Map<Object, Object> map = new HashMap<>();
        AsyncTestContext ctx = newContext();
        onItsOwnThread(ctx, () -> {
            AgentCollectionHooks.mapPut(map, "k", "v");
            AgentCollectionHooks.mapGet(map, "k");
            AgentCollectionHooks.mapContainsKey(map, "k");
            AgentCollectionHooks.mapRemove(map, "k");
        });
        assertFalse(ctx.sharedCollectionDetector.analyze().hasIssues(),
                "a collection one thread owns is not shared, however many hooks it went through; "
                        + "without this the sweeps above would pass on a detector that reported "
                        + "every collection it was told about");
    }
}
