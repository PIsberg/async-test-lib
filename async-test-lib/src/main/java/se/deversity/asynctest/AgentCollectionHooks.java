package se.deversity.asynctest;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.AbstractQueue;
import java.util.Collection;
import java.util.BitSet;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;
import org.jspecify.annotations.Nullable;

import se.deversity.asynctest.diagnostics.AtomicMapUpdateTokens;
import se.deversity.asynctest.diagnostics.HappensBefore;
import se.deversity.asynctest.diagnostics.HeldLocks;
import se.deversity.asynctest.diagnostics.BlockingQueueDetector;
import se.deversity.asynctest.diagnostics.NonAtomicConcurrentMapUpdateDetector;
import se.deversity.asynctest.diagnostics.SharedCollectionDetector;
import se.deversity.asynctest.diagnostics.SynchronizedCollectionIterationDetector;
import se.deversity.asynctest.telemetry.TelemetryRegistry;
import se.deversity.asynctest.telemetry.ControlledSchedule;
import se.deversity.vibetags.annotations.AIContract;

/**
 * Weave targets for the agent's {@code collections=true} mode: record a collection access, then
 * perform it.
 *
 * <h2>Why these exist as methods</h2>
 *
 * <p>The agent's other hook, {@code TelemetryRegistry.recordAccess}, carries a field name and a
 * thread id. That is enough for the detectors that reason about fields, and useless to the ones
 * keyed by <em>instance</em>: {@link SharedCollectionDetector} and its siblings need the object
 * itself, because the lockset that decides whether an access was guarded is computed against that
 * instance. Nothing in the agent's stream carried an instance, which is why a class that keeps its
 * state in a {@code HashMap} recorded nothing at all no matter how many threads raced on it.
 *
 * <p>Capturing a receiver mid-expression is the whole difficulty: at the call site the stack holds
 * {@code [receiver, args...]}, so reaching the receiver means spilling the arguments. Substituting
 * the invocation with a static whose <em>first parameter is the receiver</em> lets Byte Buddy emit
 * that shape, costs no array and no boxing, and keeps the stack depth of the original call.
 *
 * <p>Each method here must therefore mirror one invocation shape exactly, receiver first, and must
 * end by performing the original call. A hook that swallows the call would silently change the
 * behaviour of the code under test, which is the one thing a test library may never do.
 *
 * @since 1.9.8
 */
@API(status = Status.INTERNAL)
@AIContract(reason = "Called from bytecode the agent rewrites, not from source: the method names and erased signatures are matched by CollectionAccessWeaver and cannot change independently of it. Every hook must end by performing the original operation and must never throw on the recording path - it runs inside the user's code, so an exception here surfaces as a failure in their test. Recording is best-effort by design: no context, a disabled detector, or a type the library knows is thread-safe all mean record nothing and delegate. A queue offer or take hook with a trailing Object parameter is the variant the weaver calls with the enclosing method's own monitor: this in an instance method, the class in a static synchronized one. A synchronized method holds it with no instruction to say so (#796), and a helper that method calls holds it too (#751), so the hook counts it among the held locks exactly when Thread.holdsLock says so, and the two-argument form delegates with null.")
public final class AgentCollectionHooks {

