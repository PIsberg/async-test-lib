package se.deversity.asynctest;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;
import org.jspecify.annotations.Nullable;

import se.deversity.asynctest.diagnostics.SharedCollectionDetector;
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
 * @since 1.10.0
 */
@API(status = Status.INTERNAL)
@AIContract(reason = "Called from bytecode the agent rewrites, not from source: the method names and erased signatures are matched by CollectionAccessWeaver and cannot change independently of it. Every hook must end by performing the original operation and must never throw on the recording path - it runs inside the user's code, so an exception here surfaces as a failure in their test. Recording is best-effort by design: no context, a disabled detector, or a type the library knows is thread-safe all mean record nothing and delegate.")
public final class AgentCollectionHooks {

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
                || receiver instanceof java.util.concurrent.BlockingDeque) {
            return;
        }
        String type = receiver.getClass().getName();
        if (type.startsWith("java.util.concurrent.")
                || type.startsWith("java.util.Collections$Synchronized")) {
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

    /** Weaves {@code Map.containsKey}. @param receiver the map @param key the key @return whether the key is present */
    public static boolean mapContainsKey(Map<Object, Object> receiver, Object key) {
        record(receiver, "containsKey", false);
        return receiver.containsKey(key);
    }

    /** Weaves {@code Collection.add}. @param receiver the collection @param element the element @return whether it changed */
    public static boolean collectionAdd(Collection<Object> receiver, Object element) {
        record(receiver, "add", true);
        return receiver.add(element);
    }

    /** Weaves {@code Collection.remove}. @param receiver the collection @param element the element @return whether it changed */
    public static boolean collectionRemove(Collection<Object> receiver, Object element) {
        record(receiver, "remove", true);
        return receiver.remove(element);
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
        return receiver.offer(element);
    }

    /** Weaves {@code Queue.poll}. @param receiver the queue @return the head, or null */
    public static @Nullable Object queuePoll(Queue<Object> receiver) {
        record(receiver, "poll", true);
        return receiver.poll();
    }

    /** Weaves {@code Queue.peek}. @param receiver the queue @return the head, or null */
    public static @Nullable Object queuePeek(Queue<Object> receiver) {
        record(receiver, "peek", false);
        return receiver.peek();
    }
}
