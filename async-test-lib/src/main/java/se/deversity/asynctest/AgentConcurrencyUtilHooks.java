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
@AIContract(reason = "Called from bytecode the agent rewrites: method names and erased signatures here are matched by CollectionAccessWeaver.CONCURRENCY_ENTRIES and cannot change independently of it. Every hook must perform the original operation and propagate its exceptions unchanged, InterruptedException included - these types throw it as a matter of course and swallowing one would change the interruption semantics of the code under test. Record after acquiring and before releasing, the containment rule AgentLockHooks documents, and record nothing when the underlying call throws. offer, poll and the timed await must record their actual return value: the boolean a caller discards is the whole bug these detectors report. The observe* call must stay ahead of the operation: it is where LatchMisuseDetector and BlockingQueueDetector learn a subject exists at all, and the latch's starting count is only readable before this call decrements it. offerResultDiscarded is not an operation: the weaver substitutes it for the POP that follows an offer whose boolean the caller never read, so it is always the instruction after one of the offer hooks on the same thread, and it must stay that way - the detector correlates it to the offer recorded immediately before, and that correlation is exact only because the weaver emits it in place of the POP and nowhere else.")
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
     * Weaves {@code Semaphore.acquire(int)}.
     *
     * <p>The permit-count overloads matter more than they look. The detector's finding is a
     * balance - acquisitions against releases - so a semaphore used as {@code acquire(3)} then
     * {@code release(1)} leaks two permits, and recording one event for each call would have made
     * that read as balanced. Each permit is therefore recorded separately, which is what keeps
     * the arithmetic meaning what it says. None of these overloads was woven at all before
     * (#434), so a pool sized in permits was invisible.
     *
     * @param receiver the semaphore
     * @param permits  how many permits to take
     * @throws InterruptedException if interrupted while waiting
     */
    public static void acquire(Semaphore receiver, int permits) throws InterruptedException {
        receiver.acquire(permits);
        recordAcquired(receiver, permits);
    }

    /**
     * Weaves {@code Semaphore.tryAcquire(int)}.
     *
     * @param receiver the semaphore
     * @param permits  how many permits to take
     * @return whether they were taken
     */
    public static boolean tryAcquire(Semaphore receiver, int permits) {
        boolean acquired = receiver.tryAcquire(permits);
        if (acquired) {
            recordAcquired(receiver, permits);
        }
        return acquired;
    }

    /**
     * Weaves {@code Semaphore.tryAcquire(long, TimeUnit)}, the timed form.
     *
     * @param receiver the semaphore
     * @param timeout  how long to wait
     * @param unit     the unit of {@code timeout}
     * @return whether a permit was taken
     * @throws InterruptedException if interrupted while waiting
     */
    public static boolean tryAcquire(Semaphore receiver, long timeout, TimeUnit unit)
            throws InterruptedException {
        boolean acquired = receiver.tryAcquire(timeout, unit);
        if (acquired) {
            recordAcquired(receiver, 1);
        }
        return acquired;
    }

    /**
     * Weaves {@code Semaphore.tryAcquire(int, long, TimeUnit)}.
     *
     * @param receiver the semaphore
     * @param permits  how many permits to take
     * @param timeout  how long to wait
     * @param unit     the unit of {@code timeout}
     * @return whether they were taken
     * @throws InterruptedException if interrupted while waiting
     */
    public static boolean tryAcquire(Semaphore receiver, int permits, long timeout, TimeUnit unit)
            throws InterruptedException {
        boolean acquired = receiver.tryAcquire(permits, timeout, unit);
        if (acquired) {
            recordAcquired(receiver, permits);
        }
        return acquired;
    }

    /**
     * Weaves {@code Semaphore.release(int)}.
     *
     * @param receiver the semaphore
     * @param permits  how many permits to return
     */
    public static void release(Semaphore receiver, int permits) {
        SemaphoreMisuseDetector detector = AsyncTestContext.currentSemaphoreMisuseDetector();
        if (detector != null) {
            for (int i = 0; i < permits; i++) {
                detector.recordRelease(receiver, receiver.getClass().getName());
            }
        }
        receiver.release(permits);
    }

    private static void recordAcquired(Semaphore receiver, int permits) {
        SemaphoreMisuseDetector detector = AsyncTestContext.currentSemaphoreMisuseDetector();
        if (detector != null) {
            for (int i = 0; i < permits; i++) {
                detector.recordAcquire(receiver, receiver.getClass().getName());
            }
        }
    }

    /**
     * Weaves {@code CountDownLatch.countDown()}.
     *
     * <p>The latch is observed before it is counted down, which is what lets
     * {@code LatchMisuseDetector} recover the count it started from. Observing afterwards would
     * read a count this very call has already decremented.
     *
     * @param receiver the latch
     */
    public static void countDown(CountDownLatch receiver) {
        LatchMisuseDetector misuse = AsyncTestContext.currentLatchMisuseDetector();
        if (misuse != null) {
            misuse.observeLatch(receiver);
        }
        receiver.countDown();
        CountDownLatchDetector counts = AsyncTestContext.currentCountDownLatchDetector();
        if (counts != null) {
            counts.recordCountDown(receiver);
        }
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
            misuse.observeLatch(receiver);
            misuse.recordAwait(receiver);
        }
        receiver.await();
        // Reached only at zero, which is what tells LatchMisuseDetector that countdowns it never
        // saw happened in unwoven code rather than not at all (#499).
        if (misuse != null) {
            misuse.recordAwaitReturned(receiver);
        }
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
            misuse.observeLatch(receiver);
            misuse.recordAwait(receiver);
        }
        boolean reachedZero = receiver.await(timeout, unit);
        // Only the true branch: a timed-out await proves nothing about the latch reaching zero.
        if (misuse != null && reachedZero) {
            misuse.recordAwaitReturned(receiver);
        }
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
        BlockingQueueDetector detector = AsyncTestContext.currentBlockingQueueDetector();
        if (detector != null) {
            detector.observeQueue(receiver);
        }
        boolean added = receiver.offer(element);
        if (detector != null) {
            detector.recordOffer(receiver, receiver.getClass().getName(), added);
        }
        return added;
    }

    /**
     * Weaves {@code BlockingQueue.offer(Object, long, TimeUnit)}, the timed form.
     *
     * <p>The timed overloads are the ones production code reaches for - an untimed {@code offer}
     * that returns immediately and an untimed {@code poll} that returns {@code null} are the
     * shapes people avoid - and neither was woven (#434). The discarded boolean is the same bug
     * either way.
     *
     * @param receiver the queue
     * @param element  the element to add
     * @param timeout  how long to wait for space
     * @param unit     the unit of {@code timeout}
     * @return whether the element was added
     * @throws InterruptedException if interrupted while waiting
     */
    public static boolean offer(BlockingQueue<Object> receiver, Object element, long timeout,
                                TimeUnit unit) throws InterruptedException {
        BlockingQueueDetector detector = AsyncTestContext.currentBlockingQueueDetector();
        if (detector != null) {
            detector.observeQueue(receiver);
        }
        boolean added = receiver.offer(element, timeout, unit);
        if (detector != null) {
            detector.recordOffer(receiver, receiver.getClass().getName(), added);
        }
        return added;
    }

    /**
     * Weaves the {@code POP} that follows a {@code BlockingQueue.offer} whose result the caller
     * never read.
     *
     * <p>Not an operation of its own. The offer already happened and was recorded by
     * {@link #offer(BlockingQueue, Object)} or its timed form, on this thread, as the instruction
     * before this one: the weaver substitutes this call for the {@code POP} and for nothing else,
     * so adjacency is guaranteed by construction rather than assumed. What this adds is the one
     * fact the return value cannot carry - nobody looked. A {@code false} that was branched on is
     * backpressure working; a {@code false} that was popped is an element dropped on the floor,
     * and the detector counts only the second as a finding (#454).
     *
     * @param added what the offer returned, which is the value the caller discarded
     * @since 1.11.1
     */
    public static void offerResultDiscarded(boolean added) {
        BlockingQueueDetector detector = AsyncTestContext.currentBlockingQueueDetector();
        if (detector != null) {
            detector.recordOfferResultDiscarded(added);
        }
    }

    /**
     * Weaves {@code BlockingQueue.poll(long, TimeUnit)}, the timed form.
     *
     * @param receiver the queue
     * @param timeout  how long to wait for an element
     * @param unit     the unit of {@code timeout}
     * @return the head of the queue, or {@code null} on timeout
     * @throws InterruptedException if interrupted while waiting
     */
    public static Object poll(BlockingQueue<Object> receiver, long timeout, TimeUnit unit)
            throws InterruptedException {
        BlockingQueueDetector detector = AsyncTestContext.currentBlockingQueueDetector();
        if (detector != null) {
            detector.observeQueue(receiver);
        }
        Object taken = receiver.poll(timeout, unit);
        if (detector != null) {
            detector.recordPoll(receiver, receiver.getClass().getName(), taken != null);
        }
        return taken;
    }

    /**
     * Weaves {@code BlockingQueue.poll()}.
     *
     * @param receiver the queue
     * @return the head of the queue, or {@code null} when it was empty
     */
    public static Object poll(BlockingQueue<Object> receiver) {
        BlockingQueueDetector detector = AsyncTestContext.currentBlockingQueueDetector();
        if (detector != null) {
            detector.observeQueue(receiver);
        }
        Object taken = receiver.poll();
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
        BlockingQueueDetector detector = AsyncTestContext.currentBlockingQueueDetector();
        if (detector != null) {
            detector.observeQueue(receiver);
        }
        receiver.put(element);
        if (detector != null) {
            detector.recordPut(receiver, receiver.getClass().getName());
        }
    }
}
