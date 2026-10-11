package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.SynchronizedCollectionIterationDetector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Iterating a {@code Collections.synchronized*} wrapper without its lock, reported through the
 * woven hooks alone.
 *
 * <p>The wrappers' javadoc requires the caller to hold the wrapper's monitor while iterating, and a
 * map's views iterate under the map's monitor. {@code SYNCHRONIZED_COLLECTION_ITERATION} heard only
 * what a body recorded about itself. The agent now substitutes {@code iterator()},
 * {@code stream()} and the map views, and the hook asks {@code Thread.holdsLock} of the monitor the
 * wrapper actually uses.
 */
class AgentSynchronizedIterationHooksTest {

    private static void installed() {
        AsyncTestContext.install(
                new AsyncTestContext(AsyncTestConfig.builder().detectAll(true).build()));
    }

    private static SynchronizedCollectionIterationDetector.SynchronizedCollectionIterationReport report() {
        return AsyncTestContext.synchronizedCollectionIterationDetector().analyze();
    }

    @Test
    @DisplayName("iterating a synchronizedList without its lock is reported")
    void unguardedListIterationIsReported() {
        installed();
        try {
            List<Object> list = Collections.synchronizedList(new ArrayList<>(List.of("a", "b")));
            Iterator<Object> it = AgentCollectionHooks.iterableIterator(list);
            assertTrue(it.hasNext(), "the hook returns the wrapper's own iterator");

            assertTrue(report().hasIssues(),
                    "the list's javadoc requires synchronized (list) around iteration: " + report());
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("the same iteration inside synchronized (list) stays silent")
    void guardedListIterationIsSilent() {
        installed();
        try {
            List<Object> list = Collections.synchronizedList(new ArrayList<>(List.of("a")));
            synchronized (list) {
                AgentCollectionHooks.iterableIterator(list).next();
                AgentCollectionHooks.collectionStream(list).count();
            }
            assertFalse(report().hasIssues(), "the documented way: " + report());
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("streaming a synchronizedList without its lock is reported")
    void unguardedListStreamIsReported() {
        installed();
        try {
            List<Object> list = Collections.synchronizedList(new ArrayList<>(List.of("a", "b")));
            assertEquals(2, AgentCollectionHooks.collectionStream(list).count());

            assertTrue(report().hasIssues(),
                    "a stream walks the backing list unguarded exactly as an iterator does");
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("iterating a synchronizedMap's entrySet without the map's lock is reported")
    void unguardedMapViewIterationIsReported() {
        installed();
        try {
            Map<Object, Object> map = Collections.synchronizedMap(new HashMap<>(Map.of("k", "v")));
            @SuppressWarnings("unchecked")
            Iterable<Object> entries = (Iterable<Object>) (Iterable<?>) AgentCollectionHooks.mapEntrySet(map);
            AgentCollectionHooks.iterableIterator(entries).next();

            assertTrue(report().hasIssues(),
                    "a view iterates under the map's monitor, which was not held: " + report());
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("iterating a map view inside synchronized (map) stays silent")
    void guardedMapViewIterationIsSilent() {
        installed();
        try {
            Map<Object, Object> map = Collections.synchronizedMap(new HashMap<>(Map.of("k", "v")));
            synchronized (map) {
                AgentCollectionHooks.iterableIterator(AgentCollectionHooks.mapKeySet(map)).next();
                Collection<Object> values = AgentCollectionHooks.mapValues(map);
                AgentCollectionHooks.iterableIterator(values).next();
            }
            assertFalse(report().hasIssues(),
                    "holding the map's monitor guards its views; asking the view's own monitor "
                            + "would report the documented idiom: " + report());
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("plain collections and synchronized sets of unknown origin are not judged")
    void notJudged() {
        installed();
        try {
            AgentCollectionHooks.iterableIterator(new ArrayList<>(List.of("a"))).next();
            Set<Object> set = Collections.synchronizedSet(new HashSet<>(Set.of("a")));
            AgentCollectionHooks.iterableIterator(set).next();

            assertFalse(report().hasIssues(),
                    "a plain list is not a wrapper, and a synchronized set the run did not see "
                            + "come out of a map could be a view guarded by a map's monitor, so "
                            + "judging it against its own would report correct code: " + report());
        } finally {
            AsyncTestContext.uninstall();
        }
    }
}
