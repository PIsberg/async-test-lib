package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.HappensBefore;
import se.deversity.asynctest.diagnostics.HeldLocks;
import se.deversity.asynctest.diagnostics.RaceConditionDetector;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A woven call that throws leaves the bookkeeping as the call left the object (#1418): a value the
 * map or queue never took is not published, a failed thread construction pairs no task with the
 * next thread, and an unlock that is refused keeps the hold it did not release.
 */
class AgentHookExceptionPathsTest {

    static final class Box {
        int value;
    }

    /**
     * {@return whether a write before {@code publish} and a read after acquiring the same object
     * on another thread are reported as a race}: true means nothing ordered them, which is right
     * for an object the container never took.
     */
    private static boolean raceReportedAfter(Consumer<Box> publish) throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box box = new Box();
        Thread producer = new Thread(() -> {
            box.value = 1;
            detector.recordFieldWrite(box, "value");
            try {
                publish.accept(box);
            } catch (RuntimeException expected) {
                // the call under test throws; its bookkeeping is what is checked
            }
        });
        producer.start();
        producer.join();
        Thread consumer = new Thread(() -> {
            HappensBefore.acquire(box);
            detector.recordFieldRead(box, "value");
            box.value++;
            detector.recordFieldWrite(box, "value");
        });
        consumer.start();
        consumer.join();
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a put the map refuses with an exception publishes nothing")
    void aThrowingPutPublishesNothing() throws InterruptedException {
        assertTrue(raceReportedAfter(b -> AgentCollectionHooks.mapPut(new ConcurrentHashMap<>(), null, b)),
                "ConcurrentHashMap.put(null, value) threw: the value never went in");
    }

    @Test
    @DisplayName("a merge whose function throws publishes nothing")
    void aThrowingMergePublishesNothing() throws InterruptedException {
        ConcurrentHashMap<Object, Object> map = new ConcurrentHashMap<>();
        map.put("key", "present");
        assertTrue(raceReportedAfter(b -> AgentCollectionHooks.mapMerge(map, "key", b, (old, given) -> {
            throw new IllegalStateException("refused");
        })), "the remapping threw: the value never went in");
    }

    /** A deque that refuses every offer by throwing, as a policy-checking deque would. */
    static final class RefusingDeque extends LinkedBlockingDeque<Object> {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean offerFirst(Object element) {
            throw new IllegalArgumentException("refused");
        }

        @Override
        public boolean offerLast(Object element) {
            throw new IllegalArgumentException("refused");
        }
    }

    @Test
    @DisplayName("an offer the queue refuses with an exception publishes nothing")
    @SuppressWarnings("unchecked")
    void aThrowingOfferPublishesNothing() throws InterruptedException {
        // A Box is not Comparable, so a priority queue throws ClassCastException on offer.
        assertTrue(raceReportedAfter(b -> AgentCollectionHooks.queueOffer(new PriorityBlockingQueue<>(), b)),
                "Queue.offer threw");
        assertTrue(raceReportedAfter(b -> AgentConcurrencyUtilHooks.offer(
                        (BlockingQueue<Object>) (BlockingQueue<?>) new PriorityBlockingQueue<>(), b)),
                "BlockingQueue.offer threw");
        assertTrue(raceReportedAfter(b -> AgentCollectionHooks.dequeOfferFirst(new RefusingDeque(), b)),
                "Deque.offerFirst threw");
        assertTrue(raceReportedAfter(b -> AgentCollectionHooks.dequeOfferLast(new RefusingDeque(), b)),
                "Deque.offerLast threw");
    }

    @Test
    @DisplayName("a thread construction that throws pairs its task with no later thread")
    void aFailedConstructionLeavesNoPendingTask() {
        FutureTask<Object> task = new FutureTask<>(() -> null);
        // What the woven new Thread(task, null) does before its constructor throws.
        AgentThreadHooks.threadTarget(task);
        // The next woven construction, around something else.
        Runnable other = () -> { };
        AgentThreadHooks.threadTarget(other);
        Thread next = new Thread(other);
        AgentThreadHooks.threadConstructed(next);

        assertNull(AgentThreadHooks.runnerOf(task),
                "the task's constructor failed, so no thread will run it, least of all the next one");
    }

    @Test
    @DisplayName("an unlock refused in the wrong mode keeps the hold this thread still has")
    void aRefusedUnlockKeepsTheHold() {
        ReentrantReadWriteLock readWrite = new ReentrantReadWriteLock();
        // Through the woven view calls, which is how the hooks learn both views are one lock.
        java.util.concurrent.locks.Lock read = AgentLockHooks.readLock(readWrite);
        java.util.concurrent.locks.Lock write = AgentLockHooks.writeLock(readWrite);
        AgentLockHooks.lock(read);
        try {
            assertTrue(HeldLocks.holds(readWrite), "the read lock is held");
            assertThrows(IllegalMonitorStateException.class, () -> AgentLockHooks.unlock(write));
            assertTrue(HeldLocks.holds(readWrite), "the read lock is still held");
        } finally {
            AgentLockHooks.unlock(read);
            HeldLocks.clear();
        }

        StampedLock stamped = new StampedLock();
        long readStamp = AgentLockHooks.readLock(stamped);
        try {
            assertThrows(IllegalMonitorStateException.class, () -> AgentLockHooks.unlockWrite(stamped, readStamp));
            assertTrue(HeldLocks.holds(stamped), "the read stamp is still held");
        } finally {
            AgentLockHooks.unlockRead(stamped, readStamp);
            HeldLocks.clear();
        }
    }
}
