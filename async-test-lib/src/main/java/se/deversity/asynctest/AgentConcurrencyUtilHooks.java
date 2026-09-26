package se.deversity.asynctest;

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import se.deversity.asynctest.diagnostics.BlockingQueueDetector;
import se.deversity.asynctest.diagnostics.CountDownLatchDetector;
import se.deversity.asynctest.diagnostics.HappensBefore;
import se.deversity.asynctest.diagnostics.LatchMisuseDetector;
import se.deversity.asynctest.diagnostics.SemaphoreMisuseDetector;
import se.deversity.asynctest.telemetry.TelemetryRegistry;
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
 * <p>The same rule feeds {@link HappensBefore}: a {@code release} and a {@code countDown} release
 * the primitive before the call, an acquire that took a permit and an {@code await} that reached
 * zero acquire it after, which are the edges the {@code java.util.concurrent} memory consistency
 * effects name. The queue hand-offs reach the model through the ownership events. The
 * {@code CompletableFuture}, {@code Future}, {@code ExecutorService} and {@code Exchanger} hooks
 * exist for the model alone (#741), and follow the same rule: release before the call, withdraw
 * a release the call refused, acquire after a call that returned.
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
        HappensBefore.acquire(receiver);
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
            HappensBefore.acquire(receiver);
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
        HappensBefore.release(receiver);
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
        HappensBefore.release(receiver);
        receiver.release(permits);
    }

    private static void recordAcquired(Semaphore receiver, int permits) {
        HappensBefore.acquire(receiver);
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
        HappensBefore.release(receiver);
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
        HappensBefore.acquire(receiver);
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
        if (reachedZero) {
            HappensBefore.acquire(receiver);
        }
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
        // Before the offer, so the take that removes the element drains after it (#630).
        TelemetryRegistry.ownershipOffered(element, receiver);
        boolean added = receiver.offer(element);
        if (!added) {
            TelemetryRegistry.ownershipRefused(element, receiver);
        }
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
        TelemetryRegistry.ownershipOffered(element, receiver);
        boolean added = false;
        try {
            added = receiver.offer(element, timeout, unit);
        } finally {
            if (!added) { // refused, or the call threw: nothing was handed over (#742)
                TelemetryRegistry.ownershipRefused(element, receiver);
            }
        }
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
        TelemetryRegistry.ownershipTaken(taken, receiver);
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
        TelemetryRegistry.ownershipTaken(taken, receiver);
        return taken;
    }

    /**
     * Weaves {@code BlockingQueue.take()}.
     *
     * <p>A take hands the head to this thread exactly as {@code poll} does, so it is reported the
     * same way, with the queue as container (#664). Unwoven, it left the offer that put the element
     * there on record, and an element put back by another thread through a call nothing observes
     * then named the first offerer as owner of its next generation.
     *
     * @param receiver the queue
     * @return the head of the queue
     * @throws InterruptedException if interrupted while waiting
     * @since 1.12.2
     */
    public static Object take(BlockingQueue<Object> receiver) throws InterruptedException {
        BlockingQueueDetector detector = AsyncTestContext.currentBlockingQueueDetector();
        if (detector != null) {
            detector.observeQueue(receiver);
        }
        Object taken = receiver.take();
        if (detector != null) {
            detector.recordTake(receiver, receiver.getClass().getName());
        }
        TelemetryRegistry.ownershipTaken(taken, receiver);
        return taken;
    }

    /**
     * Weaves {@code BlockingQueue.drainTo(Collection)}.
     *
     * <p>The elements move into {@code target} without being named one by one, so the drain is
     * reported as a whole: every offer into this queue recorded before it is stale (#664).
     *
     * @param receiver the queue
     * @param target   the collection the elements move to
     * @return how many elements moved
     * @since 1.12.2
     */
    public static int drainTo(BlockingQueue<Object> receiver, Collection<Object> target) {
        BlockingQueueDetector detector = AsyncTestContext.currentBlockingQueueDetector();
        if (detector != null) {
            detector.observeQueue(receiver);
        }
        int drained = receiver.drainTo(target);
        TelemetryRegistry.ownershipDrained(receiver);
        return drained;
    }

    /**
     * Weaves {@code BlockingQueue.drainTo(Collection, int)}, reported like {@link #drainTo(BlockingQueue, Collection)}.
     *
     * @param receiver    the queue
     * @param target      the collection the elements move to
     * @param maxElements the most elements to move
     * @return how many elements moved
     * @since 1.12.2
     */
    public static int drainTo(BlockingQueue<Object> receiver, Collection<Object> target,
                              int maxElements) {
        BlockingQueueDetector detector = AsyncTestContext.currentBlockingQueueDetector();
        if (detector != null) {
            detector.observeQueue(receiver);
        }
        int drained = receiver.drainTo(target, maxElements);
        TelemetryRegistry.ownershipDrained(receiver);
        return drained;
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
        TelemetryRegistry.ownershipOffered(element, receiver);
        boolean accepted = false;
        try {
            receiver.put(element);
            accepted = true;
        } finally {
            if (!accepted) { // the call threw: nothing was handed over (#742)
                TelemetryRegistry.ownershipRefused(element, receiver);
            }
        }
        if (detector != null) {
            detector.recordPut(receiver, receiver.getClass().getName());
        }
    }

    // ---- CompletableFuture, Future, ExecutorService and Exchanger hand-offs (#741) -------------
    //
    // Each of these hands what one thread did to another at a point the java.util.concurrent
    // package javadoc names: a completion before a join or get that observes it, a submission
    // before the task runs and the task before the get that returns, each side of an exchange
    // before its partner's return. The hooks feed exactly those points to HappensBefore: release
    // before the call, acquire after it returned, and a release the call refused withdrawn.

    /**
     * Weaves {@code CompletableFuture.complete(Object)}: the completing thread's accesses are
     * published to whoever observes the completion (#741). A future that was already complete
     * refuses the value, and the release is withdrawn.
     *
     * @param receiver the future
     * @param value    the value to complete it with
     * @return whether this call completed the future
     * @since 1.12.3
     */
    public static boolean complete(CompletableFuture<Object> receiver, @Nullable Object value) {
        HappensBefore.release(receiver);
        boolean completed = false;
        try {
            completed = receiver.complete(value);
        } finally {
            if (!completed) { // already complete, or the call threw: nothing was published
                HappensBefore.retract(receiver);
            }
        }
        return completed;
    }

    /**
     * Weaves {@code CompletableFuture.completeExceptionally(Throwable)}, a completion like
     * {@link #complete}: a join that throws with the failure observed it too (#741).
     *
     * @param receiver the future
     * @param failure  the exception to complete it with
     * @return whether this call completed the future
     * @since 1.12.3
     */
    public static boolean completeExceptionally(CompletableFuture<Object> receiver,
                                                Throwable failure) {
        HappensBefore.release(receiver);
        boolean completed = false;
        try {
            completed = receiver.completeExceptionally(failure);
        } finally {
            if (!completed) {
                HappensBefore.retract(receiver);
            }
        }
        return completed;
    }

    /**
     * Weaves {@code CompletableFuture.obtrudeValue(Object)}, which completes the future whatever
     * it held (#741).
     *
     * @param receiver the future
     * @param value    the value it now holds
     * @since 1.12.3
     */
    public static void obtrudeValue(CompletableFuture<Object> receiver, @Nullable Object value) {
        HappensBefore.release(receiver);
        boolean obtruded = false;
        try {
            receiver.obtrudeValue(value);
            obtruded = true;
        } finally {
            if (!obtruded) {
                HappensBefore.retract(receiver);
            }
        }
    }

    /**
     * Weaves {@code CompletableFuture.obtrudeException(Throwable)}, which completes the future
     * exceptionally whatever it held (#741).
     *
     * @param receiver the future
     * @param failure  the exception it now holds
     * @since 1.12.3
     */
    public static void obtrudeException(CompletableFuture<Object> receiver, Throwable failure) {
        HappensBefore.release(receiver);
        boolean obtruded = false;
        try {
            receiver.obtrudeException(failure);
            obtruded = true;
        } finally {
            if (!obtruded) {
                HappensBefore.retract(receiver);
            }
        }
    }

    /**
     * Weaves {@code CompletableFuture.join()}: a join that returned, or threw the exception the
     * future completed with, observed the completion and is ordered after it (#741).
     *
     * @param receiver the future
     * @return its value
     * @since 1.12.3
     */
    public static @Nullable Object join(CompletableFuture<Object> receiver) {
        Object value;
        try {
            value = receiver.join();
        } catch (CompletionException failed) {
            completionObserved(receiver);
            throw failed;
        }
        completionObserved(receiver);
        return value;
    }

    /**
     * Weaves {@code Future.get()}: a get that returned, or threw the task's exception, observed
     * the completion and is ordered after it (#741). Matches a {@code CompletableFuture.get} as
     * well, which is the same method.
     *
     * @param receiver the future
     * @return its value
     * @throws InterruptedException if interrupted while waiting
     * @throws ExecutionException   if the task completed exceptionally
     * @since 1.12.3
     */
    public static @Nullable Object get(Future<Object> receiver)
            throws InterruptedException, ExecutionException {
        Object value;
        try {
            value = receiver.get();
        } catch (ExecutionException failed) {
            completionObserved(receiver);
            throw failed;
        }
        completionObserved(receiver);
        return value;
    }

    /**
     * Weaves {@code Future.get(long, TimeUnit)}, as {@link #get(Future)}; a get that timed out
     * observed nothing.
     *
     * @param receiver the future
     * @param timeout  how long to wait
     * @param unit     the unit of {@code timeout}
     * @return its value
     * @throws InterruptedException if interrupted while waiting
     * @throws ExecutionException   if the task completed exceptionally
     * @throws TimeoutException     if the wait timed out
     * @since 1.12.3
     */
    public static @Nullable Object get(Future<Object> receiver, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        Object value;
        try {
            value = receiver.get(timeout, unit);
        } catch (ExecutionException failed) {
            completionObserved(receiver);
            throw failed;
        }
        completionObserved(receiver);
        return value;
    }

    /**
     * Weaves {@code CompletableFuture.supplyAsync(Supplier)}: the supplier runs as a
     * {@link HandedTask}, so it starts ordered after this call and a join on this thread is
     * ordered after it finished (#741). The wrapper is internal to the future and never seen.
     *
     * @param supplier the value's supplier
     * @return the new future
     * @since 1.12.3
     */
    public static CompletableFuture<Object> supplyAsync(Supplier<Object> supplier) {
        // The NullPointerException the call itself throws for a missing function.
        HandedTask handed = new HandedTask(Objects.requireNonNull(supplier));
        return Submissions.remember(CompletableFuture.supplyAsync(handed), handed);
    }

    /**
     * Weaves {@code CompletableFuture.supplyAsync(Supplier, Executor)}, as
     * {@link #supplyAsync(Supplier)}.
     *
     * @param supplier the value's supplier
     * @param executor the executor to run it on
     * @return the new future
     * @since 1.12.3
     */
    public static CompletableFuture<Object> supplyAsync(Supplier<Object> supplier,
                                                        Executor executor) {
        // The NullPointerException the call itself throws for a missing function.
        HandedTask handed = new HandedTask(Objects.requireNonNull(supplier));
        return Submissions.remember(CompletableFuture.supplyAsync(handed, executor), handed);
    }

    /**
     * Weaves {@code CompletableFuture.runAsync(Runnable)}, as {@link #supplyAsync(Supplier)}.
     *
     * @param task the work to run asynchronously, not null; it is wrapped, so its start is ordered
     *             after this call
     * @return the new future
     * @since 1.12.3
     */
    public static CompletableFuture<Void> runAsync(Runnable task) {
        // The NullPointerException the call itself throws for a missing function.
        HandedTask handed = new HandedTask(Objects.requireNonNull(task));
        return Submissions.remember(CompletableFuture.runAsync(handed), handed);
    }

    /**
     * Weaves {@code CompletableFuture.runAsync(Runnable, Executor)}, as
     * {@link #supplyAsync(Supplier)}.
     *
     * @param task     the work to run on {@code executor}, not null; it is wrapped, so its start is
     *                 ordered after this call
     * @param executor the executor to run it on
     * @return the new future
     * @since 1.12.3
     */
    public static CompletableFuture<Void> runAsync(Runnable task, Executor executor) {
        // The NullPointerException the call itself throws for a missing function.
        HandedTask handed = new HandedTask(Objects.requireNonNull(task));
        return Submissions.remember(CompletableFuture.runAsync(handed, executor), handed);
    }

    /**
     * Weaves {@code ExecutorService.submit(Callable)}: submitted to a {@code java.util.concurrent}
     * executor, the task runs as a {@link HandedTask}, so it starts ordered after this call and a
     * get on this thread is ordered after it finished (#741).
     *
     * <p>Only the JDK's own executors get the wrapper, because only there is it invisible: they
     * wrap every submitted task in a future of their own, and nothing a caller can reach returns
     * the task. A user's executor may override {@code newTaskFor} and inspect the task, and a
     * {@code ForkJoinTask} is run differently from any other, so both keep the task as it was.
     *
     * @param receiver the executor
     * @param task     the work submitted, not null; on a JDK executor it is wrapped, so its run is
     *                 ordered after this call and a get after its end
     * @return the task's future
     * @since 1.12.3
     */
    public static Future<Object> submit(ExecutorService receiver, Callable<Object> task) {
        if (!wrapsInvisibly(receiver, task)) {
            return receiver.submit(task);
        }
        HandedTask handed = new HandedTask(task);
        return Submissions.remember(receiver.submit((Callable<Object>) handed), handed);
    }

    /**
     * Weaves {@code ExecutorService.submit(Runnable)}, as {@link #submit(ExecutorService, Callable)}.
     *
     * @param receiver the executor
     * @param task     the work submitted, not null; on a JDK executor it is wrapped, as in the
     *                 {@code Callable} form
     * @return the task's future
     * @since 1.12.3
     */
    public static Future<?> submit(ExecutorService receiver, Runnable task) {
        if (!wrapsInvisibly(receiver, task)) {
            return receiver.submit(task);
        }
        HandedTask handed = new HandedTask(task);
        return Submissions.remember(receiver.submit((Runnable) handed), handed);
    }

    /**
     * Weaves {@code ExecutorService.submit(Runnable, Object)}, as
     * {@link #submit(ExecutorService, Callable)}.
     *
     * @param receiver the executor
     * @param task     the work submitted, not null; on a JDK executor it is wrapped, as in the
     *                 {@code Callable} form
     * @param result   what the future returns
     * @return the task's future
     * @since 1.12.3
     */
    public static Future<Object> submit(ExecutorService receiver, Runnable task,
                                        @Nullable Object result) {
        if (!wrapsInvisibly(receiver, task)) {
            return receiver.submit(task, result);
        }
        HandedTask handed = new HandedTask(task);
        return Submissions.remember(receiver.submit((Runnable) handed, result), handed);
    }

    /**
     * Weaves {@code Exchanger.exchange(Object)}: this thread's accesses are published through the
     * object it hands over, and it receives what its partner published through the one it got
     * back (#741). An exchange that threw handed nothing over, and its release is withdrawn.
     *
     * @param receiver the exchanger
     * @param item     the object handed over
     * @return the partner's object
     * @throws InterruptedException if interrupted while waiting
     * @since 1.12.3
     */
    public static @Nullable Object exchange(Exchanger<Object> receiver, @Nullable Object item)
            throws InterruptedException {
        HappensBefore.release(item);
        Object received;
        boolean exchanged = false;
        try {
            received = receiver.exchange(item);
            exchanged = true;
        } finally {
            if (!exchanged) {
                HappensBefore.retract(item);
            }
        }
        HappensBefore.acquire(received);
        return received;
    }

    /**
     * Weaves {@code Exchanger.exchange(Object, long, TimeUnit)}, as
     * {@link #exchange(Exchanger, Object)}; one that timed out handed nothing over.
     *
     * @param receiver the exchanger
     * @param item     the object handed over
     * @param timeout  how long to wait for a partner
     * @param unit     the unit of {@code timeout}
     * @return the partner's object
     * @throws InterruptedException if interrupted while waiting
     * @throws TimeoutException     if no partner arrived in time
     * @since 1.12.3
     */
    public static @Nullable Object exchange(Exchanger<Object> receiver, @Nullable Object item,
                                            long timeout, TimeUnit unit)
            throws InterruptedException, TimeoutException {
        HappensBefore.release(item);
        Object received;
        boolean exchanged = false;
        try {
            received = receiver.exchange(item, timeout, unit);
            exchanged = true;
        } finally {
            if (!exchanged) {
                HappensBefore.retract(item);
            }
        }
        HappensBefore.acquire(received);
        return received;
    }

    /**
     * {@return whether wrapping {@code task} for {@code executor} changes nothing it can observe}
     *
     * <p>{@link Class#getName()} is cached, so this allocates nothing.
     */
    private static boolean wrapsInvisibly(ExecutorService executor, @Nullable Object task) {
        return task != null && !(task instanceof ForkJoinTask)
                && executor.getClass().getName().startsWith("java.util.concurrent.");
    }

    /**
     * The acquire half of a completion this thread observed: what a woven {@code complete}
     * released to {@code future}, and what the task this thread submitted behind it did.
     *
     * <p>Only a {@code CompletableFuture} is completed through a hook, so only one is looked up:
     * the lookup costs a key, and an executor's future would pay it on every get for nothing.
     */
    private static void completionObserved(Object future) {
        if (future instanceof CompletableFuture) {
            HappensBefore.acquire(future);
        }
        HandedTask handed = Submissions.forget(future);
        if (handed != null) {
            HappensBefore.receive(handed.finished);
        }
    }

    /**
     * A submitted task carrying the submitter's clock to the thread that runs it, and that
     * thread's clock back to whoever gets the result (#741).
     *
     * <p>The executor hands the task over in JDK code the agent does not weave, so neither end
     * has an object to name at the moment it happens; the wrapper is that object. It takes the
     * submitter's stamp when it is made, which the running thread receives before the task, and
     * takes the running thread's stamp when the task ends, before the executor completes the
     * future, so a get that returned finds it. It also carries a token the telemetry bridge
     * attributes the task by: the submitter publishes it when the wrapper is made, and the running
     * thread publishes it around the task, so a task a worker submitted is judged as part of the
     * worker's run while it runs, and the pool thread is not before or after (#745). One object of
     * four fields per submission; the stamps are snapshots the threads already hold.
     */
    static final class HandedTask implements Runnable, Callable<Object>, Supplier<Object> {

        /** Where task tokens come from; unique in the JVM, which is what the bridge matches on. */
        private static final AtomicLong TOKENS = new AtomicLong();

        private final Object task;
        private final HappensBefore.Stamp submitted;
        private final long token;
        private volatile HappensBefore.@Nullable Stamp finished;

        HandedTask(Object task) {
            this.task = task;
            this.submitted = HappensBefore.handOff();
            this.token = TOKENS.incrementAndGet();
            TelemetryRegistry.taskSubmitted(token);
        }

        @Override
        public void run() {
            HappensBefore.receive(submitted);
            TelemetryRegistry.taskStarted(token);
            try {
                ((Runnable) task).run();
            } finally {
                finished = HappensBefore.handOff();
                TelemetryRegistry.taskEnded();
            }
        }

        @Override
        public @Nullable Object call() throws Exception {
            HappensBefore.receive(submitted);
            TelemetryRegistry.taskStarted(token);
            try {
                return ((Callable<?>) task).call();
            } finally {
                finished = HappensBefore.handOff();
                TelemetryRegistry.taskEnded();
            }
        }

        @Override
        public @Nullable Object get() {
            HappensBefore.receive(submitted);
            TelemetryRegistry.taskStarted(token);
            try {
                return ((Supplier<?>) task).get();
            } finally {
                finished = HappensBefore.handOff();
                TelemetryRegistry.taskEnded();
            }
        }

        /** The task's own, so a future that prints its task prints the caller's. */
        @Override
        public String toString() {
            return task.toString();
        }
    }

    /**
     * The futures this thread submitted most recently, each with its {@link HandedTask}, so a
     * get on this thread can find what the task published (#741).
     *
     * <p>A future the JDK returns cannot carry the task's clock, and a map keyed by every future
     * would cost an entry per submission. What a get needs is almost always a future its own
     * thread submitted, so each thread keeps its last {@link #KEPT} and a get forgets the one it
     * found. A get on another thread, or of a future pushed out by later submissions, finds
     * nothing and orders nothing, which is the answer the model had before.
     */
    static final class Submissions {

        /** How many submissions a thread remembers. */
        static final int KEPT = 16;

        private static final ThreadLocal<Submissions> MINE = ThreadLocal.withInitial(Submissions::new);

        private final Object[] futures = new Object[KEPT];
        private final HandedTask[] tasks = new HandedTask[KEPT];
        private int next;

        /** {@return {@code future}, remembered on this thread with the task behind it} */
        static <F> F remember(F future, HandedTask task) {
            Submissions mine = MINE.get();
            mine.futures[mine.next] = future;
            mine.tasks[mine.next] = task;
            mine.next = (mine.next + 1) % KEPT;
            return future;
        }

        /** {@return the task behind {@code future}, forgotten, or {@code null} if not remembered} */
        @SuppressWarnings("ReferenceEquality") // the same future, not an equal one
        static @Nullable HandedTask forget(Object future) {
            Submissions mine = MINE.get();
            for (int i = 0; i < KEPT; i++) {
                if (mine.futures[i] == future) { // NOPMD CompareObjectsWithEquals - identity
                    HandedTask task = mine.tasks[i];
                    mine.futures[i] = null;
                    mine.tasks[i] = null;
                    return task;
                }
            }
            return null;
        }
    }
}
