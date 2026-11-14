package se.deversity.asynctest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import se.deversity.asynctest.diagnostics.HappensBefore;
import se.deversity.asynctest.diagnostics.RaceConditionDetector;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The agent's hooks feed the happens-before model, so correct hand-offs in woven code stop
 * reading as races without a single declaration in the test.
 *
 * <p>The hooks are called directly, which is exactly what the woven call sites do: the weaver
 * replaces {@code queue.put(x)} with {@code AgentConcurrencyUtilHooks.put(queue, x)}. Each case
 * has a twin that makes the same hand-off through the plain, unwoven call, which the model cannot
 * see and which must therefore keep its finding: an edge the hooks did not report is not one the
 * detector may assume.
 */
class AgentHappensBeforeFeedTest {

    static final class Box {
        int value;
    }

    /** Two threads, one hand-off between them, recorded the way a test records by hand. */
    private static boolean handOffReported(boolean woven) throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        Box box = new Box();
        Thread producer = new Thread(() -> {
            box.value = 1;
            detector.recordFieldWrite(box, "value");
            try {
                if (woven) {
                    AgentConcurrencyUtilHooks.put(queue, box);
                } else {
                    queue.put(box);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread consumer = new Thread(() -> {
            try {
                Box taken = (Box) (woven ? AgentConcurrencyUtilHooks.take(queue) : queue.take());
                detector.recordFieldRead(taken, "value");
                taken.value++;
                detector.recordFieldWrite(taken, "value");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();
        producer.start();
        producer.join();
        consumer.join();
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a woven BlockingQueue put and take order the hand-off")
    void wovenQueueHandOff() throws InterruptedException {
        assertFalse(handOffReported(true), "the take received what the put published");
        assertTrue(handOffReported(false), "the unwoven twin: nothing told the model");
    }

    /**
     * The hand-off of {@link #handOffReported}, taken out with a drain (#1295): 0 through the
     * woven {@code drainTo(Collection)}, 1 through the woven {@code drainTo(Collection, int)},
     * 2 through the plain call. The put is woven in all three.
     */
    private static boolean drainHandOffReported(int how) throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        Box box = new Box();
        Thread producer = new Thread(() -> {
            box.value = 1;
            detector.recordFieldWrite(box, "value");
            try {
                AgentConcurrencyUtilHooks.put(queue, box);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread consumer = new Thread(() -> {
            List<Object> drained = new java.util.ArrayList<>();
            drained.add("already there"); // only what the drain moved is received
            while (drained.size() == 1) {
                switch (how) {
                    case 0 -> AgentConcurrencyUtilHooks.drainTo(queue, drained);
                    case 1 -> AgentConcurrencyUtilHooks.drainTo(queue, drained, 1);
                    default -> queue.drainTo(drained);
                }
                Thread.onSpinWait();
            }
            Box taken = (Box) drained.get(1);
            detector.recordFieldRead(taken, "value");
            taken.value++;
            detector.recordFieldWrite(taken, "value");
        });
        consumer.start();
        producer.start();
        producer.join();
        consumer.join();
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a woven BlockingQueue drainTo receives each element it moved into a list")
    void wovenDrainHandOff() throws InterruptedException {
        assertFalse(drainHandOffReported(0), "the drain received what the put published");
        assertFalse(drainHandOffReported(1), "the bounded drain did too");
        assertTrue(drainHandOffReported(2), "the unwoven twin: nothing told the model");
    }

    /**
     * One thread builds a box, writes it and publishes it with {@code publish}; when that thread
     * has ended, a second fetches it with {@code fetch}, reads it and writes it (#1294). Nothing
     * tells the model about the threads' lifecycle, so the container is the only edge there is.
     */
    private static boolean containerHandOffReported(Consumer<Box> publish, Supplier<Object> fetch)
            throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Thread producer = new Thread(() -> {
            Box box = new Box();
            box.value = 1;
            detector.recordFieldWrite(box, "value");
            publish.accept(box);
        });
        producer.start();
        producer.join();
        Thread consumer = new Thread(() -> {
            Box taken = (Box) fetch.get();
            detector.recordFieldRead(taken, "value");
            taken.value++;
            detector.recordFieldWrite(taken, "value");
        });
        consumer.start();
        consumer.join();
        return detector.analyzeRaceConditions().hasIssues();
    }

    /** The last element a for-each over {@code iterable} sees, through the woven call or the plain one. */
    private static Object lastOf(Iterable<Object> iterable, boolean woven) {
        Iterator<Object> each = woven ? AgentCollectionHooks.iterableIterator(iterable) : iterable.iterator();
        Object last = null;
        while (each.hasNext()) {
            last = each.next();
        }
        return last;
    }

    @Test
    @DisplayName("a woven add, addAll or set on a concurrent list publishes to a woven get and for-each (#1294)")
    void wovenListPublication() throws InterruptedException {
        List<Object> list = new CopyOnWriteArrayList<>();
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.collectionAdd(list, b),
                () -> AgentCollectionHooks.listGet(list, list.size() - 1)), "add then get");
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.collectionAdd(list, b),
                () -> lastOf(list, true)), "add then for-each");
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.listSet(list, 0, b),
                () -> AgentCollectionHooks.listGet(list, 0)), "set then get");
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.collectionAddAll(list, List.of(b)),
                () -> AgentCollectionHooks.listGet(list, list.size() - 1)), "addAll then get");

        assertTrue(containerHandOffReported(list::add,
                () -> AgentCollectionHooks.listGet(list, list.size() - 1)), "the unwoven add published nothing");
        assertTrue(containerHandOffReported(b -> AgentCollectionHooks.collectionAdd(list, b),
                () -> list.get(list.size() - 1)), "the unwoven get received nothing");
        assertTrue(containerHandOffReported(b -> AgentCollectionHooks.collectionAdd(list, b),
                () -> lastOf(list, false)), "nor did the unwoven for-each");
    }

    @Test
    @DisplayName("a plain list publishes nothing however it is woven (#1294)")
    void plainListPublishesNothing() throws InterruptedException {
        List<Object> list = new ArrayList<>();
        assertTrue(containerHandOffReported(b -> AgentCollectionHooks.collectionAdd(list, b),
                () -> AgentCollectionHooks.listGet(list, 0)), "an ArrayList orders nothing");
        assertTrue(containerHandOffReported(b -> AgentCollectionHooks.listSet(list, 0, b),
                () -> lastOf(list, true)), "nor does iterating it");
    }

    /** A concurrent set that refuses every add, as one already holding an equal element does. */
    private static final class Refusing extends CopyOnWriteArraySet<Object> {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean add(Object element) {
            return false;
        }
    }

    @Test
    @DisplayName("a concurrent set's add publishes to a for-each, and a refused add publishes nothing (#1294)")
    void wovenSetPublication() throws InterruptedException {
        Set<Object> set = ConcurrentHashMap.newKeySet();
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.collectionAdd(set, b),
                () -> lastOf(set, true)), "add then for-each");

