package se.deversity.asynctest.agent;

import com.example.agentfixture.OfferShapesSample;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AgentConcurrencyUtilHooks;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one-instruction lookahead: a popped offer result reaches the discard hook, nothing else does.
 *
 * <p>Woven against a stub so the assertion is about which instruction was rewritten rather than
 * what a detector made of it. Loading the woven class is itself the strongest check here: the
 * verifier runs on it, so a stale flag that rewrote the wrong {@code POP} fails the load rather
 * than an assertion (#454).
 */
class DiscardedOfferWeavingTest {

    /** Every result handed to the discard hook, in order. */
    static final ConcurrentLinkedQueue<Boolean> DISCARDED = new ConcurrentLinkedQueue<>();

    /** How many offers, of either form, went through the stub. */
    static final AtomicInteger OFFERS = new AtomicInteger();

    /**
     * Stands in for {@link AgentConcurrencyUtilHooks}. One method per concurrency-table entry,
     * because the weaver resolves the whole table against this class and throws for a missing one.
     */
    public static class StubConcurrencyHooks {
        public static void acquire(Semaphore s) throws InterruptedException {
            s.acquire();
        }

        public static boolean tryAcquire(Semaphore s) {
            return s.tryAcquire();
        }

        public static void release(Semaphore s) {
            s.release();
        }

        public static void acquire(Semaphore s, int permits) throws InterruptedException {
            s.acquire(permits);
        }

        public static boolean tryAcquire(Semaphore s, int permits) {
            return s.tryAcquire(permits);
        }

        public static boolean tryAcquire(Semaphore s, long timeout, TimeUnit unit)
                throws InterruptedException {
            return s.tryAcquire(timeout, unit);
        }

        public static boolean tryAcquire(Semaphore s, int permits, long timeout, TimeUnit unit)
                throws InterruptedException {
            return s.tryAcquire(permits, timeout, unit);
        }

        public static void release(Semaphore s, int permits) {
            s.release(permits);
        }

        public static void countDown(CountDownLatch l) {
            l.countDown();
        }

        public static void await(CountDownLatch l) throws InterruptedException {
            l.await();
        }

        public static boolean await(CountDownLatch l, long timeout, TimeUnit unit)
                throws InterruptedException {
            return l.await(timeout, unit);
        }

        public static boolean offer(BlockingQueue<Object> q, Object e) {
            OFFERS.incrementAndGet();
            return q.offer(e);
        }

        public static Object poll(BlockingQueue<Object> q) {
            return q.poll();
        }

        public static void put(BlockingQueue<Object> q, Object e) throws InterruptedException {
            q.put(e);
        }

        public static boolean offer(BlockingQueue<Object> q, Object e, long timeout, TimeUnit unit)
                throws InterruptedException {
            OFFERS.incrementAndGet();
            return q.offer(e, timeout, unit);
        }

        public static Object poll(BlockingQueue<Object> q, long timeout, TimeUnit unit)
                throws InterruptedException {
            return q.poll(timeout, unit);
        }

        public static void offerResultDiscarded(boolean added) {
            DISCARDED.add(added);
        }
    }

    @Test
    @DisplayName("the concurrency table resolves its discard hook against the real hooks class")
    void tableAndHooksAgreeOnTheDiscardHook() {
        // targets() throws for an entry whose hook is missing or mis-shaped, and the discard
        // hook is resolved and checked the same way. Reaching the assertion is the check.
        assertEquals(1, CollectionAccessWeaver.concurrencySubstitutions(
                        AgentConcurrencyUtilHooks.class).size(),
                "one visitor carries the whole concurrency table, discard hook included");
    }

    @Test
    @DisplayName("only the popped result reaches the discard hook; every other shape is untouched")
    void onlyThePoppedResultReachesTheDiscardHook() throws Exception {
        DISCARDED.clear();
        OFFERS.set(0);
        Class<?> woven = new ByteBuddy()
                .redefine(OfferShapesSample.class)
                .visit(CollectionAccessWeaver.concurrencySubstitutions(StubConcurrencyHooks.class)
                        .get(0))
                .make()
                .load(getClass().getClassLoader(), ClassLoadingStrategy.Default.CHILD_FIRST)
                .getLoaded();

        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(1);
        Object sample = woven.getConstructor(BlockingQueue.class).newInstance(queue);

        woven.getMethod("discarded", Object.class).invoke(sample, "fits");
        assertEquals(List.of(true), List.copyOf(DISCARDED),
                "a discarded offer that succeeded still reaches the hook, with the true it popped");

        woven.getMethod("discarded", Object.class).invoke(sample, "rejected");
        woven.getMethod("discardedTimed", Object.class).invoke(sample, "rejected too");
        assertEquals(List.of(true, false, false), List.copyOf(DISCARDED),
                "both offer forms hand the popped false to the hook when the queue is full");

        assertFalse((boolean) woven.getMethod("branched", Object.class).invoke(sample, "b"),
                "the branched shape still performs the offer and still sees the rejection");
        assertFalse((boolean) woven.getMethod("returned", Object.class).invoke(sample, "r"),
                "so does the returned shape");
        woven.getMethod("storedInALocal", Object.class).invoke(sample, "s");
        assertEquals(3, DISCARDED.size(),
                "IFNE, IRETURN and ISTORE are the caller reading the result: none of them is a "
                        + "discard, and none may reach the hook. Got " + DISCARDED);
        assertEquals(6, OFFERS.get(), "all six offers went through the ordinary hook");

        woven.getMethod("unrelatedBooleanPopped", Object.class).invoke(sample, "list");
        woven.getMethod("unrelatedReferencePopped", Object.class).invoke(sample, "map");
        assertEquals(3, DISCARDED.size(),
                "a POP after a call that is not an offer must stay a POP. The reference case "
                        + "would not have loaded at all had the flag been stale; the boolean "
                        + "case would have reached the hook. Got " + DISCARDED);
        assertEquals(1, ((List<?>) woven.getField("list").get(sample)).size(),
                "the unrelated add still happened");
        assertEquals(1, ((java.util.Map<?, ?>) woven.getField("map").get(sample)).size(),
                "the unrelated put still happened");
        assertTrue(queue.contains("fits"), "the one accepted element is really in the queue");
    }
}
