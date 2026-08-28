package se.deversity.asynctest;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import se.deversity.asynctest.diagnostics.BlockingQueueDetector;
import se.deversity.asynctest.diagnostics.CountDownLatchDetector;
import se.deversity.asynctest.diagnostics.LatchMisuseDetector;
import se.deversity.asynctest.diagnostics.SemaphoreMisuseDetector;
import se.deversity.vibetags.annotations.AIContract;

/**
 * Hooks for the {@code java.util.concurrent} coordination primitives.
 *
 * <h2>Why these need the agent</h2>
 *
 * <p>A {@link Semaphore} whose permits leak, a {@link CountDownLatch} counted down the wrong number
 * of times, a {@link BlockingQueue} whose {@code offer} silently returned {@code false}: each is a
 * bug the library has had a detector for throughout, and each detector could only see it if the
 * test author called a {@code record} method by hand. These are not types a test author thinks to
 * instrument - they are plumbing, used deep inside the class under test.
 *
 * <h2>Why sharing is not the question here</h2>
 *
 * <p>Unlike the shared-instance family, sharing is the whole point of these objects: a latch nobody
 * shares does nothing. What the detectors report is misuse of the protocol, so the hooks record the
 * operation and its outcome rather than the instance alone. That is also why the return values
 * matter: the boolean an {@code offer} discards is the entire finding.
 *
 * <h2>Ordering</h2>
 *
 * <p>Acquisition and release follow the rule {@link AgentLockHooks} sets - record after acquiring
 * and before releasing, so the recorded interval is contained by the real one. A call that throws
 * records nothing, because nothing happened.
 *
 * @since 1.10.0
 */
@AIContract(reason = "Called from bytecode the agent rewrites: method names and erased signatures here are matched by CollectionAccessWeaver.CONCURRENCY_ENTRIES and cannot change independently of it. Every hook must perform the original operation and propagate its exceptions unchanged, InterruptedException included - these types throw it as a matter of course and swallowing one would change the interruption semantics of the code under test. Record after acquiring and before releasing, the containment rule AgentLockHooks documents, and record nothing when the underlying call throws. offer, poll and the timed await must record their actual return value: the boolean a caller discards is the whole bug these detectors report.")
public final class AgentConcurrencyUtilHooks {

    private AgentConcurrencyUtilHooks() {
    }

    /**
     * Weaves {@code Semaphore.acquire()}.
     *
     * @param receiver the semaphore
     * @throws InterruptedException if interrupted while waiting
     */
    public static void acquire(Semaphore receiver) throws InterruptedException {
        receiver.acquire();
        SemaphoreMisuseDetector detector = AsyncTestContext.currentSemaphoreMisuseDetector();
        if (detector != null) {
            detector.recordAcquire(receiver, receiver.getClass().getName());
        }
    }

    /**
     * Weaves {@code Semaphore.tryAcquire()}.
     *
     * @param receiver the semaphore
     * @return whether a permit was taken
     */
    public static boolean tryAcquire(Semaphore receiver) {
        boolean acquired = receiver.tryAcquire();
        if (acquired) {
            SemaphoreMisuseDetector detector = AsyncTestContext.currentSemaphoreMisuseDetector();
            if (detector != null) {
                detector.recordAcquire(receiver, receiver.getClass().getName());
            }
        }
        return acquired;
    }

    /**
     * Weaves {@code Semaphore.release()}.
     *
     * @param receiver the semaphore
     */
    public static void release(Semaphore receiver) {
        SemaphoreMisuseDetector detector = AsyncTestContext.currentSemaphoreMisuseDetector();
        if (detector != null) {
            detector.recordRelease(receiver, receiver.getClass().getName());
        }
        receiver.release();
    }

    /**
     * Weaves {@code CountDownLatch.countDown()}.
     *
     * @param receiver the latch
     */
    public static void countDown(CountDownLatch receiver) {
        receiver.countDown();
        CountDownLatchDetector counts = AsyncTestContext.currentCountDownLatchDetector();
        if (counts != null) {
            counts.recordCountDown(receiver);
        }
        LatchMisuseDetector misuse = AsyncTestContext.currentLatchMisuseDetector();
        if (misuse != null) {
            misuse.recordCountDown(receiver);
        }
    }

    /**
     * Weaves {@code CountDownLatch.await()}.
     *
     * @param receiver the latch
     * @throws InterruptedException if interrupted while waiting
     */
    public static void await(CountDownLatch receiver) throws InterruptedException {
        LatchMisuseDetector misuse = AsyncTestContext.currentLatchMisuseDetector();
        if (misuse != null) {
            misuse.recordAwait(receiver);
        }
        receiver.await();
        CountDownLatchDetector counts = AsyncTestContext.currentCountDownLatchDetector();
        if (counts != null) {
            counts.recordAwaitSuccess(receiver);
        }
    }

    /**
     * Weaves {@code CountDownLatch.await(long, TimeUnit)}.
     *
     * <p>The timeout branch is the finding: a latch that timed out was not counted down as often as
     * somebody expected, and the boolean saying so is routinely discarded.
     *
     * @param receiver the latch
     * @param timeout  how long to wait
     * @param unit     the unit of {@code timeout}
     * @return whether the latch reached zero before the timeout
     * @throws InterruptedException if interrupted while waiting
     */
    public static boolean await(CountDownLatch receiver, long timeout, TimeUnit unit)
            throws InterruptedException {
        LatchMisuseDetector misuse = AsyncTestContext.currentLatchMisuseDetector();
        if (misuse != null) {
            misuse.recordAwait(receiver);
        }
        boolean reachedZero = receiver.await(timeout, unit);
        CountDownLatchDetector counts = AsyncTestContext.currentCountDownLatchDetector();
        if (counts != null) {
            if (reachedZero) {
                counts.recordAwaitSuccess(receiver);
            } else {
                counts.recordTimeout(receiver);
            }
        }
        return reachedZero;
    }

    /**
     * Weaves {@code BlockingQueue.offer(Object)}.
     *
     * <p>The discarded boolean is the bug: an {@code offer} that returned {@code false} dropped the
     * element, and the caller usually never looks.
     *
     * @param receiver the queue
     * @param element  the element to add
     * @return whether the element was added
     */
    public static boolean offer(BlockingQueue<Object> receiver, Object element) {
        boolean added = receiver.offer(element);
        BlockingQueueDetector detector = AsyncTestContext.currentBlockingQueueDetector();
        if (detector != null) {
            detector.recordOffer(receiver, receiver.getClass().getName(), added);
        }
        return added;
    }

    /**
     * Weaves {@code BlockingQueue.poll()}.
     *
     * @param receiver the queue
     * @return the head of the queue, or {@code null} when it was empty
     */
    public static Object poll(BlockingQueue<Object> receiver) {
        Object taken = receiver.poll();
        BlockingQueueDetector detector = AsyncTestContext.currentBlockingQueueDetector();
        if (detector != null) {
            detector.recordPoll(receiver, receiver.getClass().getName(), taken != null);
        }
        return taken;
    }

    /**
     * Weaves {@code BlockingQueue.put(Object)}.
     *
     * @param receiver the queue
     * @param element  the element to add
     * @throws InterruptedException if interrupted while waiting for space
     */
    public static void put(BlockingQueue<Object> receiver, Object element)
            throws InterruptedException {
        receiver.put(element);
        BlockingQueueDetector detector = AsyncTestContext.currentBlockingQueueDetector();
        if (detector != null) {
            detector.recordPut(receiver, receiver.getClass().getName());
        }
    }
}