        Set<Object> refusing = new Refusing();
        AtomicReference<Object> leaked = new AtomicReference<>();
        assertTrue(containerHandOffReported(b -> {
            leaked.setPlain(b);
            AgentCollectionHooks.collectionAdd(refusing, b);
        }, () -> {
            HappensBefore.acquire(leaked.getPlain());
            return leaked.getPlain();
        }), "the refused add withdrew its release, so an acquire of the element receives nothing");
    }

    @Test
    @DisplayName("a concurrent queue's woven peek and element receive what the offer published (#1294)")
    void wovenQueuePeek() throws InterruptedException {
        Queue<Object> queue = new ConcurrentLinkedQueue<>();
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.queueOffer(queue, b),
                () -> AgentCollectionHooks.queuePeek(queue)), "offer then peek");
        queue.clear();
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.queueOffer(queue, b),
                () -> AgentCollectionHooks.queueElement(queue)), "offer then element");
        queue.clear();
        assertTrue(containerHandOffReported(b -> AgentCollectionHooks.queueOffer(queue, b), queue::peek),
                "the unwoven peek received nothing");

        Queue<Object> plain = new ArrayDeque<>();
        assertTrue(containerHandOffReported(b -> AgentCollectionHooks.queueOffer(plain, b),
                () -> AgentCollectionHooks.queuePeek(plain)), "an ArrayDeque orders nothing");
    }

    @Test
    @DisplayName("a value a woven compute, computeIfAbsent, computeIfPresent or merge stores is published (#1294)")
    void wovenComputePublication() throws InterruptedException {
        Map<Object, Object> map = new ConcurrentHashMap<>();
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.mapComputeIfAbsent(map, "a", k -> b),
                () -> AgentCollectionHooks.mapGet(map, "a")), "computeIfAbsent then get");
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.mapCompute(map, "b", (k, v) -> b),
                () -> AgentCollectionHooks.mapGet(map, "b")), "compute then get");
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.mapMerge(map, "c", b, (old, v) -> v),
                () -> AgentCollectionHooks.mapGet(map, "c")), "merge of an absent key then get");
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.mapMerge(map, "c", "x", (old, v) -> b),
                () -> AgentCollectionHooks.mapGet(map, "c")), "merge through the function then get");
        map.put("d", "old");
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.mapComputeIfPresent(map, "d", (k, v) -> b),
                () -> AgentCollectionHooks.mapGet(map, "d")), "computeIfPresent then get");

        assertTrue(containerHandOffReported(b -> map.computeIfAbsent("u", k -> b),
                () -> AgentCollectionHooks.mapGet(map, "u")), "the unwoven computeIfAbsent published nothing");
    }

    @Test
    @DisplayName("the lazy cache: a thread reads what its own computeIfAbsent or merge returned (#1294)")
    void wovenComputeReturn() throws InterruptedException {
        Map<Object, Object> map = new ConcurrentHashMap<>();
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.mapComputeIfAbsent(map, "k", k -> b),
                () -> AgentCollectionHooks.mapComputeIfAbsent(map, "k", k -> new Box())),
                "the second caller received the first one's value");
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.mapComputeIfAbsent(map, "m", k -> b),
                () -> AgentCollectionHooks.mapMerge(map, "m", "x", (old, v) -> old)),
                "a merge that keeps the old value returns it received");
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.mapComputeIfAbsent(map, "p", k -> b),
                () -> AgentCollectionHooks.mapCompute(map, "p", (k, old) -> old)),
                "and so does a compute that keeps it");

        Map<Object, Object> plain = new HashMap<>();
        assertTrue(containerHandOffReported(b -> AgentCollectionHooks.mapComputeIfAbsent(plain, "k", k -> b),
                () -> AgentCollectionHooks.mapComputeIfAbsent(plain, "k", k -> new Box())),
                "a HashMap orders nothing");
    }

    /** The last element {@code walk} hands its consumer. */
    private static Object lastSeen(Consumer<Consumer<Object>> walk) {
        Object[] last = new Object[1];
        walk.accept(element -> last[0] = element);
        return last[0];
    }

    @Test
    @DisplayName("a woven stream() or forEach over a concurrent collection receives each element (#1294)")
    void wovenStreamAndForEach() throws InterruptedException {
        List<Object> list = new CopyOnWriteArrayList<>();
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.collectionAdd(list, b),
                () -> lastSeen(seen -> AgentCollectionHooks.collectionStream(list).forEach(seen))),
                "add then stream");
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.collectionAdd(list, b),
                () -> lastSeen(seen -> AgentCollectionHooks.iterableForEach(list, seen))),
                "add then forEach");
        assertTrue(containerHandOffReported(b -> AgentCollectionHooks.collectionAdd(list, b),
                () -> lastSeen(seen -> list.stream().forEach(seen))), "the unwoven stream received nothing");
        assertTrue(containerHandOffReported(b -> AgentCollectionHooks.collectionAdd(list, b),
                () -> lastSeen(list::forEach)), "nor did the unwoven forEach");

        List<Object> plain = new ArrayList<>();
        assertTrue(containerHandOffReported(b -> AgentCollectionHooks.collectionAdd(plain, b),
                () -> lastSeen(seen -> AgentCollectionHooks.collectionStream(plain).forEach(seen))),
                "an ArrayList's stream orders nothing");
        assertTrue(containerHandOffReported(b -> AgentCollectionHooks.collectionAdd(plain, b),
                () -> lastSeen(seen -> AgentCollectionHooks.iterableForEach(plain, seen))),
                "nor does its forEach");
    }

    @Test
    @DisplayName("a woven Map.forEach, getOrDefault and putIfAbsent receive and publish a value (#1294)")
    void wovenMapForEachGetOrDefaultPutIfAbsent() throws InterruptedException {
        Map<Object, Object> map = new ConcurrentHashMap<>();
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.mapPut(map, "a", b),
                () -> lastSeen(seen -> AgentCollectionHooks.mapForEach(map, (k, v) -> seen.accept(v)))),
                "put then Map.forEach");
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.mapPut(map, "a", b),
                () -> AgentCollectionHooks.mapGetOrDefault(map, "a", "none")), "put then getOrDefault");
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.mapPutIfAbsent(map, "b", b),
                () -> AgentCollectionHooks.mapGet(map, "b")), "putIfAbsent then get");
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.mapPutIfAbsent(map, "c", b),
                () -> AgentCollectionHooks.mapPutIfAbsent(map, "c", new Box())),
                "the losing putIfAbsent received the winner's value");

        assertTrue(containerHandOffReported(b -> map.putIfAbsent("u", b),
                () -> AgentCollectionHooks.mapGetOrDefault(map, "u", "none")),
                "the unwoven putIfAbsent published nothing");
        assertTrue(containerHandOffReported(b -> AgentCollectionHooks.mapPut(map, "w", b),
                () -> map.getOrDefault("w", "none")), "the unwoven getOrDefault received nothing");

        // The key is taken: the stranger's box is refused, and its release is withdrawn.
        AtomicReference<Object> refused = new AtomicReference<>();
        map.put("taken", "first");
        assertTrue(containerHandOffReported(b -> {
            refused.setPlain(b);
            AgentCollectionHooks.mapPutIfAbsent(map, "taken", b);
        }, () -> {
            HappensBefore.acquire(refused.getPlain());
            return refused.getPlain();
        }), "a refused putIfAbsent published nothing");

        Map<Object, Object> plain = new HashMap<>();
        assertTrue(containerHandOffReported(b -> AgentCollectionHooks.mapPutIfAbsent(plain, "k", b),
                () -> AgentCollectionHooks.mapGetOrDefault(plain, "k", "none")), "a HashMap orders nothing");
    }

    @Test
    @DisplayName("a Vector's and a Stack's own reads receive what the add or push published (#1294)")
    void wovenVectorAndStackReads() throws InterruptedException {
        java.util.Vector<Object> vector = new java.util.Vector<>();
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.collectionAdd(vector, b),
                () -> AgentCollectionHooks.vectorElementAt(vector, vector.size() - 1)), "add then elementAt");
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.collectionAdd(vector, b),
                () -> AgentCollectionHooks.vectorLastElement(vector)), "add then lastElement");
        java.util.Vector<Object> single = new java.util.Vector<>();
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.collectionAdd(single, b),
                () -> AgentCollectionHooks.vectorFirstElement(single)), "add then firstElement");
        java.util.Vector<Object> unwoven = new java.util.Vector<>();
        assertTrue(containerHandOffReported(b -> AgentCollectionHooks.collectionAdd(unwoven, b),
                unwoven::firstElement), "the unwoven firstElement received nothing");

        java.util.Stack<Object> stack = new java.util.Stack<>();
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.stackPush(stack, b),
                () -> AgentCollectionHooks.stackPeek(stack)), "push then peek");
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.stackPush(stack, b),
                () -> AgentCollectionHooks.stackPop(stack)), "push then pop");
        Object pushed = new Object();
        assertSame(pushed, AgentCollectionHooks.stackPush(stack, pushed), "push returns its argument");
        java.util.Stack<Object> plainPush = new java.util.Stack<>();
        assertTrue(containerHandOffReported(plainPush::push,
                () -> AgentCollectionHooks.stackPeek(plainPush)), "the unwoven push published nothing");
    }

    @Test
    @DisplayName("getFirst and getLast on a concurrent deque or list, and a descending iterator, receive (#1294)")
    void wovenEndReads() throws InterruptedException {
        Deque<Object> deque = new ConcurrentLinkedDeque<>();
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.dequeAddFirst(deque, b),
                () -> AgentCollectionHooks.dequeGetFirst(deque)), "addFirst then getFirst");
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.dequeAddLast(deque, b),
                () -> AgentCollectionHooks.dequeGetLast(deque)), "addLast then getLast");
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.dequeAddLast(deque, b),
                () -> AgentCollectionHooks.dequeDescendingIterator(deque).next()),
                "addLast then descendingIterator");
        assertTrue(containerHandOffReported(b -> AgentCollectionHooks.dequeAddLast(deque, b), deque::getLast),
                "the unwoven getLast received nothing");
        Deque<Object> plain = new ArrayDeque<>();
        assertTrue(containerHandOffReported(b -> AgentCollectionHooks.dequeAddFirst(plain, b),
                () -> AgentCollectionHooks.dequeGetFirst(plain)), "an ArrayDeque orders nothing");
        Deque<Object> walked = new ArrayDeque<>();
        assertTrue(containerHandOffReported(b -> AgentCollectionHooks.dequeAddFirst(walked, b),
                () -> AgentCollectionHooks.dequeDescendingIterator(walked).next()),
                "an ArrayDeque's descending iterator orders nothing");

        List<Object> list = new CopyOnWriteArrayList<>();
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.collectionAdd(list, b),
                () -> AgentCollectionHooks.listGetLast(list)), "add then List.getLast");
        List<Object> one = new CopyOnWriteArrayList<>();
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.collectionAdd(one, b),
                () -> AgentCollectionHooks.listGetFirst(one)), "add then List.getFirst");
        List<Object> plainList = new ArrayList<>();
        assertTrue(containerHandOffReported(b -> AgentCollectionHooks.collectionAdd(plainList, b),
                () -> AgentCollectionHooks.listGetFirst(plainList)), "an ArrayList orders nothing");
    }

    @Test
    @DisplayName("a concurrent sorted set's navigation reads receive what the add published (#1294)")
    void wovenSortedSetReads() throws InterruptedException {
        // Every element compares equal, so each navigation read finds the one box whatever it is asked for.
        List<Function<java.util.NavigableSet<Object>, Object>> woven = List.of(
                AgentCollectionHooks::sortedSetFirst,
                AgentCollectionHooks::sortedSetLast,
                s -> AgentCollectionHooks.navigableSetFloor(s, "probe"),
                s -> AgentCollectionHooks.navigableSetCeiling(s, "probe"),
                AgentCollectionHooks::navigableSetPollFirst,
                AgentCollectionHooks::navigableSetPollLast);
        for (int i = 0; i < woven.size(); i++) {
            java.util.NavigableSet<Object> set = new java.util.concurrent.ConcurrentSkipListSet<>((a, b) -> 0);
            var read = woven.get(i);
            assertFalse(containerHandOffReported(b -> AgentCollectionHooks.collectionAdd(set, b),
                    () -> read.apply(set)), "add then sorted-set read " + i);
        }
        java.util.NavigableSet<Object> ordered = new java.util.concurrent.ConcurrentSkipListSet<>(
                java.util.Comparator.comparing(o -> o instanceof String ? 1 : 0));
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.collectionAdd(ordered, b),
                () -> AgentCollectionHooks.navigableSetLower(ordered, "probe")), "add then lower");
        java.util.NavigableSet<Object> reversed = new java.util.concurrent.ConcurrentSkipListSet<>(
                java.util.Comparator.comparing(o -> o instanceof String ? 0 : 1));
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.collectionAdd(reversed, b),
                () -> AgentCollectionHooks.navigableSetHigher(reversed, "probe")), "add then higher");

        java.util.NavigableSet<Object> unwoven = new java.util.concurrent.ConcurrentSkipListSet<>((a, b) -> 0);
        assertTrue(containerHandOffReported(b -> AgentCollectionHooks.collectionAdd(unwoven, b),
                unwoven::first), "the unwoven first received nothing");
        java.util.NavigableSet<Object> tree = new java.util.TreeSet<>((a, b) -> 0);
        assertTrue(containerHandOffReported(b -> AgentCollectionHooks.collectionAdd(tree, b),
                () -> AgentCollectionHooks.sortedSetFirst(tree)), "a TreeSet orders nothing");
        assertNull(AgentCollectionHooks.navigableSetPollFirst(new java.util.concurrent.ConcurrentSkipListSet<>()),
                "an empty set");
    }

    @Test
    @DisplayName("toArray and parallelStream on a concurrent collection receive every element (#1294)")
    void wovenBulkReads() throws InterruptedException {
        List<Object> list = new CopyOnWriteArrayList<>();
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.collectionAdd(list, b),
                () -> AgentCollectionHooks.collectionToArray(list)[0]), "add then toArray()");
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.listSet(list, 0, b),
                () -> AgentCollectionHooks.collectionToArray(list, new Object[0])[0]), "set then toArray(T[])");
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.listSet(list, 0, b),
                () -> AgentCollectionHooks.collectionToArray(list, Object[]::new)[0]),
                "set then toArray(IntFunction)");
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.listSet(list, 0, b),
                () -> AgentCollectionHooks.collectionParallelStream(list).findFirst().orElseThrow()),
                "set then parallelStream()");
        assertTrue(containerHandOffReported(b -> AgentCollectionHooks.listSet(list, 0, b),
                () -> list.toArray()[0]), "the unwoven toArray received nothing");

        List<Object> plain = new ArrayList<>();
        assertTrue(containerHandOffReported(b -> AgentCollectionHooks.collectionAdd(plain, b),
                () -> AgentCollectionHooks.collectionToArray(plain)[0]), "an ArrayList orders nothing");
        List<Object> streamed = new ArrayList<>();
        assertTrue(containerHandOffReported(b -> AgentCollectionHooks.collectionAdd(streamed, b),
                () -> AgentCollectionHooks.collectionParallelStream(streamed).findFirst().orElseThrow()),
                "an ArrayList's parallel stream orders nothing");
        Object[] sized = AgentCollectionHooks.collectionToArray(new ArrayList<>(List.of("a", "b")), new Object[5]);
        assertEquals(5, sized.length, "toArray(T[]) keeps the array it was given, null terminator and all");
        assertNull(sized[2], "the null terminator is not an element");
    }

    @Test
    @DisplayName("a concurrent deque's woven peekFirst and peekLast receive what the offer published (#1294)")
    void wovenDequePeeks() throws InterruptedException {
        Deque<Object> deque = new ConcurrentLinkedDeque<>();
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.dequeAddLast(deque, b),
                () -> AgentCollectionHooks.dequePeekLast(deque)), "addLast then peekLast");
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.dequeAddFirst(deque, b),
                () -> AgentCollectionHooks.dequePeekFirst(deque)), "addFirst then peekFirst");
        assertTrue(containerHandOffReported(b -> AgentCollectionHooks.dequeAddFirst(deque, b), deque::peekFirst),
                "the unwoven peekFirst received nothing");

        Deque<Object> plain = new ArrayDeque<>();
        assertTrue(containerHandOffReported(b -> AgentCollectionHooks.dequeAddFirst(plain, b),
                () -> AgentCollectionHooks.dequePeekFirst(plain)), "an ArrayDeque orders nothing");
    }

    @Test
    @DisplayName("a concurrent navigable map's woven entry reads receive the value the put published (#1294)")
    void wovenNavigationReads() throws InterruptedException {
        List<Function<ConcurrentSkipListMap<Object, Object>, Map.Entry<Object, Object>>> woven = List.of(
                AgentCollectionHooks::navigableFirstEntry,
                AgentCollectionHooks::navigableLastEntry,
                m -> AgentCollectionHooks.navigableFloorEntry(m, "k"),
                m -> AgentCollectionHooks.navigableCeilingEntry(m, "k"),
                m -> AgentCollectionHooks.navigableLowerEntry(m, "z"),
                m -> AgentCollectionHooks.navigableHigherEntry(m, "a"),
                AgentCollectionHooks::navigablePollFirstEntry,
                AgentCollectionHooks::navigablePollLastEntry);
        for (int i = 0; i < woven.size(); i++) {
            ConcurrentSkipListMap<Object, Object> map = new ConcurrentSkipListMap<>();
            var read = woven.get(i);
            assertFalse(containerHandOffReported(b -> AgentCollectionHooks.mapPut(map, "k", b),
                    () -> read.apply(map).getValue()), "put then navigation read " + i);
        }
        ConcurrentSkipListMap<Object, Object> plain = new ConcurrentSkipListMap<>();
        assertTrue(containerHandOffReported(b -> AgentCollectionHooks.mapPut(plain, "k", b),
                () -> plain.firstEntry().getValue()), "the unwoven firstEntry received nothing");
        java.util.TreeMap<Object, Object> tree = new java.util.TreeMap<>();
        assertTrue(containerHandOffReported(b -> AgentCollectionHooks.mapPut(tree, "k", b),
                () -> AgentCollectionHooks.navigableFirstEntry(tree).getValue()),
                "a TreeMap orders nothing");
        assertNull(AgentCollectionHooks.navigableFirstEntry(new ConcurrentSkipListMap<>()), "an empty map");
    }

    @Test
    @DisplayName("iterating a concurrent map's entry set receives each entry's value (#1294)")
    void wovenEntrySetValues() throws InterruptedException {
        ConcurrentHashMap<Object, Object> looped = new ConcurrentHashMap<>();
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.mapPut(looped, "k", b), () -> {
            Iterable<Object> entries = erased(AgentCollectionHooks.mapEntrySet(looped));
            return ((Map.Entry<?, ?>) AgentCollectionHooks.iterableIterator(entries).next()).getValue();
        }), "put then a for-each over entrySet()");

        ConcurrentHashMap<Object, Object> remaining = new ConcurrentHashMap<>();
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.mapPut(remaining, "k", b), () -> {
            Iterable<Object> entries = erased(AgentCollectionHooks.mapEntrySet(remaining));
            Object[] seen = new Object[1];
            AgentCollectionHooks.iterableIterator(entries)
                    .forEachRemaining(e -> seen[0] = ((Map.Entry<?, ?>) e).getValue());
            return seen[0];
        }), "put then forEachRemaining over entrySet()");

        ConcurrentHashMap<Object, Object> each = new ConcurrentHashMap<>();
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.mapPut(each, "k", b), () -> {
            Iterable<Object> entries = erased(AgentCollectionHooks.mapEntrySet(each));
            Object[] seen = new Object[1];
            AgentCollectionHooks.iterableForEach(entries, e -> seen[0] = ((Map.Entry<?, ?>) e).getValue());
            return seen[0];
        }), "put then entrySet().forEach");

        ConcurrentHashMap<Object, Object> streamed = new ConcurrentHashMap<>();
        assertFalse(containerHandOffReported(b -> AgentCollectionHooks.mapPut(streamed, "k", b), () -> {
            Collection<Object> entries = erased(AgentCollectionHooks.mapEntrySet(streamed));
            return AgentCollectionHooks.collectionStream(entries)
                    .map(e -> ((Map.Entry<?, ?>) e).getValue()).findFirst().orElseThrow();
        }), "put then entrySet().stream()");

        ConcurrentHashMap<Object, Object> bare = new ConcurrentHashMap<>();
        assertTrue(containerHandOffReported(b -> AgentCollectionHooks.mapPut(bare, "k", b),
                () -> bare.entrySet().iterator().next().getValue()), "the unwoven loop received nothing");
    }

    @SuppressWarnings("unchecked")
    private static <T> T erased(Object view) {
        return (T) view;
    }

    /**
     * One thread runs a {@code FutureTask} itself, another gets its result and reads what the
     * callable wrote (#1307).
     */
    private static boolean directRunReported(boolean wovenRun, boolean wovenGet)
            throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        FutureTask<Object> task = new FutureTask<>(() -> {
            Box box = new Box();
            box.value = 1;
            detector.recordFieldWrite(box, "value");
            return box;
        });
        Thread runner = new Thread(() -> {
            if (wovenRun) {
                AgentConcurrencyUtilHooks.futureTaskRun(task);
            } else {
                task.run();
            }
        });
        runner.start();
        runner.join();
        Thread getter = new Thread(() -> {
            try {
                Box got = (Box) (wovenGet ? AgentConcurrencyUtilHooks.get(task) : task.get());
                detector.recordFieldRead(got, "value");
            } catch (InterruptedException | ExecutionException e) {
                throw new AssertionError(e);
            }
        });
        getter.start();
        getter.join();
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a FutureTask the caller runs itself orders its callable before a get (#1307)")
    void aDirectlyRunFutureTaskPublishes() throws InterruptedException {
        assertFalse(directRunReported(true, true), "run then get");
        assertTrue(directRunReported(false, true), "an unwoven run released nothing");
        assertTrue(directRunReported(true, false), "an unwoven get acquired nothing");
    }

    /**
     * One thread writes a field and then stores into a primitive atomic, another reads the atomic
     * and then the field (#1315). {@code store} and {@code load} are the woven or the unwoven call.
     */
    private static boolean flagPublicationReported(Runnable store, Runnable load)
            throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box box = new Box();
        Thread writer = new Thread(() -> {
            box.value = 1;
            detector.recordFieldWrite(box, "value");
            store.run();
        });
        writer.start();
        writer.join();
        Thread reader = new Thread(() -> {
            load.run();
            detector.recordFieldRead(box, "value");
        });
        reader.start();
        reader.join();
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a store into a primitive atomic orders the writer before a get that saw it (#1315)")
    void aPrimitiveAtomicFlagPublishes() throws InterruptedException {
        AtomicBoolean flag = new AtomicBoolean();
        assertFalse(flagPublicationReported(() -> TelemetryRegistry.setAtomicBoolean(flag, true),
                () -> TelemetryRegistry.getAtomicBoolean(flag)), "set then get");
        AtomicBoolean unwovenStore = new AtomicBoolean();
        assertTrue(flagPublicationReported(() -> unwovenStore.set(true),
                () -> TelemetryRegistry.getAtomicBoolean(unwovenStore)), "an unwoven set released nothing");
        AtomicBoolean unwovenLoad = new AtomicBoolean();
        assertTrue(flagPublicationReported(() -> TelemetryRegistry.setAtomicBoolean(unwovenLoad, true),
                unwovenLoad::get), "an unwoven get acquired nothing");

        AtomicBoolean swapped = new AtomicBoolean();
        assertFalse(flagPublicationReported(
                () -> TelemetryRegistry.compareAndSetAtomicBoolean(swapped, false, true),
                () -> TelemetryRegistry.getAtomicBoolean(swapped)), "compareAndSet then get");

        AtomicInteger count = new AtomicInteger();
        assertFalse(flagPublicationReported(() -> TelemetryRegistry.incrementAndGetAtomicInteger(count),
                () -> TelemetryRegistry.getAtomicInteger(count)), "incrementAndGet then get");
        AtomicInteger added = new AtomicInteger();
        assertFalse(flagPublicationReported(() -> TelemetryRegistry.getAndAddAtomicInteger(added, 3),
                () -> TelemetryRegistry.getAtomicInteger(added)), "getAndAdd then get");

        AtomicLong total = new AtomicLong();
        assertFalse(flagPublicationReported(() -> TelemetryRegistry.setAtomicLong(total, 7L),
                () -> TelemetryRegistry.getAtomicLong(total)), "AtomicLong set then get");
        AtomicLong decremented = new AtomicLong();
        assertFalse(flagPublicationReported(() -> TelemetryRegistry.decrementAndGetAtomicLong(decremented),
                () -> TelemetryRegistry.getAtomicLong(decremented)), "AtomicLong decrement then get");
    }

    @Test
    @DisplayName("a get that returned a value no store published acquires nothing (#1315)")
    void aStaleReadOfAPrimitiveAtomicAcquiresNothing() throws InterruptedException {
        AtomicInteger count = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        assertTrue(flagPublicationReported(() -> TelemetryRegistry.setAtomicInteger(count, 5),
                () -> TelemetryRegistry.getAtomicInteger(other)), "a read of another atomic");
    }

    @Test
    @DisplayName("a compareAndSet that lost stored nothing, so it publishes nothing (#1315)")
    void aLostCompareAndSetPublishesNothing() throws InterruptedException {
        AtomicBoolean flag = new AtomicBoolean();
        TelemetryRegistry.setAtomicBoolean(flag, true);
        assertTrue(flagPublicationReported(
                () -> TelemetryRegistry.compareAndSetAtomicBoolean(flag, false, true),
                () -> TelemetryRegistry.getAtomicBoolean(flag)),
                "the reader saw the true this thread stored, not one the losing writer did");

        AtomicInteger count = new AtomicInteger();
        TelemetryRegistry.setAtomicInteger(count, 3);
        assertTrue(flagPublicationReported(
                () -> TelemetryRegistry.compareAndSetAtomicInteger(count, 0, 3),
                () -> TelemetryRegistry.getAtomicInteger(count)), "AtomicInteger");

        AtomicLong total = new AtomicLong();
        TelemetryRegistry.setAtomicLong(total, 3L);
        assertTrue(flagPublicationReported(
                () -> TelemetryRegistry.compareAndSetAtomicLong(total, 0L, 3L),
                () -> TelemetryRegistry.getAtomicLong(total)), "AtomicLong");
    }

    @Test
    @DisplayName("the arithmetic hooks still return what the JDK call returns (#1315)")
    void theArithmeticHooksKeepTheirResults() {
        AtomicInteger count = new AtomicInteger(10);
        org.junit.jupiter.api.Assertions.assertEquals(11, TelemetryRegistry.incrementAndGetAtomicInteger(count));
        org.junit.jupiter.api.Assertions.assertEquals(11, TelemetryRegistry.getAndIncrementAtomicInteger(count));
        org.junit.jupiter.api.Assertions.assertEquals(12, TelemetryRegistry.getAndAddAtomicInteger(count, 5));
        org.junit.jupiter.api.Assertions.assertEquals(20, TelemetryRegistry.addAndGetAtomicInteger(count, 3));
        org.junit.jupiter.api.Assertions.assertEquals(20, TelemetryRegistry.getAndDecrementAtomicInteger(count));
        org.junit.jupiter.api.Assertions.assertEquals(18, TelemetryRegistry.decrementAndGetAtomicInteger(count));
        org.junit.jupiter.api.Assertions.assertEquals(18, count.get());

        AtomicLong total = new AtomicLong(10L);
        org.junit.jupiter.api.Assertions.assertEquals(11L, TelemetryRegistry.incrementAndGetAtomicLong(total));
        org.junit.jupiter.api.Assertions.assertEquals(11L, TelemetryRegistry.getAndIncrementAtomicLong(total));
        org.junit.jupiter.api.Assertions.assertEquals(12L, TelemetryRegistry.getAndAddAtomicLong(total, 5L));
        org.junit.jupiter.api.Assertions.assertEquals(20L, TelemetryRegistry.addAndGetAtomicLong(total, 3L));
        org.junit.jupiter.api.Assertions.assertEquals(20L, TelemetryRegistry.getAndDecrementAtomicLong(total));
        org.junit.jupiter.api.Assertions.assertEquals(18L, TelemetryRegistry.decrementAndGetAtomicLong(total));
        org.junit.jupiter.api.Assertions.assertEquals(18L, total.get());
    }

    private static boolean startJoinReported(boolean woven) throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box box = new Box();
        box.value = 1;
        detector.recordFieldWrite(box, "value");
        Thread child = new Thread(() -> {
            box.value++;
            detector.recordFieldWrite(box, "value");
        });
        if (woven) {
            AgentThreadHooks.threadStart(child);
            AgentThreadHooks.threadJoin(child);
        } else {
            child.start();
            child.join();
        }
        detector.recordFieldRead(box, "value");
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a woven Thread.start and join order the child between the parent's accesses")
    void wovenStartAndJoin() throws InterruptedException {
        assertFalse(startJoinReported(true), "start and join are the lifecycle's two edges");
        assertTrue(startJoinReported(false), "the unwoven twin keeps its finding");
    }

    /**
     * The start and join of {@link #startJoinReported}, with the child started the way
     * {@code how} names and waited for by a spin on {@code isAlive} (#834): 0 through a platform
     * {@code Thread.Builder}, 1 through a virtual one, 2 through {@code startVirtualThread}.
     */
    private static boolean builtStartPolledEndReported(int how, boolean woven) {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box box = new Box();
        box.value = 1;
        detector.recordFieldWrite(box, "value");
        Runnable task = () -> {
            box.value++;
            detector.recordFieldWrite(box, "value");
        };
        Thread.Builder builder = how == 0 ? Thread.ofPlatform() : Thread.ofVirtual();
        Thread child;
        if (woven) {
            child = how == 2 ? AgentThreadHooks.threadStartVirtual(task)
                    : AgentThreadHooks.threadBuilderStart(builder, task);
            while (AgentThreadHooks.threadIsAlive(child)) {
                Thread.onSpinWait();
            }
        } else {
            child = how == 2 ? Thread.startVirtualThread(task) : builder.start(task);
            while (child.isAlive()) {
                Thread.onSpinWait();
            }
        }
        detector.recordFieldRead(box, "value");
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a woven Thread.Builder start or startVirtualThread, and an isAlive that said false, order the child")
    void wovenBuiltStartAndPolledEnd() {
        for (int how = 0; how < 3; how++) {
            assertFalse(builtStartPolledEndReported(how, true),
                    "start " + how + ": the builder start is a fork and a false isAlive a join");
            assertTrue(builtStartPolledEndReported(how, false),
                    "start " + how + ": the unwoven twin keeps its finding");
        }
    }

    @Test
    @DisplayName("an isAlive that says true orders nothing, and one about an unstarted thread nothing either")
    void anIsAliveThatSaysTrueOrdersNothing() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box box = new Box();
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread child = new Thread(() -> {
            box.value = 1;
            detector.recordFieldWrite(box, "value");
            written.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertFalse(AgentThreadHooks.threadIsAlive(child), "a thread not started yet is not alive");
        child.start();
        written.await();
        assertTrue(AgentThreadHooks.threadIsAlive(child), "the hook answers what isAlive answers");
        detector.recordFieldRead(box, "value");
        release.countDown();
        child.join();
        assertTrue(detector.analyzeRaceConditions().hasIssues(),
                "the read came after a latch the model does not see and an isAlive that said true");
    }

    private static boolean mapPublicationReported(Map<Object, Object> map)
            throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        CountDownLatch published = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            Box fresh = new Box();
            fresh.value = 7;
            detector.recordFieldWrite(fresh, "value");
            AgentCollectionHooks.mapPut(map, "config", fresh);
            published.countDown();
        });
        Thread reader = new Thread(() -> {
            try {
                published.await(); // unwoven, so it orders nothing as far as the model knows
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Box seen = (Box) AgentCollectionHooks.mapGet(map, "config");
            detector.recordFieldRead(seen, "value");
        });
        writer.start();
        reader.start();
        writer.join();
        reader.join();
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a ConcurrentHashMap put and get publish the value; a HashMap does not")
    void concurrentMapPublication() throws InterruptedException {
        assertFalse(mapPublicationReported(new ConcurrentHashMap<>()),
                "ConcurrentMap's memory consistency effect orders the put before the get");
        assertTrue(mapPublicationReported(new HashMap<>()),
                "a HashMap promises nothing, so the same calls order nothing");
    }

    private static boolean latchReported(boolean woven) throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        CountDownLatch ready = new CountDownLatch(1);
        Box box = new Box();
        Thread writer = new Thread(() -> {
            box.value = 3;
            detector.recordFieldWrite(box, "value");
            if (woven) {
                AgentConcurrencyUtilHooks.countDown(ready);
            } else {
                ready.countDown();
            }
        });
        Thread reader = new Thread(() -> {
            try {
                if (woven) {
                    AgentConcurrencyUtilHooks.await(ready);
                } else {
                    ready.await();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            detector.recordFieldRead(box, "value");
        });
        writer.start();
        reader.start();
        writer.join();
        reader.join();
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a woven countDown and await order the writer before the waiter")
    void wovenLatch() throws InterruptedException {
        assertFalse(latchReported(true));
        assertTrue(latchReported(false));
    }

    /**
     * A writer publishes {@code Box.value} with a volatile write of {@code Box.ready}, storing 1;
     * the reader reads the volatile field {@code readField} of the same box, which returns
     * {@code valueRead}, then {@code Box.value}. Each side emits exactly what the weaver emits.
     */
    private static boolean volatileFlagReported(String readField, int valueRead)
            throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box box = new Box();
        CountDownLatch flagged = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            box.value = 5;
            detector.recordFieldWrite(box, "value");
            // What the weaver emits before a volatile write of Box.ready that stores 1.
            TelemetryRegistry.recordAccess(box, null, null, Thread.currentThread().threadId(),
                    "Box.ready", true, true, Integer.MIN_VALUE, false, false);
            TelemetryRegistry.volatileStore(box, 1, "Box.ready");
            flagged.countDown();
        });
        Thread reader = new Thread(() -> {
            try {
                flagged.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            // What the weaver emits around the volatile read of readField: before, then after.
            TelemetryRegistry.recordAccess(box, null, null, Thread.currentThread().threadId(),
                    readField, false, true, Integer.MIN_VALUE, false, false);
            TelemetryRegistry.volatileLoad(box, valueRead, readField);
            // A plain read the weaver marks as following a volatile read of the same object.
            TelemetryRegistry.recordAccess(box, null, null, Thread.currentThread().threadId(),
                    "Box.value", false, false, Integer.MIN_VALUE, true, false);
            detector.recordFieldRead(box, "value");
        });
        writer.start();
        reader.start();
        writer.join();
        reader.join();
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a woven volatile write publishes to a read that returned the value it stored")
    void wovenVolatilePublication() throws InterruptedException {
        assertFalse(volatileFlagReported("Box.ready", 1));
    }

    @Test
    @DisplayName("a volatile read that returned the older value receives nothing (#742)")
    void aReadOfTheOlderValueReceivesNothing() throws InterruptedException {
        assertTrue(volatileFlagReported("Box.ready", 0),
                "the reader read 0, not the 1 the writer stored: it synchronizes with a write "
                        + "before the writer's, and Box.value reached it through nothing");
    }

    @Test
    @DisplayName("a volatile read of one field does not receive what a write of another published (#742)")
    void aVolatileEdgeIsPerField() throws InterruptedException {
        assertTrue(volatileFlagReported("Box.other", 1),
                "the reader read Box.other, which nobody wrote: Box.value reached it through "
                        + "nothing, and the write of Box.ready must not order it");
        assertFalse(volatileFlagReported("Box.ready", 1),
                "the twin reads the flag the writer set, which orders the plain write before it");
    }

    /** The two ways the producer below hands the token over, each of which can be refused. */
    private interface Hand {

        /** {@return whether the container took {@code token}} */
        boolean offer(Object token);

        /** {@return the token, taken back out of the container} */
        Object take() throws InterruptedException;
    }

    /**
     * A producer writes, then hands a token over through {@code hand}; a consumer then reads what
     * the producer wrote. With the hand-off accepted the consumer takes the token out of the same
     * container. With it refused the consumer can only get the token from a concurrent map the
     * main thread published it through before either thread ran, which orders nothing between the
     * two of them: the producer's refused offer published nothing to anybody.
     */
    private static boolean handOffAfterOfferReported(Hand hand, boolean accepted)
            throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box box = new Box();
        Object token = new Object();
        Map<Object, Object> registry = new ConcurrentHashMap<>();
        AgentCollectionHooks.mapPut(registry, "token", token);
        CountDownLatch handed = new CountDownLatch(1);
        AtomicReference<String> setUpWrong = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            box.value = 1;
            detector.recordFieldWrite(box, "value");
            if (hand.offer(token) != accepted) {
                setUpWrong.set("the container did not " + (accepted ? "take" : "refuse") + " the token");
            }
            handed.countDown(); // unwoven, so it orders nothing as far as the model knows
        });
        Thread consumer = new Thread(() -> {
            Object received;
            try {
                handed.await();
                received = accepted ? hand.take() : AgentCollectionHooks.mapGet(registry, "token");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (received != token) {
                setUpWrong.set("the consumer did not receive the token");
            }
            detector.recordFieldRead(box, "value");
            box.value++;
            detector.recordFieldWrite(box, "value");
        });
        producer.start();
        consumer.start();
        producer.join();
        consumer.join();
        assertNull(setUpWrong.get());
        return detector.analyzeRaceConditions().hasIssues();
    }

    /** A bounded queue, full when the offer must be refused. */
    private static Hand queue(boolean accepts) {
        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(1);
        if (!accepts) {
            queue.add(new Object());
        }
        return new Hand() {
            @Override
            public boolean offer(Object token) {
                return AgentConcurrencyUtilHooks.offer(queue, token);
            }

            @Override
            public Object take() throws InterruptedException {
                return AgentConcurrencyUtilHooks.take(queue);
            }
        };
    }

    /** An atomic slot, whose compareAndSet fails when the expected value is not the one it holds. */
    private static Hand slot(boolean accepts) {
        AtomicReference<Object> slot = new AtomicReference<>();
        Object expected = accepts ? null : new Object();
        return new Hand() {
            @Override
            public boolean offer(Object token) {
                return TelemetryRegistry.compareAndSetAtomicReference(slot, expected, token);
            }

            @Override
            public Object take() {
                return TelemetryRegistry.getAndSetAtomicReference(slot, null);
            }
        };
    }

    @Test
    @DisplayName("a refused offer publishes nothing; an accepted one still orders the take (#742)")
    void aRefusedOfferIsNotARelease() throws InterruptedException {
        assertTrue(handOffAfterOfferReported(queue(false), false),
                "the queue refused the token, so the producer's write reached the consumer through "
                        + "nothing: the offer must not have released it");
        assertFalse(handOffAfterOfferReported(queue(true), true),
                "the queue took the token and the consumer took it out: the hand-off orders them");
    }

    @Test
    @DisplayName("a compareAndSet retry that fails and then succeeds orders the take (#742)")
    void aRetryThatSucceedsStillPublishes() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box box = new Box();
        Object token = new Object();
        Object stale = new Object();
        AtomicReference<Object> slot = new AtomicReference<>(stale);
        CountDownLatch handed = new CountDownLatch(1);
        AtomicReference<String> setUpWrong = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            box.value = 1;
            detector.recordFieldWrite(box, "value");
            Object expected = new Object(); // the first attempt reads the slot wrong and fails
            while (!TelemetryRegistry.compareAndSetAtomicReference(slot, expected, token)) {
                expected = slot.get();
            }
            handed.countDown(); // unwoven, so it orders nothing as far as the model knows
        });
        Thread consumer = new Thread(() -> {
            try {
                handed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (TelemetryRegistry.getAndSetAtomicReference(slot, null) != token) {
                setUpWrong.set("the consumer did not take the token");
            }
            detector.recordFieldRead(box, "value");
            box.value++;
            detector.recordFieldWrite(box, "value");
        });
        producer.start();
        consumer.start();
        producer.join();
        consumer.join();
        assertNull(setUpWrong.get());
        assertFalse(detector.analyzeRaceConditions().hasIssues(),
                "the withdrawn release of the failed attempt must not take the successful one with it");
    }

    @Test
    @DisplayName("a failed compareAndSet publishes nothing; a successful one still orders the take (#742)")
    void aFailedCompareAndSetIsNotARelease() throws InterruptedException {
        assertTrue(handOffAfterOfferReported(slot(false), false),
                "the swap failed, so the producer's write reached the consumer through nothing: "
                        + "the compareAndSet must not have released it");
        assertFalse(handOffAfterOfferReported(slot(true), true),
                "the swap stored the token and the consumer's getAndSet took it: that orders them");
    }

    /**
     * A producer writes two boxes and adds both to a bounded queue with room for one, through the
     * woven {@code addAll}; the queue takes the first and refuses the second. The consumer then
     * reads one of them: the accepted box taken out of the queue, or the refused box through a
     * concurrent map the main thread published it through before either thread ran, which orders
     * nothing between the two of them (#806).
     */
    private static boolean batchElementReported(boolean readTheAcceptedOne)
            throws InterruptedException {
        return batchElementReported(readTheAcceptedOne, new ArrayBlockingQueue<>(1));
    }

    private static boolean batchElementReported(boolean readTheAcceptedOne, BlockingQueue<Object> queue)
            throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box accepted = new Box();
        Box refused = new Box();
        Map<Object, Object> registry = new ConcurrentHashMap<>();
        AgentCollectionHooks.mapPut(registry, "refused", refused);
        CountDownLatch handed = new CountDownLatch(1);
        AtomicReference<String> setUpWrong = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            accepted.value = 1;
            detector.recordFieldWrite(accepted, "value");
            refused.value = 1;
            detector.recordFieldWrite(refused, "value");
            try {
                AgentCollectionHooks.collectionAddAll(queue, List.of(accepted, refused));
                setUpWrong.set("the queue took the whole batch");
            } catch (IllegalStateException full) {
                // The second element did not fit, which is the point.
            }
            handed.countDown(); // unwoven, so it orders nothing as far as the model knows
        });
        Thread consumer = new Thread(() -> {
            Object received;
            try {
                handed.await();
                received = readTheAcceptedOne ? AgentConcurrencyUtilHooks.take(queue)
                        : AgentCollectionHooks.mapGet(registry, "refused");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Box box = readTheAcceptedOne ? accepted : refused;
            if (received != box) {
                setUpWrong.set("the consumer did not receive the box it reads");
            }
            detector.recordFieldRead(box, "value");
            box.value++;
            detector.recordFieldWrite(box, "value");
        });
        producer.start();
        consumer.start();
        producer.join();
        consumer.join();
        assertNull(setUpWrong.get());
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("an addAll that the queue takes only part of publishes only that part (#806)")
    void aPartlyRefusedAddAllPublishesOnlyWhatWentIn() throws InterruptedException {
        assertTrue(batchElementReported(false),
                "the queue refused the second box, so the producer's write of it reached the "
                        + "consumer through nothing: the addAll must not have released it");
        assertFalse(batchElementReported(true),
                "the queue took the first box and the consumer took it out: the hand-off orders them");
    }

    @Test
    @DisplayName("a queue with its own addAll that takes only part of the batch publishes only that part (#806)")
    void aPartlyRefusedOwnAddAllPublishesOnlyWhatWentIn() throws InterruptedException {
        // LinkedBlockingDeque links the whole batch at once when it fits, and when it does not,
        // falls back to adding one at a time and throws at the first that does not fit. The hook
        // keeps that call, so before #806's rest it released every element up front and withdrew
        // nothing when the call threw.
        assertTrue(batchElementReported(false, new java.util.concurrent.LinkedBlockingDeque<>(1)),
                "the deque refused the second box, so the producer's write of it reached the "
                        + "consumer through nothing: the addAll must not have released it");
        assertFalse(batchElementReported(true, new java.util.concurrent.LinkedBlockingDeque<>(1)),
                "the deque took the first box and the consumer took it out: the hand-off orders them");
    }

    /** How the reader below reaches the box a writer completed a future with. */
    private enum Completion {
        /** The writer completes the future; the reader joins it. */
        JOINED,
        /** The same, with the reader calling get instead of join. */
        GOT,
        /** The writer completes the future; the reader skips it and reads the box directly. */
        SKIPPED,
        /** The main thread completed the future first, so the writer's complete is refused. */
        REFUSED
    }

    /**
     * A writer writes a box and completes a future with it; a reader then reads and writes the
     * box. The two threads are otherwise coordinated only through an unwoven latch, so the only
     * edge the model can know is the one the future makes (#741).
     */
    private static boolean completionReported(Completion shape) throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        CompletableFuture<Object> future = new CompletableFuture<>();
        Box box = new Box();
        if (shape == Completion.REFUSED) {
            future.complete(box); // unwoven: publishes nothing
        }
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> setUpWrong = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            box.value = 1;
            detector.recordFieldWrite(box, "value");
            if (AgentConcurrencyUtilHooks.complete(future, box) == (shape == Completion.REFUSED)) {
                setUpWrong.set("the complete call did not answer what the case needs");
            }
            completed.countDown(); // unwoven, so it orders nothing as far as the model knows
        });
        Thread reader = new Thread(() -> {
            try {
                completed.await();
                Object received = switch (shape) {
                    case GOT -> AgentConcurrencyUtilHooks.get(future);
                    case SKIPPED -> box;
                    default -> AgentConcurrencyUtilHooks.join(future);
                };
                if (received != box) {
                    setUpWrong.set("the reader did not receive the box");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.util.concurrent.ExecutionException e) {
                setUpWrong.set("the future failed: " + e);
                return;
            }
            detector.recordFieldRead(box, "value");
            box.value++;
            detector.recordFieldWrite(box, "value");
        });
        writer.start();
        reader.start();
        writer.join();
        reader.join();
        assertNull(setUpWrong.get());
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a woven complete orders the writer before a woven join or get of the future (#741)")
    void completionPublishes() throws InterruptedException {
        assertFalse(completionReported(Completion.JOINED),
                "the writer completed the future after writing the box, and the reader's join "
                        + "returned it: CompletableFuture orders the two");
        assertFalse(completionReported(Completion.GOT), "the same through Future.get");
        assertTrue(completionReported(Completion.SKIPPED),
                "the reader never joined, so nothing orders the writer's write before its read");
        assertTrue(completionReported(Completion.REFUSED),
                "the future was already complete, so the writer's complete published nothing and "
                        + "the join observed a completion the writer did not make");
    }

    /** Where the registrar below breaks the order a dependent stage makes, if it does. */
    private enum Stage {
        /** Input written before thenApply, output read after joining the stage it returned. */
        ORDERED,
        /** The input is written after thenApply, where the function may already be reading it. */
        INPUT_AFTER_REGISTERING,
        /** The output is read before the join. */
        OUTPUT_BEFORE_JOIN,
        /** The source is already complete, so the function runs inside thenApply. */
        ALREADY_COMPLETE,
        /** Both calls made on the future directly, which the model cannot see. */
        UNWOVEN,
        /** As ORDERED, through a call site typed against CompletionStage. */
        TYPED_AS_STAGE
    }

    /**
     * The registering thread writes an input and registers a function on a future another thread
     * completes with a box it wrote; the function reads the input and the box and writes an
     * output, and the registering thread joins the stage and reads the output (#741). The function
     * runs on the completing thread, or inside thenApply when the source was already complete.
     */
    private static boolean stageReported(Stage shape) throws Exception {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box input = new Box();
        Box completed = new Box();
        Box output = new Box();
        CompletableFuture<Object> source = new CompletableFuture<>();
        Runnable complete = () -> {
            completed.value = 1;
            detector.recordFieldWrite(completed, "value");
            AgentConcurrencyUtilHooks.complete(source, completed);
        };
        if (shape == Stage.ALREADY_COMPLETE) {
            Thread completer = new Thread(complete);
            completer.start();
            completer.join();
        }
        if (shape != Stage.INPUT_AFTER_REGISTERING) {
            input.value = 1;
            detector.recordFieldWrite(input, "value");
        }
        java.util.function.Function<Object, Object> function = value -> {
            detector.recordFieldRead(input, "value");
            detector.recordFieldRead(value, "value");
            output.value = input.value + ((Box) value).value;
            detector.recordFieldWrite(output, "value");
            return output;
        };
        CompletableFuture<Object> stage = switch (shape) {
            case UNWOVEN -> source.thenApply(function);
            case TYPED_AS_STAGE -> (CompletableFuture<Object>) AgentConcurrencyUtilHooks.thenApply(
                    (java.util.concurrent.CompletionStage<Object>) source, function);
            default -> AgentConcurrencyUtilHooks.thenApply(source, function);
        };
        if (shape == Stage.INPUT_AFTER_REGISTERING) {
            input.value = 1;
            detector.recordFieldWrite(input, "value");
        }
        if (shape != Stage.ALREADY_COMPLETE) {
            Thread completer = new Thread(complete);
            completer.start();
            completer.join(); // unwoven: orders nothing as far as the model knows
        }
        if (shape == Stage.OUTPUT_BEFORE_JOIN) {
            detector.recordFieldRead(output, "value");
        }
        Object joined = shape == Stage.UNWOVEN ? stage.join() : AgentConcurrencyUtilHooks.join(stage);
        if (joined != output) {
            throw new AssertionError("the stage did not return the output");
        }
        if (shape != Stage.OUTPUT_BEFORE_JOIN) {
            detector.recordFieldRead(output, "value");
        }
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a woven thenApply orders the registrar and the completer before the function, and the function before a woven join (#741)")
    void dependentStagesPublish() throws Exception {
        assertFalse(stageReported(Stage.ORDERED),
                "the input was written before thenApply, the box before the completion the function "
                        + "followed, and the output read after joining the stage: all three ordered");
        assertFalse(stageReported(Stage.TYPED_AS_STAGE),
                "the same through a call typed against CompletionStage, which a library returning "
                        + "one makes");
        assertFalse(stageReported(Stage.ALREADY_COMPLETE),
                "the same with the source completed first, so the function ran inside thenApply");
        assertTrue(stageReported(Stage.INPUT_AFTER_REGISTERING),
                "the input was written after thenApply, which orders nothing before the function");
        assertTrue(stageReported(Stage.OUTPUT_BEFORE_JOIN),
                "the output was read before the join, which orders nothing after the function");
        assertTrue(stageReported(Stage.UNWOVEN),
                "the unwoven twin: nothing told the model");
    }

    @Test
    @DisplayName("a stage that is not a CompletableFuture gets the caller's own function (#741)")
    void anotherStageImplementationSeesNoWrapper() {
        java.util.concurrent.atomic.AtomicReference<Object> handed = new java.util.concurrent.atomic.AtomicReference<>();
        @SuppressWarnings("unchecked")
        java.util.concurrent.CompletionStage<Object> stage = (java.util.concurrent.CompletionStage<Object>)
                java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[] {java.util.concurrent.CompletionStage.class}, (proxy, method, args) -> {
                            if (method.getName().equals("thenApply")) {
                                handed.set(args[0]);
                                return proxy;
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
        java.util.function.Function<Object, Object> function = value -> value;

        AgentConcurrencyUtilHooks.thenApply(stage, function);

        assertTrue(handed.get() == function,
                "a stage the JDK did not write may hand its functions back, so it must get the "
                        + "caller's own, unwrapped: " + handed.get());
    }

    /**
     * The registering thread composes a stage whose function returns an inner future another
     * thread completes with a box it wrote, then joins the composed stage and reads the box
     * (#741). Only the inner future's completion orders that box, and the join observes it.
     */
    private static boolean composedStageReported(boolean joined) throws Exception {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box inner = new Box();
        CompletableFuture<Object> source = new CompletableFuture<>();
        CompletableFuture<Object> innerFuture = new CompletableFuture<>();
        CompletableFuture<Object> composed = AgentConcurrencyUtilHooks.thenCompose(source, value -> innerFuture);
        AgentConcurrencyUtilHooks.complete(source, Boolean.TRUE);
        Thread innerCompleter = new Thread(() -> {
            inner.value = 1;
            detector.recordFieldWrite(inner, "value");
            AgentConcurrencyUtilHooks.complete(innerFuture, inner);
        });
        innerCompleter.start();
        innerCompleter.join(); // unwoven: orders nothing as far as the model knows
        Object got = joined ? AgentConcurrencyUtilHooks.join(composed) : inner;
        if (got != inner) {
            throw new AssertionError("the composed stage did not return the inner box");
        }
        detector.recordFieldRead(inner, "value");
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a join of a composed stage is ordered after the inner stage's completion (#741)")
    void composedStagesPublish() throws Exception {
        assertFalse(composedStageReported(true),
                "the composed stage completed when the inner future did, and the join returned the "
                        + "inner future's box: the inner completion orders the box before the read");
        assertTrue(composedStageReported(false),
                "the box read without the join is ordered by nothing");
    }

    /**
     * The function of a composed stage returns a future that is itself a submitted task, which
     * writes a box on a pool thread. The registering thread joins the composed stage and
     * reads the box. Nothing is released to the inner future by a woven {@code complete}: what
     * orders the box is the task behind it, which only the thread that ran the function knows.
     */
    private static boolean composedTaskReported(boolean joined) throws Exception {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box inner = new Box();
        CompletableFuture<Object> source = new CompletableFuture<>();
        AgentConcurrencyUtilHooks.complete(source, Boolean.TRUE);
        CompletableFuture<Object> composed = AgentConcurrencyUtilHooks.thenCompose(source,
                value -> AgentConcurrencyUtilHooks.supplyAsync(() -> {
                    inner.value = 1;
                    detector.recordFieldWrite(inner, "value");
                    return inner;
                }));
        // The unwoven join waits without ordering anything as far as the model knows.
        Object got = joined ? AgentConcurrencyUtilHooks.join(composed) : composed.join();
        if (got != inner) {
            throw new AssertionError("the composed stage did not return the inner box");
        }
        detector.recordFieldRead(inner, "value");
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a join of a composed stage is ordered after the task its function submitted")
    void composedTasksPublish() throws Exception {
        assertFalse(composedTaskReported(true),
                "the composed stage completed when the inner task did, and the join returned the "
                        + "box that task wrote: the task's end orders the box before the read");
        assertTrue(composedTaskReported(false),
                "the box read without the woven join is ordered by nothing");
    }

    @Test
    @DisplayName("a stage following a composed stage is ordered after the task its function submitted")
    void aStageAfterAComposedTaskIsOrdered() throws Exception {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box inner = new Box();
        CompletableFuture<Object> source = new CompletableFuture<>();
        AgentConcurrencyUtilHooks.complete(source, Boolean.TRUE);
        CompletableFuture<Object> composed = AgentConcurrencyUtilHooks.thenCompose(source,
                value -> AgentConcurrencyUtilHooks.supplyAsync(() -> {
                    inner.value = 1;
                    detector.recordFieldWrite(inner, "value");
                    return inner;
                }));
        composed.join(); // unwoven: the next stage's function then runs on this thread
        AgentConcurrencyUtilHooks.thenApply(composed, value -> {
            detector.recordFieldRead(inner, "value");
            return value;
        });
        assertFalse(detector.analyzeRaceConditions().hasIssues(),
                "the function ran after the composed stage completed, which is after the inner task ended");
    }

    /** How the caller learns that the task which wrote the box has finished. */
    private enum Learned {
        /** A woven join of anyOf over the task's future. */
        ANY_OF,
        /** A woven join of a copy of the task's future. */
        COPY,
        /** A woven isDone on the task's CompletableFuture that returned true. */
        IS_DONE,
        /** A woven isDone on an executor's Future that returned true. */
        IS_DONE_ON_A_POOL,
        /** A woven isDone that returned false: the caller then waits unwoven. */
        IS_DONE_TOO_EARLY
    }

    /** A task writes a box, the caller learns it finished in the given way, then reads the box. */
    private static boolean learnedCompletionReported(Learned how) throws Exception {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box box = new Box();
        Runnable write = () -> {
            box.value = 1;
            detector.recordFieldWrite(box, "value");
        };
        switch (how) {
            case ANY_OF -> {
                CompletableFuture<Void> task = AgentConcurrencyUtilHooks.runAsync(write);
                CompletableFuture<Object> any = AgentConcurrencyUtilHooks.anyOf(
                        new CompletableFuture<?>[] {task, new CompletableFuture<>()});
                AgentConcurrencyUtilHooks.join(any);
            }
            case COPY -> {
                CompletableFuture<Void> task = AgentConcurrencyUtilHooks.runAsync(write);
                AgentConcurrencyUtilHooks.join(erased(AgentConcurrencyUtilHooks.copy(erased(task))));
            }
            case IS_DONE -> {
                CompletableFuture<Void> task = AgentConcurrencyUtilHooks.runAsync(write);
                task.join(); // unwoven: it waits, and orders nothing as far as the model knows
                assertTrue(AgentConcurrencyUtilHooks.isDone(task));
            }
            case IS_DONE_ON_A_POOL -> {
                ExecutorService pool = AgentConcurrencyUtilHooks.newFixedThreadPool(1);
                try {
                    Future<?> task = AgentConcurrencyUtilHooks.submit(pool, write);
                    while (!AgentConcurrencyUtilHooks.isDone(task)) {
                        Thread.onSpinWait();
                    }
                } finally {
                    pool.shutdownNow();
                }
            }
            case IS_DONE_TOO_EARLY -> {
                CountDownLatch asked = new CountDownLatch(1);
                CompletableFuture<Void> task = AgentConcurrencyUtilHooks.runAsync(() -> {
                    try {
                        asked.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    write.run();
                });
                assertFalse(AgentConcurrencyUtilHooks.isDone(task));
                asked.countDown();
                task.join(); // unwoven
            }
        }
        detector.recordFieldRead(box, "value");
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a join on anyOf orders the caller after the child that had completed")
    void anyOfPublishesTheChildThatCompleted() throws Exception {
        assertFalse(learnedCompletionReported(Learned.ANY_OF),
                "anyOf completed because the task's future did, and the other never completes");
    }

    @Test
    @DisplayName("a join on a copy orders the caller after the future it copies")
    void aCopyPublishesItsSource() throws Exception {
        assertFalse(learnedCompletionReported(Learned.COPY),
                "a copy completes when its source does, so a join on it observed the task");
    }

    @Test
    @DisplayName("an isDone that returned true orders the caller after the task")
    void isDoneThatReturnedTruePublishes() throws Exception {
        assertFalse(learnedCompletionReported(Learned.IS_DONE),
                "isDone read the completion, which is the read a get would have made");
        assertFalse(learnedCompletionReported(Learned.IS_DONE_ON_A_POOL),
                "the same for an executor's future, polled until it is done");
        assertTrue(learnedCompletionReported(Learned.IS_DONE_TOO_EARLY),
                "an isDone that returned false observed nothing, and nothing woven followed it");
    }

    /** Which executor the task below is given to, and when the input is written. */
    private enum Execution {
        /** A ForkJoinPool, input written before execute. */
        FORK_JOIN_POOL,
        /** A ScheduledThreadPoolExecutor, input written before execute. */
        SCHEDULED_POOL,
        /** A virtual thread per task, input written before execute. */
        THREAD_PER_TASK,
        /** A ForkJoinPool, with the input written after execute. */
        INPUT_AFTER_EXECUTE,
        /**
         * A ThreadPoolExecutor, input written before execute. Inside a run the hook wraps its task
         * too (#1155), and the woven shutdownNow and remove keep the wrapper from showing.
         */
        THREAD_POOL_EXECUTOR,
        /**
         * A ScheduledThreadPoolExecutor behind a woven Executors.unconfigurableExecutorService, as
         * kotlinx's newSingleThreadContext builds it (#1107): the wrapper forwards execute to a pool
         * that never hands the task back, so the hook looks through it.
         */
        UNCONFIGURABLE_SCHEDULED_POOL,
        /** A ThreadPoolExecutor behind the same wrapper, which the hook looks through as well. */
        UNCONFIGURABLE_THREAD_POOL,
        /**
         * A ThreadPoolExecutor subclass that overrides none of the methods a task reaches, only
         * {@code terminated}, so the wrapper is as invisible to it as to the pool itself (#1173).
         */
        QUIET_SUBCLASS,
        /** A ThreadPoolExecutor subclass whose {@code beforeExecute} would see the wrapper. */
        HOOKED_SUBCLASS
    }

    /** {@link Execution#QUIET_SUBCLASS}: overrides only what no task reaches. */
    private static final class QuietPool extends java.util.concurrent.ThreadPoolExecutor {
        QuietPool() {
            super(1, 1, 0, java.util.concurrent.TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        }

        @Override
        protected void terminated() {
            super.terminated();
        }
    }

    /** {@link Execution#HOOKED_SUBCLASS}: its beforeExecute is handed each task. */
    private static final class HookedPool extends java.util.concurrent.ThreadPoolExecutor {
        HookedPool() {
            super(1, 1, 0, java.util.concurrent.TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        }

        @Override
        protected void beforeExecute(Thread worker, Runnable task) {
            super.beforeExecute(worker, task);
        }
    }

    /**
     * The calling thread writes an input and executes a task that reads it, on the executor the
     * shape names (#741, #834). The task's read is recorded on the pool thread; the caller waits
     * for it through an unwoven latch, which orders nothing as far as the model knows.
     */
    private static boolean executionReported(Execution shape) throws Exception {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box input = new Box();
        java.util.concurrent.ExecutorService executor = switch (shape) {
            case SCHEDULED_POOL -> new java.util.concurrent.ScheduledThreadPoolExecutor(1);
            case THREAD_PER_TASK -> Executors.newVirtualThreadPerTaskExecutor();
            case THREAD_POOL_EXECUTOR -> new java.util.concurrent.ThreadPoolExecutor(1, 1, 0,
                    java.util.concurrent.TimeUnit.SECONDS, new LinkedBlockingQueue<>());
            case UNCONFIGURABLE_SCHEDULED_POOL -> AgentConcurrencyUtilHooks.unconfigurableExecutorService(
                    new java.util.concurrent.ScheduledThreadPoolExecutor(1));
            case UNCONFIGURABLE_THREAD_POOL -> AgentConcurrencyUtilHooks.unconfigurableExecutorService(
                    new java.util.concurrent.ThreadPoolExecutor(1, 1, 0,
                            java.util.concurrent.TimeUnit.SECONDS, new LinkedBlockingQueue<>()));
            case QUIET_SUBCLASS -> new QuietPool();
            case HOOKED_SUBCLASS -> new HookedPool();
            default -> new java.util.concurrent.ForkJoinPool(1);
        };
        try {
            if (shape != Execution.INPUT_AFTER_EXECUTE) {
                input.value = 1;
                detector.recordFieldWrite(input, "value");
            }
            CountDownLatch inputWritten = new CountDownLatch(1);
            CountDownLatch read = new CountDownLatch(1);
            AgentConcurrencyUtilHooks.execute(executor, () -> {
                try {
                    inputWritten.await(); // unwoven: only holds the task until the input is written
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                detector.recordFieldRead(input, "value");
                read.countDown();
            });
            if (shape == Execution.INPUT_AFTER_EXECUTE) {
                input.value = 1;
                detector.recordFieldWrite(input, "value");
            }
            inputWritten.countDown();
            if (!read.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new AssertionError("the task never ran");
            }
        } finally {
            executor.shutdownNow();
        }
        return detector.analyzeRaceConditions().hasIssues();
    }

    /** {@link #executionReported} with a run installed, which is when a ThreadPoolExecutor wraps. */
    private static boolean executionReportedInARun(Execution shape) throws Exception {
        AsyncTestContext.install(new AsyncTestContext(AsyncTestConfig.builder().detectAll(true).build()));
        try {
            return executionReported(shape);
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("a woven execute orders the caller before the task where the executor never hands the task back (#741, #834)")
    void executionPublishes() throws Exception {
        assertFalse(executionReported(Execution.FORK_JOIN_POOL),
                "the input was written before execute, which a ForkJoinPool orders before the task");
        assertFalse(executionReported(Execution.SCHEDULED_POOL),
                "the same through a ScheduledThreadPoolExecutor, whose queue holds its own futures");
        assertFalse(executionReported(Execution.THREAD_PER_TASK),
                "the same through a virtual thread per task");
        assertTrue(executionReported(Execution.INPUT_AFTER_EXECUTE),
                "the input was written after execute, which orders nothing before the task");
        assertFalse(executionReportedInARun(Execution.THREAD_POOL_EXECUTOR),
                "the same through a ThreadPoolExecutor, the pool most code has (#1155)");
        assertTrue(executionReported(Execution.THREAD_POOL_EXECUTOR),
                "outside a run nothing is watching, so that pool's task is left unwrapped");
    }

    @Test
    @DisplayName("a woven execute wraps a ThreadPoolExecutor subclass only where no override would see it (#1173)")
    void executionOnAThreadPoolSubclass() throws Exception {
        assertFalse(executionReportedInARun(Execution.QUIET_SUBCLASS),
                "the subclass overrides only terminated, which no task reaches, so its task is"
                        + " wrapped like the pool's own and the caller is ordered before it");
        assertTrue(executionReportedInARun(Execution.HOOKED_SUBCLASS),
                "an overriding beforeExecute would be handed the wrapper, so the task is left"
                        + " alone and nothing orders the caller before it");
    }

    @Test
    @DisplayName("a woven execute looks through an unconfigurable wrapper to the pool behind it (#1107)")
    void executionThroughAnUnconfigurableWrapperPublishes() throws Exception {
        assertFalse(executionReported(Execution.UNCONFIGURABLE_SCHEDULED_POOL),
                "the wrapper forwards to a ScheduledThreadPoolExecutor, which orders the caller"
                        + " before the task, as kotlinx's newSingleThreadContext relies on");
        assertFalse(executionReportedInARun(Execution.UNCONFIGURABLE_THREAD_POOL),
                "behind the wrapper is a ThreadPoolExecutor, which the hook wraps too (#1155)");
    }

    /** Whether a read after both tasks finished is reported, by how the caller waited for them. */
    @SuppressWarnings("unchecked")
    private static boolean readAfterAllOfReported(boolean throughTheWovenJoin) throws Exception {
        RaceConditionDetector detector = new RaceConditionDetector();
        Object result = new Object();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.CompletableFuture<Void> writes = AgentConcurrencyUtilHooks.runAsync(
                    () -> detector.recordFieldWrite(result, "value"), pool);
            java.util.concurrent.CompletableFuture<Void> idles = AgentConcurrencyUtilHooks.runAsync(() -> { }, pool);
            java.util.concurrent.CompletableFuture<Void> all = AgentConcurrencyUtilHooks.allOf(
                    new java.util.concurrent.CompletableFuture<?>[] {writes, idles});
            if (throughTheWovenJoin) {
                AgentConcurrencyUtilHooks.join((java.util.concurrent.CompletableFuture<Object>) (Object) all);
            } else {
                all.join();
            }
            detector.recordFieldRead(result, "value");
            return detector.analyzeRaceConditions().hasIssues();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a join on allOf orders the caller after every task it waited for")
    void aJoinOnAllOfOrdersTheCallerAfterItsTasks() throws Exception {
        assertFalse(readAfterAllOfReported(true),
                "allOf completes only when its children have, so a join on it observed each of them");
        assertTrue(readAfterAllOfReported(false),
                "the same tasks and the same read, with a join the agent did not see: the edge "
                        + "comes from the woven call, not from the timing");
    }

    /**
     * One writer on a pool of its own, and {@code others} tasks on a second pool, so no later task's
     * clock carries the write and only the writer's own edge can order a read after it (#1176).
     */
    private static boolean readAfterGettingEachReported(int others) throws Exception {
        RaceConditionDetector detector = new RaceConditionDetector();
        Object result = new Object();
        java.util.concurrent.ExecutorService writerPool = java.util.concurrent.Executors.newFixedThreadPool(1);
        java.util.concurrent.ExecutorService otherPool = java.util.concurrent.Executors.newFixedThreadPool(1);
        try {
            java.util.List<java.util.concurrent.Future<Object>> futures = new java.util.ArrayList<>();
            futures.add(AgentConcurrencyUtilHooks.submit(writerPool, (java.util.concurrent.Callable<Object>) () -> {
                detector.recordFieldWrite(result, "value");
                return null;
            }));
            for (int i = 0; i < others; i++) {
                futures.add(AgentConcurrencyUtilHooks.submit(otherPool, (java.util.concurrent.Callable<Object>) () -> null));
            }
            for (java.util.concurrent.Future<Object> future : futures) {
                AgentConcurrencyUtilHooks.get(future);
            }
            detector.recordFieldRead(result, "value");
            return detector.analyzeRaceConditions().hasIssues();
        } finally {
            writerPool.shutdownNow();
            otherPool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a read after getting more than 16 submissions is ordered after each of them (#1176)")
    void gettingManySubmissionsOrdersTheCallerAfterEach() throws Exception {
        assertFalse(readAfterGettingEachReported(64),
                "the writer is older than 16 submissions, and its get still ordered the read");
    }

    /** {@link #readAfterGettingEachReported} through one allOf. */
    @SuppressWarnings("unchecked")
    private static boolean readAfterAllOfManyReported(int others) throws Exception {
        RaceConditionDetector detector = new RaceConditionDetector();
        Object result = new Object();
        java.util.concurrent.ExecutorService writerPool = java.util.concurrent.Executors.newFixedThreadPool(1);
        java.util.concurrent.ExecutorService otherPool = java.util.concurrent.Executors.newFixedThreadPool(1);
        try {
            java.util.concurrent.CompletableFuture<?>[] children = new java.util.concurrent.CompletableFuture<?>[others + 1];
            children[0] = AgentConcurrencyUtilHooks.runAsync(() -> detector.recordFieldWrite(result, "value"), writerPool);
            for (int i = 1; i < children.length; i++) {
                children[i] = AgentConcurrencyUtilHooks.runAsync(() -> { }, otherPool);
            }
            AgentConcurrencyUtilHooks.join((java.util.concurrent.CompletableFuture<Object>) (Object)
                    AgentConcurrencyUtilHooks.allOf(children));
            detector.recordFieldRead(result, "value");
            return detector.analyzeRaceConditions().hasIssues();
        } finally {
            writerPool.shutdownNow();
            otherPool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a join on allOf orders the caller after a child older than 16 submissions (#1176)")
    void aJoinOnAllOfCoversManyChildren() throws Exception {
        assertFalse(readAfterAllOfManyReported(64), "allOf covers every child");
    }

    @Test
    @DisplayName("a callback chained on allOf is ordered after every child (#1176)")
    @SuppressWarnings("unchecked")
    void aCallbackOnAllOfIsOrderedAfterItsChildren() throws Exception {
        RaceConditionDetector detector = new RaceConditionDetector();
        Object result = new Object();
        java.util.concurrent.ExecutorService writerPool = java.util.concurrent.Executors.newFixedThreadPool(1);
        java.util.concurrent.ExecutorService lastPool = java.util.concurrent.Executors.newFixedThreadPool(1);
        java.util.concurrent.CountDownLatch writerDone = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.CompletableFuture<Void> writes = AgentConcurrencyUtilHooks.runAsync(() -> {
                detector.recordFieldWrite(result, "value");
                writerDone.countDown();
            }, writerPool);
            java.util.concurrent.CompletableFuture<Void> last = AgentConcurrencyUtilHooks.runAsync(() -> {
                try {
                    writerDone.await(); // unwoven: finishes after the writer, so the callback runs here
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, lastPool);
            java.util.concurrent.CompletableFuture<Void> all = AgentConcurrencyUtilHooks.allOf(
                    new java.util.concurrent.CompletableFuture<?>[] {writes, last});
            AgentConcurrencyUtilHooks.join((java.util.concurrent.CompletableFuture<Object>) (Object)
                    AgentConcurrencyUtilHooks.thenRun((java.util.concurrent.CompletableFuture<Object>) (Object) all,
                            () -> detector.recordFieldRead(result, "value")));
            assertFalse(detector.analyzeRaceConditions().hasIssues(),
                    "thenRun on allOf runs after every child, whichever thread finished last");
        } finally {
            writerPool.shutdownNow();
            lastPool.shutdownNow();
        }
    }

    /** Where the submitter below breaks the order an executor makes, if it does. */
    private enum Submission {
        /** Input written before submit, output read after get. */
        ORDERED,
        /** The input is written after the submit, where the task may already be reading it. */
        INPUT_AFTER_SUBMIT,
        /** The output is read before the get. */
        OUTPUT_BEFORE_GET,
        /** Both calls made on the executor directly, which the model cannot see. */
        UNWOVEN
    }

    /**
     * The calling thread writes an input, submits a task that reads it and writes an output, gets
     * the task's future and reads the output: the idiom the {@code java.util.concurrent} package
     * javadoc orders at both ends (#741). The task's accesses are recorded on the pool thread.
     */
    private static boolean submissionReported(Submission shape) throws Exception {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box input = new Box();
        Box output = new Box();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            if (shape != Submission.INPUT_AFTER_SUBMIT) {
                input.value = 1;
                detector.recordFieldWrite(input, "value");
            }
            CountDownLatch inputWritten = new CountDownLatch(1);
            Runnable task = () -> {
                try {
                    inputWritten.await(); // unwoven: only holds the task until the input is written
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                detector.recordFieldRead(input, "value");
                output.value = input.value * 2;
                detector.recordFieldWrite(output, "value");
            };
            Future<?> result = shape == Submission.UNWOVEN ? executor.submit(task)
                    : AgentConcurrencyUtilHooks.submit(executor, task);
            if (shape == Submission.INPUT_AFTER_SUBMIT) {
                input.value = 1;
                detector.recordFieldWrite(input, "value");
            }
            inputWritten.countDown();
            if (shape == Submission.OUTPUT_BEFORE_GET) {
                detector.recordFieldRead(output, "value");
            }
            @SuppressWarnings("unchecked")
            Future<Object> future = (Future<Object>) result;
            Object ignored = shape == Submission.UNWOVEN ? future.get()
                    : AgentConcurrencyUtilHooks.get(future);
            if (shape != Submission.OUTPUT_BEFORE_GET) {
                detector.recordFieldRead(output, "value");
            }
        } finally {
            executor.shutdownNow();
        }
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a woven submit orders the submitter before the task, and a woven get the task before the getter (#741)")
    void submissionPublishes() throws Exception {
        assertFalse(submissionReported(Submission.ORDERED),
                "the input was written before the submit and the output read after the get: "
                        + "the executor orders both");
        assertTrue(submissionReported(Submission.INPUT_AFTER_SUBMIT),
                "the input was written after the submit, which orders nothing before the task");
        assertTrue(submissionReported(Submission.OUTPUT_BEFORE_GET),
                "the output was read before the get, which orders nothing after the task");
        assertTrue(submissionReported(Submission.UNWOVEN),
                "the unwoven twin: nothing told the model");
    }

    /**
     * Two threads each write a box of their own, exchange it and read the one they got. With
     * {@code lateWrite} each also writes its own box again after the exchange, where the partner
     * is already reading it (#741).
     */
    private static boolean exchangeReported(boolean woven, boolean lateWrite)
            throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Exchanger<Object> exchanger = new Exchanger<>();
        AtomicReference<String> setUpWrong = new AtomicReference<>();
        Runnable side = () -> {
            Box mine = new Box();
            mine.value = 1;
            detector.recordFieldWrite(mine, "value");
            Object theirs;
            try {
                theirs = woven ? AgentConcurrencyUtilHooks.exchange(exchanger, mine)
                        : exchanger.exchange(mine);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!(theirs instanceof Box) || theirs == mine) {
                setUpWrong.set("the exchange did not hand over the partner's box");
                return;
            }
            if (lateWrite) {
                mine.value++;
                detector.recordFieldWrite(mine, "value");
            }
            detector.recordFieldRead(theirs, "value");
        };
        Thread left = new Thread(side);
        Thread right = new Thread(side);
        left.start();
        right.start();
        left.join();
        right.join();
        assertNull(setUpWrong.get());
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a woven exchange orders each side's writes before its partner's reads (#741)")
    void exchangePublishes() throws InterruptedException {
        assertFalse(exchangeReported(true, false),
                "each thread wrote its box before exchange and read the partner's after it: the "
                        + "Exchanger orders them");
        assertTrue(exchangeReported(true, true),
                "each thread wrote its box again after the exchange, which orders nothing before "
                        + "the partner's read");
        assertTrue(exchangeReported(false, false), "the unwoven twin: nothing told the model");
    }

    /** What the reader below reads out of an atomic slot, and how the writer filled it. */
    private enum SlotShape {
        /** The writer writes the box, then sets it; the reader gets it. */
        SET_THEN_GET,
        /** The writer sets the box and writes it after. */
        WRITTEN_AFTER_THE_SET,
        /**
         * The writer writes the box and sets a shared token into one slot; the reader gets the
         * same token out of another slot the main thread filled, and reaches the box through a map.
         */
        SAME_VALUE_OTHER_SLOT
    }

    /**
     * {@code AtomicReference.set} is a volatile write and {@code get} a volatile read, so a get
     * that returned what a set stored is ordered after it (#741). The edge is the slot's, found by
     * the value the get returned, as for a volatile field: the same object read out of another
     * slot orders nothing.
     */
    private static boolean slotReported(SlotShape shape) throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        AtomicReference<Object> slot = new AtomicReference<>();
        AtomicReference<Object> otherSlot = new AtomicReference<>();
        Object token = new Object();
        Box box = new Box();
        Map<Object, Object> registry = new ConcurrentHashMap<>();
        AgentCollectionHooks.mapPut(registry, "box", box);
        TelemetryRegistry.setAtomicReference(otherSlot, token);
        CountDownLatch set = new CountDownLatch(1);
        AtomicReference<String> setUpWrong = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            if (shape != SlotShape.WRITTEN_AFTER_THE_SET) {
                box.value = 1;
                detector.recordFieldWrite(box, "value");
            }
            TelemetryRegistry.setAtomicReference(slot,
                    shape == SlotShape.SAME_VALUE_OTHER_SLOT ? token : box);
            if (shape == SlotShape.WRITTEN_AFTER_THE_SET) {
                box.value = 1;
                detector.recordFieldWrite(box, "value");
            }
            set.countDown(); // unwoven, so it orders nothing as far as the model knows
        });
        Thread reader = new Thread(() -> {
            try {
                set.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Object got = TelemetryRegistry.getAtomicReference(
                    shape == SlotShape.SAME_VALUE_OTHER_SLOT ? otherSlot : slot);
            Object expected = shape == SlotShape.SAME_VALUE_OTHER_SLOT ? token : box;
            if (got != expected) {
                setUpWrong.set("the reader's get did not return what the case needs");
            }
            // Only the case that reads another slot needs the map; a get that returned the box
            // must be the only thing that hands it over.
            Box read = (Box) (shape == SlotShape.SAME_VALUE_OTHER_SLOT
                    ? AgentCollectionHooks.mapGet(registry, "box") : got);
            detector.recordFieldRead(read, "value");
            read.value++;
            detector.recordFieldWrite(read, "value");
        });
        writer.start();
        reader.start();
        writer.join();
        reader.join();
        assertNull(setUpWrong.get());
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a woven AtomicReference get receives what the set that stored its value published (#741)")
    void atomicReferencePublishes() throws InterruptedException {
        assertFalse(slotReported(SlotShape.SET_THEN_GET),
                "the writer wrote the box and set it; the reader's get returned it: the volatile "
                        + "semantics of set and get order the write before the read");
        assertTrue(slotReported(SlotShape.WRITTEN_AFTER_THE_SET),
                "the writer wrote the box after the set, which orders nothing before the read");
        assertTrue(slotReported(SlotShape.SAME_VALUE_OTHER_SLOT),
                "the reader read the token out of a slot the writer never set, so it received "
                        + "nothing the writer published through its own slot");
    }

    /**
     * A stopped telemetry registry stops the event ring, not the happens-before model.
     *
     * <p>{@code TelemetryRegistry.stop()} runs at JVM shutdown, and in tests that stand in for
     * the agent. It left a flag that every woven hand-off checked before telling the model, so in
     * any JVM where it had run once, every later hand-off went unrecorded and read as a race. That
     * is what kept the weekly mutation gate red from 2026-09-27: pitest runs every test class in
     * one JVM, and a test that stopped the registry ran before this class, whose woven cases then
     * all failed. Surefire gives each class its own JVM and never saw it.
     */
    @Test
    @DisplayName("a stopped telemetry registry does not switch off the happens-before feed")
    void aStoppedRegistryStillOrdersWovenHandOffs() throws InterruptedException {
        TelemetryRegistry.start(null);
        TelemetryRegistry.stop();
        assertFalse(handOffReported(true), "a woven queue hand-off, after the registry stopped");
        assertFalse(slotReported(SlotShape.SET_THEN_GET),
                "an AtomicReference set and get, after the registry stopped");
        assertFalse(volatileFlagReported("Box.ready", 1), "a volatile publication, after the registry stopped");
    }
}
