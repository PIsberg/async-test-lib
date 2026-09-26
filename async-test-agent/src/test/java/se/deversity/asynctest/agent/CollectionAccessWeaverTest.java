package se.deversity.asynctest.agent;

import com.example.agentfixture.CollectionCallSample;
import com.example.agentfixture.SynchronizedQueueCallSample;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AgentCollectionHooks;
import se.deversity.asynctest.AgentLockHooks;
import se.deversity.asynctest.AgentThreadHooks;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The weave table, and the receiver capture it exists to perform. */
class CollectionAccessWeaverTest {

    public static final ConcurrentLinkedQueue<Object> SEEN = new ConcurrentLinkedQueue<>();

    /** The monitors the synchronized-method variants were handed, in call order (#796). */
    public static final ConcurrentLinkedQueue<Object> MONITORS = new ConcurrentLinkedQueue<>();

    /**
     * Stands in for {@link AgentCollectionHooks} so the assertion is about capture, not recording.
     * One method per table entry: the weaver resolves the whole table against this class, which is
     * itself the check that the table and the hook signatures agree.
     */
    public static class StubHooks {
        public static Object mapPut(Map<Object, Object> receiver, Object key, Object value) {
            SEEN.add(receiver);
            return receiver.put(key, value);
        }

        public static Object mapGet(Map<Object, Object> receiver, Object key) {
            SEEN.add(receiver);
            return receiver.get(key);
        }

        public static Object mapRemove(Map<Object, Object> receiver, Object key) {
            return receiver.remove(key);
        }

        public static boolean mapRemove(Map<Object, Object> receiver, Object key, Object value) {
            return receiver.remove(key, value);
        }

        public static boolean mapContainsKey(Map<Object, Object> receiver, Object key) {
            return receiver.containsKey(key);
        }

        public static boolean collectionAdd(java.util.Collection<Object> receiver, Object element) {
            return receiver.add(element);
        }

        public static boolean collectionRemove(java.util.Collection<Object> receiver, Object element) {
            return receiver.remove(element);
        }

        public static boolean collectionContains(java.util.Collection<Object> receiver, Object element) {
            return receiver.contains(element);
        }

        public static void collectionClear(java.util.Collection<Object> receiver) {
            receiver.clear();
        }

        public static Object listGet(List<Object> receiver, int index) {
            return receiver.get(index);
        }

        public static Object listSet(List<Object> receiver, int index, Object element) {
            return receiver.set(index, element);
        }

        public static boolean queueOffer(Queue<Object> receiver, Object element) {
            return receiver.offer(element);
        }

        public static Object queuePoll(Queue<Object> receiver) {
            return receiver.poll();
        }

        public static Object queuePeek(Queue<Object> receiver) {
            return receiver.peek();
        }

        public static boolean collectionAddAll(java.util.Collection<Object> receiver,
                                               java.util.Collection<? extends Object> elements) {
            return receiver.addAll(elements);
        }

        public static boolean collectionRemoveIf(java.util.Collection<Object> receiver,
                                                 java.util.function.Predicate<? super Object> filter) {
            return receiver.removeIf(filter);
        }

        public static Object queueRemove(Queue<Object> receiver) {
            return receiver.remove();
        }

        public static boolean dequeOfferFirst(java.util.Deque<Object> receiver, Object element) {
            return receiver.offerFirst(element);
        }

        public static boolean dequeOfferLast(java.util.Deque<Object> receiver, Object element) {
            return receiver.offerLast(element);
        }

        public static void dequeAddFirst(java.util.Deque<Object> receiver, Object element) {
            receiver.addFirst(element);
        }

        public static void dequeAddLast(java.util.Deque<Object> receiver, Object element) {
            receiver.addLast(element);
        }

        public static void dequePush(java.util.Deque<Object> receiver, Object element) {
            receiver.push(element);
        }

        public static Object dequePollFirst(java.util.Deque<Object> receiver) {
            return receiver.pollFirst();
        }

        public static Object dequePollLast(java.util.Deque<Object> receiver) {
            return receiver.pollLast();
        }

        public static Object dequeRemoveFirst(java.util.Deque<Object> receiver) {
            return receiver.removeFirst();
        }

        public static Object dequeRemoveLast(java.util.Deque<Object> receiver) {
            return receiver.removeLast();
        }

        public static Object dequePop(java.util.Deque<Object> receiver) {
            return receiver.pop();
        }

        public static void blockingDequePutFirst(java.util.concurrent.BlockingDeque<Object> receiver,
                                                 Object element) throws InterruptedException {
            receiver.putFirst(element);
        }

