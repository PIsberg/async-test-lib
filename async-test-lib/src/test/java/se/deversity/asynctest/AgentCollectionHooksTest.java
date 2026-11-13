package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.AbstractQueue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import se.deversity.asynctest.telemetry.TelemetryEventBuffer;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

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
    @DisplayName("the queue hooks publish each offer before the take that removes it, naming the queue (#630)")
    void queueHooksPublishOffersAheadOfTakesWithTheQueueIdentity() throws InterruptedException {
        List<String> ownership = Collections.synchronizedList(new ArrayList<>());
        Object chunk = new Object();
        Object buffer = new Object();
        Queue<Object> queue = new ArrayDeque<>();
        BlockingQueue<Object> blocking = new LinkedBlockingQueue<>();
        int chunkId = System.identityHashCode(chunk);
        int bufferId = System.identityHashCode(buffer);
        TelemetryRegistry.start(new TelemetryEventBuffer.DrainCallback() {
            @Override
            public void onEvent(long threadId, @Nullable String targetField, boolean isWrite) {
                // Only the full-shape overload below carries what this test reads.
            }

            @Override
            public void onEvent(long threadId, @Nullable String targetField, boolean isWrite,
                                long lockFingerprint, boolean volatileField, int constantTag,
                                int identity, boolean afterVolatileRead, int ownMonitor,
                                int methodMonitor, int storedIdentity) {
                if ((identity == chunkId || identity == bufferId) && targetField != null
                        && targetField.startsWith("#ownership-")) {
                    ownership.add(targetField + " " + (identity == chunkId ? "chunk" : "buffer")
                            + " from " + (storedIdentity == System.identityHashCode(queue)
                            ? "queue" : storedIdentity == System.identityHashCode(blocking)
                            ? "blocking" : String.valueOf(storedIdentity)));
                }
            }
        });
        try {
            AgentCollectionHooks.queueOffer(queue, chunk);
            assertEquals(chunk, AgentCollectionHooks.queuePoll(queue), "poll hands the chunk back");
            AgentCollectionHooks.collectionAdd(queue, chunk);
            assertEquals(chunk, AgentCollectionHooks.queuePoll(queue), "add then poll round-trips");
            AgentConcurrencyUtilHooks.put(blocking, buffer);
            assertEquals(buffer, AgentConcurrencyUtilHooks.poll(blocking), "put then poll");
            AgentConcurrencyUtilHooks.offer(blocking, buffer);
            assertEquals(buffer, AgentConcurrencyUtilHooks.poll(blocking, 1, TimeUnit.SECONDS),
                    "offer then timed poll");
            AgentConcurrencyUtilHooks.offer(blocking, buffer, 1, TimeUnit.SECONDS);
            assertEquals(buffer, AgentConcurrencyUtilHooks.poll(blocking), "timed offer then poll");
            TelemetryRegistry.flush();
        } finally {
            TelemetryRegistry.stop();
        }
        List<String> offerThenTake = List.of(
                "#ownership-offered chunk from queue", "#ownership-taken chunk from queue");
        List<String> blockingOfferThenTake = List.of(
                "#ownership-offered buffer from blocking", "#ownership-taken buffer from blocking");
        List<String> expected = new ArrayList<>();
        expected.addAll(offerThenTake);
        expected.addAll(offerThenTake);
        expected.addAll(blockingOfferThenTake);
        expected.addAll(blockingOfferThenTake);
        expected.addAll(blockingOfferThenTake);
        assertEquals(expected, ownership,
                "Every offer hook must publish the element and its queue before the take hook "
                        + "publishes the same pair: the validator names generation 0's owner only "
                        + "from an offer it drained first, into the container the take names");
    }

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
    @DisplayName("every BitSet hook performs the call it replaced (#1297)")
    void bitSetHooksDelegate() {
        BitSet bits = new BitSet();
        AgentCollectionHooks.bitSetSet(bits, 1);
        AgentCollectionHooks.bitSetSet(bits, 2, true);
        AgentCollectionHooks.bitSetSet(bits, 2, false);
        AgentCollectionHooks.bitSetSet(bits, 4, 8);
        AgentCollectionHooks.bitSetSet(bits, 6, 8, false);
        assertEquals(BitSet.valueOf(new long[] {0b110010L}), bits, "the four set forms");
        assertTrue(AgentCollectionHooks.bitSetGet(bits, 1), "get sees a set bit");
        assertFalse(AgentCollectionHooks.bitSetGet(bits, 2), "and a cleared one");
        assertEquals(BitSet.valueOf(new long[] {0b11L}), AgentCollectionHooks.bitSetGet(bits, 4, 8),
                "the range get returns a copy of the range");
        assertEquals(3, AgentCollectionHooks.bitSetCardinality(bits), "cardinality");
        assertEquals(6, AgentCollectionHooks.bitSetLength(bits), "length");
        assertEquals(4, AgentCollectionHooks.bitSetNextSetBit(bits, 2), "nextSetBit");
        assertEquals(2, AgentCollectionHooks.bitSetNextClearBit(bits, 1), "nextClearBit");

        AgentCollectionHooks.bitSetFlip(bits, 0);
        AgentCollectionHooks.bitSetFlip(bits, 4, 6);
        assertEquals(BitSet.valueOf(new long[] {0b11L}), bits, "the two flip forms");
        AgentCollectionHooks.bitSetOr(bits, BitSet.valueOf(new long[] {0b1100L}));
        assertEquals(BitSet.valueOf(new long[] {0b1111L}), bits, "or");
        AgentCollectionHooks.bitSetAnd(bits, BitSet.valueOf(new long[] {0b0111L}));
        assertEquals(BitSet.valueOf(new long[] {0b0111L}), bits, "and");
        AgentCollectionHooks.bitSetXor(bits, BitSet.valueOf(new long[] {0b0101L}));
        assertEquals(BitSet.valueOf(new long[] {0b0010L}), bits, "xor");
        AgentCollectionHooks.bitSetOr(bits, BitSet.valueOf(new long[] {0b1100L}));
        AgentCollectionHooks.bitSetAndNot(bits, BitSet.valueOf(new long[] {0b1000L}));
        assertEquals(BitSet.valueOf(new long[] {0b0110L}), bits, "andNot");

        AgentCollectionHooks.bitSetClear(bits, 1);
        assertEquals(BitSet.valueOf(new long[] {0b0100L}), bits, "clear of one bit");
        AgentCollectionHooks.bitSetSet(bits, 0, 8);
        AgentCollectionHooks.bitSetClear(bits, 2, 8);
        assertEquals(BitSet.valueOf(new long[] {0b11L}), bits, "clear of a range");
        assertFalse(AgentCollectionHooks.bitSetIsEmpty(bits), "isEmpty on set bits");
        AgentCollectionHooks.bitSetClear(bits);
        assertTrue(AgentCollectionHooks.bitSetIsEmpty(bits), "clear of everything");
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

    @Test
    @DisplayName("one writer beside other readers through the hooks is reported; a writer alone is not")
    void aWriterAndSeveralReadersAreReported() throws InterruptedException {
        // The read path of record(): every case above writes, so a hook that dropped the read
        // half would leave a reader's access invisible. Since #1052 the detector's rule is one
        // writer beside any other thread reading in a round (one reader iterating while another
        // adds is the concurrent-modification race); a writer with no reader is below it, and
        // pinning that boundary is what keeps this from passing on a detector that reports
        // every collection a thread touched.
        assertTrue(readersReport(2),
                "a HashMap written by one thread and read by two others through the hooks is "
                        + "the visibility finding, and the read half of record() is what delivers "
                        + "the readers' accesses");
        assertTrue(readersReport(1),
                "one writer and one other reader is reported since #1052");
        assertFalse(readersReport(0),
                "a writer with no other thread reading races nobody and must stay silent");
    }

    /** One writer, then {@code readerCount} readers, each on its own thread, through the hooks. */
    private static boolean readersReport(int readerCount) throws InterruptedException {
        AsyncTestConfig cfg = AsyncTestConfig.builder().detectSharedCollections(true).build();
        AsyncTestContext ctx = new AsyncTestContext(cfg);
        Map<Object, Object> map = new HashMap<>();
        onItsOwnThread(ctx, () -> AgentCollectionHooks.mapPut(map, "k", "v"));
        for (int i = 0; i < readerCount; i++) {
            onItsOwnThread(ctx, () -> {
                assertEquals("v", AgentCollectionHooks.mapGet(map, "k"), "the read delegates");
                assertTrue(AgentCollectionHooks.mapContainsKey(map, "k"), "so does containsKey");
            });
        }
        return ctx.sharedCollectionDetector.analyze().hasIssues();
    }

    /** Runs {@code body} on a fresh thread with {@code ctx} installed, and waits for it. */
    private static void onItsOwnThread(AsyncTestContext ctx, Runnable body) throws InterruptedException {
        Thread thread = new Thread(() -> {
            AsyncTestContext.install(ctx);
            try {
                body.run();
            } finally {
                AsyncTestContext.uninstall();
            }
        });
        thread.start();
        thread.join();
    }
}