    /**
     * Whether a receiver's class inherits instance state from a class the weaver cannot see.
     *
     * <p>These hooks exist to stand in for fields inside {@code java.util}, where nothing is
     * woven. A receiver whose state lives entirely in weavable classes is already watched field by
     * field, so recording it here adds nothing when it is correct and invents a corruption risk
     * when it has no state at all: Guava's cache hands a stateless no-op {@code AbstractQueue} to
     * every lock-free read, and the corpus eval reported that as "write operations from 240
     * threads" on a class whose javadoc says it is safe for concurrent use. The walk stops at the
     * first bootstrap-loaded class, since that is where the weaver stops; {@code AbstractQueue},
     * {@code AbstractCollection} and {@code AbstractSet} declare no instance fields, so a subclass
     * of one of those is judged by its own fields, while {@code extends ArrayList} keeps being
     * recorded because the list's array is state the weaver will never see.
     */
    private static final ClassValue<Boolean> INHERITS_UNWEAVABLE_STATE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> current = type; current != null && current != Object.class;
                    current = current.getSuperclass()) {
                if (current.getClassLoader() != null) {
                    continue;
                }
                for (Field field : current.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())) {
                        return Boolean.TRUE;
                    }
                }
            }
            return Boolean.FALSE;
        }
    };

    /**
     * Whether a queue's {@code addAll} is the one {@link AbstractQueue} declares, a loop over its
     * own {@code add}, which {@link #addEach} can run in its place with the same calls (#806).
     *
     * <p>A queue that implements {@code addAll} itself, {@code ConcurrentLinkedQueue} or
     * {@code LinkedBlockingDeque}, may insert the batch in a way no loop here reproduces, so it
     * keeps its own call. Reflection is asked once per class; a failure to ask answers no.
     */
    private static final ClassValue<Boolean> ADDS_ONE_BY_ONE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("addAll", Collection.class).getDeclaringClass()
                        == AbstractQueue.class;
            } catch (NoSuchMethodException | SecurityException e) {
                return Boolean.FALSE;
            }
        }
    };

    private AgentCollectionHooks() {
    }

    /**
     * Records one access, unless the receiver's own type already answers for thread safety.
     *
     * <p>A {@code ConcurrentHashMap} synchronizes inside {@code java.util.concurrent}, where the
     * agent weaves nothing, and a {@code Collections.synchronizedMap} wrapper takes a monitor the
     * same way. Neither emits anything the lockset can see, so every access to them would look
     * unguarded and every shared one would be reported. Reading the concrete type here is the same
     * answer {@code ConcurrentModificationDetector} already gives for the same reason, and it is
     * confined to the agent-fed path: a caller that records such a collection by hand asked for it
     * and still gets it.
     */
    private static void record(@Nullable Object receiver, String operation, boolean isWrite) {
        if (receiver == null) {
            return;
        }
        // What the receiver's own type promises. A ConcurrentMap or a BlockingQueue is a contract
        // its implementor has to keep, wherever it lives: Guava's cache implements ConcurrentMap
        // and guards itself with striped locks, so an Eraser intersection over the whole structure
        // is empty however correct it is. Asking the interface rather than the package name is
        // both narrower and more general than a prefix, and it covers a user's own implementation.
        if (receiver instanceof java.util.concurrent.ConcurrentMap
                || receiver instanceof java.util.concurrent.BlockingQueue
                || receiver instanceof BlockingDeque
                // The legacy synchronized collections: every method takes the instance's own
                // monitor, inside java.util where no MONITORENTER is woven and before this hook
                // could probe it.
                || receiver instanceof java.util.Hashtable
                || receiver instanceof java.util.Vector) {
            return;
        }
        String type = receiver.getClass().getName();
        if (type.startsWith("java.util.concurrent.")
                || type.startsWith("java.util.Collections$Synchronized")) {
            return;
        }
        if (!INHERITS_UNWEAVABLE_STATE.get(receiver.getClass())) {
            return;
        }
        SharedCollectionDetector detector = AsyncTestContext.currentSharedCollectionDetector();
        if (detector == null) {
            return;
        }
        // The label is the receiver's type name, which Class already holds: deriving a prettier
        // one would allocate a string on a path that runs inside the code under test. Findings are
        // keyed by instance identity regardless, so the label is only what the report prints.
        if (isWrite) {
            detector.recordWrite(receiver, type, operation);
        } else {
            detector.recordRead(receiver, type, operation);
        }
    }

    /**
     * Whether each call on {@code map} is atomic on its own, so that only a pair of calls can race.
     *
     * <p>These are the maps {@link #record} skips, and the ones the check-then-act feed watches: a
     * {@code ConcurrentMap}, a {@code Hashtable}, or a {@code Collections.synchronized*} wrapper,
     * which takes its monitor inside each call and releases it before the next. A plain map races
     * on every call, which the shared-collection detector already reports, so pairing its calls
     * too would report one bug twice.
     */
    private static boolean selfSynchronized(Map<?, ?> map) {
        return map instanceof java.util.concurrent.ConcurrentMap
                || map instanceof java.util.Hashtable
                || map.getClass().getName().startsWith("java.util.Collections$Synchronized");
    }

    /**
     * Hands a {@code containsKey} or {@code get} on a self-synchronized map to the check-then-act
     * detector as the check half of a pair.
     *
     * @param receiver the map
     * @param key      the key read
     * @param absent   whether the call found the key absent
     * @param read     whether the call was a {@code get}
     */
    private static void checkHalf(Map<Object, Object> receiver, @Nullable Object key, boolean absent,
                                  boolean read) {
        NonAtomicConcurrentMapUpdateDetector detector =
                AsyncTestContext.currentNonAtomicConcurrentMapUpdateDetector();
        if (detector != null && selfSynchronized(receiver)) {
            detector.agentCheck(receiver, key, absent, read);
        }
    }

    /**
     * Hands a {@code size} or {@code isEmpty} on a self-synchronized map to the check-then-act
     * detector as a check that names no key (#1249).
     *
     * @param receiver the map
     * @param isEmpty  whether the call was {@code isEmpty}
     */
    private static void keylessCheck(Map<Object, Object> receiver, boolean isEmpty) {
        NonAtomicConcurrentMapUpdateDetector detector =
                AsyncTestContext.currentNonAtomicConcurrentMapUpdateDetector();
        if (detector != null && selfSynchronized(receiver)) {
            detector.agentSizeCheck(receiver, isEmpty);
        }
    }

    /**
     * Hands a {@code put} or {@code remove} on a self-synchronized map to the check-then-act
     * detector, which pairs it with the thread's last check on the same map and key.
     *
     * @param receiver the map
     * @param key      the key acted on
     * @param value    the value put, {@code null} for a remove
     * @param remove   whether the call was a {@code remove}
     */
    private static void actHalf(Map<Object, Object> receiver, @Nullable Object key,
                                @Nullable Object value, boolean remove) {
        NonAtomicConcurrentMapUpdateDetector detector =
                AsyncTestContext.currentNonAtomicConcurrentMapUpdateDetector();
        if (detector != null && selfSynchronized(receiver)) {
            detector.agentAct(receiver, key, value, remove);
        }
    }

    /** {@return whether {@code collection} is one of the {@code Collections.synchronized*} wrappers} */
    private static boolean synchronizedWrapper(Object collection) {
        return collection.getClass().getName().startsWith("java.util.Collections$Synchronized");
    }

    /** Tells the iteration detector what a {@code contains} on a synchronized list found (#1159). */
    private static void checkedSynchronizedList(Collection<?> receiver, @Nullable Object element,
                                                boolean found) {
        if (receiver instanceof List && synchronizedWrapper(receiver)) {
            SynchronizedCollectionIterationDetector detector =
                    AsyncTestContext.currentSynchronizedCollectionIterationDetector();
            if (detector != null) {
                detector.agentContains(receiver, element, found);
            }
        }
    }

    /** Tells the iteration detector about an {@code add} on a synchronized list (#1159). */
    private static void addingToSynchronizedList(Collection<?> receiver, @Nullable Object element) {
        if (receiver instanceof List && synchronizedWrapper(receiver)) {
            SynchronizedCollectionIterationDetector detector =
                    AsyncTestContext.currentSynchronizedCollectionIterationDetector();
            if (detector != null) {
                detector.agentAdd(receiver, element);
            }
        }
    }

    /** Hands an iteration of a synchronized wrapper to its detector, which knows its monitor. */
    private static void iterated(Object collection) {
        // Walking a collection reads all of it: beside another thread's add, that is the concurrent
        // modification race, and without this read SharedCollectionDetector saw one writer (#1052).
        // record() skips the types that synchronize themselves, as it does for every other call.
        record(collection, "iterator", false);
        if (!synchronizedWrapper(collection)) {
            return;
        }
        SynchronizedCollectionIterationDetector detector =
                AsyncTestContext.currentSynchronizedCollectionIterationDetector();
        if (detector != null) {
            detector.agentIteration(collection, Thread.currentThread());
        }
    }

    /** Tells the iteration detector that {@code view} came out of the synchronized map {@code owner}. */
    private static <V> V viewOf(V view, Map<Object, Object> owner) {
        if (view != null && synchronizedWrapper(owner)) {
            SynchronizedCollectionIterationDetector detector =
                    AsyncTestContext.currentSynchronizedCollectionIterationDetector();
            if (detector != null) {
                detector.recordView(view, owner);
            }
        }
        return view;
    }

    /**
     * Weaves {@code Iterable.iterator()}, the call a for-each loop compiles to: an iteration of a
     * {@code Collections.synchronized*} wrapper is judged against the monitor it needs held.
     *
     * <p>Over a container that orders what passes through it, the iterator returned wraps the
     * container's own and tells the happens-before model about each element as it hands it out
     * (#1294). It delegates every call, so it behaves as the original does, but it is not the
     * original's class. Any other iterable gets its own iterator back.
     *
     * @param receiver the iterable
     * @return its iterator
     * @since 1.13.2
     */
    public static java.util.Iterator<Object> iterableIterator(Iterable<Object> receiver) {
        ControlledSchedule.point();
        iterated(receiver);
        java.util.Iterator<Object> elements = receiver.iterator();
        // Each element is received as the loop reaches it (#1294). A container that orders
        // nothing keeps its own iterator.
        return elements != null && HappensBefore.publishesElements(receiver)
                ? new ReceivingIterator(elements) : elements;
    }

    /**
     * Weaves {@code Collection.stream()}, which walks the backing collection as an iterator does.
     *
     * @param receiver the collection
     * @return its stream
     * @since 1.13.2
     */
    public static java.util.stream.Stream<Object> collectionStream(Collection<Object> receiver) {
        ControlledSchedule.point();
        iterated(receiver);
        java.util.stream.Stream<Object> elements = receiver.stream();
        // Each element is received as the pipeline reaches it, on the thread that does (#1294).
        return elements != null && HappensBefore.publishesElements(receiver)
                ? elements.peek(AgentCollectionHooks::receive) : elements;
    }

    /**
     * Weaves {@code Map.keySet()}: a view of a synchronized map is remembered with the map, whose
     * monitor guards iterating it.
     *
     * @param receiver the map
     * @return its key set
     * @since 1.13.2
     */
    public static java.util.Set<Object> mapKeySet(Map<Object, Object> receiver) {
        ControlledSchedule.point();
        return viewOf(receiver.keySet(), receiver);
    }

    /**
     * Weaves {@code Map.values()}, as {@link #mapKeySet}.
     *
     * @param receiver the map
     * @return its values
     * @since 1.13.2
     */
    public static Collection<Object> mapValues(Map<Object, Object> receiver) {
        ControlledSchedule.point();
        return viewOf(receiver.values(), receiver);
    }

    /**
     * Weaves {@code Map.entrySet()}, as {@link #mapKeySet}.
     *
     * @param receiver the map
     * @return its entry set
     * @since 1.13.2
     */
    public static java.util.Set<Map.Entry<Object, Object>> mapEntrySet(Map<Object, Object> receiver) {
        ControlledSchedule.point();
        return viewOf(receiver.entrySet(), receiver);
    }

    /**
     * Weaves {@code Iterable.forEach}, a walk of the collection like a for-each loop: over a
     * container that orders what passes through it, each element is received before the action
     * sees it (#1294).
     *
     * @param receiver the iterable
     * @param action   the action to run on each element
     */
    public static void iterableForEach(Iterable<Object> receiver,
                                       java.util.function.Consumer<Object> action) {
        ControlledSchedule.point();
        iterated(receiver);
        if (action == null || !HappensBefore.publishesElements(receiver)) {
            receiver.forEach(action);
            return;
        }
        receiver.forEach(element -> {
            receive(element);
            action.accept(element);
        });
    }

    /**
     * Receives an element a walk over an ordering container handed out. A map entry stands for
     * its key and value, which are what the walk reads next, so both are received with it: the
     * values of an iterated {@code entrySet()} (#1294).
     */
    private static void receive(@Nullable Object element) {
        HappensBefore.acquire(element);
        if (element instanceof Map.Entry<?, ?> entry) {
            receiveEntry(entry);
        }
    }

    /** Receives what {@code entry} maps, and nothing when the entry no longer says. */
    private static void receiveEntry(Map.Entry<?, ?> entry) {
        try {
            HappensBefore.acquire(entry.getKey());
            HappensBefore.acquire(entry.getValue());
        } catch (IllegalStateException removed) { // NOPMD EmptyCatchBlock - the entry left its map
            // Map.Entry allows an entry whose mapping was removed to refuse; nothing to receive.
        }
    }

    /**
     * A navigation read's entry from a map that orders what passes through it: its key and
     * value are received, as a {@code get} receives the value (#1294).
     */
    private static Map.@Nullable Entry<Object, Object> receivedEntry(
            Object receiver, Map.@Nullable Entry<Object, Object> entry) {
        if (entry != null && HappensBefore.publishesElements(receiver)) {
            receiveEntry(entry);
        }
        return entry;
    }

    /** Weaves {@code NavigableMap.firstEntry}, a read (#1294). @param receiver the map @return its first entry, or null */
    public static Map.@Nullable Entry<Object, Object> navigableFirstEntry(NavigableMap<Object, Object> receiver) {
        ControlledSchedule.point();
        record(receiver, "firstEntry", false);
        return receivedEntry(receiver, receiver.firstEntry());
    }

    /** Weaves {@code NavigableMap.lastEntry}, a read (#1294). @param receiver the map @return its last entry, or null */
    public static Map.@Nullable Entry<Object, Object> navigableLastEntry(NavigableMap<Object, Object> receiver) {
        ControlledSchedule.point();
        record(receiver, "lastEntry", false);
        return receivedEntry(receiver, receiver.lastEntry());
    }

    /**
     * Weaves {@code NavigableMap.floorEntry}, a read (#1294).
     *
     * @param receiver the map
     * @param key      the key to search from
     * @return the entry at or below {@code key}, or null
     */
    public static Map.@Nullable Entry<Object, Object> navigableFloorEntry(NavigableMap<Object, Object> receiver,
                                                                         Object key) {
        ControlledSchedule.point();
        record(receiver, "floorEntry", false);
        return receivedEntry(receiver, receiver.floorEntry(key));
    }

    /**
     * Weaves {@code NavigableMap.ceilingEntry}, a read (#1294).
     *
     * @param receiver the map
     * @param key      the key to search from
     * @return the entry at or above {@code key}, or null
     */
    public static Map.@Nullable Entry<Object, Object> navigableCeilingEntry(NavigableMap<Object, Object> receiver,
                                                                           Object key) {
        ControlledSchedule.point();
        record(receiver, "ceilingEntry", false);
        return receivedEntry(receiver, receiver.ceilingEntry(key));
    }

    /**
     * Weaves {@code NavigableMap.lowerEntry}, a read (#1294).
     *
     * @param receiver the map
     * @param key      the key to search from
     * @return the entry strictly below {@code key}, or null
     */
    public static Map.@Nullable Entry<Object, Object> navigableLowerEntry(NavigableMap<Object, Object> receiver,
                                                                         Object key) {
        ControlledSchedule.point();
        record(receiver, "lowerEntry", false);
        return receivedEntry(receiver, receiver.lowerEntry(key));
    }

    /**
     * Weaves {@code NavigableMap.higherEntry}, a read (#1294).
     *
     * @param receiver the map
     * @param key      the key to search from
     * @return the entry strictly above {@code key}, or null
     */
    public static Map.@Nullable Entry<Object, Object> navigableHigherEntry(NavigableMap<Object, Object> receiver,
                                                                          Object key) {
        ControlledSchedule.point();
        record(receiver, "higherEntry", false);
        return receivedEntry(receiver, receiver.higherEntry(key));
    }

    /** Weaves {@code NavigableMap.pollFirstEntry}, a removal (#1294). @param receiver the map @return the entry removed, or null */
    public static Map.@Nullable Entry<Object, Object> navigablePollFirstEntry(NavigableMap<Object, Object> receiver) {
        ControlledSchedule.point();
        record(receiver, "pollFirstEntry", true);
        return receivedEntry(receiver, receiver.pollFirstEntry());
    }

    /** Weaves {@code NavigableMap.pollLastEntry}, a removal (#1294). @param receiver the map @return the entry removed, or null */
    public static Map.@Nullable Entry<Object, Object> navigablePollLastEntry(NavigableMap<Object, Object> receiver) {
        ControlledSchedule.point();
        record(receiver, "pollLastEntry", true);
        return receivedEntry(receiver, receiver.pollLastEntry());
    }

    /**
     * Weaves {@code Map.forEach}: over a map that orders what passes through it, each key and
     * value is received before the action sees it (#1294).
     *
     * @param receiver the map
     * @param action   the action to run on each entry
     */
    public static void mapForEach(Map<Object, Object> receiver,
                                  java.util.function.BiConsumer<Object, Object> action) {
        ControlledSchedule.point();
        record(receiver, "forEach", false);
        if (action == null || !HappensBefore.publishesElements(receiver)) {
            receiver.forEach(action);
            return;
        }
        receiver.forEach((key, value) -> {
            HappensBefore.acquire(key);
            HappensBefore.acquire(value);
            action.accept(key, value);
        });
    }

    /**
     * Weaves {@code Map.getOrDefault}, a read like {@code get} (#1294).
     *
     * @param receiver     the map
     * @param key          the key looked up
     * @param defaultValue what to return for an absent key
     * @return the mapped value, or {@code defaultValue}
     */
    @SuppressWarnings("ReferenceEquality") // the default came back, or a mapped value did
    public static @Nullable Object mapGetOrDefault(Map<Object, Object> receiver, Object key,
                                                   Object defaultValue) {
        ControlledSchedule.point();
        record(receiver, "getOrDefault", false);
        Object value = receiver.getOrDefault(key, defaultValue);
        return value == defaultValue ? value : received(receiver, value); // NOPMD CompareObjectsWithEquals - the same object, not an equal one
    }

    /**
     * Weaves {@code Map.putIfAbsent}: the value is published before the call, and the release is
     * withdrawn when the key was taken; the value already there is received (#1294).
     *
     * @param receiver the map
     * @param key      the key the value is stored under when absent
     * @param value    the value to store for an absent key
     * @return the value already mapped, or {@code null} when this one went in
     */
    public static @Nullable Object mapPutIfAbsent(Map<Object, Object> receiver, Object key,
                                                  Object value) {
        ControlledSchedule.point();
        record(receiver, "putIfAbsent", true);
        boolean published = publishing(receiver, value);
        boolean stored = false;
        try {
            Object present = receiver.putIfAbsent(key, value);
            stored = present == null;
            return received(receiver, present);
        } finally {
            if (published && !stored) {
                HappensBefore.retract(value);
            }
        }
    }

    /** Weaves {@code Deque.peekFirst}, a read of the head (#1294). @param receiver the deque @return the first element, or null */
    public static @Nullable Object dequePeekFirst(Deque<Object> receiver) {
        ControlledSchedule.point();
        record(receiver, "peekFirst", false);
        return received(receiver, receiver.peekFirst());
    }

    /** Weaves {@code Deque.peekLast}, a read of the tail (#1294). @param receiver the deque @return the last element, or null */
    public static @Nullable Object dequePeekLast(Deque<Object> receiver) {
        ControlledSchedule.point();
        record(receiver, "peekLast", false);
        return received(receiver, receiver.peekLast());
    }

    /**
     * Weaves {@code BitSet.set(int)}, a write to the shared words (#1297).
     *
     * @param receiver the bit set the call was made on
     * @param bitIndex the index of the bit
     */
    public static void bitSetSet(BitSet receiver, int bitIndex) {
        ControlledSchedule.point();
        record(receiver, "set", true);
        receiver.set(bitIndex);
    }

    /**
     * Weaves {@code BitSet.set(int, boolean)}, a write to the shared words (#1297).
     *
     * @param receiver the bit set the call was made on
     * @param bitIndex the index of the bit
     * @param value what the bit or range is set to
     */
    public static void bitSetSet(BitSet receiver, int bitIndex, boolean value) {
        ControlledSchedule.point();
        record(receiver, "set", true);
        receiver.set(bitIndex, value);
    }

    /**
     * Weaves {@code BitSet.set(int, int)}, a write to the shared words (#1297).
     *
     * @param receiver the bit set the call was made on
     * @param fromIndex the first index of the range, inclusive
     * @param toIndex the end of the range, exclusive
     */
    public static void bitSetSet(BitSet receiver, int fromIndex, int toIndex) {
        ControlledSchedule.point();
        record(receiver, "set", true);
        receiver.set(fromIndex, toIndex);
    }

    /**
     * Weaves {@code BitSet.set(int, int, boolean)}, a write to the shared words (#1297).
     *
     * @param receiver the bit set the call was made on
     * @param fromIndex the first index of the range, inclusive
     * @param toIndex the end of the range, exclusive
     * @param value what the bit or range is set to
     */
    public static void bitSetSet(BitSet receiver, int fromIndex, int toIndex, boolean value) {
        ControlledSchedule.point();
        record(receiver, "set", true);
        receiver.set(fromIndex, toIndex, value);
    }

    /**
     * Weaves {@code BitSet.clear(int)}, a write to the shared words (#1297).
     *
     * @param receiver the bit set the call was made on
     * @param bitIndex the index of the bit
     */
    public static void bitSetClear(BitSet receiver, int bitIndex) {
        ControlledSchedule.point();
        record(receiver, "clear", true);
        receiver.clear(bitIndex);
    }

    /**
     * Weaves {@code BitSet.clear(int, int)}, a write to the shared words (#1297).
     *
     * @param receiver the bit set the call was made on
     * @param fromIndex the first index of the range, inclusive
     * @param toIndex the end of the range, exclusive
     */
    public static void bitSetClear(BitSet receiver, int fromIndex, int toIndex) {
        ControlledSchedule.point();
        record(receiver, "clear", true);
        receiver.clear(fromIndex, toIndex);
    }

    /**
     * Weaves {@code BitSet.clear()}, a write to the shared words (#1297).
     *
     * @param receiver the bit set the call was made on
     */
    public static void bitSetClear(BitSet receiver) {
        ControlledSchedule.point();
        record(receiver, "clear", true);
        receiver.clear();
    }

    /**
     * Weaves {@code BitSet.flip(int)}, a write to the shared words (#1297).
     *
     * @param receiver the bit set the call was made on
     * @param bitIndex the index of the bit
     */
    public static void bitSetFlip(BitSet receiver, int bitIndex) {
        ControlledSchedule.point();
        record(receiver, "flip", true);
        receiver.flip(bitIndex);
    }

    /**
     * Weaves {@code BitSet.flip(int, int)}, a write to the shared words (#1297).
     *
     * @param receiver the bit set the call was made on
     * @param fromIndex the first index of the range, inclusive
     * @param toIndex the end of the range, exclusive
     */
    public static void bitSetFlip(BitSet receiver, int fromIndex, int toIndex) {
        ControlledSchedule.point();
        record(receiver, "flip", true);
        receiver.flip(fromIndex, toIndex);
    }

    /**
     * Weaves {@code BitSet.and(BitSet)}, a write to the shared words (#1297).
     *
     * @param receiver the bit set the call was made on
     * @param other the bit set combined into the receiver, which is only read
     */
    public static void bitSetAnd(BitSet receiver, BitSet other) {
        ControlledSchedule.point();
        record(receiver, "and", true);
        receiver.and(other);
    }

    /**
     * Weaves {@code BitSet.or(BitSet)}, a write to the shared words (#1297).
     *
     * @param receiver the bit set the call was made on
     * @param other the bit set combined into the receiver, which is only read
     */
    public static void bitSetOr(BitSet receiver, BitSet other) {
        ControlledSchedule.point();
        record(receiver, "or", true);
        receiver.or(other);
    }

    /**
     * Weaves {@code BitSet.xor(BitSet)}, a write to the shared words (#1297).
     *
     * @param receiver the bit set the call was made on
     * @param other the bit set combined into the receiver, which is only read
     */
    public static void bitSetXor(BitSet receiver, BitSet other) {
        ControlledSchedule.point();
        record(receiver, "xor", true);
        receiver.xor(other);
    }

    /**
     * Weaves {@code BitSet.andNot(BitSet)}, a write to the shared words (#1297).
     *
     * @param receiver the bit set the call was made on
     * @param other the bit set combined into the receiver, which is only read
     */
    public static void bitSetAndNot(BitSet receiver, BitSet other) {
        ControlledSchedule.point();
        record(receiver, "andNot", true);
        receiver.andNot(other);
    }

    /**
     * Weaves {@code BitSet.get(int)}, a read of the shared words (#1297).
     *
     * @param receiver the bit set the call was made on
     * @param bitIndex the index of the bit
     * @return what the call it replaced returns
     */
    public static boolean bitSetGet(BitSet receiver, int bitIndex) {
        ControlledSchedule.point();
        record(receiver, "get", false);
        return receiver.get(bitIndex);
    }

    /**
     * Weaves {@code BitSet.get(int, int)}, a read of the shared words (#1297).
     *
     * @param receiver the bit set the call was made on
     * @param fromIndex the first index of the range, inclusive
     * @param toIndex the end of the range, exclusive
     * @return what the call it replaced returns
     */
    public static BitSet bitSetGet(BitSet receiver, int fromIndex, int toIndex) {
        ControlledSchedule.point();
        record(receiver, "get", false);
        return receiver.get(fromIndex, toIndex);
    }

    /**
     * Weaves {@code BitSet.cardinality()}, a read of the shared words (#1297).
     *
     * @param receiver the bit set the call was made on
     * @return what the call it replaced returns
     */
    public static int bitSetCardinality(BitSet receiver) {
        ControlledSchedule.point();
        record(receiver, "cardinality", false);
        return receiver.cardinality();
    }

    /**
     * Weaves {@code BitSet.nextSetBit(int)}, a read of the shared words (#1297).
     *
     * @param receiver the bit set the call was made on
     * @param fromIndex the index the search starts at, inclusive
     * @return what the call it replaced returns
     */
    public static int bitSetNextSetBit(BitSet receiver, int fromIndex) {
        ControlledSchedule.point();
        record(receiver, "nextSetBit", false);
        return receiver.nextSetBit(fromIndex);
    }

    /**
     * Weaves {@code BitSet.nextClearBit(int)}, a read of the shared words (#1297).
     *
     * @param receiver the bit set the call was made on
     * @param fromIndex the index the search starts at, inclusive
     * @return what the call it replaced returns
     */
    public static int bitSetNextClearBit(BitSet receiver, int fromIndex) {
        ControlledSchedule.point();
        record(receiver, "nextClearBit", false);
        return receiver.nextClearBit(fromIndex);
    }

    /**
     * Weaves {@code BitSet.length()}, a read of the shared words (#1297).
     *
     * @param receiver the bit set the call was made on
     * @return what the call it replaced returns
     */
    public static int bitSetLength(BitSet receiver) {
        ControlledSchedule.point();
        record(receiver, "length", false);
        return receiver.length();
    }

    /**
     * Weaves {@code BitSet.isEmpty()}, a read of the shared words (#1297).
     *
     * @param receiver the bit set the call was made on
     * @return what the call it replaced returns
     */
    public static boolean bitSetIsEmpty(BitSet receiver) {
        ControlledSchedule.point();
        record(receiver, "isEmpty", false);
        return receiver.isEmpty();
    }

    /** Weaves {@code Map.put}. @param receiver the map @param key the key @param value the value @return the previous value */
    public static @Nullable Object mapPut(Map<Object, Object> receiver, Object key, Object value) {
        ControlledSchedule.point();
        record(receiver, "put", true);
        actHalf(receiver, key, value, false);
        // A concurrent map publishes the value to whoever later reads it (HappensBefore): the
        // release comes before the put, so every get that can return the value finds it.
        if (value != null && HappensBefore.publishesElements(receiver)) {
            HappensBefore.release(value);
        }
        return receiver.put(key, value);
    }

    /** Weaves {@code Map.get}. @param receiver the map @param key the key @return the mapped value */
    public static @Nullable Object mapGet(Map<Object, Object> receiver, Object key) {
        ControlledSchedule.point();
        record(receiver, "get", false);
        Object value = receiver.get(key);
        checkHalf(receiver, key, value == null, true);
        return received(receiver, value);
    }

    /** Weaves {@code Map.remove}. @param receiver the map @param key the key @return the removed value */
    public static @Nullable Object mapRemove(Map<Object, Object> receiver, Object key) {
        ControlledSchedule.point();
        record(receiver, "remove", true);
        actHalf(receiver, key, null, true);
        return received(receiver, receiver.remove(key));
    }

    /** {@return {@code value}, after acquiring what its entry published when the container orders it} */
    private static @Nullable Object received(Object receiver, @Nullable Object value) {
        if (value != null && HappensBefore.publishesElements(receiver)) {
            HappensBefore.acquire(value);
        }
        return value;
    }

    /**
     * Releases {@code element} on its way into {@code receiver}, when that container orders what
     * passes through it (#1294). Before the call, so every read that can return the element finds
     * the release.
     *
     * @return whether a release was made, for the caller to withdraw if the element did not go in
     */
    private static boolean publishing(Object receiver, @Nullable Object element) {
        if (element != null && HappensBefore.publishesElements(receiver)) {
            HappensBefore.release(element);
            return true;
        }
        return false;
    }

    /** {@return {@code remapping}, releasing what it returns before the map stores it (#1294)} */
    private static BiFunction<Object, Object, Object> publishing(Map<Object, Object> receiver,
            BiFunction<Object, Object, Object> remapping) {
        if (remapping == null || !HappensBefore.publishesElements(receiver)) {
            return remapping;
        }
        return (key, old) -> {
            Object stored = remapping.apply(key, old);
            HappensBefore.release(stored);
            return stored;
        };
    }

    /**
     * An iterator over a container that orders its elements, acquiring each as it hands it out
     * (#1294): a for-each is a read of every element it visits.
     */
    private static final class ReceivingIterator implements java.util.Iterator<Object> {
        private final java.util.Iterator<Object> delegate;

        ReceivingIterator(java.util.Iterator<Object> delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean hasNext() {
            return delegate.hasNext();
        }

        @Override
        public @Nullable Object next() {
            Object element = delegate.next();
            receive(element);
            return element;
        }

        @Override
        public void remove() {
            delegate.remove();
        }

        @Override
        public void forEachRemaining(java.util.function.Consumer<? super Object> action) {
            Objects.requireNonNull(action);
            delegate.forEachRemaining(element -> {
                receive(element);
                action.accept(element);
            });
        }
    }

    /**
     * Weaves {@code Map.remove(Object, Object)}, the conditional removal.
     *
     * <p>A separate overload rather than a widening of the one above, because this one answers
     * whether it removed anything rather than handing back what it removed. It is a mutation
     * either way, which is the only thing the record call cares about, and it was unwoven while
     * its one-argument sibling was (#440).
     *
     * @param receiver the map
     * @param key      the key to remove
     * @param value    the value the entry must currently hold
     * @return whether the entry was removed
     */
    public static boolean mapRemove(Map<Object, Object> receiver, Object key, Object value) {
        ControlledSchedule.point();
        record(receiver, "remove", true);
        return receiver.remove(key, value);
    }

    /** Weaves {@code Map.containsKey}. @param receiver the map @param key the key @return whether the key is present */
    public static boolean mapContainsKey(Map<Object, Object> receiver, Object key) {
        ControlledSchedule.point();
        record(receiver, "containsKey", false);
        boolean present = receiver.containsKey(key);
        checkHalf(receiver, key, !present, false);
        return present;
    }

    /**
     * Weaves {@code Map.size}, the keyless check of {@code if (map.size() < LIMIT) map.put(k, v)}
     * (#1249).
     *
     * @param receiver the map
     * @return its size
     * @since 1.14.3
     */
    public static int mapSize(Map<Object, Object> receiver) {
        ControlledSchedule.point();
        int size = receiver.size();
        keylessCheck(receiver, false);
        return size;
    }

    /**
     * Weaves {@code Map.isEmpty}, the keyless check of {@code if (map.isEmpty()) map.put(k, v)}
     * (#1249).
     *
     * @param receiver the map
     * @return whether it is empty
     * @since 1.14.3
     */
    public static boolean mapIsEmpty(Map<Object, Object> receiver) {
        ControlledSchedule.point();
        boolean empty = receiver.isEmpty();
        keylessCheck(receiver, true);
        return empty;
    }

    // The atomic per-key updates (#1245). A ConcurrentHashMap runs the whole call under a lock
    // inside the JDK the lockset cannot see, so the call holds a per-(map, key) stand-in for it,
    // and a value mutated inside the function reads as guarded. Any other map is called as is.

    /** Weaves {@code Map.compute}. @param receiver the map @param key the key @param remapping the function @return the new value */
    public static @Nullable Object mapCompute(Map<Object, Object> receiver, Object key,
            BiFunction<Object, Object, Object> remapping) {
        ControlledSchedule.point();
        Object token = AtomicMapUpdateTokens.tokenFor(receiver, key);
        HeldLocks.acquired(token);
        try {
            return received(receiver, receiver.compute(key, publishing(receiver, remapping)));
        } finally {
            HeldLocks.released(token);
        }
    }

    /** Weaves {@code Map.computeIfPresent}. @param receiver the map @param key the key @param remapping the function @return the new value */
    public static @Nullable Object mapComputeIfPresent(Map<Object, Object> receiver, Object key,
            BiFunction<Object, Object, Object> remapping) {
        ControlledSchedule.point();
        Object token = AtomicMapUpdateTokens.tokenFor(receiver, key);
        HeldLocks.acquired(token);
        try {
            return received(receiver, receiver.computeIfPresent(key, publishing(receiver, remapping)));
        } finally {
            HeldLocks.released(token);
        }
    }

    /** Weaves {@code Map.computeIfAbsent}. @param receiver the map @param key the key @param mapping the function @return the current or computed value */
    public static @Nullable Object mapComputeIfAbsent(Map<Object, Object> receiver, Object key,
            Function<Object, Object> mapping) {
        ControlledSchedule.point();
        Object token = AtomicMapUpdateTokens.tokenFor(receiver, key);
        HeldLocks.acquired(token);
        try {
            Function<Object, Object> publishing = mapping;
            if (mapping != null && HappensBefore.publishesElements(receiver)) {
                publishing = absent -> {
                    Object stored = mapping.apply(absent);
                    HappensBefore.release(stored);
                    return stored;
                };
            }
            return received(receiver, receiver.computeIfAbsent(key, publishing));
        } finally {
            HeldLocks.released(token);
        }
    }

    /** Weaves {@code Map.merge}. @param receiver the map @param key the key @param value the value to merge @param remapping the function @return the new value */
    @SuppressWarnings("ReferenceEquality") // the same object went in, or it did not
    public static @Nullable Object mapMerge(Map<Object, Object> receiver, Object key, Object value,
            BiFunction<Object, Object, Object> remapping) {
        ControlledSchedule.point();
        Object token = AtomicMapUpdateTokens.tokenFor(receiver, key);
        HeldLocks.acquired(token);
        try {
            // An absent key stores the value itself, so it is released first; a present one
            // stores what the function returns, and the value's release is withdrawn if no other
            // release followed it.
            boolean published = publishing(receiver, value);
            Object merged = receiver.merge(key, value, publishing(receiver, remapping));
            if (published && merged != value) { // NOPMD CompareObjectsWithEquals - the same object went in, or it did not
                HappensBefore.retract(value);
            }
            return received(receiver, merged);
        } finally {
            HeldLocks.released(token);
        }
    }

    /** Weaves {@code Collection.add}. @param receiver the collection @param element the element @return whether it changed */
    public static boolean collectionAdd(Collection<Object> receiver, Object element) {
        return collectionAdd(receiver, element, null);
    }

    /** Weaves {@code Collection.add}, with the enclosing method's own monitor (#796). @param receiver the collection @param element the element @param monitor the enclosing method's own monitor, {@code this} or a static synchronized method's class, counted when this thread holds it; {@code null} for none @return whether it changed */
    public static boolean collectionAdd(Collection<Object> receiver, Object element,
                                        @Nullable Object monitor) {
        ControlledSchedule.point();
        record(receiver, "add", true);
        addingToSynchronizedList(receiver, element);
        if (receiver instanceof Queue) {
            // Queue.add is an offer that throws instead of returning false (#630).
            TelemetryRegistry.ownershipOffered(element, receiver, monitor);
            boolean added = false;
            try {
                added = receiver.add(element);
            } finally {
                if (!added) { // refused, or the call threw: nothing was handed over (#742)
                    TelemetryRegistry.ownershipRefused(element, receiver);
                }
            }
            return added;
        }
        boolean published = publishing(receiver, element);
        boolean added = false;
        try {
            added = receiver.add(element);
        } finally {
            if (published && !added) { // a set that already held an equal one: nothing went in
                HappensBefore.retract(element);
            }
        }
        return added;
    }

    /** Weaves {@code Collection.remove}. @param receiver the collection @param element the element @return whether it changed */
    public static boolean collectionRemove(Collection<Object> receiver, Object element) {
        return collectionRemove(receiver, element, null);
    }

    /** Weaves {@code Collection.remove}, with the enclosing method's own monitor (#796). @param receiver the collection @param element the element @param monitor the enclosing method's own monitor, {@code this} or a static synchronized method's class, counted when this thread holds it; {@code null} for none @return whether it changed */
    public static boolean collectionRemove(Collection<Object> receiver, Object element,
                                           @Nullable Object monitor) {
        ControlledSchedule.point();
        record(receiver, "remove", true);
        boolean removed = receiver.remove(element);
        if (removed && receiver instanceof Queue) {
            // Out of the queue and in the remover's hands, like a poll that named its element
            // (#692). The argument stands for the element: a queue matches by equals, and an
            // equal stand-in is a reference the remover already holds alone.
            TelemetryRegistry.ownershipTaken(element, receiver, monitor);
        }
        return removed;
    }

    /**
     * Weaves {@code Collection.addAll}: on a queue, an offer of every element (#692).
     *
     * <p>The elements are read before the call, for the reason every offer is published first:
     * the take that removes one has to drain after it. Reading them is the recording path, so
     * whatever it throws is dropped and the call itself decides what the caller sees.
     *
     * <p>A queue whose {@code addAll} is {@code AbstractQueue}'s, every bounded
     * {@code BlockingQueue} in the JDK among them, is filled here one {@code add} at a time, which
     * is what its own {@code addAll} does, so an element it refuses withdraws its release and the
     * ones after it are never offered (#806). A queue with an {@code addAll} of its own keeps it,
     * since it may link the batch in a way no loop here reproduces; when that call throws part way,
     * as {@code LinkedBlockingDeque}'s does past its capacity, every offered element the queue
     * does not hold afterwards withdraws its release ({@link #withdrawWhatDidNotGoIn}).
     *
     * @param receiver the collection
     * @param elements the elements to add
     * @return whether the collection changed
     * @since 1.12.2
     */
    public static boolean collectionAddAll(Collection<Object> receiver,
                                           Collection<? extends Object> elements) {
        return collectionAddAll(receiver, elements, null);
    }

    /**
     * {@link #collectionAddAll(Collection, Collection)} with the enclosing method's own monitor,
     * which the weaver passes because a {@code synchronized} method takes it with no instruction
     * (#796).
     *
     * @param receiver the collection
     * @param elements the elements to add
     * @param monitor  the enclosing method's own monitor, {@code this} or a static synchronized
     *                 method's class, counted when this thread holds it; {@code null} for none
     * @return whether the collection changed
     * @since 1.12.3
     */
    public static boolean collectionAddAll(Collection<Object> receiver,
                                           Collection<? extends Object> elements,
                                           @Nullable Object monitor) {
        ControlledSchedule.point();
        record(receiver, "addAll", true);
        if (receiver instanceof Queue && Boolean.TRUE.equals(ADDS_ONE_BY_ONE.get(receiver.getClass()))) {
            return addEach(receiver, elements, monitor);
        }
        if (receiver instanceof Queue && elements != null) {
            try {
                for (Object element : elements) {
                    TelemetryRegistry.ownershipOffered(element, receiver, monitor);
                }
            } catch (RuntimeException ignored) { // NOPMD - recording never fails the caller
                // addAll below reads the same source and reports what is wrong with it.
            }
            boolean completed = false;
            try {
                boolean changed = receiver.addAll(elements);
                completed = true;
                return changed;
            } finally {
                if (!completed) {
                    withdrawWhatDidNotGoIn(receiver, elements);
                }
            }
        }
        if (elements != null && HappensBefore.publishesElements(receiver)) {
            try {
                for (Object element : elements) {
                    HappensBefore.release(element);
                }
            } catch (RuntimeException ignored) { // NOPMD - recording never fails the caller
                // addAll below reads the same source and reports what is wrong with it.
            }
        }
        return receiver.addAll(elements);
    }

    /**
     * Withdraws the release of every element of {@code elements} that {@code queue} does not hold,
     * after its own {@code addAll} threw part way (#806).
     *
     * <p>Held is judged by identity, by walking the queue. An element a consumer already took
     * reads as not held, and withdrawing its release then changes nothing, because the take has
     * acquired it already. An element equal to one the queue held before, but not the same object,
     * reads as not held and is withdrawn, which is right. Runs only on the exception path; what
     * the walk throws is dropped, and the caller sees the original exception.
     */
    private static void withdrawWhatDidNotGoIn(Collection<Object> queue, Collection<? extends Object> elements) {
        try {
            java.util.Set<Object> held = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            held.addAll(queue);
            for (Object element : elements) {
                if (!held.contains(element)) {
                    TelemetryRegistry.ownershipRefused(element, queue);
                }
            }
        } catch (RuntimeException ignored) { // NOPMD - recording never fails the caller
            // A queue that cannot be walked now keeps every release, as it did before #806.
        }
    }

    /**
     * {@code AbstractQueue.addAll}, run here so each element is offered on its own (#806).
     *
     * <p>A queue that inherits {@code addAll} from {@code AbstractQueue} does exactly this: it
     * checks the source, then calls its own {@code add} once per element, and a bounded queue's
     * {@code add} throws at the first element that does not fit, with the earlier ones already in.
     * Running the same calls here puts each element's release right before its {@code add}, and
     * a refused element withdraws its release before the exception leaves, as a refused
     * {@code add} does. The elements after it were never offered and released nothing.
     */
    @SuppressWarnings("ReferenceEquality") // the source being the queue itself, as AbstractQueue asks
    private static boolean addEach(Collection<Object> receiver,
                                   Collection<? extends Object> elements,
                                   @Nullable Object monitor) {
        Objects.requireNonNull(elements); // AbstractQueue's own check, before anything is offered
        if (elements == receiver) { // NOPMD CompareObjectsWithEquals - AbstractQueue's own check
            throw new IllegalArgumentException();
        }
        boolean modified = false;
        for (Object element : elements) {
            TelemetryRegistry.ownershipOffered(element, receiver, monitor);
            boolean added = false;
            try {
                added = receiver.add(element);
            } finally {
                if (!added) { // refused, or the call threw: nothing was handed over (#742)
                    TelemetryRegistry.ownershipRefused(element, receiver);
                }
            }
            modified |= added;
        }
        return modified;
    }

    /**
     * Weaves {@code Collection.removeIf}: on a queue, a removal that names no element, reported
     * like {@code drainTo} (#692). Every offer recorded into the queue before it is stale.
     *
     * @param receiver the collection
     * @param filter   the predicate selecting what to remove
     * @return whether anything was removed
     * @since 1.12.2
     */
    public static boolean collectionRemoveIf(Collection<Object> receiver,
                                             Predicate<? super Object> filter) {
        ControlledSchedule.point();
        record(receiver, "removeIf", true);
        boolean removed = receiver.removeIf(filter);
        if (removed && receiver instanceof Queue) {
            TelemetryRegistry.ownershipDrained(receiver);
        }
        return removed;
    }

    /** Weaves {@code Collection.contains}. @param receiver the collection @param element the element @return whether present */
    public static boolean collectionContains(Collection<Object> receiver, Object element) {
        ControlledSchedule.point();
        record(receiver, "contains", false);
        boolean found = receiver.contains(element);
        checkedSynchronizedList(receiver, element, found);
        return found;
    }

    /** Weaves {@code Collection.clear}. @param receiver the collection */
    public static void collectionClear(Collection<Object> receiver) {
        ControlledSchedule.point();
        record(receiver, "clear", true);
        receiver.clear();
    }

    /** Weaves {@code List.get}. @param receiver the list @param index the index @return the element */
    public static @Nullable Object listGet(List<Object> receiver, int index) {
        ControlledSchedule.point();
        record(receiver, "get", false);
        return received(receiver, receiver.get(index));
    }

    /** Weaves {@code List.set}. @param receiver the list @param index the index @param element the element @return the previous element */
    public static @Nullable Object listSet(List<Object> receiver, int index, Object element) {
        ControlledSchedule.point();
        record(receiver, "set", true);
        publishing(receiver, element);
        return receiver.set(index, element);
    }

    /** Weaves {@code Queue.offer}. @param receiver the queue @param element the element @return whether it was accepted */
    public static boolean queueOffer(Queue<Object> receiver, Object element) {
        return queueOffer(receiver, element, null);
    }

    /** Weaves {@code Queue.offer}, with the enclosing method's own monitor (#796). @param receiver the queue @param element the element @param monitor the enclosing method's own monitor, {@code this} or a static synchronized method's class, counted when this thread holds it; {@code null} for none @return whether it was accepted */
    public static boolean queueOffer(Queue<Object> receiver, Object element,
                                     @Nullable Object monitor) {
        ControlledSchedule.point();
        record(receiver, "offer", true);
        // Before the offer, so the take that removes the element drains after it (#630).
        TelemetryRegistry.ownershipOffered(element, receiver, monitor);
        boolean accepted = receiver.offer(element);
        if (!accepted) {
            TelemetryRegistry.ownershipRefused(element, receiver);
        }
        return accepted;
    }

    /** Weaves {@code Queue.poll}. @param receiver the queue @return the head, or null */
    public static @Nullable Object queuePoll(Queue<Object> receiver) {
        return queuePoll(receiver, null);
    }

    /** Weaves {@code Queue.poll}, with the enclosing method's own monitor (#796). @param receiver the queue @param monitor the enclosing method's own monitor, {@code this} or a static synchronized method's class, counted when this thread holds it; {@code null} for none @return the head, or null */
    public static @Nullable Object queuePoll(Queue<Object> receiver,
                                             @Nullable Object monitor) {
        ControlledSchedule.point();
        record(receiver, "poll", true);
        Object taken = receiver.poll();
        BlockingQueueDetector detector = queueDetector(receiver);
        if (detector != null) {
            detector.agentPolled(receiver);
        }
        // The element left the queue, so it is this thread's alone as far as the queue goes (#555).
        TelemetryRegistry.ownershipTaken(taken, receiver, monitor);
        return taken;
    }

    /** Weaves {@code Queue.isEmpty} (#1249). @param receiver the queue @return whether it was empty */
    public static boolean queueIsEmpty(Queue<Object> receiver) {
        ControlledSchedule.point();
        record(receiver, "isEmpty", false);
        boolean empty = receiver.isEmpty();
        BlockingQueueDetector detector = queueDetector(receiver);
        if (detector != null) {
            // The head this answer was about; null when a peer emptied the queue since, which
            // leaves no check pending.
            detector.agentHeadCheck(receiver, empty ? null : receiver.peek(), false);
        }
        return empty;
    }

    /** Weaves {@code Queue.element} (#1249). @param receiver the queue @return the head */
    public static Object queueElement(Queue<Object> receiver) {
        ControlledSchedule.point();
        record(receiver, "element", false);
        BlockingQueueDetector detector = queueDetector(receiver);
        Object head;
        try {
            head = receiver.element();
        } catch (NoSuchElementException empty) {
            if (detector != null) {
                detector.agentHeadTaken(receiver, null, true);
            }
            throw empty;
        }
        if (detector != null) {
            detector.agentHeadTaken(receiver, head, true);
        }
        return Objects.requireNonNull(received(receiver, head));
    }

    /**
     * The queue detector when {@code receiver} is a thread-safe queue whose check-then-act it
     * judges (#1249): a {@code BlockingQueue} or another {@code java.util.concurrent} queue. A
     * plain deque is left to the shared-collection detectors, which report it whatever the pair.
     */
    private static @Nullable BlockingQueueDetector queueDetector(Queue<Object> receiver) {
        if (!(receiver instanceof java.util.concurrent.BlockingQueue)
                && !receiver.getClass().getName().startsWith("java.util.concurrent.")) {
            return null;
        }
        return AsyncTestContext.currentBlockingQueueDetector();
    }

    /** Weaves {@code Queue.peek}. @param receiver the queue @return the head, or null */
    public static @Nullable Object queuePeek(Queue<Object> receiver) {
        ControlledSchedule.point();
        record(receiver, "peek", false);
        Object head = receiver.peek();
        BlockingQueueDetector detector = queueDetector(receiver);
        if (detector != null) {
            detector.agentHeadCheck(receiver, head, true);
        }
        return received(receiver, head);
    }

    // ---- The entry and removal forms #664 left unwoven (#692). Offers publish before the
    //      structure accepts the element and takes after it let go, like offer and poll above.

    /** Weaves {@code Deque.offerFirst}, an offer at one end (#692). @param receiver the deque @param element the element @return whether it was accepted */
    public static boolean dequeOfferFirst(Deque<Object> receiver, Object element) {
        return dequeOfferFirst(receiver, element, null);
    }

    /** Weaves {@code Deque.offerFirst}, an offer at one end (#692), with the enclosing method's own monitor (#796). @param receiver the deque @param element the element @param monitor the enclosing method's own monitor, {@code this} or a static synchronized method's class, counted when this thread holds it; {@code null} for none @return whether it was accepted */
    public static boolean dequeOfferFirst(Deque<Object> receiver, Object element,
                                          @Nullable Object monitor) {
        ControlledSchedule.point();
        record(receiver, "offerFirst", true);
        TelemetryRegistry.ownershipOffered(element, receiver, monitor);
        boolean accepted = receiver.offerFirst(element);
        if (!accepted) {
            TelemetryRegistry.ownershipRefused(element, receiver);
        }
        return accepted;
    }

    /** Weaves {@code Deque.offerLast}, an offer at one end (#692). @param receiver the deque @param element the element @return whether it was accepted */
    public static boolean dequeOfferLast(Deque<Object> receiver, Object element) {
        return dequeOfferLast(receiver, element, null);
    }

    /** Weaves {@code Deque.offerLast}, an offer at one end (#692), with the enclosing method's own monitor (#796). @param receiver the deque @param element the element @param monitor the enclosing method's own monitor, {@code this} or a static synchronized method's class, counted when this thread holds it; {@code null} for none @return whether it was accepted */
    public static boolean dequeOfferLast(Deque<Object> receiver, Object element,
                                         @Nullable Object monitor) {
        ControlledSchedule.point();
        record(receiver, "offerLast", true);
        TelemetryRegistry.ownershipOffered(element, receiver, monitor);
        boolean accepted = receiver.offerLast(element);
        if (!accepted) {
            TelemetryRegistry.ownershipRefused(element, receiver);
        }
        return accepted;
    }

    /** Weaves {@code Deque.addFirst}, an offer at one end (#692). @param receiver the deque @param element the element */
    public static void dequeAddFirst(Deque<Object> receiver, Object element) {
        dequeAddFirst(receiver, element, null);
    }

    /** Weaves {@code Deque.addFirst}, an offer at one end (#692), with the enclosing method's own monitor (#796). @param receiver the deque @param element the element @param monitor the enclosing method's own monitor, {@code this} or a static synchronized method's class, counted when this thread holds it; {@code null} for none */
    public static void dequeAddFirst(Deque<Object> receiver, Object element,
                                     @Nullable Object monitor) {
        ControlledSchedule.point();
        record(receiver, "addFirst", true);
        TelemetryRegistry.ownershipOffered(element, receiver, monitor);
        boolean accepted = false;
        try {
            receiver.addFirst(element);
            accepted = true;
        } finally {
            if (!accepted) { // the call threw: nothing was handed over (#742)
                TelemetryRegistry.ownershipRefused(element, receiver);
            }
        }
    }

    /** Weaves {@code Deque.addLast}, an offer at one end (#692). @param receiver the deque @param element the element */
    public static void dequeAddLast(Deque<Object> receiver, Object element) {
        dequeAddLast(receiver, element, null);
    }

    /** Weaves {@code Deque.addLast}, an offer at one end (#692), with the enclosing method's own monitor (#796). @param receiver the deque @param element the element @param monitor the enclosing method's own monitor, {@code this} or a static synchronized method's class, counted when this thread holds it; {@code null} for none */
    public static void dequeAddLast(Deque<Object> receiver, Object element,
                                    @Nullable Object monitor) {
        ControlledSchedule.point();
        record(receiver, "addLast", true);
        TelemetryRegistry.ownershipOffered(element, receiver, monitor);
        boolean accepted = false;
        try {
            receiver.addLast(element);
            accepted = true;
        } finally {
            if (!accepted) { // the call threw: nothing was handed over (#742)
                TelemetryRegistry.ownershipRefused(element, receiver);
            }
        }
    }

    /** Weaves {@code Deque.push}, an offer at one end (#692). @param receiver the deque @param element the element */
    public static void dequePush(Deque<Object> receiver, Object element) {
        dequePush(receiver, element, null);
    }

    /** Weaves {@code Deque.push}, an offer at one end (#692), with the enclosing method's own monitor (#796). @param receiver the deque @param element the element @param monitor the enclosing method's own monitor, {@code this} or a static synchronized method's class, counted when this thread holds it; {@code null} for none */
    public static void dequePush(Deque<Object> receiver, Object element,
                                 @Nullable Object monitor) {
        ControlledSchedule.point();
        record(receiver, "push", true);
        TelemetryRegistry.ownershipOffered(element, receiver, monitor);
        boolean accepted = false;
        try {
            receiver.push(element);
            accepted = true;
        } finally {
            if (!accepted) { // the call threw: nothing was handed over (#742)
                TelemetryRegistry.ownershipRefused(element, receiver);
            }
        }
    }

    /** Weaves {@code Deque.pollFirst}, a take like {@code poll} (#692). @param receiver the deque @return the element taken */
    public static @Nullable Object dequePollFirst(Deque<Object> receiver) {
        return dequePollFirst(receiver, null);
    }

    /** Weaves {@code Deque.pollFirst}, a take like {@code poll} (#692), with the enclosing method's own monitor (#796). @param receiver the deque @param monitor the enclosing method's own monitor, {@code this} or a static synchronized method's class, counted when this thread holds it; {@code null} for none @return the element taken */
    public static @Nullable Object dequePollFirst(Deque<Object> receiver,
                                                  @Nullable Object monitor) {
        ControlledSchedule.point();
        record(receiver, "pollFirst", true);
        Object taken = receiver.pollFirst();
        TelemetryRegistry.ownershipTaken(taken, receiver, monitor);
        return taken;
    }

    /** Weaves {@code Deque.pollLast}, a take like {@code poll} (#692). @param receiver the deque @return the element taken */
    public static @Nullable Object dequePollLast(Deque<Object> receiver) {
        return dequePollLast(receiver, null);
    }

    /** Weaves {@code Deque.pollLast}, a take like {@code poll} (#692), with the enclosing method's own monitor (#796). @param receiver the deque @param monitor the enclosing method's own monitor, {@code this} or a static synchronized method's class, counted when this thread holds it; {@code null} for none @return the element taken */
    public static @Nullable Object dequePollLast(Deque<Object> receiver,
                                                 @Nullable Object monitor) {
        ControlledSchedule.point();
        record(receiver, "pollLast", true);
        Object taken = receiver.pollLast();
        TelemetryRegistry.ownershipTaken(taken, receiver, monitor);
        return taken;
    }

    /** Weaves {@code Deque.removeFirst}, a take like {@code poll} (#692). @param receiver the deque @return the element taken */
    public static @Nullable Object dequeRemoveFirst(Deque<Object> receiver) {
        return dequeRemoveFirst(receiver, null);
    }

    /** Weaves {@code Deque.removeFirst}, a take like {@code poll} (#692), with the enclosing method's own monitor (#796). @param receiver the deque @param monitor the enclosing method's own monitor, {@code this} or a static synchronized method's class, counted when this thread holds it; {@code null} for none @return the element taken */
    public static @Nullable Object dequeRemoveFirst(Deque<Object> receiver,
                                                    @Nullable Object monitor) {
        ControlledSchedule.point();
        record(receiver, "removeFirst", true);
        Object taken = receiver.removeFirst();
        TelemetryRegistry.ownershipTaken(taken, receiver, monitor);
        return taken;
    }

    /** Weaves {@code Deque.removeLast}, a take like {@code poll} (#692). @param receiver the deque @return the element taken */
    public static @Nullable Object dequeRemoveLast(Deque<Object> receiver) {
        return dequeRemoveLast(receiver, null);
    }

    /** Weaves {@code Deque.removeLast}, a take like {@code poll} (#692), with the enclosing method's own monitor (#796). @param receiver the deque @param monitor the enclosing method's own monitor, {@code this} or a static synchronized method's class, counted when this thread holds it; {@code null} for none @return the element taken */
    public static @Nullable Object dequeRemoveLast(Deque<Object> receiver,
                                                   @Nullable Object monitor) {
        ControlledSchedule.point();
        record(receiver, "removeLast", true);
        Object taken = receiver.removeLast();
        TelemetryRegistry.ownershipTaken(taken, receiver, monitor);
        return taken;
    }

    /** Weaves {@code Deque.pop}, a take like {@code poll} (#692). @param receiver the deque @return the element taken */
    public static @Nullable Object dequePop(Deque<Object> receiver) {
        return dequePop(receiver, null);
    }

    /** Weaves {@code Deque.pop}, a take like {@code poll} (#692), with the enclosing method's own monitor (#796). @param receiver the deque @param monitor the enclosing method's own monitor, {@code this} or a static synchronized method's class, counted when this thread holds it; {@code null} for none @return the element taken */
    public static @Nullable Object dequePop(Deque<Object> receiver,
                                            @Nullable Object monitor) {
        ControlledSchedule.point();
        record(receiver, "pop", true);
        Object taken = receiver.pop();
        TelemetryRegistry.ownershipTaken(taken, receiver, monitor);
        return taken;
    }

    /** Weaves {@code Queue.remove}, a take like {@code poll} (#692). @param receiver the queue @return the element taken */
    public static @Nullable Object queueRemove(Queue<Object> receiver) {
        return queueRemove(receiver, null);
    }

    /** Weaves {@code Queue.remove}, a take like {@code poll} (#692), with the enclosing method's own monitor (#796). @param receiver the queue @param monitor the enclosing method's own monitor, {@code this} or a static synchronized method's class, counted when this thread holds it; {@code null} for none @return the element taken */
    public static @Nullable Object queueRemove(Queue<Object> receiver,
                                               @Nullable Object monitor) {
        ControlledSchedule.point();
        record(receiver, "remove", true);
        BlockingQueueDetector detector = queueDetector(receiver);
        Object taken;
        try {
            taken = receiver.remove();
        } catch (NoSuchElementException empty) {
            if (detector != null) {
                detector.agentHeadTaken(receiver, null, false);
            }
            throw empty;
        }
        if (detector != null) {
            detector.agentHeadTaken(receiver, taken, false);
        }
        TelemetryRegistry.ownershipTaken(taken, receiver, monitor);
        return taken;
    }

    /** Weaves {@code BlockingDeque.putFirst}, a blocking offer at one end (#692). @param receiver the deque @param element the element @throws InterruptedException if interrupted while waiting */
    public static void blockingDequePutFirst(BlockingDeque<Object> receiver, Object element)
            throws InterruptedException {
        ControlledSchedule.point();
        record(receiver, "putFirst", true);
        TelemetryRegistry.ownershipOffered(element, receiver);
        boolean accepted = false;
        try {
            receiver.putFirst(element);
            accepted = true;
        } finally {
            if (!accepted) { // the call threw: nothing was handed over (#742)
                TelemetryRegistry.ownershipRefused(element, receiver);
            }
        }
    }

    /** Weaves {@code BlockingDeque.putLast}, a blocking offer at one end (#692). @param receiver the deque @param element the element @throws InterruptedException if interrupted while waiting */
    public static void blockingDequePutLast(BlockingDeque<Object> receiver, Object element)
            throws InterruptedException {
        ControlledSchedule.point();
        record(receiver, "putLast", true);
        TelemetryRegistry.ownershipOffered(element, receiver);
        boolean accepted = false;
        try {
            receiver.putLast(element);
            accepted = true;
        } finally {
            if (!accepted) { // the call threw: nothing was handed over (#742)
                TelemetryRegistry.ownershipRefused(element, receiver);
            }
        }
    }

    /** Weaves {@code BlockingDeque.offerFirst(E, long, TimeUnit)}, a timed offer at one end (#692). @param receiver the deque @param element the element @param timeout how long to wait @param unit the unit of {@code timeout} @return whether it was accepted @throws InterruptedException if interrupted while waiting */
    public static boolean blockingDequeOfferFirst(BlockingDeque<Object> receiver, Object element, long timeout,
                                   TimeUnit unit) throws InterruptedException {
        ControlledSchedule.point();
        record(receiver, "offerFirst", true);
        TelemetryRegistry.ownershipOffered(element, receiver);
        boolean accepted = false;
        try {
            accepted = receiver.offerFirst(element, timeout, unit);
        } finally {
            if (!accepted) { // refused, or the call threw: nothing was handed over (#742)
                TelemetryRegistry.ownershipRefused(element, receiver);
            }
        }
        return accepted;
    }

    /** Weaves {@code BlockingDeque.offerLast(E, long, TimeUnit)}, a timed offer at one end (#692). @param receiver the deque @param element the element @param timeout how long to wait @param unit the unit of {@code timeout} @return whether it was accepted @throws InterruptedException if interrupted while waiting */
    public static boolean blockingDequeOfferLast(BlockingDeque<Object> receiver, Object element, long timeout,
                                   TimeUnit unit) throws InterruptedException {
        ControlledSchedule.point();
        record(receiver, "offerLast", true);
        TelemetryRegistry.ownershipOffered(element, receiver);
        boolean accepted = false;
        try {
            accepted = receiver.offerLast(element, timeout, unit);
        } finally {
            if (!accepted) { // refused, or the call threw: nothing was handed over (#742)
                TelemetryRegistry.ownershipRefused(element, receiver);
            }
        }
        return accepted;
    }

    /** Weaves {@code BlockingDeque.takeFirst}, a blocking take at one end (#692). @param receiver the deque @return the element taken @throws InterruptedException if interrupted while waiting */
    public static Object blockingDequeTakeFirst(BlockingDeque<Object> receiver) throws InterruptedException {
        ControlledSchedule.point();
        record(receiver, "takeFirst", true);
        Object taken = receiver.takeFirst();
        TelemetryRegistry.ownershipTaken(taken, receiver);
        return taken;
    }

    /** Weaves {@code BlockingDeque.takeLast}, a blocking take at one end (#692). @param receiver the deque @return the element taken @throws InterruptedException if interrupted while waiting */
    public static Object blockingDequeTakeLast(BlockingDeque<Object> receiver) throws InterruptedException {
        ControlledSchedule.point();
        record(receiver, "takeLast", true);
        Object taken = receiver.takeLast();
        TelemetryRegistry.ownershipTaken(taken, receiver);
        return taken;
    }

    /** Weaves {@code BlockingDeque.pollFirst(long, TimeUnit)}, a timed take at one end (#692). @param receiver the deque @param timeout how long to wait @param unit the unit of {@code timeout} @return the element taken, or null @throws InterruptedException if interrupted while waiting */
    public static @Nullable Object blockingDequePollFirst(BlockingDeque<Object> receiver, long timeout,
                                   TimeUnit unit) throws InterruptedException {
        ControlledSchedule.point();
        record(receiver, "pollFirst", true);
        Object taken = receiver.pollFirst(timeout, unit);
        TelemetryRegistry.ownershipTaken(taken, receiver);
        return taken;
    }

    /** Weaves {@code BlockingDeque.pollLast(long, TimeUnit)}, a timed take at one end (#692). @param receiver the deque @param timeout how long to wait @param unit the unit of {@code timeout} @return the element taken, or null @throws InterruptedException if interrupted while waiting */
    public static @Nullable Object blockingDequePollLast(BlockingDeque<Object> receiver, long timeout,
                                   TimeUnit unit) throws InterruptedException {
        ControlledSchedule.point();
        record(receiver, "pollLast", true);
        Object taken = receiver.pollLast(timeout, unit);
        TelemetryRegistry.ownershipTaken(taken, receiver);
        return taken;
    }
}