        public static void blockingDequePutLast(java.util.concurrent.BlockingDeque<Object> receiver,
                                                Object element) throws InterruptedException {
            receiver.putLast(element);
        }

        public static boolean blockingDequeOfferFirst(
                java.util.concurrent.BlockingDeque<Object> receiver, Object element, long timeout,
                java.util.concurrent.TimeUnit unit) throws InterruptedException {
            return receiver.offerFirst(element, timeout, unit);
        }

        public static boolean blockingDequeOfferLast(
                java.util.concurrent.BlockingDeque<Object> receiver, Object element, long timeout,
                java.util.concurrent.TimeUnit unit) throws InterruptedException {
            return receiver.offerLast(element, timeout, unit);
        }

        public static Object blockingDequeTakeFirst(java.util.concurrent.BlockingDeque<Object> receiver)
                throws InterruptedException {
            return receiver.takeFirst();
        }

        public static Object blockingDequeTakeLast(java.util.concurrent.BlockingDeque<Object> receiver)
                throws InterruptedException {
            return receiver.takeLast();
        }

        public static Object blockingDequePollFirst(
                java.util.concurrent.BlockingDeque<Object> receiver, long timeout,
                java.util.concurrent.TimeUnit unit) throws InterruptedException {
            return receiver.pollFirst(timeout, unit);
        }

        public static Object blockingDequePollLast(
                java.util.concurrent.BlockingDeque<Object> receiver, long timeout,
                java.util.concurrent.TimeUnit unit) throws InterruptedException {
            return receiver.pollLast(timeout, unit);
        }

        // The synchronized-method variants (#796): the same calls, plus the monitor the weaver
        // loaded, which is what the capture test below reads back.

        public static boolean collectionAdd(java.util.Collection<Object> receiver, Object element, Object monitor) {
            MONITORS.add(monitor);
            return receiver.add(element);
        }

        public static boolean collectionRemove(java.util.Collection<Object> receiver, Object element, Object monitor) {
            MONITORS.add(monitor);
            return receiver.remove(element);
        }

        public static boolean collectionAddAll(java.util.Collection<Object> receiver, java.util.Collection<? extends Object> elements, Object monitor) {
            MONITORS.add(monitor);
            return receiver.addAll(elements);
        }

        public static boolean queueOffer(Queue<Object> receiver, Object element, Object monitor) {
            MONITORS.add(monitor);
            return receiver.offer(element);
        }

        public static Object queuePoll(Queue<Object> receiver, Object monitor) {
            MONITORS.add(monitor);
            return receiver.poll();
        }

        public static Object queueRemove(Queue<Object> receiver, Object monitor) {
            MONITORS.add(monitor);
            return receiver.remove();
        }

        public static boolean dequeOfferFirst(java.util.Deque<Object> receiver, Object element, Object monitor) {
            MONITORS.add(monitor);
            return receiver.offerFirst(element);
        }

        public static boolean dequeOfferLast(java.util.Deque<Object> receiver, Object element, Object monitor) {
            MONITORS.add(monitor);
            return receiver.offerLast(element);
        }

        public static void dequeAddFirst(java.util.Deque<Object> receiver, Object element, Object monitor) {
            MONITORS.add(monitor);
            receiver.addFirst(element);
        }

        public static void dequeAddLast(java.util.Deque<Object> receiver, Object element, Object monitor) {
            MONITORS.add(monitor);
            receiver.addLast(element);
        }

        public static void dequePush(java.util.Deque<Object> receiver, Object element, Object monitor) {
            MONITORS.add(monitor);
            receiver.push(element);
        }

        public static Object dequePollFirst(java.util.Deque<Object> receiver, Object monitor) {
            MONITORS.add(monitor);
            return receiver.pollFirst();
        }

        public static Object dequePollLast(java.util.Deque<Object> receiver, Object monitor) {
            MONITORS.add(monitor);
            return receiver.pollLast();
        }

        public static Object dequeRemoveFirst(java.util.Deque<Object> receiver, Object monitor) {
            MONITORS.add(monitor);
            return receiver.removeFirst();
        }

        public static Object dequeRemoveLast(java.util.Deque<Object> receiver, Object monitor) {
            MONITORS.add(monitor);
            return receiver.removeLast();
        }

        public static Object dequePop(java.util.Deque<Object> receiver, Object monitor) {
            MONITORS.add(monitor);
            return receiver.pop();
        }
    }

