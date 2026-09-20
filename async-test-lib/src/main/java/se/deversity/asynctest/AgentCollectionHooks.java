package se.deversity.asynctest;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.function.Predicate;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;
import org.jspecify.annotations.Nullable;

import se.deversity.asynctest.diagnostics.SharedCollectionDetector;
import se.deversity.asynctest.telemetry.TelemetryRegistry;
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
@AIContract(reason = "Called from bytecode the agent rewrites, not from source: the method names and erased signatures are matched by CollectionAccessWeaver and cannot change independently of it. Every hook must end by performing the original operation and must never throw on the recording path - it runs inside the user's code, so an exception here surfaces as a failure in their test. Recording is best-effort by design: no context, a disabled detector, or a type the library knows is thread-safe all mean record nothing and delegate.")
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
                || receiver instanceof java.util.concurrent.BlockingDeque
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

    /** Weaves {@code Map.put}. @param receiver the map @param key the key @param value the value @return the previous value */
    public static @Nullable Object mapPut(Map<Object, Object> receiver, Object key, Object value) {
        record(receiver, "put", true);
        return receiver.put(key, value);
    }

    /** Weaves {@code Map.get}. @param receiver the map @param key the key @return the mapped value */
    public static @Nullable Object mapGet(Map<Object, Object> receiver, Object key) {
        record(receiver, "get", false);
        return receiver.get(key);
    }

    /** Weaves {@code Map.remove}. @param receiver the map @param key the key @return the removed value */
    public static @Nullable Object mapRemove(Map<Object, Object> receiver, Object key) {
        record(receiver, "remove", true);
        return receiver.remove(key);
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
        record(receiver, "remove", true);
        return receiver.remove(key, value);
    }

    /** Weaves {@code Map.containsKey}. @param receiver the map @param key the key @return whether the key is present */
    public static boolean mapContainsKey(Map<Object, Object> receiver, Object key) {
        record(receiver, "containsKey", false);
        return receiver.containsKey(key);
    }

    /** Weaves {@code Collection.add}. @param receiver the collection @param element the element @return whether it changed */
    public static boolean collectionAdd(Collection<Object> receiver, Object element) {
        record(receiver, "add", true);
        if (receiver instanceof Queue) {
            // Queue.add is an offer that throws instead of returning false (#630).
            TelemetryRegistry.ownershipOffered(element, receiver);
        }
        return receiver.add(element);
    }

    /** Weaves {@code Collection.remove}. @param receiver the collection @param element the element @return whether it changed */
    public static boolean collectionRemove(Collection<Object> receiver, Object element) {
        record(receiver, "remove", true);
        boolean removed = receiver.remove(element);
        if (removed && receiver instanceof Queue) {
            // Out of the queue and in the remover's hands, like a poll that named its element
            // (#692). The argument stands for the element: a queue matches by equals, and an
            // equal stand-in is a reference the remover already holds alone.
            TelemetryRegistry.ownershipTaken(element, receiver);
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
     * @param receiver the collection
     * @param elements the elements to add
     * @return whether the collection changed
     * @since 1.12.2
     */
    public static boolean collectionAddAll(Collection<Object> receiver,
                                           Collection<? extends Object> elements) {
        record(receiver, "addAll", true);
        if (receiver instanceof Queue && elements != null) {
            try {
                for (Object element : elements) {
                    TelemetryRegistry.ownershipOffered(element, receiver);
                }
            } catch (RuntimeException ignored) { // NOPMD - recording never fails the caller
                // addAll below reads the same source and reports what is wrong with it.
            }
        }
        return receiver.addAll(elements);
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
        record(receiver, "removeIf", true);
        boolean removed = receiver.removeIf(filter);
        if (removed && receiver instanceof Queue) {
            TelemetryRegistry.ownershipDrained(receiver);
        }
        return removed;
    }

    /** Weaves {@code Collection.contains}. @param receiver the collection @param element the element @return whether present */
    public static boolean collectionContains(Collection<Object> receiver, Object element) {
        record(receiver, "contains", false);
        return receiver.contains(element);
    }

    /** Weaves {@code Collection.clear}. @param receiver the collection */
    public static void collectionClear(Collection<Object> receiver) {
        record(receiver, "clear", true);
        receiver.clear();
    }

    /** Weaves {@code List.get}. @param receiver the list @param index the index @return the element */
    public static @Nullable Object listGet(List<Object> receiver, int index) {
        record(receiver, "get", false);
        return receiver.get(index);
    }

    /** Weaves {@code List.set}. @param receiver the list @param index the index @param element the element @return the previous element */
    public static @Nullable Object listSet(List<Object> receiver, int index, Object element) {
        record(receiver, "set", true);
        return receiver.set(index, element);
    }

    /** Weaves {@code Queue.offer}. @param receiver the queue @param element the element @return whether it was accepted */
    public static boolean queueOffer(Queue<Object> receiver, Object element) {
        record(receiver, "offer", true);
        // Before the offer, so the take that removes the element drains after it (#630).
        TelemetryRegistry.ownershipOffered(element, receiver);
        return receiver.offer(element);
    }

    /** Weaves {@code Queue.poll}. @param receiver the queue @return the head, or null */
    public static @Nullable Object queuePoll(Queue<Object> receiver) {
        record(receiver, "poll", true);
        Object taken = receiver.poll();
        // The element left the queue, so it is this thread's alone as far as the queue goes (#555).
        TelemetryRegistry.ownershipTaken(taken, receiver);
        return taken;
    }

    /** Weaves {@code Queue.peek}. @param receiver the queue @return the head, or null */
    public static @Nullable Object queuePeek(Queue<Object> receiver) {
        record(receiver, "peek", false);
        return receiver.peek();
    }

    // ---- The entry and removal forms #664 left unwoven (#692). Offers publish before the
    //      structure accepts the element and takes after it let go, like offer and poll above.

    /** Weaves {@code Deque.offerFirst}, an offer at one end (#692). @param receiver the deque @param element the element @return whether it was accepted */
    public static boolean dequeOfferFirst(Deque<Object> receiver, Object element) {
        record(receiver, "offerFirst", true);
        TelemetryRegistry.ownershipOffered(element, receiver);
        return receiver.offerFirst(element);
    }

    /** Weaves {@code Deque.offerLast}, an offer at one end (#692). @param receiver the deque @param element the element @return whether it was accepted */
    public static boolean dequeOfferLast(Deque<Object> receiver, Object element) {
        record(receiver, "offerLast", true);
        TelemetryRegistry.ownershipOffered(element, receiver);
        return receiver.offerLast(element);
    }

    /** Weaves {@code Deque.addFirst}, an offer at one end (#692). @param receiver the deque @param element the element */
    public static void dequeAddFirst(Deque<Object> receiver, Object element) {
        record(receiver, "addFirst", true);
        TelemetryRegistry.ownershipOffered(element, receiver);
        receiver.addFirst(element);
    }

    /** Weaves {@code Deque.addLast}, an offer at one end (#692). @param receiver the deque @param element the element */
    public static void dequeAddLast(Deque<Object> receiver, Object element) {
        record(receiver, "addLast", true);
        TelemetryRegistry.ownershipOffered(element, receiver);
        receiver.addLast(element);
    }

    /** Weaves {@code Deque.push}, an offer at one end (#692). @param receiver the deque @param element the element */
    public static void dequePush(Deque<Object> receiver, Object element) {
        record(receiver, "push", true);
        TelemetryRegistry.ownershipOffered(element, receiver);
        receiver.push(element);
    }

    /** Weaves {@code Deque.pollFirst}, a take like {@code poll} (#692). @param receiver the deque @return the element taken */
    public static @Nullable Object dequePollFirst(Deque<Object> receiver) {
        record(receiver, "pollFirst", true);
        Object taken = receiver.pollFirst();
        TelemetryRegistry.ownershipTaken(taken, receiver);
        return taken;
    }

    /** Weaves {@code Deque.pollLast}, a take like {@code poll} (#692). @param receiver the deque @return the element taken */
    public static @Nullable Object dequePollLast(Deque<Object> receiver) {
        record(receiver, "pollLast", true);
        Object taken = receiver.pollLast();
        TelemetryRegistry.ownershipTaken(taken, receiver);
        return taken;
    }

    /** Weaves {@code Deque.removeFirst}, a take like {@code poll} (#692). @param receiver the deque @return the element taken */
    public static @Nullable Object dequeRemoveFirst(Deque<Object> receiver) {
        record(receiver, "removeFirst", true);
        Object taken = receiver.removeFirst();
        TelemetryRegistry.ownershipTaken(taken, receiver);
        return taken;
    }

    /** Weaves {@code Deque.removeLast}, a take like {@code poll} (#692). @param receiver the deque @return the element taken */
    public static @Nullable Object dequeRemoveLast(Deque<Object> receiver) {
        record(receiver, "removeLast", true);
        Object taken = receiver.removeLast();
        TelemetryRegistry.ownershipTaken(taken, receiver);
        return taken;
    }

    /** Weaves {@code Deque.pop}, a take like {@code poll} (#692). @param receiver the deque @return the element taken */
    public static @Nullable Object dequePop(Deque<Object> receiver) {
        record(receiver, "pop", true);
        Object taken = receiver.pop();
        TelemetryRegistry.ownershipTaken(taken, receiver);
        return taken;
    }

    /** Weaves {@code Queue.remove}, a take like {@code poll} (#692). @param receiver the queue @return the element taken */
    public static @Nullable Object queueRemove(Queue<Object> receiver) {
        record(receiver, "remove", true);
        Object taken = receiver.remove();
        TelemetryRegistry.ownershipTaken(taken, receiver);
        return taken;
    }
}