    @Test
    @DisplayName("every table entry resolves to a hook with the matching erased signature")
    void tableAndHooksAgree() {
        List<AsmVisitorWrapper> substitutions =
                CollectionAccessWeaver.substitutions(AgentCollectionHooks.class);
        List<AsmVisitorWrapper> lockSubstitutions =
                CollectionAccessWeaver.lockSubstitutions(AgentLockHooks.class);
        List<AsmVisitorWrapper> threadSubstitutions =
                CollectionAccessWeaver.threadSubstitutions(AgentThreadHooks.class);

        assertEquals(AgentCollectionHooks.class.getName(), CollectionAccessWeaver.hooksClassName(),
                "the weaver names the hook class by string, because the agent module must not "
                        + "depend on the library. If these drift, weaving fails at install time "
                        + "with a ClassNotFoundException instead of here.");
        assertEquals(AgentLockHooks.class.getName(), CollectionAccessWeaver.lockHooksClassName(),
                "same contract for the lock hooks");
        assertEquals(AgentThreadHooks.class.getName(), CollectionAccessWeaver.threadHooksClassName(),
                "same contract for the thread hooks");
        // targets() throws IllegalStateException for an entry with no matching hook, so reaching
        // these lines is the assertion: every entry of both tables found its method. The whole
        // table travels in one visitor since the MemberSubstitution replacement.
        assertEquals(1, substitutions.size(), "one visitor carries the whole collection table");
        assertEquals(1, lockSubstitutions.size(), "one visitor carries the whole lock table");
        assertEquals(1, threadSubstitutions.size(), "one visitor carries the whole thread table");
    }

    @Test
    @DisplayName("the receiver is captured through both an interface call and a concrete-type call")
    void capturesReceiverForBothCallShapes() throws Exception {
        SEEN.clear();
        Class<?> woven = new ByteBuddy()
                .redefine(CollectionCallSample.class)
                .visit(CollectionAccessWeaver.substitutions(StubHooks.class).get(0))
                .make()
                .load(getClass().getClassLoader(), ClassLoadingStrategy.Default.CHILD_FIRST)
                .getLoaded();

        Object sample = woven.getDeclaredConstructor().newInstance();
        woven.getMethod("store", String.class, String.class).invoke(sample, "k", "v");

        Object viaInterface = woven.getField("viaInterface").get(sample);
        Object viaConcreteType = woven.getField("viaConcreteType").get(sample);

        assertEquals(1, ((Map<?, ?>) viaInterface).size(), "the original interface call must still happen");
        assertEquals(1, ((Map<?, ?>) viaConcreteType).size(), "the original concrete call must still happen");
        assertEquals(2, SEEN.size(), "both call shapes must be observed");
        assertSame(viaInterface, SEEN.poll(), "first recorded object is the interface-typed receiver");
        assertSame(viaConcreteType, SEEN.poll(), "second recorded object is the concrete-typed receiver");
    }

    @Test
    @DisplayName("a queue offer or take inside a synchronized method hands its hook the method's monitor")
    void passesTheSynchronizedMethodsMonitorToTheQueueHooks() throws Exception {
        MONITORS.clear();
        Class<?> woven = new ByteBuddy()
                .redefine(SynchronizedQueueCallSample.class)
                .visit(CollectionAccessWeaver.substitutions(StubHooks.class).get(0))
                .make()
                .load(getClass().getClassLoader(), ClassLoadingStrategy.Default.CHILD_FIRST)
                .getLoaded();
        Object sample = woven.getDeclaredConstructor().newInstance();
        Object element = new Object();

        assertSame(element, woven.getMethod("offerAndPollHoldingThis", Object.class)
                        .invoke(sample, element),
                "the original offer and poll must still happen");
        assertEquals(List.of(sample, sample), List.copyOf(MONITORS),
                "an instance synchronized method holds this, and both the offer and the poll must be "
                        + "handed it: nothing else tells a queue hook the monitor is held");

        MONITORS.clear();
        assertSame(element, woven.getMethod("pushAndPopHoldingTheClass", Object.class)
                        .invoke(null, element),
                "the original push and pop must still happen");
        assertEquals(List.of(woven, woven), List.copyOf(MONITORS),
                "a static synchronized method holds its class");

        MONITORS.clear();
        assertSame(element, woven.getMethod("offerAndPollHoldingNothing", Object.class)
                        .invoke(sample, element),
                "the original offer and poll must still happen");
        assertTrue(MONITORS.isEmpty(),
                "outside a synchronized method the ordinary hooks run, with no monitor to pass");
    }
}
