package se.deversity.asynctest;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collector;
import java.util.function.Predicate;
import java.util.function.IntFunction;
import java.util.function.BinaryOperator;
import java.util.Optional;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.DoubleStream;
import java.util.stream.LongStream;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.function.IntToLongFunction;
import java.util.function.IntToDoubleFunction;
import java.util.function.LongToIntFunction;
import java.util.function.LongToDoubleFunction;
import java.util.function.DoubleToIntFunction;
import java.util.function.DoubleToLongFunction;
import java.util.stream.BaseStream;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.OptionalDouble;
import java.util.IntSummaryStatistics;
import java.util.LongSummaryStatistics;
import java.util.DoubleSummaryStatistics;
import java.util.function.IntUnaryOperator;
import java.util.function.IntPredicate;
import java.util.function.IntBinaryOperator;
import java.util.function.ObjIntConsumer;
import java.util.function.ToIntFunction;
import java.util.function.LongUnaryOperator;
import java.util.function.LongPredicate;
import java.util.function.LongFunction;
import java.util.function.LongBinaryOperator;
import java.util.function.ObjLongConsumer;
import java.util.function.ToLongFunction;
import java.util.function.DoubleUnaryOperator;
import java.util.function.DoublePredicate;
import java.util.function.DoubleFunction;
import java.util.function.DoubleBinaryOperator;
import java.util.function.ObjDoubleConsumer;
import java.util.function.ToDoubleFunction;
import java.util.function.DoubleConsumer;
import java.util.function.LongConsumer;
import java.util.function.IntConsumer;
import java.util.Set;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.PrimitiveIterator;
import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Phaser;
import java.util.concurrent.locks.ReentrantLock;
import java.lang.reflect.Field;
import java.util.concurrent.Exchanger;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import se.deversity.asynctest.diagnostics.CompletableFutureObtrudeDetector;
import se.deversity.asynctest.diagnostics.ThreadPoolMonitor;
import se.deversity.asynctest.diagnostics.ABAProblemDetector;
import se.deversity.asynctest.diagnostics.BlockingQueueDetector;
import se.deversity.asynctest.diagnostics.CompletableFutureBlockingCallbackDetector;
import se.deversity.asynctest.diagnostics.CompletableFutureCommonPoolBlockingDetector;
import se.deversity.asynctest.diagnostics.CompletableFutureExceptionDetector;
import se.deversity.asynctest.diagnostics.CountDownLatchDetector;
import se.deversity.asynctest.diagnostics.ExecutorShutdownDetector;
import se.deversity.asynctest.diagnostics.FutureIgnoredDetector;
import se.deversity.asynctest.diagnostics.HappensBefore;
import se.deversity.asynctest.diagnostics.LatchMisuseDetector;
import se.deversity.asynctest.diagnostics.BlockingOnNonBlockingThreadDetector;
import se.deversity.asynctest.diagnostics.AtomicNonAtomicUpdateDetector;
import se.deversity.asynctest.diagnostics.ExecutorDeadlockDetector;
import se.deversity.asynctest.diagnostics.MdcContextLeakDetector;
import se.deversity.asynctest.diagnostics.ProcessStreamStarvationDetector;
import se.deversity.asynctest.diagnostics.ThreadLocalMonitor;
import se.deversity.asynctest.diagnostics.SemaphoreMisuseDetector;
import se.deversity.asynctest.telemetry.TelemetryRegistry;
import se.deversity.asynctest.telemetry.ControlledSchedule;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.asynctest.AgentHandOffHooks.Handed;
import se.deversity.asynctest.AgentHandOffHooks.HandedAll;
import se.deversity.asynctest.AgentHandOffHooks.HandedStage;
import se.deversity.asynctest.AgentHandOffHooks.HandedTask;
import se.deversity.asynctest.AgentHandOffHooks.StageBiFunction;
import se.deversity.asynctest.AgentHandOffHooks.StageFunction;
import se.deversity.asynctest.AgentHandOffHooks.Submissions;

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
@AIContract(reason = "Called from bytecode the agent rewrites: method names and erased signatures here are matched by CollectionAccessWeaver.CONCURRENCY_ENTRIES and cannot change independently of it. Every hook must perform the original operation and propagate its exceptions unchanged, InterruptedException included - these types throw it as a matter of course and swallowing one would change the interruption semantics of the code under test. Record after acquiring and before releasing, the containment rule AgentLockHooks documents, and record nothing when the underlying call throws, except that a submit or execute the executor refuses records the RejectedExecutionException with ThreadPoolMonitor before rethrowing that same exception (#1055). offer, poll and the timed await must record their actual return value: the boolean a caller discards is the whole bug these detectors report. The observe* call must stay ahead of the operation: it is where LatchMisuseDetector and BlockingQueueDetector learn a subject exists at all, and the latch's starting count is only readable before this call decrements it. offerResultDiscarded is not an operation: the weaver substitutes it for the POP that follows an offer whose boolean the caller never read, so it is always the instruction after one of the offer hooks on the same thread, and it must stay that way - the detector correlates it to the offer recorded immediately before, and that correlation is exact only because the weaver emits it in place of the POP and nowhere else.")
public final class AgentConcurrencyUtilHooks {

    private AgentConcurrencyUtilHooks() {
    }

    /**
     * {@return the calling thread's {@link ABAProblemDetector} view of {@code atomic}, or
     * {@code null} when no test with that detector runs on this thread} (#817)
     *
     * <p>Not a substitution: the {@code AtomicReference} hooks in {@link TelemetryRegistry} ask it
     * before each woven operation, and run the operation through the answer when there is one.
     *
     * @param atomic the atomic a woven call site invoked
     * @since 1.12.3
     */
    public static ABAProblemDetector.@Nullable AgentSlot abaSlot(Object atomic) {
        ControlledSchedule.point();
        ABAProblemDetector detector = AsyncTestContext.currentABAProblemDetector();
        return detector == null ? null : detector.agentSlot(atomic);
    }

    /**
     * {@return the calling thread's {@link ABAProblemDetector} view of the reference field
     * {@code selector} reaches inside {@code holder}, or {@code null} when no test with that
     * detector runs on this thread} (#817)
     *
     * <p>For the {@code AtomicReferenceFieldUpdater} and {@code VarHandle} hooks in
     * {@link TelemetryRegistry}, which perform their operation under the view's monitor.
     *
     * @param holder   the object whose field is the slot
     * @param selector the updater or handle the call site invoked
     * @since 1.12.4
     */
    public static ABAProblemDetector.@Nullable AgentSlot abaSlot(Object holder, Object selector) {
        ControlledSchedule.point();
        ABAProblemDetector detector = AsyncTestContext.currentABAProblemDetector();
        return detector == null ? null : detector.agentSlot(holder, selector);
    }

    /**
     * {@return the calling thread's {@link ABAProblemDetector} view of element {@code index} of
     * {@code array}, or {@code null} when no test with that detector runs on this thread} (#817)
     *
     * @param array the {@code AtomicReferenceArray}, or the array a {@code VarHandle} reaches
     * @param index the element's index
     * @since 1.12.4
     */
    public static ABAProblemDetector.@Nullable AgentSlot abaSlot(Object array, int index) {
        ControlledSchedule.point();
        ABAProblemDetector detector = AsyncTestContext.currentABAProblemDetector();
        return detector == null ? null : detector.agentSlot(array, index);
    }

    /**
     * Weaves {@code Semaphore.acquire()}.
     *
     * @param receiver the semaphore
     * @throws InterruptedException if interrupted while waiting
     */
    public static void acquire(Semaphore receiver) throws InterruptedException {
        ControlledSchedule.point();
        receiver.acquire();
        recordAcquired(receiver, 1);
    }

    /**
     * Weaves {@code Semaphore.tryAcquire()}.
     *
     * @param receiver the semaphore
     * @return whether a permit was taken
     */
    public static boolean tryAcquire(Semaphore receiver) {
        ControlledSchedule.point();
        boolean acquired = receiver.tryAcquire();
        if (acquired) {
            recordAcquired(receiver, 1);
        }
        return acquired;
    }

    /**
     * The tasks a woven {@link #futureTaskRun} is inside right now. A get on another thread can
     * return between the task completing and the run returning, and waits out that window in
     * {@link #awaitDirectRun} so that it acquires what the run released (#1307).
     */
    private static final Set<Object> RUNNING_DIRECTLY = ConcurrentHashMap.newKeySet();

    /**
     * Whether any {@code FutureTask} was ever run through {@link #futureTaskRun}. Every executor
     * future is a {@code FutureTask} too, and until this is set a get on one looks nothing up.
     */
    private static volatile boolean futureTaskRunDirectly;

    /** How long a get waits for a direct run it observed complete to return and release. */
    private static final long DIRECT_RUN_RETURN_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

    /**
     * Weaves {@code FutureTask.run()} called by the code under test, the Memoizer idiom, where
     * no executor wraps the task: when the run ends, what the callable did is released to the
     * task, and a woven get that observed the completion acquires it (#1307).
     *
     * @param receiver the task the caller runs on its own thread
     */
    public static void futureTaskRun(FutureTask<Object> receiver) {
        ControlledSchedule.point();
        futureTaskRunDirectly = true;
        RUNNING_DIRECTLY.add(receiver);
        try {
            receiver.run();
        } finally {
            HappensBefore.release(receiver);
            RUNNING_DIRECTLY.remove(receiver);
        }
    }

    /**
     * Waits, briefly, for a direct run of {@code task} that has completed it to release: the
     * runner is between the task's completion and the end of {@link #futureTaskRun}, with no
     * scheduling point in between. Bounded, so a task that gets itself costs a pause, not a hang.
     */
    private static void awaitDirectRun(Object task) {
        if (!RUNNING_DIRECTLY.contains(task)) {
            return;
        }
        long deadline = System.nanoTime() + DIRECT_RUN_RETURN_NANOS;
        while (RUNNING_DIRECTLY.contains(task) && System.nanoTime() - deadline < 0) {
            Thread.onSpinWait();
        }
    }

    /**
     * Weaves {@code Semaphore.release()}.
     *
     * @param receiver the semaphore
     */
    public static void release(Semaphore receiver) {
        ControlledSchedule.point();
        recordReleasing(receiver, 1);
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
        ControlledSchedule.point();
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
        ControlledSchedule.point();
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
        ControlledSchedule.point();
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
        ControlledSchedule.point();
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
        ControlledSchedule.point();
        recordReleasing(receiver, permits);
        HappensBefore.release(receiver);
        receiver.release(permits);
    }

    /**
     * Records permits taken. On a worker they are the run's own; on a thread that only works
     * for the run, a pool thread with a handed task or a thread the body started, they go to
     * the helper count, which is never a finding by itself.
     */
    private static void recordAcquired(Semaphore receiver, int permits) {
        HappensBefore.acquire(receiver);
        SemaphoreMisuseDetector detector = AsyncTestContext.currentSemaphoreMisuseDetector();
        if (detector != null) {
            for (int i = 0; i < permits; i++) {
                detector.recordAcquire(receiver, receiver.getClass().getName());
            }
            return;
        }
        detector = AsyncTestContext.helperSemaphoreMisuseDetector();
        if (detector != null) {
            for (int i = 0; i < permits; i++) {
                detector.recordHelperAcquire(receiver, receiver.getClass().getName());
            }
        }
    }

    /**
     * Records permits about to be returned, the way {@link #recordAcquired} records them taken.
     * A release on a helper thread used to be dropped, so a worker waiting on a signal that a
     * pool task or a started thread sent was reported as leaking the permit it took.
     */
    private static void recordReleasing(Semaphore receiver, int permits) {
        SemaphoreMisuseDetector detector = AsyncTestContext.currentSemaphoreMisuseDetector();
        if (detector != null) {
            for (int i = 0; i < permits; i++) {
                detector.recordRelease(receiver, receiver.getClass().getName());
            }
            return;
        }
        detector = AsyncTestContext.helperSemaphoreMisuseDetector();
        if (detector != null) {
            for (int i = 0; i < permits; i++) {
                detector.recordHelperRelease(receiver, receiver.getClass().getName());
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
        ControlledSchedule.point();
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
        ControlledSchedule.point();
        if (receiver.getCount() > 0) {
            blocking("CountDownLatch.await", false);
        }
        LatchMisuseDetector misuse = AsyncTestContext.currentLatchMisuseDetector();
        // A thread the run handed work to may outlive it, so its await counts once it is back.
        boolean helper = misuse != null && AsyncTestContext.notAWorker();
        if (misuse != null) {
            misuse.observeLatch(receiver);
            if (!helper) {
                misuse.recordAwait(receiver);
            }
        }
        receiver.await();
        HappensBefore.acquire(receiver);
        // Reached only at zero, which is what tells LatchMisuseDetector that countdowns it never
        // saw happened in unwoven code rather than not at all (#499).
        if (misuse != null) {
            if (helper) {
                misuse.recordAwait(receiver);
            }
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
        ControlledSchedule.point();
        LatchMisuseDetector misuse = AsyncTestContext.currentLatchMisuseDetector();
        // As in await(): a helper thread's await is recorded once it has come back (#1339).
        boolean helper = misuse != null && AsyncTestContext.notAWorker();
        if (misuse != null) {
            misuse.observeLatch(receiver);
            if (!helper) {
                misuse.recordAwait(receiver);
            }
        }
        boolean reachedZero = receiver.await(timeout, unit);
        if (reachedZero) {
            HappensBefore.acquire(receiver);
        }
        if (misuse != null && helper) {
            misuse.recordAwait(receiver);
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
     * {@code CyclicBarrier}'s private {@code generation} field, or {@code null} until it has been
     * opened for reading, which needs {@code java.util.concurrent} open to this library. The agent
     * opens it under collection weaving, the mode that weaves the barrier hooks, before the first
     * woven class runs (#1246). Set before {@link #barrierLockField}, so a non-null lock field
     * means this one is set too.
     */
    private static volatile @Nullable Field barrierGenerationField;

    /** {@code CyclicBarrier}'s private {@code lock} field, or {@code null} until it is readable. */
    private static volatile @Nullable Field barrierLockField;

    /** {@return the barrier's lock field, opened for reading now if it can be, or {@code null}} */
    private static @Nullable Field resolveBarrierFields() {
        try {
            Field lock = CyclicBarrier.class.getDeclaredField("lock");
            Field generation = CyclicBarrier.class.getDeclaredField("generation");
            if (lock.getType() != ReentrantLock.class
                    || !lock.trySetAccessible() || !generation.trySetAccessible()) {
                return null;
            }
            barrierGenerationField = generation;
            barrierLockField = lock;
            return lock;
        } catch (ReflectiveOperationException | RuntimeException e) { // NOPMD - a closed package is judged by the plain call
            return null;
        }
    }

    /**
     * Weaves {@code CyclicBarrier.await()} (#1246).
     *
     * <p>The memory model orders every action a party takes before {@code await} before every
     * action any party takes after a normal return from the same generation. The edge is keyed by
     * the barrier's own generation object, read under the barrier's own lock, and the arrival is
     * made inside the same hold: {@code await} takes that lock reentrantly, so no other party can
     * trip the barrier between the read and the arrival, and every release into a generation comes
     * from a party that arrived in it. A party already arriving for the next generation therefore
     * cannot publish into the one a slower party is just returning from. The party that trips the
     * barrier runs the barrier action inside its {@code await}, and the memory model orders every
     * party's actions before {@code await} before the action, and the action before every return
     * (#1267). So the tripping party, seen under the lock as the last to arrive, acquires the
     * generation before its {@code await}, and once that returns 0 it releases again before
     * giving up the lock, which no other party can return without.
     * A broken barrier, a
     * timeout, an interrupt or a {@code reset} returns abnormally, and acquires nothing; the release
     * is withdrawn. Where the fields cannot be read the call is made unchanged and orders nothing.
     *
     * @param receiver the barrier
     * @return the arrival index {@code await} returned
     * @throws InterruptedException   if interrupted while waiting
     * @throws BrokenBarrierException if the barrier broke
     */
    public static int await(CyclicBarrier receiver) throws InterruptedException, BrokenBarrierException {
        ControlledSchedule.point();
        blocking("CyclicBarrier.await", false);
        ReentrantLock lock = barrierLock(receiver);
        if (lock == null) {
            return receiver.await();
        }
        Object generation;
        int index;
        lock.lock();
        try {
            generation = barrierGeneration(receiver);
            HappensBefore.release(generation);
            if (trips(receiver)) {
                // Every other party released into this generation under the lock (#1267).
                HappensBefore.acquire(generation);
            }
            try {
                index = receiver.await();
            } catch (InterruptedException | BrokenBarrierException | RuntimeException | Error e) {
                HappensBefore.retract(generation);
                throw e;
            }
            if (index == 0) {
                // The tripping party ran the barrier action after its release above (#1267).
                HappensBefore.release(generation);
            }
        } finally {
            lock.unlock();
        }
        HappensBefore.acquire(generation);
        return index;
    }

    /**
     * Weaves {@code CyclicBarrier.await(long, TimeUnit)}, the same edge as {@link #await(CyclicBarrier)}.
     *
     * @param receiver the barrier
     * @param timeout  how long to wait
     * @param unit     the unit of {@code timeout}
     * @return the arrival index {@code await} returned
     * @throws InterruptedException   if interrupted while waiting
     * @throws BrokenBarrierException if the barrier broke
     * @throws TimeoutException       if the timeout elapsed, which breaks the barrier
     */
    public static int await(CyclicBarrier receiver, long timeout, TimeUnit unit)
            throws InterruptedException, BrokenBarrierException, TimeoutException {
        ControlledSchedule.point();
        blocking("CyclicBarrier.await", false);
        ReentrantLock lock = barrierLock(receiver);
        if (lock == null) {
            return receiver.await(timeout, unit);
        }
        Object generation;
        int index;
        lock.lock();
        try {
            generation = barrierGeneration(receiver);
            HappensBefore.release(generation);
            if (trips(receiver)) {
                // Every other party released into this generation under the lock (#1267).
                HappensBefore.acquire(generation);
            }
            try {
                index = receiver.await(timeout, unit);
            } catch (InterruptedException | BrokenBarrierException | TimeoutException
                     | RuntimeException | Error e) {
                HappensBefore.retract(generation);
                throw e;
            }
            if (index == 0) {
                // The tripping party ran the barrier action after its release above (#1267).
                HappensBefore.release(generation);
            }
        } finally {
            lock.unlock();
        }
        HappensBefore.acquire(generation);
        return index;
    }

    /**
     * {@return whether the calling party, holding the barrier's lock, is the one whose
     * {@code await} trips it and runs its action} Every other party is waiting, the barrier is not
     * broken, and nothing will make this {@code await} throw before it trips: an interrupted
     * thread's would.
     */
    private static boolean trips(CyclicBarrier barrier) {
        return !barrier.isBroken() && !Thread.currentThread().isInterrupted()
                && barrier.getNumberWaiting() == barrier.getParties() - 1;
    }

    /** {@return the barrier's lock, or {@code null} when it cannot be read} */
    private static @Nullable ReentrantLock barrierLock(CyclicBarrier barrier) {
        Field field = barrierLockField;
        if (field == null) {
            field = resolveBarrierFields();
            if (field == null) {
                return null;
            }
        }
        try {
            return (ReentrantLock) field.get(barrier);
        } catch (ReflectiveOperationException | RuntimeException e) { // NOPMD - unreadable orders nothing
            return null;
        }
    }

    /** {@return the generation the barrier is in, read under its lock, or {@code null}} */
    private static @Nullable Object barrierGeneration(CyclicBarrier barrier) {
        Field field = barrierGenerationField;
        if (field == null) {
            return null;
        }
        try {
            return field.get(barrier);
        } catch (ReflectiveOperationException | RuntimeException e) { // NOPMD - unreadable orders nothing
            return null;
        }
    }

    /**
     * Per root phaser, the object {@link HappensBefore} keys each recent phase by (#1265). Weak
     * on the phaser, so a phaser that is collected takes its tokens with it.
     */
    private static final Map<Phaser, PhaseTokens> PHASE_TOKENS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * A token for each of a phaser's last four phases. A registered party cannot be more than one
     * phase behind the phaser, since the phase never advances without its arrival, so four is
     * room to spare; an unregistered waiter that falls further behind finds no token and orders
     * nothing, which keeps a finding rather than hiding one.
     */
    private static final class PhaseTokens {
        private final int[] phases = new int[4];
        private final @Nullable Object[] tokens = new Object[4];

        /** {@return the token of {@code phase}, made now if there is none} */
        synchronized Object token(int phase) {
            int slot = phase & 3;
            Object token = tokens[slot];
            if (token == null || phases[slot] != phase) {
                token = new Object();
                phases[slot] = phase;
                tokens[slot] = token;
            }
            return token;
        }

        /** {@return the token of {@code phase}, or {@code null} when nobody released into it} */
        synchronized @Nullable Object existing(int phase) {
            int slot = phase & 3;
            return phases[slot] == phase ? tokens[slot] : null;
        }
    }

    /**
     * {@return the token of the phase {@code phaser} is in now, or {@code null} once it has
     * terminated} Keyed by the root, whose phase every child of a tiered phaser shares.
     */
    private static @Nullable Object currentPhaseToken(Phaser phaser, int phase) {
        if (phase < 0) {
            return null;
        }
        return PHASE_TOKENS.computeIfAbsent(phaser.getRoot(), root -> new PhaseTokens()).token(phase);
    }

    /** {@return the token of {@code phase} of {@code phaser}'s root, or {@code null}} */
    private static @Nullable Object pastPhaseToken(Phaser phaser, int phase) {
        PhaseTokens tokens = PHASE_TOKENS.get(phaser.getRoot());
        return tokens == null ? null : tokens.existing(phase);
    }

    /**
     * Weaves {@code Phaser.arriveAndAwaitAdvance()} (#1265).
     *
     * <p>The {@code Phaser} javadoc orders every arrival in a phase before every return from
     * waiting for that phase to advance. This party releases into the phase it is in before it
     * arrives, and acquires that phase once the call returns the next one. The phase is read
     * before the call, which is exact for a registered party: the phase cannot advance without
     * its arrival. Where the returned phase says otherwise, an arrival the phaser did not need,
     * the release is withdrawn. A terminated phaser waits for nobody and orders nothing.
     *
     * <p>A tiered phaser is keyed by its root: children advance when the root does, with the
     * root's phase numbers, so arrivals on two children of one root order each other.
     *
     * @param receiver the phaser
     * @return the phase the call returned: the next phase, or a negative one once terminated
     */
    public static int arriveAndAwaitAdvance(Phaser receiver) {
        ControlledSchedule.point();
        int phase = receiver.getPhase();
        Object token = currentPhaseToken(receiver, phase);
        HappensBefore.release(token);
        int next;
        try {
            next = receiver.arriveAndAwaitAdvance();
        } catch (RuntimeException | Error e) {
            HappensBefore.retract(token);
            throw e;
        }
        if (next < 0) {
            HappensBefore.retract(token);
            return next;
        }
        int arrived = (next - 1) & Integer.MAX_VALUE;
        if (arrived != phase) {
            HappensBefore.retract(token);
        }
        HappensBefore.acquire(pastPhaseToken(receiver, arrived));
        return next;
    }

    /**
     * Weaves {@code Phaser.arrive()}: a release into the phase it arrives in, as in
     * {@link #arriveAndAwaitAdvance}, with nothing to acquire, since it does not wait (#1265).
     *
     * @param receiver the phaser
     * @return the arrival phase, or a negative phase once terminated
     */
    public static int arrive(Phaser receiver) {
        ControlledSchedule.point();
        int phase = receiver.getPhase();
        Object token = currentPhaseToken(receiver, phase);
        HappensBefore.release(token);
        int arrived;
        try {
            arrived = receiver.arrive();
        } catch (RuntimeException | Error e) {
            HappensBefore.retract(token);
            throw e;
        }
        if (arrived != phase) {
            HappensBefore.retract(token);
        }
        return arrived;
    }

    /**
     * Weaves {@code Phaser.arriveAndDeregister()}: the same release as {@link #arrive}; the
     * party's last arrival still counts toward the phase it arrives in (#1265).
     *
     * @param receiver the phaser
     * @return the arrival phase, or a negative phase once terminated
     */
    public static int arriveAndDeregister(Phaser receiver) {
        ControlledSchedule.point();
        int phase = receiver.getPhase();
        Object token = currentPhaseToken(receiver, phase);
        HappensBefore.release(token);
        int arrived;
        try {
            arrived = receiver.arriveAndDeregister();
        } catch (RuntimeException | Error e) {
            HappensBefore.retract(token);
            throw e;
        }
        if (arrived != phase) {
            HappensBefore.retract(token);
        }
        return arrived;
    }

    /**
     * Weaves {@code Phaser.awaitAdvance(int)} (#1265): once the phaser is past {@code phase},
     * every arrival in it happened, so the caller acquires it. A return of {@code phase} itself
     * cannot happen for a live phaser, and a negative one means it terminated; neither orders
     * anything. A phase the phaser has not reached yet returns at once and has no token.
     *
     * @param receiver the phaser
     * @param phase    the phase to wait out
     * @return the phase the call returned
     */
    public static int awaitAdvance(Phaser receiver, int phase) {
        ControlledSchedule.point();
        int next = receiver.awaitAdvance(phase);
        acquireAdvanced(receiver, phase, next);
        return next;
    }

    /**
     * Weaves {@code Phaser.awaitAdvanceInterruptibly(int)}, the same edge as
     * {@link #awaitAdvance(Phaser, int)} on a normal return.
     *
     * @param receiver the phaser
     * @param phase    the phase to wait out
     * @return the phase the call returned
     * @throws InterruptedException if interrupted while waiting
     */
    public static int awaitAdvanceInterruptibly(Phaser receiver, int phase)
            throws InterruptedException {
        ControlledSchedule.point();
        int next = receiver.awaitAdvanceInterruptibly(phase);
        acquireAdvanced(receiver, phase, next);
        return next;
    }

    /**
     * Weaves {@code Phaser.awaitAdvanceInterruptibly(int, long, TimeUnit)}, the same edge as
     * {@link #awaitAdvance(Phaser, int)} on a normal return.
     *
     * @param receiver the phaser
     * @param phase    the phase to wait out
     * @param timeout  how long to wait
     * @param unit     the unit of {@code timeout}
     * @return the phase the call returned
     * @throws InterruptedException if interrupted while waiting
     * @throws TimeoutException     if the timeout elapsed first
     */
    public static int awaitAdvanceInterruptibly(Phaser receiver, int phase, long timeout,
                                                TimeUnit unit)
            throws InterruptedException, TimeoutException {
        ControlledSchedule.point();
        int next = receiver.awaitAdvanceInterruptibly(phase, timeout, unit);
        acquireAdvanced(receiver, phase, next);
        return next;
    }

    /** Acquires {@code phase} when the call that waited it out returned a later, live phase. */
    private static void acquireAdvanced(Phaser receiver, int phase, int returned) {
        if (phase >= 0 && returned >= 0 && returned != phase) {
            HappensBefore.acquire(pastPhaseToken(receiver, phase));
        }
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
        ControlledSchedule.point();
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
        ControlledSchedule.point();
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
        ControlledSchedule.point();
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
        ControlledSchedule.point();
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
        ControlledSchedule.point();
        if (receiver.isEmpty()) {
            blocking("BlockingQueue.take", false);
        }
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
     * reported as a whole: every offer into this queue recorded before it is stale (#664). The
     * happens-before model is told per element where the target is a list (#1295).
     *
     * @param receiver the queue
     * @param target   the collection the elements move to
     * @return how many elements moved
     * @since 1.12.2
     */
    public static int drainTo(BlockingQueue<Object> receiver, Collection<Object> target) {
        ControlledSchedule.point();
        BlockingQueueDetector detector = AsyncTestContext.currentBlockingQueueDetector();
        if (detector != null) {
            detector.observeQueue(receiver);
        }
        int drained = receiver.drainTo(target);
        TelemetryRegistry.ownershipDrained(receiver);
        receiveDrained(receiver, target, drained);
        return drained;
    }

    /**
     * The acquire half of a drain (#1295): the calling thread receives what each element's offer
     * published, as a {@code take} of it would have.
     *
     * <p>Only a {@code List} target says which elements moved: a drain appends, so they are its
     * last {@code drained}. Any other target acquires nothing, as every target did before, which
     * leaves the detectors with the answer they had. A target another thread changes during the
     * drain may name the wrong elements; the drain's own contract already leaves that undefined.
     */
    private static void receiveDrained(BlockingQueue<Object> receiver, @Nullable Collection<Object> target,
                                       int drained) {
        if (drained <= 0 || !(target instanceof List<Object> list)
                || !HappensBefore.publishesElements(receiver)) {
            return;
        }
        try {
            int size = list.size();
            for (Iterator<Object> moved = list.listIterator(Math.max(0, size - drained)); moved.hasNext();) {
                HappensBefore.acquire(moved.next());
            }
        } catch (RuntimeException ignored) { // NOPMD - recording never fails the caller
            // A list that cannot be walked now leaves the drain as it was before #1295.
        }
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
        ControlledSchedule.point();
        BlockingQueueDetector detector = AsyncTestContext.currentBlockingQueueDetector();
        if (detector != null) {
            detector.observeQueue(receiver);
        }
        int drained = receiver.drainTo(target, maxElements);
        TelemetryRegistry.ownershipDrained(receiver);
        receiveDrained(receiver, target, drained);
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
        ControlledSchedule.point();
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
        ControlledSchedule.point();
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
        ControlledSchedule.point();
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

    /** Tells the run's obtrude detector: the call is the finding, whether or not it then throws. */
    private static void noteObtrude(CompletableFuture<Object> receiver) {
        CompletableFutureObtrudeDetector detector =
                AsyncTestContext.currentCompletableFutureObtrudeDetector();
        if (detector != null) {
            detector.recordObtrude(receiver, "CompletableFuture", Thread.currentThread());
        }
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
        ControlledSchedule.point();
        noteObtrude(receiver);
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
        ControlledSchedule.point();
        noteObtrude(receiver);
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
        ControlledSchedule.awaitingTask(adoptionBehind(receiver));
        if (!receiver.isDone()) {
            blocking("CompletableFuture.join", true);
        }
        Object siblingPool = siblingWaitBegins(receiver);
        Object value;
        try {
            value = receiver.join();
        } catch (CompletionException failed) {
            completionObserved(receiver);
            throw failed;
        } finally {
            siblingWaitEnds(siblingPool);
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
        ControlledSchedule.awaitingTask(adoptionBehind(receiver));
        if (!receiver.isDone()) {
            blockingOn(receiver);
        }
        Object siblingPool = siblingWaitBegins(receiver);
        Object value;
        try {
            value = receiver.get();
        } catch (ExecutionException failed) {
            completionObserved(receiver);
            failureRead(receiver);
            throw failed;
        } finally {
            siblingWaitEnds(siblingPool);
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
        ControlledSchedule.awaitingTask(adoptionBehind(receiver));
        if (!receiver.isDone()) {
            blockingOn(receiver);
        }
        Object siblingPool = siblingWaitBegins(receiver);
        Object value;
        try {
            value = receiver.get(timeout, unit);
        } catch (ExecutionException failed) {
            completionObserved(receiver);
            failureRead(receiver);
            throw failed;
        } finally {
            siblingWaitEnds(siblingPool);
        }
        completionObserved(receiver);
        return value;
    }

    /**
     * Weaves {@code ProcessBuilder.start()}: the child is started, then recorded on
     * {@code PROCESS_STREAM_STARVATION} with its builder, which alone tells a stderr merged into
     * stdout from a pipe on Linux.
     *
     * @param receiver the builder
     * @return the started child
     * @throws IOException if the child cannot be started
     * @since 1.13.2
     */
    public static Process processBuilderStart(ProcessBuilder receiver) throws IOException {
        ControlledSchedule.point();
        Process process = receiver.start();
        started(receiver, process);
        return process;
    }

    /**
     * Weaves {@code ProcessBuilder.startPipeline(List)}, as {@link #processBuilderStart} for each
     * builder and the child it started.
     *
     * @param builders the builders, in pipeline order
     * @return the started children, in the same order
     * @throws IOException if a child cannot be started
     * @since 1.13.2
     */
    public static List<Process> processBuilderStartPipeline(List<ProcessBuilder> builders)
            throws IOException {
        ControlledSchedule.point();
        List<Process> processes = ProcessBuilder.startPipeline(builders);
        for (int i = 0; i < processes.size() && i < builders.size(); i++) {
            started(builders.get(i), processes.get(i));
        }
        return processes;
    }

    /**
     * Weaves {@code Process.waitFor()}: the wait is recorded on
     * {@code PROCESS_STREAM_STARVATION} with the output pipes no thread had taken yet, then made.
     *
     * @param receiver the child process
     * @return its exit value
     * @throws InterruptedException if interrupted while waiting
     * @since 1.13.2
     */
    public static int processWaitFor(Process receiver) throws InterruptedException {
        ControlledSchedule.point();
        waitingFor(receiver);
        return receiver.waitFor();
    }

    /**
     * Weaves {@code Process.waitFor(long, TimeUnit)}, as {@link #processWaitFor(Process)}: a wait
     * with a timeout still waits on a child that cannot finish.
     *
     * @param receiver the child process
     * @param timeout  how long to wait
     * @param unit     the unit of {@code timeout}
     * @return whether the child exited in time
     * @throws InterruptedException if interrupted while waiting
     * @since 1.13.2
     */
    public static boolean processWaitFor(Process receiver, long timeout, TimeUnit unit)
            throws InterruptedException {
        ControlledSchedule.point();
        waitingFor(receiver);
        return receiver.waitFor(timeout, unit);
    }

    /**
     * Weaves {@code Process.waitFor(Duration)}, as {@link #processWaitFor(Process)}. The method
     * exists from JDK 25 and the library compiles for 21, so it is called through a handle
     * resolved once; on an older JDK no call site can carry this descriptor, and the hook is
     * never reached.
     *
     * @param receiver the child process
     * @param duration how long to wait
     * @return whether the child exited in time
     * @throws InterruptedException if interrupted while waiting
     * @since 1.13.2
     */
    public static boolean processWaitFor(Process receiver, Duration duration)
            throws InterruptedException {
        ControlledSchedule.point();
        waitingFor(receiver);
        MethodHandle waitFor = WAIT_FOR_DURATION;
        if (waitFor == null) {
            throw new UnsupportedOperationException("Process.waitFor(Duration) needs JDK 25");
        }
        try {
            return (boolean) waitFor.invokeExact(receiver, duration);
        } catch (InterruptedException | RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new UndeclaredThrowableException(t);
        }
    }

    /** {@code Process.waitFor(Duration)}, or {@code null} before JDK 25. */
    private static final @Nullable MethodHandle WAIT_FOR_DURATION = waitForDuration();

    private static @Nullable MethodHandle waitForDuration() {
        try {
            return MethodHandles.publicLookup().findVirtual(Process.class, "waitFor",
                    MethodType.methodType(boolean.class, Duration.class));
        } catch (NoSuchMethodException | IllegalAccessException beforeJdk25) {
            return null;
        }
    }

    /**
     * Weaves {@code Process.getInputStream()}: with {@code PROCESS_STREAM_STARVATION} on, the caller
     * gets the child's stdout pipe in a stream that notes who reads it and whether it reached its
     * end, so a pipe taken before a wait but read only after it is told from one read first (#998).
     * Otherwise the pipe itself.
     *
     * @param receiver the child process
     * @return the stream
     * @since 1.13.2
     */
    public static InputStream processInputStream(Process receiver) {
        ControlledSchedule.point();
        return observed(receiver, ProcessStreamStarvationDetector.Stream.STDOUT, receiver.getInputStream());
    }

    /**
     * Weaves {@code Process.getErrorStream()}, as {@link #processInputStream} for stderr.
     *
     * @param receiver the child process
     * @return the stream
     * @since 1.13.2
     */
    public static InputStream processErrorStream(Process receiver) {
        ControlledSchedule.point();
        return observed(receiver, ProcessStreamStarvationDetector.Stream.STDERR, receiver.getErrorStream());
    }

    /**
     * Weaves {@code Process.inputReader()}, as {@link #processInputStream}.
     *
     * @param receiver the child process
     * @return the reader
     * @since 1.13.2
     */
    public static BufferedReader processInputReader(Process receiver) {
        ControlledSchedule.point();
        streamTaken(receiver, ProcessStreamStarvationDetector.Stream.STDOUT);
        return receiver.inputReader();
    }

    /**
     * Weaves {@code Process.inputReader(Charset)}, as {@link #processInputStream}.
     *
     * @param receiver the child process
     * @param charset  the charset to decode with
     * @return the reader
     * @since 1.13.2
     */
    public static BufferedReader processInputReader(Process receiver, Charset charset) {
        ControlledSchedule.point();
        streamTaken(receiver, ProcessStreamStarvationDetector.Stream.STDOUT);
        return receiver.inputReader(charset);
    }

    /**
     * Weaves {@code Process.errorReader()}, as {@link #processErrorStream}.
     *
     * @param receiver the child process
     * @return the reader
     * @since 1.13.2
     */
    public static BufferedReader processErrorReader(Process receiver) {
        ControlledSchedule.point();
        streamTaken(receiver, ProcessStreamStarvationDetector.Stream.STDERR);
        return receiver.errorReader();
    }

    /**
     * Weaves {@code Process.errorReader(Charset)}, as {@link #processErrorStream}.
     *
     * @param receiver the child process
     * @param charset  the charset to decode with
     * @return the reader
     * @since 1.13.2
     */
    public static BufferedReader processErrorReader(Process receiver, Charset charset) {
        ControlledSchedule.point();
        streamTaken(receiver, ProcessStreamStarvationDetector.Stream.STDERR);
        return receiver.errorReader(charset);
    }

    private static void started(ProcessBuilder builder, Process process) {
        ProcessStreamStarvationDetector detector = AsyncTestContext.currentProcessStreamStarvationDetector();
        if (detector != null) {
            detector.recordStarted(builder, process);
        }
    }

    private static void waitingFor(Process process) {
        ProcessStreamStarvationDetector detector = AsyncTestContext.currentProcessStreamStarvationDetector();
        if (detector != null) {
            detector.recordWaitFor(process, Thread.currentThread());
        }
    }

    /**
     * Weaves {@code PipedOutputStream.write(int)}: the write is recorded against the pipe's reading
     * end, so one thread on both ends of a pair is reported (#994).
     *
     * @param receiver the writing end
     * @param b        the byte
     * @throws IOException as the write does
     * @since 1.13.2
     */
    public static void pipedWrite(java.io.PipedOutputStream receiver, int b) throws IOException {
        ControlledSchedule.point();
        pipeWritten(receiver);
        receiver.write(b);
    }

    /**
     * Weaves {@code PipedOutputStream.write(byte[], int, int)}, as {@link #pipedWrite(java.io.PipedOutputStream, int)}.
     *
     * @param receiver the writing end
     * @param b        the bytes
     * @param off      the first byte to write
     * @param len      how many to write
     * @throws IOException as the write does
     * @since 1.13.2
     */
    public static void pipedWrite(java.io.PipedOutputStream receiver, byte[] b, int off, int len)
            throws IOException {
        ControlledSchedule.point();
        pipeWritten(receiver);
        receiver.write(b, off, len);
    }

    /**
     * Weaves {@code PipedOutputStream.write(byte[])}, as {@link #pipedWrite(java.io.PipedOutputStream, int)}.
     *
     * @param receiver the writing end
     * @param b        the bytes
     * @throws IOException as the write does
     * @since 1.13.2
     */
    public static void pipedWrite(java.io.PipedOutputStream receiver, byte[] b) throws IOException {
        ControlledSchedule.point();
        pipeWritten(receiver);
        receiver.write(b);
    }

    /**
     * Weaves {@code PipedInputStream.read()}: the read is recorded against the pipe (#994).
     *
     * @param receiver the reading end
     * @return what the read returns
     * @throws IOException as the read does
     * @since 1.13.2
     */
    public static int pipedRead(java.io.PipedInputStream receiver) throws IOException {
        ControlledSchedule.point();
        pipeRead(receiver);
        return receiver.read();
    }

    /**
     * Weaves {@code PipedInputStream.read(byte[], int, int)}, as {@link #pipedRead(java.io.PipedInputStream)}.
     *
     * @param receiver the reading end
     * @param b        where to read into
     * @param off      the first index to fill
     * @param len      how many bytes at most
     * @return what the read returns
     * @throws IOException as the read does
     * @since 1.13.2
     */
    public static int pipedRead(java.io.PipedInputStream receiver, byte[] b, int off, int len)
            throws IOException {
        ControlledSchedule.point();
        pipeRead(receiver);
        return receiver.read(b, off, len);
    }

    /**
     * Weaves {@code PipedInputStream.read(byte[])}, as {@link #pipedRead(java.io.PipedInputStream)}.
     *
     * @param receiver the reading end
     * @param b        where to read into
     * @return what the read returns
     * @throws IOException as the read does
     * @since 1.13.2
     */
    public static int pipedRead(java.io.PipedInputStream receiver, byte[] b) throws IOException {
        ControlledSchedule.point();
        pipeRead(receiver);
        return receiver.read(b);
    }

    private static void pipeWritten(java.io.PipedOutputStream out) {
        ProcessStreamStarvationDetector detector = AsyncTestContext.currentProcessStreamStarvationDetector();
        if (detector != null) {
            detector.agentPipeWrite(out, Thread.currentThread());
        }
    }

    private static void pipeRead(java.io.PipedInputStream in) {
        ProcessStreamStarvationDetector detector = AsyncTestContext.currentProcessStreamStarvationDetector();
        if (detector != null) {
            detector.recordPipeRead(in, Thread.currentThread());
        }
    }

    private static InputStream observed(Process process, ProcessStreamStarvationDetector.Stream stream,
                                        InputStream raw) {
        ProcessStreamStarvationDetector detector = AsyncTestContext.currentProcessStreamStarvationDetector();
        return detector == null ? raw : detector.observeStream(process, stream, raw);
    }

    private static void streamTaken(Process process, ProcessStreamStarvationDetector.Stream stream) {
        ProcessStreamStarvationDetector detector = AsyncTestContext.currentProcessStreamStarvationDetector();
        if (detector != null) {
            detector.recordStreamTaken(process, stream);
        }
    }

    /**
     * Weaves {@code CompletableFuture.thenApply(Function)}: the function runs wrapped, after this
     * thread's clock and the completion it follows, and a join of the returned stage on this thread
     * is ordered after it (#741).
     *
     * @param receiver the stage the function follows
     * @param function what runs next; the thread running it receives this thread's clock first
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Object> thenApply(
            CompletableFuture<Object> receiver,
            Function<Object, Object> function) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(function), receiver, null);
        return Submissions.remember(receiver.thenApply(stage), stage);
    }

    /**
     * Weaves {@code CompletableFuture.thenApplyAsync(Function)}, as {@link
     * #thenApply(CompletableFuture, Function)}.
     *
     * @param receiver the stage the function follows
     * @param function what runs next; the thread running it receives this thread's clock first
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Object> thenApplyAsync(
            CompletableFuture<Object> receiver,
            Function<Object, Object> function) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(function), receiver, null);
        return Submissions.remember(receiver.thenApplyAsync(stage), stage);
    }

    /**
     * Weaves {@code CompletableFuture.thenApplyAsync(Function, Executor)}, as {@link
     * #thenApply(CompletableFuture, Function)}.
     *
     * @param receiver the stage the function follows
     * @param function what runs next; the thread running it receives this thread's clock first
     * @param executor the executor the function runs on
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Object> thenApplyAsync(
            CompletableFuture<Object> receiver,
            Function<Object, Object> function,
            Executor executor) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(function), receiver, null);
        return Submissions.remember(receiver.thenApplyAsync(stage, executor), stage);
    }

    /**
     * Weaves {@code CompletableFuture.thenAccept(Consumer)}: the function runs wrapped, after this
     * thread's clock and the completion it follows, and a join of the returned stage on this thread
     * is ordered after it (#741).
     *
     * @param receiver the stage the function follows
     * @param action what runs next; the thread running it receives this thread's clock first
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Void> thenAccept(
            CompletableFuture<Object> receiver,
            Consumer<Object> action) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(action), receiver, null);
        return Submissions.remember(receiver.thenAccept(stage), stage);
    }

    /**
     * Weaves {@code CompletableFuture.thenAcceptAsync(Consumer)}, as {@link
     * #thenAccept(CompletableFuture, Consumer)}.
     *
     * @param receiver the stage the function follows
     * @param action what runs next; the thread running it receives this thread's clock first
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Void> thenAcceptAsync(
            CompletableFuture<Object> receiver,
            Consumer<Object> action) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(action), receiver, null);
        return Submissions.remember(receiver.thenAcceptAsync(stage), stage);
    }

    /**
     * Weaves {@code CompletableFuture.thenAcceptAsync(Consumer, Executor)}, as {@link
     * #thenAccept(CompletableFuture, Consumer)}.
     *
     * @param receiver the stage the function follows
     * @param action what runs next; the thread running it receives this thread's clock first
     * @param executor the executor the function runs on
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Void> thenAcceptAsync(
            CompletableFuture<Object> receiver,
            Consumer<Object> action,
            Executor executor) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(action), receiver, null);
        return Submissions.remember(receiver.thenAcceptAsync(stage, executor), stage);
    }

    /**
     * Weaves {@code CompletableFuture.thenRun(Runnable)}: the function runs wrapped, after this
     * thread's clock and the completion it follows, and a join of the returned stage on this thread
     * is ordered after it (#741).
     *
     * @param receiver the stage the function follows
     * @param action what runs next; the thread running it receives this thread's clock first
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Void> thenRun(
            CompletableFuture<Object> receiver,
            Runnable action) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(action), receiver, null);
        return Submissions.remember(receiver.thenRun(stage), stage);
    }

    /**
     * Weaves {@code CompletableFuture.thenRunAsync(Runnable)}, as {@link
     * #thenRun(CompletableFuture, Runnable)}.
     *
     * @param receiver the stage the function follows
     * @param action what runs next; the thread running it receives this thread's clock first
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Void> thenRunAsync(
            CompletableFuture<Object> receiver,
            Runnable action) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(action), receiver, null);
        return Submissions.remember(receiver.thenRunAsync(stage), stage);
    }

    /**
     * Weaves {@code CompletableFuture.thenRunAsync(Runnable, Executor)}, as {@link
     * #thenRun(CompletableFuture, Runnable)}.
     *
     * @param receiver the stage the function follows
     * @param action what runs next; the thread running it receives this thread's clock first
     * @param executor the executor the function runs on
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Void> thenRunAsync(
            CompletableFuture<Object> receiver,
            Runnable action,
            Executor executor) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(action), receiver, null);
        return Submissions.remember(receiver.thenRunAsync(stage, executor), stage);
    }

    /**
     * Weaves {@code CompletableFuture.thenCombine(CompletionStage, BiFunction)}: the function runs
     * wrapped, after this thread's clock and the completions it follows, and a join of the returned
     * stage on this thread is ordered after it (#741).
     *
     * @param receiver the stage the function follows
     * @param other the second stage, which must also complete before the function runs
     * @param function what runs next; the thread running it receives this thread's clock first
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Object> thenCombine(
            CompletableFuture<Object> receiver,
            CompletionStage<Object> other,
            BiFunction<Object, Object, Object> function) {
        ControlledSchedule.point();
        StageBiFunction stage =
                new StageBiFunction(Objects.requireNonNull(function), receiver, other);
        return Submissions.remember(receiver.thenCombine(other, stage), stage);
    }

    /**
     * Weaves {@code CompletableFuture.thenCombineAsync(CompletionStage, BiFunction)}, as {@link
     * #thenCombine(CompletableFuture, CompletionStage, BiFunction)}.
     *
     * @param receiver the stage the function follows
     * @param other the second stage, which must also complete before the function runs
     * @param function what runs next; the thread running it receives this thread's clock first
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Object> thenCombineAsync(
            CompletableFuture<Object> receiver,
            CompletionStage<Object> other,
            BiFunction<Object, Object, Object> function) {
        ControlledSchedule.point();
        StageBiFunction stage =
                new StageBiFunction(Objects.requireNonNull(function), receiver, other);
        return Submissions.remember(receiver.thenCombineAsync(other, stage), stage);
    }

    /**
     * Weaves {@code CompletableFuture.thenCombineAsync(CompletionStage, BiFunction, Executor)}, as
     * {@link #thenCombine(CompletableFuture, CompletionStage, BiFunction)}.
     *
     * @param receiver the stage the function follows
     * @param other the second stage, which must also complete before the function runs
     * @param function what runs next; the thread running it receives this thread's clock first
     * @param executor the executor the function runs on
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Object> thenCombineAsync(
            CompletableFuture<Object> receiver,
            CompletionStage<Object> other,
            BiFunction<Object, Object, Object> function,
            Executor executor) {
        ControlledSchedule.point();
        StageBiFunction stage =
                new StageBiFunction(Objects.requireNonNull(function), receiver, other);
        return Submissions.remember(receiver.thenCombineAsync(other, stage, executor), stage);
    }

    /**
     * Weaves {@code CompletableFuture.thenAcceptBoth(CompletionStage, BiConsumer)}: the function
     * runs wrapped, after this thread's clock and the completions it follows, and a join of the
     * returned stage on this thread is ordered after it (#741).
     *
     * @param receiver the stage the function follows
     * @param other the second stage, which must also complete before the function runs
     * @param action what runs next; the thread running it receives this thread's clock first
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Void> thenAcceptBoth(
            CompletableFuture<Object> receiver,
            CompletionStage<Object> other,
            BiConsumer<Object, Object> action) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(action), receiver, other);
        return Submissions.remember(receiver.thenAcceptBoth(other, stage), stage);
    }

    /**
     * Weaves {@code CompletableFuture.thenAcceptBothAsync(CompletionStage, BiConsumer)}, as {@link
     * #thenAcceptBoth(CompletableFuture, CompletionStage, BiConsumer)}.
     *
     * @param receiver the stage the function follows
     * @param other the second stage, which must also complete before the function runs
     * @param action what runs next; the thread running it receives this thread's clock first
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Void> thenAcceptBothAsync(
            CompletableFuture<Object> receiver,
            CompletionStage<Object> other,
            BiConsumer<Object, Object> action) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(action), receiver, other);
        return Submissions.remember(receiver.thenAcceptBothAsync(other, stage), stage);
    }

    /**
     * Weaves {@code CompletableFuture.thenAcceptBothAsync(CompletionStage, BiConsumer, Executor)},
     * as {@link #thenAcceptBoth(CompletableFuture, CompletionStage, BiConsumer)}.
     *
     * @param receiver the stage the function follows
     * @param other the second stage, which must also complete before the function runs
     * @param action what runs next; the thread running it receives this thread's clock first
     * @param executor the executor the function runs on
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Void> thenAcceptBothAsync(
            CompletableFuture<Object> receiver,
            CompletionStage<Object> other,
            BiConsumer<Object, Object> action,
            Executor executor) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(action), receiver, other);
        return Submissions.remember(receiver.thenAcceptBothAsync(other, stage, executor), stage);
    }

    /**
     * Weaves {@code CompletableFuture.runAfterBoth(CompletionStage, Runnable)}: the function runs
     * wrapped, after this thread's clock and the completions it follows, and a join of the returned
     * stage on this thread is ordered after it (#741).
     *
     * @param receiver the stage the function follows
     * @param other the second stage, which must also complete before the function runs
     * @param action what runs next; the thread running it receives this thread's clock first
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Void> runAfterBoth(
            CompletableFuture<Object> receiver,
            CompletionStage<Object> other,
            Runnable action) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(action), receiver, other);
        return Submissions.remember(receiver.runAfterBoth(other, stage), stage);
    }

    /**
     * Weaves {@code CompletableFuture.runAfterBothAsync(CompletionStage, Runnable)}, as {@link
     * #runAfterBoth(CompletableFuture, CompletionStage, Runnable)}.
     *
     * @param receiver the stage the function follows
     * @param other the second stage, which must also complete before the function runs
     * @param action what runs next; the thread running it receives this thread's clock first
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Void> runAfterBothAsync(
            CompletableFuture<Object> receiver,
            CompletionStage<Object> other,
            Runnable action) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(action), receiver, other);
        return Submissions.remember(receiver.runAfterBothAsync(other, stage), stage);
    }

    /**
     * Weaves {@code CompletableFuture.runAfterBothAsync(CompletionStage, Runnable, Executor)}, as
     * {@link #runAfterBoth(CompletableFuture, CompletionStage, Runnable)}.
     *
     * @param receiver the stage the function follows
     * @param other the second stage, which must also complete before the function runs
     * @param action what runs next; the thread running it receives this thread's clock first
     * @param executor the executor the function runs on
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Void> runAfterBothAsync(
            CompletableFuture<Object> receiver,
            CompletionStage<Object> other,
            Runnable action,
            Executor executor) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(action), receiver, other);
        return Submissions.remember(receiver.runAfterBothAsync(other, stage, executor), stage);
    }

    /**
     * Weaves {@code CompletableFuture.applyToEither(CompletionStage, Function)}: the function runs
     * wrapped, after this thread's clock only, since which stage it followed is not known, and a
     * join of the returned stage on this thread is ordered after it (#741).
     *
     * @param receiver the stage the function follows
     * @param other the second stage; either one completing runs the function
     * @param function what runs next; the thread running it receives this thread's clock first
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Object> applyToEither(
            CompletableFuture<Object> receiver,
            CompletionStage<Object> other,
            Function<Object, Object> function) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(function), null, null);
        return Submissions.remember(receiver.applyToEither(other, stage), stage);
    }

    /**
     * Weaves {@code CompletableFuture.applyToEitherAsync(CompletionStage, Function)}, as {@link
     * #applyToEither(CompletableFuture, CompletionStage, Function)}.
     *
     * @param receiver the stage the function follows
     * @param other the second stage; either one completing runs the function
     * @param function what runs next; the thread running it receives this thread's clock first
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Object> applyToEitherAsync(
            CompletableFuture<Object> receiver,
            CompletionStage<Object> other,
            Function<Object, Object> function) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(function), null, null);
        return Submissions.remember(receiver.applyToEitherAsync(other, stage), stage);
    }

    /**
     * Weaves {@code CompletableFuture.applyToEitherAsync(CompletionStage, Function, Executor)}, as
     * {@link #applyToEither(CompletableFuture, CompletionStage, Function)}.
     *
     * @param receiver the stage the function follows
     * @param other the second stage; either one completing runs the function
     * @param function what runs next; the thread running it receives this thread's clock first
     * @param executor the executor the function runs on
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Object> applyToEitherAsync(
            CompletableFuture<Object> receiver,
            CompletionStage<Object> other,
            Function<Object, Object> function,
            Executor executor) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(function), null, null);
        return Submissions.remember(receiver.applyToEitherAsync(other, stage, executor), stage);
    }

    /**
     * Weaves {@code CompletableFuture.acceptEither(CompletionStage, Consumer)}: the function runs
     * wrapped, after this thread's clock only, since which stage it followed is not known, and a
     * join of the returned stage on this thread is ordered after it (#741).
     *
     * @param receiver the stage the function follows
     * @param other the second stage; either one completing runs the function
     * @param action what runs next; the thread running it receives this thread's clock first
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Void> acceptEither(
            CompletableFuture<Object> receiver,
            CompletionStage<Object> other,
            Consumer<Object> action) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(action), null, null);
        return Submissions.remember(receiver.acceptEither(other, stage), stage);
    }

    /**
     * Weaves {@code CompletableFuture.acceptEitherAsync(CompletionStage, Consumer)}, as {@link
     * #acceptEither(CompletableFuture, CompletionStage, Consumer)}.
     *
     * @param receiver the stage the function follows
     * @param other the second stage; either one completing runs the function
     * @param action what runs next; the thread running it receives this thread's clock first
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Void> acceptEitherAsync(
            CompletableFuture<Object> receiver,
            CompletionStage<Object> other,
            Consumer<Object> action) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(action), null, null);
        return Submissions.remember(receiver.acceptEitherAsync(other, stage), stage);
    }

    /**
     * Weaves {@code CompletableFuture.acceptEitherAsync(CompletionStage, Consumer, Executor)}, as
     * {@link #acceptEither(CompletableFuture, CompletionStage, Consumer)}.
     *
     * @param receiver the stage the function follows
     * @param other the second stage; either one completing runs the function
     * @param action what runs next; the thread running it receives this thread's clock first
     * @param executor the executor the function runs on
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Void> acceptEitherAsync(
            CompletableFuture<Object> receiver,
            CompletionStage<Object> other,
            Consumer<Object> action,
            Executor executor) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(action), null, null);
        return Submissions.remember(receiver.acceptEitherAsync(other, stage, executor), stage);
    }

    /**
     * Weaves {@code CompletableFuture.runAfterEither(CompletionStage, Runnable)}: the function runs
     * wrapped, after this thread's clock only, since which stage it followed is not known, and a
     * join of the returned stage on this thread is ordered after it (#741).
     *
     * @param receiver the stage the function follows
     * @param other the second stage; either one completing runs the function
     * @param action what runs next; the thread running it receives this thread's clock first
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Void> runAfterEither(
            CompletableFuture<Object> receiver,
            CompletionStage<Object> other,
            Runnable action) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(action), null, null);
        return Submissions.remember(receiver.runAfterEither(other, stage), stage);
    }

    /**
     * Weaves {@code CompletableFuture.runAfterEitherAsync(CompletionStage, Runnable)}, as {@link
     * #runAfterEither(CompletableFuture, CompletionStage, Runnable)}.
     *
     * @param receiver the stage the function follows
     * @param other the second stage; either one completing runs the function
     * @param action what runs next; the thread running it receives this thread's clock first
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Void> runAfterEitherAsync(
            CompletableFuture<Object> receiver,
            CompletionStage<Object> other,
            Runnable action) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(action), null, null);
        return Submissions.remember(receiver.runAfterEitherAsync(other, stage), stage);
    }

    /**
     * Weaves {@code CompletableFuture.runAfterEitherAsync(CompletionStage, Runnable, Executor)}, as
     * {@link #runAfterEither(CompletableFuture, CompletionStage, Runnable)}.
     *
     * @param receiver the stage the function follows
     * @param other the second stage; either one completing runs the function
     * @param action what runs next; the thread running it receives this thread's clock first
     * @param executor the executor the function runs on
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Void> runAfterEitherAsync(
            CompletableFuture<Object> receiver,
            CompletionStage<Object> other,
            Runnable action,
            Executor executor) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(action), null, null);
        return Submissions.remember(receiver.runAfterEitherAsync(other, stage, executor), stage);
    }

    /**
     * Weaves {@code CompletableFuture.thenCompose(Function)}: the function runs wrapped, after this
     * thread's clock and the completion it follows, and a join of the returned stage on this thread
     * is ordered after it (#741).
     *
     * @param receiver the stage the function follows
     * @param function what runs next; the thread running it receives this thread's clock first
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Object> thenCompose(
            CompletableFuture<Object> receiver,
            Function<Object, Object> function) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(function), receiver, null);
        return Submissions.remember(receiver.thenCompose(stage.composing()), stage);
    }

    /**
     * Weaves {@code CompletableFuture.thenComposeAsync(Function)}, as {@link
     * #thenCompose(CompletableFuture, Function)}.
     *
     * @param receiver the stage the function follows
     * @param function what runs next; the thread running it receives this thread's clock first
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Object> thenComposeAsync(
            CompletableFuture<Object> receiver,
            Function<Object, Object> function) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(function), receiver, null);
        return Submissions.remember(receiver.thenComposeAsync(stage.composing()), stage);
    }

    /**
     * Weaves {@code CompletableFuture.thenComposeAsync(Function, Executor)}, as {@link
     * #thenCompose(CompletableFuture, Function)}.
     *
     * @param receiver the stage the function follows
     * @param function what runs next; the thread running it receives this thread's clock first
     * @param executor the executor the function runs on
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Object> thenComposeAsync(
            CompletableFuture<Object> receiver,
            Function<Object, Object> function,
            Executor executor) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(function), receiver, null);
        return Submissions.remember(receiver.thenComposeAsync(stage.composing(), executor), stage);
    }

    /**
     * Weaves {@code CompletableFuture.whenComplete(BiConsumer)}: the function runs wrapped, after
     * this thread's clock and the completion it follows, and a join of the returned stage on this
     * thread is ordered after it (#741).
     *
     * @param receiver the stage the function follows
     * @param action what runs next; the thread running it receives this thread's clock first
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Object> whenComplete(
            CompletableFuture<Object> receiver,
            BiConsumer<Object, Object> action) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(action), receiver, null);
        return Submissions.remember(receiver.whenComplete(stage), stage);
    }

    /**
     * Weaves {@code CompletableFuture.whenCompleteAsync(BiConsumer)}, as {@link
     * #whenComplete(CompletableFuture, BiConsumer)}.
     *
     * @param receiver the stage the function follows
     * @param action what runs next; the thread running it receives this thread's clock first
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Object> whenCompleteAsync(
            CompletableFuture<Object> receiver,
            BiConsumer<Object, Object> action) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(action), receiver, null);
        return Submissions.remember(receiver.whenCompleteAsync(stage), stage);
    }

    /**
     * Weaves {@code CompletableFuture.whenCompleteAsync(BiConsumer, Executor)}, as {@link
     * #whenComplete(CompletableFuture, BiConsumer)}.
     *
     * @param receiver the stage the function follows
     * @param action what runs next; the thread running it receives this thread's clock first
     * @param executor the executor the function runs on
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Object> whenCompleteAsync(
            CompletableFuture<Object> receiver,
            BiConsumer<Object, Object> action,
            Executor executor) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(action), receiver, null);
        return Submissions.remember(receiver.whenCompleteAsync(stage, executor), stage);
    }

    /**
     * Weaves {@code CompletableFuture.handle(BiFunction)}: the function runs wrapped, after this
     * thread's clock and the completion it follows, and a join of the returned stage on this thread
     * is ordered after it (#741).
     *
     * @param receiver the stage the function follows
     * @param function what runs next; the thread running it receives this thread's clock first
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Object> handle(
            CompletableFuture<Object> receiver,
            BiFunction<Object, Object, Object> function) {
        ControlledSchedule.point();
        StageBiFunction stage =
                new StageBiFunction(Objects.requireNonNull(function), receiver, null);
        return Submissions.remember(receiver.handle(stage.handling()), stage);
    }

    /**
     * Weaves {@code CompletableFuture.handleAsync(BiFunction)}, as {@link
     * #handle(CompletableFuture, BiFunction)}.
     *
     * @param receiver the stage the function follows
     * @param function what runs next; the thread running it receives this thread's clock first
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Object> handleAsync(
            CompletableFuture<Object> receiver,
            BiFunction<Object, Object, Object> function) {
        ControlledSchedule.point();
        StageBiFunction stage =
                new StageBiFunction(Objects.requireNonNull(function), receiver, null);
        return Submissions.remember(receiver.handleAsync(stage.handling()), stage);
    }

    /**
     * Weaves {@code CompletableFuture.handleAsync(BiFunction, Executor)}, as {@link
     * #handle(CompletableFuture, BiFunction)}.
     *
     * @param receiver the stage the function follows
     * @param function what runs next; the thread running it receives this thread's clock first
     * @param executor the executor the function runs on
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Object> handleAsync(
            CompletableFuture<Object> receiver,
            BiFunction<Object, Object, Object> function,
            Executor executor) {
        ControlledSchedule.point();
        StageBiFunction stage =
                new StageBiFunction(Objects.requireNonNull(function), receiver, null);
        return Submissions.remember(receiver.handleAsync(stage.handling(), executor), stage);
    }

    /**
     * Weaves {@code CompletableFuture.exceptionally(Function)}: the function runs wrapped, after
     * this thread's clock and the completion it follows, and a join of the returned stage on this
     * thread is ordered after it (#741).
     *
     * @param receiver the stage the function follows
     * @param function what runs next; the thread running it receives this thread's clock first
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Object> exceptionally(
            CompletableFuture<Object> receiver,
            Function<Object, Object> function) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(function), receiver, null);
        return Submissions.remember(receiver.exceptionally(stage.recovering()), stage);
    }

    /**
     * Weaves {@code CompletableFuture.exceptionallyAsync(Function)}, as {@link
     * #exceptionally(CompletableFuture, Function)}.
     *
     * @param receiver the stage the function follows
     * @param function what runs next; the thread running it receives this thread's clock first
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Object> exceptionallyAsync(
            CompletableFuture<Object> receiver,
            Function<Object, Object> function) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(function), receiver, null);
        return Submissions.remember(receiver.exceptionallyAsync(stage.recovering()), stage);
    }

    /**
     * Weaves {@code CompletableFuture.exceptionallyAsync(Function, Executor)}, as {@link
     * #exceptionally(CompletableFuture, Function)}.
     *
     * @param receiver the stage the function follows
     * @param function what runs next; the thread running it receives this thread's clock first
     * @param executor the executor the function runs on
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Object> exceptionallyAsync(
            CompletableFuture<Object> receiver,
            Function<Object, Object> function,
            Executor executor) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(function), receiver, null);
        return Submissions.remember(
                receiver.exceptionallyAsync(stage.recovering(), executor), stage);
    }

    /**
     * Weaves {@code CompletableFuture.exceptionallyCompose(Function)}: the function runs wrapped,
     * after this thread's clock and the completion it follows, and a join of the returned stage on
     * this thread is ordered after it (#741).
     *
     * @param receiver the stage the function follows
     * @param function what runs next; the thread running it receives this thread's clock first
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Object> exceptionallyCompose(
            CompletableFuture<Object> receiver,
            Function<Object, Object> function) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(function), receiver, null);
        return Submissions.remember(receiver.exceptionallyCompose(stage.recoveringBy()), stage);
    }

    /**
     * Weaves {@code CompletableFuture.exceptionallyComposeAsync(Function)}, as {@link
     * #exceptionallyCompose(CompletableFuture, Function)}.
     *
     * @param receiver the stage the function follows
     * @param function what runs next; the thread running it receives this thread's clock first
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Object> exceptionallyComposeAsync(
            CompletableFuture<Object> receiver,
            Function<Object, Object> function) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(function), receiver, null);
        return Submissions.remember(
                receiver.exceptionallyComposeAsync(stage.recoveringBy()), stage);
    }

    /**
     * Weaves {@code CompletableFuture.exceptionallyComposeAsync(Function, Executor)}, as {@link
     * #exceptionallyCompose(CompletableFuture, Function)}.
     *
     * @param receiver the stage the function follows
     * @param function what runs next; the thread running it receives this thread's clock first
     * @param executor the executor the function runs on
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletableFuture<Object> exceptionallyComposeAsync(
            CompletableFuture<Object> receiver,
            Function<Object, Object> function,
            Executor executor) {
        ControlledSchedule.point();
        StageFunction stage = new StageFunction(Objects.requireNonNull(function), receiver, null);
        return Submissions.remember(
                receiver.exceptionallyComposeAsync(stage.recoveringBy(), executor), stage);
    }

    /**
     * Weaves {@code CompletionStage.thenApply(Function)}: on a {@code CompletableFuture}, as {@link
     * #thenApply(CompletableFuture, Function)}; any other stage gets the caller's function
     * unwrapped, since it may hand it back (#741).
     *
     * @param receiver the stage the function follows
     * @param function what runs next
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletionStage<Object> thenApply(
            CompletionStage<Object> receiver,
            Function<Object, Object> function) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return thenApply(future, function);
        }
        return receiver.thenApply(function);
    }

    /**
     * Weaves {@code CompletionStage.thenApplyAsync(Function)}: on a {@code CompletableFuture}, as
     * {@link #thenApplyAsync(CompletableFuture, Function)}; any other stage gets the caller's
     * function unwrapped, since it may hand it back (#741).
     *
     * @param receiver the stage the function follows
     * @param function what runs next
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletionStage<Object> thenApplyAsync(
            CompletionStage<Object> receiver,
            Function<Object, Object> function) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return thenApplyAsync(future, function);
        }
        return receiver.thenApplyAsync(function);
    }

    /**
     * Weaves {@code CompletionStage.thenApplyAsync(Function, Executor)}: on a {@code
     * CompletableFuture}, as {@link #thenApplyAsync(CompletableFuture, Function, Executor)}; any
     * other stage gets the caller's function unwrapped, since it may hand it back (#741).
     *
     * @param receiver the stage the function follows
     * @param function what runs next
     * @param executor the executor the function runs on
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletionStage<Object> thenApplyAsync(
            CompletionStage<Object> receiver,
            Function<Object, Object> function,
            Executor executor) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return thenApplyAsync(future, function, executor);
        }
        return receiver.thenApplyAsync(function, executor);
    }

    /**
     * Weaves {@code CompletionStage.thenAccept(Consumer)}: on a {@code CompletableFuture}, as
     * {@link #thenAccept(CompletableFuture, Consumer)}; any other stage gets the caller's function
     * unwrapped, since it may hand it back (#741).
     *
     * @param receiver the stage the function follows
     * @param action what runs next
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletionStage<Void> thenAccept(
            CompletionStage<Object> receiver,
            Consumer<Object> action) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return thenAccept(future, action);
        }
        return receiver.thenAccept(action);
    }

    /**
     * Weaves {@code CompletionStage.thenAcceptAsync(Consumer)}: on a {@code CompletableFuture}, as
     * {@link #thenAcceptAsync(CompletableFuture, Consumer)}; any other stage gets the caller's
     * function unwrapped, since it may hand it back (#741).
     *
     * @param receiver the stage the function follows
     * @param action what runs next
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletionStage<Void> thenAcceptAsync(
            CompletionStage<Object> receiver,
            Consumer<Object> action) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return thenAcceptAsync(future, action);
        }
        return receiver.thenAcceptAsync(action);
    }

    /**
     * Weaves {@code CompletionStage.thenAcceptAsync(Consumer, Executor)}: on a {@code
     * CompletableFuture}, as {@link #thenAcceptAsync(CompletableFuture, Consumer, Executor)}; any
     * other stage gets the caller's function unwrapped, since it may hand it back (#741).
     *
     * @param receiver the stage the function follows
     * @param action what runs next
     * @param executor the executor the function runs on
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletionStage<Void> thenAcceptAsync(
            CompletionStage<Object> receiver,
            Consumer<Object> action,
            Executor executor) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return thenAcceptAsync(future, action, executor);
        }
        return receiver.thenAcceptAsync(action, executor);
    }

    /**
     * Weaves {@code CompletionStage.thenRun(Runnable)}: on a {@code CompletableFuture}, as {@link
     * #thenRun(CompletableFuture, Runnable)}; any other stage gets the caller's function unwrapped,
     * since it may hand it back (#741).
     *
     * @param receiver the stage the function follows
     * @param action what runs next
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletionStage<Void> thenRun(
            CompletionStage<Object> receiver,
            Runnable action) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return thenRun(future, action);
        }
        return receiver.thenRun(action);
    }

    /**
     * Weaves {@code CompletionStage.thenRunAsync(Runnable)}: on a {@code CompletableFuture}, as
     * {@link #thenRunAsync(CompletableFuture, Runnable)}; any other stage gets the caller's
     * function unwrapped, since it may hand it back (#741).
     *
     * @param receiver the stage the function follows
     * @param action what runs next
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletionStage<Void> thenRunAsync(
            CompletionStage<Object> receiver,
            Runnable action) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return thenRunAsync(future, action);
        }
        return receiver.thenRunAsync(action);
    }

    /**
     * Weaves {@code CompletionStage.thenRunAsync(Runnable, Executor)}: on a {@code
     * CompletableFuture}, as {@link #thenRunAsync(CompletableFuture, Runnable, Executor)}; any
     * other stage gets the caller's function unwrapped, since it may hand it back (#741).
     *
     * @param receiver the stage the function follows
     * @param action what runs next
     * @param executor the executor the function runs on
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletionStage<Void> thenRunAsync(
            CompletionStage<Object> receiver,
            Runnable action,
            Executor executor) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return thenRunAsync(future, action, executor);
        }
        return receiver.thenRunAsync(action, executor);
    }

    /**
     * Weaves {@code CompletionStage.thenCombine(CompletionStage, BiFunction)}: on a {@code
     * CompletableFuture}, as {@link #thenCombine(CompletableFuture, CompletionStage, BiFunction)};
     * any other stage gets the caller's function unwrapped, since it may hand it back (#741).
     *
     * @param receiver the stage the function follows
     * @param other the second stage, which must also complete before the function runs
     * @param function what runs next
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletionStage<Object> thenCombine(
            CompletionStage<Object> receiver,
            CompletionStage<Object> other,
            BiFunction<Object, Object, Object> function) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return thenCombine(future, other, function);
        }
        return receiver.thenCombine(other, function);
    }

    /**
     * Weaves {@code CompletionStage.thenCombineAsync(CompletionStage, BiFunction)}: on a {@code
     * CompletableFuture}, as {@link #thenCombineAsync(CompletableFuture, CompletionStage,
     * BiFunction)}; any other stage gets the caller's function unwrapped, since it may hand it back
     * (#741).
     *
     * @param receiver the stage the function follows
     * @param other the second stage, which must also complete before the function runs
     * @param function what runs next
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletionStage<Object> thenCombineAsync(
            CompletionStage<Object> receiver,
            CompletionStage<Object> other,
            BiFunction<Object, Object, Object> function) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return thenCombineAsync(future, other, function);
        }
        return receiver.thenCombineAsync(other, function);
    }

    /**
     * Weaves {@code CompletionStage.thenCombineAsync(CompletionStage, BiFunction, Executor)}: on a
     * {@code CompletableFuture}, as {@link #thenCombineAsync(CompletableFuture, CompletionStage,
     * BiFunction, Executor)}; any other stage gets the caller's function unwrapped, since it may
     * hand it back (#741).
     *
     * @param receiver the stage the function follows
     * @param other the second stage, which must also complete before the function runs
     * @param function what runs next
     * @param executor the executor the function runs on
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletionStage<Object> thenCombineAsync(
            CompletionStage<Object> receiver,
            CompletionStage<Object> other,
            BiFunction<Object, Object, Object> function,
            Executor executor) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return thenCombineAsync(future, other, function, executor);
        }
        return receiver.thenCombineAsync(other, function, executor);
    }

    /**
     * Weaves {@code CompletionStage.thenAcceptBoth(CompletionStage, BiConsumer)}: on a {@code
     * CompletableFuture}, as {@link #thenAcceptBoth(CompletableFuture, CompletionStage,
     * BiConsumer)}; any other stage gets the caller's function unwrapped, since it may hand it back
     * (#741).
     *
     * @param receiver the stage the function follows
     * @param other the second stage, which must also complete before the function runs
     * @param action what runs next
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletionStage<Void> thenAcceptBoth(
            CompletionStage<Object> receiver,
            CompletionStage<Object> other,
            BiConsumer<Object, Object> action) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return thenAcceptBoth(future, other, action);
        }
        return receiver.thenAcceptBoth(other, action);
    }

    /**
     * Weaves {@code CompletionStage.thenAcceptBothAsync(CompletionStage, BiConsumer)}: on a {@code
     * CompletableFuture}, as {@link #thenAcceptBothAsync(CompletableFuture, CompletionStage,
     * BiConsumer)}; any other stage gets the caller's function unwrapped, since it may hand it back
     * (#741).
     *
     * @param receiver the stage the function follows
     * @param other the second stage, which must also complete before the function runs
     * @param action what runs next
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletionStage<Void> thenAcceptBothAsync(
            CompletionStage<Object> receiver,
            CompletionStage<Object> other,
            BiConsumer<Object, Object> action) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return thenAcceptBothAsync(future, other, action);
        }
        return receiver.thenAcceptBothAsync(other, action);
    }

    /**
     * Weaves {@code CompletionStage.thenAcceptBothAsync(CompletionStage, BiConsumer, Executor)}: on
     * a {@code CompletableFuture}, as {@link #thenAcceptBothAsync(CompletableFuture,
     * CompletionStage, BiConsumer, Executor)}; any other stage gets the caller's function
     * unwrapped, since it may hand it back (#741).
     *
     * @param receiver the stage the function follows
     * @param other the second stage, which must also complete before the function runs
     * @param action what runs next
     * @param executor the executor the function runs on
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletionStage<Void> thenAcceptBothAsync(
            CompletionStage<Object> receiver,
            CompletionStage<Object> other,
            BiConsumer<Object, Object> action,
            Executor executor) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return thenAcceptBothAsync(future, other, action, executor);
        }
        return receiver.thenAcceptBothAsync(other, action, executor);
    }

    /**
     * Weaves {@code CompletionStage.runAfterBoth(CompletionStage, Runnable)}: on a {@code
     * CompletableFuture}, as {@link #runAfterBoth(CompletableFuture, CompletionStage, Runnable)};
     * any other stage gets the caller's function unwrapped, since it may hand it back (#741).
     *
     * @param receiver the stage the function follows
     * @param other the second stage, which must also complete before the function runs
     * @param action what runs next
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletionStage<Void> runAfterBoth(
            CompletionStage<Object> receiver,
            CompletionStage<Object> other,
            Runnable action) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return runAfterBoth(future, other, action);
        }
        return receiver.runAfterBoth(other, action);
    }

    /**
     * Weaves {@code CompletionStage.runAfterBothAsync(CompletionStage, Runnable)}: on a {@code
     * CompletableFuture}, as {@link #runAfterBothAsync(CompletableFuture, CompletionStage,
     * Runnable)}; any other stage gets the caller's function unwrapped, since it may hand it back
     * (#741).
     *
     * @param receiver the stage the function follows
     * @param other the second stage, which must also complete before the function runs
     * @param action what runs next
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletionStage<Void> runAfterBothAsync(
            CompletionStage<Object> receiver,
            CompletionStage<Object> other,
            Runnable action) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return runAfterBothAsync(future, other, action);
        }
        return receiver.runAfterBothAsync(other, action);
    }

    /**
     * Weaves {@code CompletionStage.runAfterBothAsync(CompletionStage, Runnable, Executor)}: on a
     * {@code CompletableFuture}, as {@link #runAfterBothAsync(CompletableFuture, CompletionStage,
     * Runnable, Executor)}; any other stage gets the caller's function unwrapped, since it may hand
     * it back (#741).
     *
     * @param receiver the stage the function follows
     * @param other the second stage, which must also complete before the function runs
     * @param action what runs next
     * @param executor the executor the function runs on
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletionStage<Void> runAfterBothAsync(
            CompletionStage<Object> receiver,
            CompletionStage<Object> other,
            Runnable action,
            Executor executor) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return runAfterBothAsync(future, other, action, executor);
        }
        return receiver.runAfterBothAsync(other, action, executor);
    }

    /**
     * Weaves {@code CompletionStage.applyToEither(CompletionStage, Function)}: on a {@code
     * CompletableFuture}, as {@link #applyToEither(CompletableFuture, CompletionStage, Function)};
     * any other stage gets the caller's function unwrapped, since it may hand it back (#741).
     *
     * @param receiver the stage the function follows
     * @param other the second stage; either one completing runs the function
     * @param function what runs next
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletionStage<Object> applyToEither(
            CompletionStage<Object> receiver,
            CompletionStage<Object> other,
            Function<Object, Object> function) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return applyToEither(future, other, function);
        }
        return receiver.applyToEither(other, function);
    }

    /**
     * Weaves {@code CompletionStage.applyToEitherAsync(CompletionStage, Function)}: on a {@code
     * CompletableFuture}, as {@link #applyToEitherAsync(CompletableFuture, CompletionStage,
     * Function)}; any other stage gets the caller's function unwrapped, since it may hand it back
     * (#741).
     *
     * @param receiver the stage the function follows
     * @param other the second stage; either one completing runs the function
     * @param function what runs next
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletionStage<Object> applyToEitherAsync(
            CompletionStage<Object> receiver,
            CompletionStage<Object> other,
            Function<Object, Object> function) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return applyToEitherAsync(future, other, function);
        }
        return receiver.applyToEitherAsync(other, function);
    }

    /**
     * Weaves {@code CompletionStage.applyToEitherAsync(CompletionStage, Function, Executor)}: on a
     * {@code CompletableFuture}, as {@link #applyToEitherAsync(CompletableFuture, CompletionStage,
     * Function, Executor)}; any other stage gets the caller's function unwrapped, since it may hand
     * it back (#741).
     *
     * @param receiver the stage the function follows
     * @param other the second stage; either one completing runs the function
     * @param function what runs next
     * @param executor the executor the function runs on
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletionStage<Object> applyToEitherAsync(
            CompletionStage<Object> receiver,
            CompletionStage<Object> other,
            Function<Object, Object> function,
            Executor executor) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return applyToEitherAsync(future, other, function, executor);
        }
        return receiver.applyToEitherAsync(other, function, executor);
    }

    /**
     * Weaves {@code CompletionStage.acceptEither(CompletionStage, Consumer)}: on a {@code
     * CompletableFuture}, as {@link #acceptEither(CompletableFuture, CompletionStage, Consumer)};
     * any other stage gets the caller's function unwrapped, since it may hand it back (#741).
     *
     * @param receiver the stage the function follows
     * @param other the second stage; either one completing runs the function
     * @param action what runs next
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletionStage<Void> acceptEither(
            CompletionStage<Object> receiver,
            CompletionStage<Object> other,
            Consumer<Object> action) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return acceptEither(future, other, action);
        }
        return receiver.acceptEither(other, action);
    }

    /**
     * Weaves {@code CompletionStage.acceptEitherAsync(CompletionStage, Consumer)}: on a {@code
     * CompletableFuture}, as {@link #acceptEitherAsync(CompletableFuture, CompletionStage,
     * Consumer)}; any other stage gets the caller's function unwrapped, since it may hand it back
     * (#741).
     *
     * @param receiver the stage the function follows
     * @param other the second stage; either one completing runs the function
     * @param action what runs next
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletionStage<Void> acceptEitherAsync(
            CompletionStage<Object> receiver,
            CompletionStage<Object> other,
            Consumer<Object> action) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return acceptEitherAsync(future, other, action);
        }
        return receiver.acceptEitherAsync(other, action);
    }

    /**
     * Weaves {@code CompletionStage.acceptEitherAsync(CompletionStage, Consumer, Executor)}: on a
     * {@code CompletableFuture}, as {@link #acceptEitherAsync(CompletableFuture, CompletionStage,
     * Consumer, Executor)}; any other stage gets the caller's function unwrapped, since it may hand
     * it back (#741).
     *
     * @param receiver the stage the function follows
     * @param other the second stage; either one completing runs the function
     * @param action what runs next
     * @param executor the executor the function runs on
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletionStage<Void> acceptEitherAsync(
            CompletionStage<Object> receiver,
            CompletionStage<Object> other,
            Consumer<Object> action,
            Executor executor) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return acceptEitherAsync(future, other, action, executor);
        }
        return receiver.acceptEitherAsync(other, action, executor);
    }

    /**
     * Weaves {@code CompletionStage.runAfterEither(CompletionStage, Runnable)}: on a {@code
     * CompletableFuture}, as {@link #runAfterEither(CompletableFuture, CompletionStage, Runnable)};
     * any other stage gets the caller's function unwrapped, since it may hand it back (#741).
     *
     * @param receiver the stage the function follows
     * @param other the second stage; either one completing runs the function
     * @param action what runs next
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletionStage<Void> runAfterEither(
            CompletionStage<Object> receiver,
            CompletionStage<Object> other,
            Runnable action) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return runAfterEither(future, other, action);
        }
        return receiver.runAfterEither(other, action);
    }

    /**
     * Weaves {@code CompletionStage.runAfterEitherAsync(CompletionStage, Runnable)}: on a {@code
     * CompletableFuture}, as {@link #runAfterEitherAsync(CompletableFuture, CompletionStage,
     * Runnable)}; any other stage gets the caller's function unwrapped, since it may hand it back
     * (#741).
     *
     * @param receiver the stage the function follows
     * @param other the second stage; either one completing runs the function
     * @param action what runs next
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletionStage<Void> runAfterEitherAsync(
            CompletionStage<Object> receiver,
            CompletionStage<Object> other,
            Runnable action) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return runAfterEitherAsync(future, other, action);
        }
        return receiver.runAfterEitherAsync(other, action);
    }

    /**
     * Weaves {@code CompletionStage.runAfterEitherAsync(CompletionStage, Runnable, Executor)}: on a
     * {@code CompletableFuture}, as {@link #runAfterEitherAsync(CompletableFuture, CompletionStage,
     * Runnable, Executor)}; any other stage gets the caller's function unwrapped, since it may hand
     * it back (#741).
     *
     * @param receiver the stage the function follows
     * @param other the second stage; either one completing runs the function
     * @param action what runs next
     * @param executor the executor the function runs on
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletionStage<Void> runAfterEitherAsync(
            CompletionStage<Object> receiver,
            CompletionStage<Object> other,
            Runnable action,
            Executor executor) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return runAfterEitherAsync(future, other, action, executor);
        }
        return receiver.runAfterEitherAsync(other, action, executor);
    }

    /**
     * Weaves {@code CompletionStage.thenCompose(Function)}: on a {@code CompletableFuture}, as
     * {@link #thenCompose(CompletableFuture, Function)}; any other stage gets the caller's function
     * unwrapped, since it may hand it back (#741).
     *
     * @param receiver the stage the function follows
     * @param function what runs next
     * @return the stage the caller gets
     * @since 1.12.4
     */
    @SuppressWarnings("unchecked") // the function as the stage types it, erased the same
    public static CompletionStage<Object> thenCompose(
            CompletionStage<Object> receiver,
            Function<Object, Object> function) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return thenCompose(future, function);
        }
        return receiver.thenCompose(
                (Function<Object, CompletionStage<Object>>) (Function<?, ?>) function);
    }

    /**
     * Weaves {@code CompletionStage.thenComposeAsync(Function)}: on a {@code CompletableFuture}, as
     * {@link #thenComposeAsync(CompletableFuture, Function)}; any other stage gets the caller's
     * function unwrapped, since it may hand it back (#741).
     *
     * @param receiver the stage the function follows
     * @param function what runs next
     * @return the stage the caller gets
     * @since 1.12.4
     */
    @SuppressWarnings("unchecked") // the function as the stage types it, erased the same
    public static CompletionStage<Object> thenComposeAsync(
            CompletionStage<Object> receiver,
            Function<Object, Object> function) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return thenComposeAsync(future, function);
        }
        return receiver.thenComposeAsync(
                (Function<Object, CompletionStage<Object>>) (Function<?, ?>) function);
    }

    /**
     * Weaves {@code CompletionStage.thenComposeAsync(Function, Executor)}: on a {@code
     * CompletableFuture}, as {@link #thenComposeAsync(CompletableFuture, Function, Executor)}; any
     * other stage gets the caller's function unwrapped, since it may hand it back (#741).
     *
     * @param receiver the stage the function follows
     * @param function what runs next
     * @param executor the executor the function runs on
     * @return the stage the caller gets
     * @since 1.12.4
     */
    @SuppressWarnings("unchecked") // the function as the stage types it, erased the same
    public static CompletionStage<Object> thenComposeAsync(
            CompletionStage<Object> receiver,
            Function<Object, Object> function,
            Executor executor) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return thenComposeAsync(future, function, executor);
        }
        return receiver.thenComposeAsync(
                (Function<Object, CompletionStage<Object>>) (Function<?, ?>) function, executor);
    }

    /**
     * Weaves {@code CompletionStage.whenComplete(BiConsumer)}: on a {@code CompletableFuture}, as
     * {@link #whenComplete(CompletableFuture, BiConsumer)}; any other stage gets the caller's
     * function unwrapped, since it may hand it back (#741).
     *
     * @param receiver the stage the function follows
     * @param action what runs next
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletionStage<Object> whenComplete(
            CompletionStage<Object> receiver,
            BiConsumer<Object, Object> action) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return whenComplete(future, action);
        }
        return receiver.whenComplete(action);
    }

    /**
     * Weaves {@code CompletionStage.whenCompleteAsync(BiConsumer)}: on a {@code CompletableFuture},
     * as {@link #whenCompleteAsync(CompletableFuture, BiConsumer)}; any other stage gets the
     * caller's function unwrapped, since it may hand it back (#741).
     *
     * @param receiver the stage the function follows
     * @param action what runs next
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletionStage<Object> whenCompleteAsync(
            CompletionStage<Object> receiver,
            BiConsumer<Object, Object> action) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return whenCompleteAsync(future, action);
        }
        return receiver.whenCompleteAsync(action);
    }

    /**
     * Weaves {@code CompletionStage.whenCompleteAsync(BiConsumer, Executor)}: on a {@code
     * CompletableFuture}, as {@link #whenCompleteAsync(CompletableFuture, BiConsumer, Executor)};
     * any other stage gets the caller's function unwrapped, since it may hand it back (#741).
     *
     * @param receiver the stage the function follows
     * @param action what runs next
     * @param executor the executor the function runs on
     * @return the stage the caller gets
     * @since 1.12.4
     */
    public static CompletionStage<Object> whenCompleteAsync(
            CompletionStage<Object> receiver,
            BiConsumer<Object, Object> action,
            Executor executor) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return whenCompleteAsync(future, action, executor);
        }
        return receiver.whenCompleteAsync(action, executor);
    }

    /**
     * Weaves {@code CompletionStage.handle(BiFunction)}: on a {@code CompletableFuture}, as {@link
     * #handle(CompletableFuture, BiFunction)}; any other stage gets the caller's function
     * unwrapped, since it may hand it back (#741).
     *
     * @param receiver the stage the function follows
     * @param function what runs next
     * @return the stage the caller gets
     * @since 1.12.4
     */
    @SuppressWarnings("unchecked") // the function as the stage types it, erased the same
    public static CompletionStage<Object> handle(
            CompletionStage<Object> receiver,
            BiFunction<Object, Object, Object> function) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return handle(future, function);
        }
        return receiver.handle(
                (BiFunction<Object, Throwable, Object>) (BiFunction<?, ?, ?>) function);
    }

    /**
     * Weaves {@code CompletionStage.handleAsync(BiFunction)}: on a {@code CompletableFuture}, as
     * {@link #handleAsync(CompletableFuture, BiFunction)}; any other stage gets the caller's
     * function unwrapped, since it may hand it back (#741).
     *
     * @param receiver the stage the function follows
     * @param function what runs next
     * @return the stage the caller gets
     * @since 1.12.4
     */
    @SuppressWarnings("unchecked") // the function as the stage types it, erased the same
    public static CompletionStage<Object> handleAsync(
            CompletionStage<Object> receiver,
            BiFunction<Object, Object, Object> function) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return handleAsync(future, function);
        }
        return receiver.handleAsync(
                (BiFunction<Object, Throwable, Object>) (BiFunction<?, ?, ?>) function);
    }

    /**
     * Weaves {@code CompletionStage.handleAsync(BiFunction, Executor)}: on a {@code
     * CompletableFuture}, as {@link #handleAsync(CompletableFuture, BiFunction, Executor)}; any
     * other stage gets the caller's function unwrapped, since it may hand it back (#741).
     *
     * @param receiver the stage the function follows
     * @param function what runs next
     * @param executor the executor the function runs on
     * @return the stage the caller gets
     * @since 1.12.4
     */
    @SuppressWarnings("unchecked") // the function as the stage types it, erased the same
    public static CompletionStage<Object> handleAsync(
            CompletionStage<Object> receiver,
            BiFunction<Object, Object, Object> function,
            Executor executor) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return handleAsync(future, function, executor);
        }
        return receiver.handleAsync(
                (BiFunction<Object, Throwable, Object>) (BiFunction<?, ?, ?>) function, executor);
    }

    /**
     * Weaves {@code CompletionStage.exceptionally(Function)}: on a {@code CompletableFuture}, as
     * {@link #exceptionally(CompletableFuture, Function)}; any other stage gets the caller's
     * function unwrapped, since it may hand it back (#741).
     *
     * @param receiver the stage the function follows
     * @param function what runs next
     * @return the stage the caller gets
     * @since 1.12.4
     */
    @SuppressWarnings("unchecked") // the function as the stage types it, erased the same
    public static CompletionStage<Object> exceptionally(
            CompletionStage<Object> receiver,
            Function<Object, Object> function) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return exceptionally(future, function);
        }
        return receiver.exceptionally((Function<Throwable, Object>) (Function<?, ?>) function);
    }

    /**
     * Weaves {@code CompletionStage.exceptionallyAsync(Function)}: on a {@code CompletableFuture},
     * as {@link #exceptionallyAsync(CompletableFuture, Function)}; any other stage gets the
     * caller's function unwrapped, since it may hand it back (#741).
     *
     * @param receiver the stage the function follows
     * @param function what runs next
     * @return the stage the caller gets
     * @since 1.12.4
     */
    @SuppressWarnings("unchecked") // the function as the stage types it, erased the same
    public static CompletionStage<Object> exceptionallyAsync(
            CompletionStage<Object> receiver,
            Function<Object, Object> function) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return exceptionallyAsync(future, function);
        }
        return receiver.exceptionallyAsync(
                (Function<Throwable, Object>) (Function<?, ?>) function);
    }

    /**
     * Weaves {@code CompletionStage.exceptionallyAsync(Function, Executor)}: on a {@code
     * CompletableFuture}, as {@link #exceptionallyAsync(CompletableFuture, Function, Executor)};
     * any other stage gets the caller's function unwrapped, since it may hand it back (#741).
     *
     * @param receiver the stage the function follows
     * @param function what runs next
     * @param executor the executor the function runs on
     * @return the stage the caller gets
     * @since 1.12.4
     */
    @SuppressWarnings("unchecked") // the function as the stage types it, erased the same
    public static CompletionStage<Object> exceptionallyAsync(
            CompletionStage<Object> receiver,
            Function<Object, Object> function,
            Executor executor) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return exceptionallyAsync(future, function, executor);
        }
        return receiver.exceptionallyAsync(
                (Function<Throwable, Object>) (Function<?, ?>) function, executor);
    }

    /**
     * Weaves {@code CompletionStage.exceptionallyCompose(Function)}: on a {@code
     * CompletableFuture}, as {@link #exceptionallyCompose(CompletableFuture, Function)}; any other
     * stage gets the caller's function unwrapped, since it may hand it back (#741).
     *
     * @param receiver the stage the function follows
     * @param function what runs next
     * @return the stage the caller gets
     * @since 1.12.4
     */
    @SuppressWarnings("unchecked") // the function as the stage types it, erased the same
    public static CompletionStage<Object> exceptionallyCompose(
            CompletionStage<Object> receiver,
            Function<Object, Object> function) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return exceptionallyCompose(future, function);
        }
        return receiver.exceptionallyCompose(
                (Function<Throwable, CompletionStage<Object>>) (Function<?, ?>) function);
    }

    /**
     * Weaves {@code CompletionStage.exceptionallyComposeAsync(Function)}: on a {@code
     * CompletableFuture}, as {@link #exceptionallyComposeAsync(CompletableFuture, Function)}; any
     * other stage gets the caller's function unwrapped, since it may hand it back (#741).
     *
     * @param receiver the stage the function follows
     * @param function what runs next
     * @return the stage the caller gets
     * @since 1.12.4
     */
    @SuppressWarnings("unchecked") // the function as the stage types it, erased the same
    public static CompletionStage<Object> exceptionallyComposeAsync(
            CompletionStage<Object> receiver,
            Function<Object, Object> function) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return exceptionallyComposeAsync(future, function);
        }
        return receiver.exceptionallyComposeAsync(
                (Function<Throwable, CompletionStage<Object>>) (Function<?, ?>) function);
    }

    /**
     * Weaves {@code CompletionStage.exceptionallyComposeAsync(Function, Executor)}: on a {@code
     * CompletableFuture}, as {@link #exceptionallyComposeAsync(CompletableFuture, Function,
     * Executor)}; any other stage gets the caller's function unwrapped, since it may hand it back
     * (#741).
     *
     * @param receiver the stage the function follows
     * @param function what runs next
     * @param executor the executor the function runs on
     * @return the stage the caller gets
     * @since 1.12.4
     */
    @SuppressWarnings("unchecked") // the function as the stage types it, erased the same
    public static CompletionStage<Object> exceptionallyComposeAsync(
            CompletionStage<Object> receiver,
            Function<Object, Object> function,
            Executor executor) {
        ControlledSchedule.point();
        if (receiver instanceof CompletableFuture<Object> future) {
            return exceptionallyComposeAsync(future, function, executor);
        }
        return receiver.exceptionallyComposeAsync(
                (Function<Throwable, CompletionStage<Object>>) (Function<?, ?>) function, executor);
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
        ControlledSchedule.point();
        // The NullPointerException the call itself throws for a missing function.
        HandedTask handed = new HandedTask(Objects.requireNonNull(supplier)).onTheCommonPool();
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
    @SuppressWarnings("ReferenceEquality") // the common pool, by identity
    public static CompletableFuture<Object> supplyAsync(Supplier<Object> supplier,
                                                        Executor executor) {
        ControlledSchedule.point();
        // The NullPointerException the call itself throws for a missing function.
        HandedTask handed = new HandedTask(Objects.requireNonNull(supplier)).onExecutor(executor);
        if (executor == ForkJoinPool.commonPool()) { // NOPMD CompareObjectsWithEquals - the one pool, by identity
            handed.onTheCommonPool();
        }
        try {
            return Submissions.remember(CompletableFuture.supplyAsync(handed, executor), handed);
        } catch (RejectedExecutionException e) {
            handed.abandoned();
            throw e;
        }
    }

    /**
     * Weaves {@code CompletableFuture.allOf}: a join on what it returns observes every child (see
     * {@link HandedAll}).
     *
     * @param children the futures to wait for
     * @return the future that completes when they all have
     * @since 1.14.1
     */
    public static CompletableFuture<Void> allOf(CompletableFuture<?>[] children) {
        ControlledSchedule.point();
        CompletableFuture<Void> all = CompletableFuture.allOf(children);
        return Submissions.remember(all, new HandedAll(children));
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
        ControlledSchedule.point();
        // The NullPointerException the call itself throws for a missing function.
        HandedTask handed = new HandedTask(Objects.requireNonNull(task)).onTheCommonPool();
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
    @SuppressWarnings("ReferenceEquality") // the common pool, by identity
    public static CompletableFuture<Void> runAsync(Runnable task, Executor executor) {
        ControlledSchedule.point();
        // The NullPointerException the call itself throws for a missing function.
        HandedTask handed = new HandedTask(Objects.requireNonNull(task)).onExecutor(executor);
        if (executor == ForkJoinPool.commonPool()) { // NOPMD CompareObjectsWithEquals - the one pool, by identity
            handed.onTheCommonPool();
        }
        try {
            return Submissions.remember(CompletableFuture.runAsync(handed, executor), handed);
        } catch (RejectedExecutionException e) {
            handed.abandoned();
            throw e;
        }
    }

    /**
     * Stands in for the {@code POP} after a woven {@code supplyAsync} or {@code runAsync} whose
     * future the caller never kept. Nothing can join, get or handle that future afterwards, so the
     * detector judges it at analysis by whether its task failed.
     *
     * @param future the future the caller discarded
     * @since 1.13.2
     */
    public static void asyncResultDiscarded(CompletableFuture<?> future) {
        ControlledSchedule.point();
        CompletableFutureExceptionDetector detector =
                AsyncTestContext.currentCompletableFutureExceptionDetector();
        if (detector != null) {
            detector.recordDiscarded(future, Thread.currentThread(),
                    "CompletableFuture.supplyAsync or runAsync");
        }
    }

    /**
     * The common-pool task the calling thread is running, for the blocking detector, while it
     * runs; unset everywhere else.
     */
    static final ThreadLocal<HandedTask> COMMON_POOL_TASK = new ThreadLocal<>();

    /**
     * Hands a call that is about to block the calling thread to the two detectors that judge a
     * block by where it happens: inside a {@code CompletableFuture} callback, and inside a task on
     * the common {@code ForkJoinPool}.
     *
     * <p>Called only when the call will block, never for a join of a future already complete:
     * {@code allOf(a, b).thenApply(v -> a.join() + b.join())} joins two finished futures inside a
     * callback, which is the documented way to combine results and blocks nothing.
     *
     * @param callType the blocking API, as the report names it
     * @param managed  whether the JDK compensates a {@code ForkJoinPool} for it, as
     *                 {@code CompletableFuture.join} and {@code get} do through
     *                 {@code ForkJoinPool.managedBlock}: such a call does not starve the pool, so
     *                 only the callback detector hears it
     */
    static void blocking(String callType, boolean managed) {
        Thread thread = Thread.currentThread();
        BlockingOnNonBlockingThreadDetector nonBlocking =
                AsyncTestContext.currentBlockingOnNonBlockingThreadDetector();
        if (nonBlocking != null) {
            // Managed or not: a join of a CompletableFuture stalls an event loop all the same (#991).
            nonBlocking.recordBlockingCall(callType, thread);
        }
        CompletableFutureBlockingCallbackDetector callbacks =
                AsyncTestContext.currentCfBlockingCallbackDetector();
        if (callbacks != null) {
            callbacks.recordBlockingCall(thread, callType);
        }
        if (managed) {
            return;
        }
        HandedTask task = COMMON_POOL_TASK.get();
        if (task != null) {
            CompletableFutureCommonPoolBlockingDetector pool =
                    AsyncTestContext.currentCfCommonPoolBlockingDetector();
            if (pool != null) {
                pool.recordBlockingCall(task, thread, callType);
            }
        }
    }

    /**
     * Inserted by the agent's {@code RunBlockingWeaver} before each call of a Kotlin
     * {@code runBlocking} (#1007). It substitutes nothing and takes nothing off the stack: the call
     * that follows it is the original, made unchanged.
     *
     * <p>Only {@code BlockingOnNonBlockingThreadDetector} hears it, which reports it on a coroutine
     * dispatcher worker or a thread that must not block; the thread is the caller's, so a
     * {@code runBlocking} on an ordinary thread, its intended use, reports nothing.
     *
     * @since 1.14.0
     */
    public static void runBlockingEntered() {
        ControlledSchedule.point();
        BlockingOnNonBlockingThreadDetector nonBlocking =
                AsyncTestContext.currentBlockingOnNonBlockingThreadDetector();
        if (nonBlocking != null) {
            nonBlocking.recordRunBlocking(Thread.currentThread());
        }
    }

    /**
     * Weaves {@code kotlinx.coroutines.sync.Mutex.lock(owner, continuation)}, the call
     * {@code mutex.withLock(owner) { }} and {@code mutex.lock(owner)} compile to (#1007). Around the
     * lock, {@code KotlinMutexReentrancyDetector} learns who holds the mutex and hears a lock that
     * waits for its own coroutine.
     *
     * <p>While a run hears it, a call with no owner is made with a fresh owner token instead, so the
     * mutex itself can later say whether this lock still holds it (#1221). kotlinx compares owners
     * by identity and the token matches nothing else, so the lock, an {@code unlock(null)} and a
     * {@code tryLock} behave exactly as with no owner. The caller's {@code Job} is recorded with the
     * owner, and a call that waits while the caller, or a coroutine suspended until the caller's
     * {@code withContext}, {@code coroutineScope} or {@code withTimeout} block completes, holds the
     * mutex is the finding: that wait ends only with an unlock the waiting coroutine itself has to
     * make. The Kotlin types in the real descriptor are not on the library's class path, so the
     * hook takes {@code Object}s and calls through a handle bound to the receiver's own kotlinx.
     *
     * @param mutex        the receiver
     * @param owner        the owner token, or {@code null}
     * @param continuation the calling coroutine's continuation
     * @return what {@code lock} returned: {@code Unit}, or {@code COROUTINE_SUSPENDED}
     * @since 1.14.2
     */
    public static Object kotlinMutexLock(Object mutex, Object owner, Object continuation) {
        ControlledSchedule.point();
        KotlinMutexes.Binding binding = KotlinMutexes.of(mutex);
        KotlinMutexes.LockWatch watch = KotlinMutexes.LockWatch.before(
                AsyncTestContext.currentKotlinMutexReentrancyDetector(), mutex, owner, continuation, binding);
        Object result;
        try {
            result = binding.lock.invokeExact(mutex, watch == null ? owner : watch.owner, continuation);
        } catch (Throwable t) {
            KotlinMutexes.LockWatch.threw(watch);
            throw KotlinMutexes.rethrow(t);
        }
        KotlinMutexes.LockWatch.returned(watch, result);
        return result;
    }

    /**
     * Weaves {@code Mutex.lock$default}, the call a bare {@code mutex.lock()} compiles to, as
     * {@link #kotlinMutexLock}. The bridge only fills in the defaulted owner, {@code null}, and calls
     * {@code lock}, so while a run hears the call it is made as {@code lock} directly, with the owner
     * token; otherwise the bridge is called unchanged.
     *
     * @param mutex        the receiver
     * @param owner        the owner token as passed, ignored by kotlinx where {@code mask} omits it
     * @param continuation the calling coroutine's continuation
     * @param mask         which parameters the call site left to their defaults
     * @param marker       always {@code null} from a compiled call site
     * @return what {@code lock} returned
     * @since 1.14.2
     */
    public static Object kotlinMutexLockDefault(Object mutex, Object owner, Object continuation, int mask,
                                                Object marker) {
        ControlledSchedule.point();
        KotlinMutexes.Binding binding = KotlinMutexes.of(mutex);
        // A non-null marker makes the bridge throw: that call goes through it untouched.
        KotlinMutexes.LockWatch watch = marker != null ? null
                : KotlinMutexes.LockWatch.before(AsyncTestContext.currentKotlinMutexReentrancyDetector(),
                        mutex, (mask & 1) != 0 ? null : owner, continuation, binding);
        Object result;
        try {
            result = watch == null
                    ? binding.lockDefault.invokeExact(mutex, owner, continuation, mask, marker)
                    : binding.lock.invokeExact(mutex, watch.owner, continuation);
        } catch (Throwable t) {
            KotlinMutexes.LockWatch.threw(watch);
            throw KotlinMutexes.rethrow(t);
        }
        KotlinMutexes.LockWatch.returned(watch, result);
        return result;
    }

    /**
     * Weaves {@code Mutex.unlock(owner)}, a schedule point; the unlock is made unchanged. Nothing is
     * recorded: whether a lock still holds the mutex is asked of the mutex itself, so an unlock the
     * agent sees and one it does not end a hold alike (#1221).
     *
     * @param mutex the receiver
     * @param owner the owner token, or {@code null}
     * @since 1.14.2
     */
    public static void kotlinMutexUnlock(Object mutex, Object owner) {
        ControlledSchedule.point();
        KotlinMutexes.Binding binding = KotlinMutexes.of(mutex);
        try {
            binding.unlock.invokeExact(mutex, owner);
        } catch (Throwable t) {
            throw KotlinMutexes.rethrow(t);
        }
    }

    /**
     * Weaves {@code Mutex.unlock$default}, as {@link #kotlinMutexUnlock}.
     *
     * @param mutex  the receiver
     * @param owner  the owner token as passed
     * @param mask   which parameters the call site left to their defaults
     * @param marker always {@code null} from a compiled call site
     * @since 1.14.2
     */
    public static void kotlinMutexUnlockDefault(Object mutex, Object owner, int mask, Object marker) {
        ControlledSchedule.point();
        KotlinMutexes.Binding binding = KotlinMutexes.of(mutex);
        try {
            binding.unlockDefault.invokeExact(mutex, owner, mask, marker);
        } catch (Throwable t) {
            throw KotlinMutexes.rethrow(t);
        }
    }

    /**
     * Weaves {@code Mutex.tryLock(owner)} for an agent older than {@link #kotlinMutexTryLockIn},
     * which passes no continuation: a schedule point, and the call is made unchanged. The coroutine
     * that took the mutex cannot be named, so nothing is recorded and no lock is judged against it.
     *
     * @param mutex the receiver
     * @param owner the owner token, or {@code null}
     * @return whether the mutex was taken
     * @since 1.14.2
     */
    public static boolean kotlinMutexTryLock(Object mutex, Object owner) {
        ControlledSchedule.point();
        KotlinMutexes.Binding binding = KotlinMutexes.of(mutex);
        try {
            return (boolean) binding.tryLock.invokeExact(mutex, owner);
        } catch (Throwable t) {
            throw KotlinMutexes.rethrow(t);
        }
    }

    /**
     * Weaves {@code Mutex.tryLock$default}, as {@link #kotlinMutexTryLock}.
     *
     * @param mutex  the receiver
     * @param owner  the owner token as passed
     * @param mask   which parameters the call site left to their defaults
     * @param marker always {@code null} from a compiled call site
     * @return whether the mutex was taken
     * @since 1.14.2
     */
    public static boolean kotlinMutexTryLockDefault(Object mutex, Object owner, int mask, Object marker) {
        ControlledSchedule.point();
        KotlinMutexes.Binding binding = KotlinMutexes.of(mutex);
        try {
            return (boolean) binding.tryLockDefault.invokeExact(mutex, owner, mask, marker);
        } catch (Throwable t) {
            throw KotlinMutexes.rethrow(t);
        }
    }

    /**
     * Weaves {@code Mutex.tryLock(owner)} where the agent could name the calling coroutine (#1220):
     * {@code continuation} is the one of the suspend function or lambda the call is made in, which
     * the agent passes because {@code tryLock} itself takes none, or {@code null} where it found
     * none. While a run hears the call and the continuation names a {@code Job}, a call with no
     * owner is made with a fresh owner token, as {@link #kotlinMutexLock} does, and a mutex it takes
     * is recorded as held by that {@code Job}, so a later {@code lock} by the same coroutine that
     * waits for it is reported. A {@code tryLock} that finds the mutex taken records nothing and
     * never waits, so it is never itself the finding.
     *
     * @param mutex        the receiver
     * @param owner        the owner token, or {@code null}
     * @param continuation the enclosing coroutine's continuation, or {@code null}
     * @return whether the mutex was taken
     * @since 1.14.2
     */
    public static boolean kotlinMutexTryLockIn(Object mutex, Object owner, @Nullable Object continuation) {
        ControlledSchedule.point();
        KotlinMutexes.Binding binding = KotlinMutexes.of(mutex);
        KotlinMutexes.TryLockWatch watch = KotlinMutexes.TryLockWatch.before(
                AsyncTestContext.currentKotlinMutexReentrancyDetector(), mutex, owner, continuation, binding);
        boolean taken;
        try {
            taken = (boolean) binding.tryLock.invokeExact(mutex, watch == null ? owner : watch.owner);
        } catch (Throwable t) {
            throw KotlinMutexes.rethrow(t);
        }
        KotlinMutexes.TryLockWatch.returned(watch, taken);
        return taken;
    }

    /**
     * Weaves {@code Mutex.tryLock$default}, the call a bare {@code mutex.tryLock()} compiles to, as
     * {@link #kotlinMutexTryLockIn}. While a run hears the call it is made as {@code tryLock}
     * directly, with the owner token; otherwise the bridge is called unchanged.
     *
     * @param mutex        the receiver
     * @param owner        the owner token as passed, ignored by kotlinx where {@code mask} omits it
     * @param mask         which parameters the call site left to their defaults
     * @param marker       always {@code null} from a compiled call site
     * @param continuation the enclosing coroutine's continuation, or {@code null}
     * @return whether the mutex was taken
     * @since 1.14.2
     */
    public static boolean kotlinMutexTryLockDefaultIn(Object mutex, Object owner, int mask, Object marker,
                                                      @Nullable Object continuation) {
        ControlledSchedule.point();
        KotlinMutexes.Binding binding = KotlinMutexes.of(mutex);
        // A non-null marker makes the bridge throw: that call goes through it untouched.
        KotlinMutexes.TryLockWatch watch = marker != null ? null
                : KotlinMutexes.TryLockWatch.before(AsyncTestContext.currentKotlinMutexReentrancyDetector(),
                        mutex, (mask & 1) != 0 ? null : owner, continuation, binding);
        boolean taken;
        try {
            taken = watch == null
                    ? (boolean) binding.tryLockDefault.invokeExact(mutex, owner, mask, marker)
                    : (boolean) binding.tryLock.invokeExact(mutex, watch.owner);
        } catch (Throwable t) {
            throw KotlinMutexes.rethrow(t);
        }
        KotlinMutexes.TryLockWatch.returned(watch, taken);
        return taken;
    }

    /**
     * Weaves {@code SelectBuilder.invoke(clause, block)}, the registration an inlined
     * {@code select { mutex.onLock { } }} compiles to (#1220). {@code continuation} is the one of the
     * suspend function or lambda the {@code select} is written in, which the agent passes, or
     * {@code null} where it found none. While a run hears it and {@code clause} is a mutex's
     * {@code onLock}, the clause is registered with a fresh owner token, as {@link #kotlinMutexLock}
     * makes a lock with no owner, and the token is recorded with the calling coroutine's {@code Job}:
     * the mutex then says, through {@code holdsLock}, whether the clause was selected and still holds
     * it, and a later {@code lock} by the same coroutine that waits for it is reported. kotlinx
     * compares owners by identity, so the select and an {@code unlock(null)} behave as with no owner.
     * Every other clause, and every clause while no run hears the call, is registered unchanged.
     * A schedule point, like every substituted call.
     *
     * @param builder      the receiver, the {@code select}'s builder
     * @param clause       the clause, a {@code SelectClause2}
     * @param block        the clause's block
     * @param continuation the enclosing coroutine's continuation, or {@code null}
     * @since 1.14.3
     */
    public static void kotlinSelectInvokeIn(Object builder, Object clause, Object block,
                                            @Nullable Object continuation) {
        ControlledSchedule.point();
        KotlinMutexes.SelectBinding binding = KotlinMutexes.selectOf(builder);
        KotlinMutexes.TryLockWatch watch = KotlinMutexes.onLockWatch(
                AsyncTestContext.currentKotlinMutexReentrancyDetector(), clause, null, continuation, binding);
        try {
            if (watch == null) {
                binding.invoke.invokeExact(builder, clause, block);
            } else {
                binding.invokeWithOwner.invokeExact(builder, clause, watch.owner, block);
            }
        } catch (Throwable t) {
            throw KotlinMutexes.rethrow(t);
        }
        KotlinMutexes.TryLockWatch.registered(watch);
    }

    /**
     * Weaves {@code SelectBuilder.invoke(clause, owner, block)}, the registration an inlined
     * {@code select { mutex.onLock(owner) { } }} compiles to, as {@link #kotlinSelectInvokeIn}; an
     * owner the caller passed is kept, and the mutex is asked about that one (#1220).
     *
     * @param builder      the receiver, the {@code select}'s builder
     * @param clause       the clause, a {@code SelectClause2}
     * @param owner        the clause's parameter: for {@code onLock}, the owner token, or {@code null}
     * @param block        the clause's block
     * @param continuation the enclosing coroutine's continuation, or {@code null}
     * @since 1.14.3
     */
    public static void kotlinSelectInvokeOwnerIn(Object builder, Object clause, Object owner, Object block,
                                                 @Nullable Object continuation) {
        ControlledSchedule.point();
        KotlinMutexes.SelectBinding binding = KotlinMutexes.selectOf(builder);
        KotlinMutexes.TryLockWatch watch = KotlinMutexes.onLockWatch(
                AsyncTestContext.currentKotlinMutexReentrancyDetector(), clause, owner, continuation, binding);
        try {
            binding.invokeWithOwner.invokeExact(builder, clause, watch == null ? owner : watch.owner, block);
        } catch (Throwable t) {
            throw KotlinMutexes.rethrow(t);
        }
        KotlinMutexes.TryLockWatch.registered(watch);
    }

    /** {@link #blocking} for a get of {@code future}, managed when it is a {@code CompletableFuture}. */
    private static void blockingOn(Future<?> future) {
        if (future instanceof CompletableFuture) {
            blocking("CompletableFuture.get", true);
        } else {
            blocking("Future.get", false);
        }
    }

    /**
     * {@return a short name for a callback or task in a report}: the declaring class for a lambda
     * or method reference, whose own class name is a generated one, else the simple class name.
     */
    static String callbackLabel(Object function) {
        String name = function.getClass().getName();
        int lambda = name.indexOf("$$Lambda");
        String owner = lambda >= 0 ? name.substring(0, lambda) : name;
        owner = owner.substring(owner.lastIndexOf('.') + 1);
        return lambda >= 0 ? "lambda in " + owner : owner;
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
        ControlledSchedule.point();
        taskSubmitted(receiver);
        HandedTask handed = null;
        try {
            if (!wrapsInvisibly(receiver, task)) {
                return receiver.submit(task);
            }
            handed = new HandedTask(task).onExecutor(receiver);
            return Submissions.remember(receiver.submit((Callable<Object>) handed), handed);
        } catch (RejectedExecutionException e) {
            if (handed != null) {
                handed.abandoned();
            }
            throw rejected(receiver, e);
        }
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
        ControlledSchedule.point();
        taskSubmitted(receiver);
        HandedTask handed = null;
        try {
            if (!wrapsInvisibly(receiver, task)) {
                return receiver.submit(task);
            }
            handed = new HandedTask(task).onExecutor(receiver);
            return Submissions.remember(receiver.submit((Runnable) handed), handed);
        } catch (RejectedExecutionException e) {
            if (handed != null) {
                handed.abandoned();
            }
            throw rejected(receiver, e);
        }
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
        ControlledSchedule.point();
        taskSubmitted(receiver);
        HandedTask handed = null;
        try {
            if (!wrapsInvisibly(receiver, task)) {
                return receiver.submit(task, result);
            }
            handed = new HandedTask(task).onExecutor(receiver);
            return Submissions.remember(receiver.submit((Runnable) handed, result), handed);
        } catch (RejectedExecutionException e) {
            if (handed != null) {
                handed.abandoned();
            }
            throw rejected(receiver, e);
        }
    }

    /**
     * Stands in for the {@code POP} after a woven {@code submit} whose future the caller never
     * kept. Nothing can inspect that future afterwards, so the detector judges it at analysis by
     * whether its task failed.
     *
     * @param future the future the caller discarded
     * @since 1.13.2
     */
    public static void submitResultDiscarded(Future<?> future) {
        ControlledSchedule.point();
        FutureIgnoredDetector detector = AsyncTestContext.currentFutureIgnoredDetector();
        if (detector != null) {
            detector.recordDiscarded(future, Thread.currentThread(), "ExecutorService.submit");
        }
    }

    /**
     * Weaves {@code ScheduledExecutorService.scheduleAtFixedRate}: the task is scheduled as it
     * was, and its future is handed to the ignored-future detector, which reports it at analysis
     * if the task threw, since the executor then cancels every later run and nobody reads a
     * periodic task's future (#1254).
     *
     * @param receiver     the scheduler
     * @param task         the periodic task, scheduled unchanged
     * @param initialDelay the delay before the first run
     * @param period       the period between the starts of runs
     * @param unit         the unit of both
     * @return the task's future
     * @since 1.14.3
     */
    public static ScheduledFuture<?> scheduleAtFixedRate(ScheduledExecutorService receiver,
            Runnable task, long initialDelay, long period, TimeUnit unit) {
        ControlledSchedule.point();
        ScheduledFuture<?> future = receiver.scheduleAtFixedRate(task, initialDelay, period, unit);
        periodicScheduled(future, "ScheduledExecutorService.scheduleAtFixedRate");
        return future;
    }

    /**
     * Weaves {@code ScheduledExecutorService.scheduleWithFixedDelay}, as
     * {@link #scheduleAtFixedRate}.
     *
     * @param receiver     the scheduler
     * @param task         the periodic task, scheduled unchanged
     * @param initialDelay the delay before the first run
     * @param delay        the delay between the end of one run and the start of the next
     * @param unit         the unit of both
     * @return the task's future
     * @since 1.14.3
     */
    public static ScheduledFuture<?> scheduleWithFixedDelay(ScheduledExecutorService receiver,
            Runnable task, long initialDelay, long delay, TimeUnit unit) {
        ControlledSchedule.point();
        ScheduledFuture<?> future = receiver.scheduleWithFixedDelay(task, initialDelay, delay, unit);
        periodicScheduled(future, "ScheduledExecutorService.scheduleWithFixedDelay");
        return future;
    }

    /** Hands a periodic task's future to the ignored-future detector of the run, if any. */
    private static void periodicScheduled(ScheduledFuture<?> future, String call) {
        FutureIgnoredDetector detector = AsyncTestContext.currentFutureIgnoredDetector();
        if (detector != null) {
            detector.recordPeriodic(future, Thread.currentThread(), call);
        }
    }

    /** Tells the ignored-future detector that a get threw {@code future}'s failure into the body. */
    private static void failureRead(Future<?> future) {
        FutureIgnoredDetector detector = AsyncTestContext.currentFutureIgnoredDetector();
        if (detector != null) {
            detector.recordPeriodicRead(future);
        }
    }

    /** Counts a task handed to {@code executor} for the shutdown detector, which ignores an executor the run did not create. */
    private static void taskSubmitted(Executor executor) {
        if (executor instanceof ExecutorService service) {
            ExecutorShutdownDetector detector = AsyncTestContext.currentExecutorShutdownDetector();
            if (detector != null) {
                detector.recordTaskSubmitted(service);
            }
        }
    }

    /**
     * Declares {@code executor}, just made by a woven {@code Executors} factory, as owned by the
     * run the calling thread works for, and returns it.
     *
     * <p>Ownership is the shutdown detector's whole rule (#387): it reports only an executor it was
     * told the code created, so a pool handed in from outside the run draws nothing. A pool built
     * in a static initializer is not declared either. It is shared by design and lives as long as
     * its class, so the run that happened to load the class is not the one to close it. The stack
     * is walked only here, once per executor made, never on a task's path.
     */
    private static <E extends ExecutorService> E created(E executor) {
        ExecutorShutdownDetector detector = AsyncTestContext.currentExecutorShutdownDetector();
        if (detector != null && !insideAStaticInitializer()) {
            detector.recordExecutorCreated(executor, null);
        }
        return executor;
    }

    /** {@return whether a class initializer is on the calling thread's stack} */
    private static boolean insideAStaticInitializer() {
        return STACK.walk(frames -> frames.anyMatch(f -> "<clinit>".equals(f.getMethodName())));
    }

    private static final StackWalker STACK = StackWalker.getInstance();

    /**
     * Weaves {@code Executors.newFixedThreadPool(int)}: the pool is declared as the run's own.
     *
     * @param threads the number of threads
     * @return the new pool
     * @since 1.13.2
     */
    public static ExecutorService newFixedThreadPool(int threads) {
        ControlledSchedule.point();
        return created(Executors.newFixedThreadPool(threads));
    }

    /**
     * Weaves {@code Executors.newFixedThreadPool(int, ThreadFactory)}, as {@link #newFixedThreadPool(int)}.
     *
     * @param threads the number of threads
     * @param factory the thread factory
     * @return the new pool
     * @since 1.13.2
     */
    public static ExecutorService newFixedThreadPool(int threads, ThreadFactory factory) {
        ControlledSchedule.point();
        return created(Executors.newFixedThreadPool(threads, factory));
    }

    /**
     * Weaves {@code Executors.newCachedThreadPool()}, as {@link #newFixedThreadPool(int)}.
     *
     * @return the new pool
     * @since 1.13.2
     */
    public static ExecutorService newCachedThreadPool() {
        ControlledSchedule.point();
        return created(Executors.newCachedThreadPool());
    }

    /**
     * Weaves {@code Executors.newCachedThreadPool(ThreadFactory)}, as {@link #newFixedThreadPool(int)}.
     *
     * @param factory the thread factory
     * @return the new pool
     * @since 1.13.2
     */
    public static ExecutorService newCachedThreadPool(ThreadFactory factory) {
        ControlledSchedule.point();
        return created(Executors.newCachedThreadPool(factory));
    }

    /**
     * Weaves {@code Executors.newSingleThreadExecutor()}, as {@link #newFixedThreadPool(int)}.
     *
     * @return the new executor
     * @since 1.13.2
     */
    public static ExecutorService newSingleThreadExecutor() {
        ControlledSchedule.point();
        return created(Executors.newSingleThreadExecutor());
    }

    /**
     * Weaves {@code Executors.newSingleThreadExecutor(ThreadFactory)}, as {@link #newFixedThreadPool(int)}.
     *
     * @param factory the thread factory
     * @return the new executor
     * @since 1.13.2
     */
    public static ExecutorService newSingleThreadExecutor(ThreadFactory factory) {
        ControlledSchedule.point();
        return created(Executors.newSingleThreadExecutor(factory));
    }

    /**
     * Weaves {@code Executors.newScheduledThreadPool(int)}, as {@link #newFixedThreadPool(int)}.
     *
     * @param coreThreads the number of threads kept in the pool
     * @return the new pool
     * @since 1.13.2
     */
    public static ScheduledExecutorService newScheduledThreadPool(int coreThreads) {
        ControlledSchedule.point();
        return created(Executors.newScheduledThreadPool(coreThreads));
    }

    /**
     * Weaves {@code Executors.newScheduledThreadPool(int, ThreadFactory)}, as {@link #newFixedThreadPool(int)}.
     *
     * @param coreThreads the number of threads kept in the pool
     * @param factory     the thread factory
     * @return the new pool
     * @since 1.13.2
     */
    public static ScheduledExecutorService newScheduledThreadPool(int coreThreads, ThreadFactory factory) {
        ControlledSchedule.point();
        return created(Executors.newScheduledThreadPool(coreThreads, factory));
    }

    /**
     * Weaves {@code Executors.newSingleThreadScheduledExecutor()}, as {@link #newFixedThreadPool(int)}.
     *
     * @return the new executor
     * @since 1.13.2
     */
    public static ScheduledExecutorService newSingleThreadScheduledExecutor() {
        ControlledSchedule.point();
        return created(Executors.newSingleThreadScheduledExecutor());
    }

    /**
     * Weaves {@code Executors.newSingleThreadScheduledExecutor(ThreadFactory)}, as {@link #newFixedThreadPool(int)}.
     *
     * @param factory the thread factory
     * @return the new executor
     * @since 1.13.2
     */
    public static ScheduledExecutorService newSingleThreadScheduledExecutor(ThreadFactory factory) {
        ControlledSchedule.point();
        return created(Executors.newSingleThreadScheduledExecutor(factory));
    }

    /**
     * Weaves {@code Executors.newWorkStealingPool()}, as {@link #newFixedThreadPool(int)}.
     *
     * @return the new pool
     * @since 1.13.2
     */
    public static ExecutorService newWorkStealingPool() {
        ControlledSchedule.point();
        return created(Executors.newWorkStealingPool());
    }

    /**
     * Weaves {@code Executors.newWorkStealingPool(int)}, as {@link #newFixedThreadPool(int)}.
     *
     * @param parallelism the targeted parallelism level
     * @return the new pool
     * @since 1.13.2
     */
    public static ExecutorService newWorkStealingPool(int parallelism) {
        ControlledSchedule.point();
        return created(Executors.newWorkStealingPool(parallelism));
    }

    /**
     * Weaves {@code Executors.newVirtualThreadPerTaskExecutor()}, as {@link #newFixedThreadPool(int)}.
     *
     * @return the new executor
     * @since 1.13.2
     */
    public static ExecutorService newVirtualThreadPerTaskExecutor() {
        ControlledSchedule.point();
        return created(Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * Weaves {@code Executors.newThreadPerTaskExecutor(ThreadFactory)}, as {@link #newFixedThreadPool(int)}.
     *
     * @param factory the factory each task's thread comes from
     * @return the new executor
     * @since 1.13.2
     */
    public static ExecutorService newThreadPerTaskExecutor(ThreadFactory factory) {
        ControlledSchedule.point();
        return created(Executors.newThreadPerTaskExecutor(factory));
    }

    /**
     * Wrappers a woven {@code Executors.unconfigurable*} call made, to the executor each forwards
     * to (#1107). Weak on the wrapper, which the executor behind it never references.
     */
    private static final Map<Executor, Executor> UNCONFIGURABLE =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Weaves {@code Executors.unconfigurableExecutorService(ExecutorService)}: remembers what the
     * wrapper forwards to, so a woven {@code execute} on it decides by the executor behind it
     * (#1107). kotlinx's {@code newSingleThreadContext} wraps a {@code ScheduledThreadPoolExecutor}
     * this way, and without the look-through nothing its thread did reached a detector.
     *
     * @param executor the executor to wrap
     * @return what the JDK returns
     * @since 1.14.1
     */
    public static ExecutorService unconfigurableExecutorService(ExecutorService executor) {
        ControlledSchedule.point();
        ExecutorService wrapper = Executors.unconfigurableExecutorService(executor);
        UNCONFIGURABLE.put(wrapper, executor);
        return wrapper;
    }

    /**
     * Weaves {@code Executors.unconfigurableScheduledExecutorService(ScheduledExecutorService)}, as
     * {@link #unconfigurableExecutorService}.
     *
     * @param executor the executor to wrap
     * @return what the JDK returns
     * @since 1.14.1
     */
    public static ScheduledExecutorService unconfigurableScheduledExecutorService(
            ScheduledExecutorService executor) {
        ControlledSchedule.point();
        ScheduledExecutorService wrapper = Executors.unconfigurableScheduledExecutorService(executor);
        UNCONFIGURABLE.put(wrapper, executor);
        return wrapper;
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
        ControlledSchedule.point();
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
        ControlledSchedule.point();
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
     * Weaves {@code Executor.execute(Runnable)} (#741, #834): on an executor that never hands the
     * task back, the task runs as a {@link HandedTask}, so it starts ordered after this call, and
     * the thread running it is attributed to the run while it does. There is no future, so nothing
     * is ordered after the task.
     *
     * <p>Only the JDK's own {@code ForkJoinPool}, {@code ScheduledThreadPoolExecutor} and the
     * thread-per-task executor behind {@code Executors.newVirtualThreadPerTaskExecutor()} qualify,
     * by exact class: the first runs it inside its own adapter and returns no queued task from
     * {@code shutdownNow}, the second queues its own futures, and the third queues nothing. A
     * {@code ThreadPoolExecutor} qualifies too, inside a run (#1155), and so does a subclass that
     * overrides none of the methods a task reaches (#1173), and a wrapper {@code Executors} built
     * around any of these (#1107, #1269); see {@link #executesInvisibly}. Any
     * other subclass's tasks are left alone, since its {@code beforeExecute} would see the wrapper,
     * as are those of any executor outside the JDK's.
     *
     * @param receiver the executor the call site invoked
     * @param command  what to run; the executor rejects {@code null}
     * @since 1.12.4
     */
    public static void execute(Executor receiver, Runnable command) {
        ControlledSchedule.point();
        // The NullPointerException the call itself throws for a missing task.
        Objects.requireNonNull(command);
        taskSubmitted(receiver);
        HandedTask handed = null;
        try {
            if (!(command instanceof ForkJoinTask) && executesInvisibly(receiver)) {
                handed = new HandedTask(command).endsIn(receiver);
                receiver.execute(handed);
                return;
            }
            receiver.execute(command);
        } catch (RejectedExecutionException e) {
            if (handed != null) {
                handed.abandoned();
            }
            throw rejected(receiver, e);
        }
    }

    /**
     * One parallel {@code forEach} the run started (#1156): the common pool's workers help it in
     * JDK code the agent does not weave, so nothing attributes them to the run on its own.
     *
     * <p>An element on a helping thread runs as a small handed task: ordered after the call, with
     * the run lent to the thread and the task token published around it. The caller's own elements
     * need none of that. When the operation returns, the caller receives each helper's clock, since
     * a terminal operation has joined its tasks: reading a result afterwards is correct. No element
     * is ordered against another, which is the race this exists to see.
     */
    static final class ParallelForEach extends Handed {

        private final Thread caller = Thread.currentThread();
        private final HappensBefore.Stamp started = HappensBefore.handOff();
        private final long token = TOKENS.incrementAndGet();
        private final @Nullable AsyncTestContext startedIn = AsyncTestContext.agentContext();
        private final Map<Long, HappensBefore.Stamp> helpers = new ConcurrentHashMap<>();

        ParallelForEach() {
            TelemetryRegistry.taskSubmitted(token);
        }

        @SuppressWarnings("ReferenceEquality") // the calling thread, by identity
        void element(Runnable body) {
            Thread thread = Thread.currentThread();
            if (thread == caller) { // NOPMD CompareObjectsWithEquals - the same thread
                body.run();
                // For a combine on a helper, which may merge what this element built (#1172).
                helpers.put(thread.threadId(), HappensBefore.handOff());
                return;
            }
            HappensBefore.receive(started);
            AsyncTestContext lentBefore = AsyncTestContext.lend(startedIn);
            TelemetryRegistry.taskStarted(token);
            long taskBefore = enterTask();
            try {
                body.run();
            } finally {
                helpers.put(thread.threadId(), HappensBefore.handOff());
                TelemetryRegistry.taskEnded();
                leaveTask(taskBefore);
                AsyncTestContext.restoreLent(lentBefore);
            }
        }

        /**
         * Runs {@code body}, a combiner, a reduce operator or a comparator, as an element ordered
         * after every element that finished before it (#1172).
         *
         * <p>Each of these merges what other subtasks built, and the operation runs it only once
         * those subtasks are done; the fork and join that order them are JDK code the agent does
         * not see. Every element publishes its thread's clock when it ends, so receiving all of
         * them covers the subtasks being merged. It may also cover a subtask that finished but is
         * not merged here, which the read of the map that carries the clocks does order.
         */
        void combined(Runnable body) {
            helpers.values().forEach(HappensBefore::receive);
            element(body);
        }

        /** Orders the caller after every helper; the operation has returned. */
        void joined() {
            helpers.values().forEach(HappensBefore::receive);
        }
    }

    /**
     * Weaves {@code Stream.forEach}: see {@link ParallelForEach}. A sequential stream, or one outside
     * a run, takes the call as it was (#1156).
     *
     * @param receiver the stream
     * @param action   what to run for each element
     * @since 1.14.1
     */
    public static void streamForEach(Stream<Object> receiver, Consumer<Object> action) {
        parallelTerminal(receiver, operation -> {
            receiver.forEach(operation == null ? action
                    : value -> operation.element(() -> action.accept(value)));
            return null;
        });
    }

    // --- The rest of a parallel Stream pipeline (#1172) ----------------------------------------

    /**
     * A lambda the run gave to {@code map}, {@code filter} or {@code peek}, waiting for the
     * terminal operation that runs it (#1172).
     *
     * <p>An intermediate operation only builds: whether the stream runs in parallel, and when the
     * caller's writes are ordered before its elements, is settled by the terminal operation. So
     * the woven terminal operation lends its {@link ParallelForEach} to every slot of its pipeline
     * for as long as it runs. A slot nothing lends to runs its lambda as it was: a pipeline whose
     * terminal operation is not woven, such as {@code iterator()}, would have no join to order the
     * caller after its helpers, and lending them the run without one would report the read of a
     * result as a race.
     */
    static final class StageSlot {

        private volatile @Nullable ParallelForEach operation;

        <T> T element(Supplier<T> body) {
            return inElement(operation, body);
        }

        /** Runs a {@code sorted} comparator, which compares what other subtasks produced, as a combine. */
        <T> T combine(Supplier<T> body) {
            return inCombine(operation, body);
        }
    }

    /**
     * Per stream a woven intermediate operation returned: the slots of every woven stage behind it.
     * Weak in the stream, which an abandoned pipeline drops, and holding only the slots, which hold
     * nothing of the stream.
     */
    private static final Map<BaseStream<?, ?>, List<StageSlot>> PIPELINES =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Runs {@code body} as an element of {@code operation}, or as it was when there is none.
     *
     * @param operation the parallel operation running it, or null
     * @param body      the lambda's own work
     * @param <T>       what it returns
     * @return what {@code body} returned
     */
    @SuppressWarnings("unchecked")
    private static <T> T inElement(@Nullable ParallelForEach operation, Supplier<T> body) {
        if (operation == null) {
            return body.get();
        }
        Object[] result = new Object[1];
        operation.element(() -> result[0] = body.get());
        return (T) result[0];
    }

    /**
     * Runs {@code body} as a combine of {@code operation} ({@link ParallelForEach#combined}), or as
     * it was when there is none.
     *
     * @param operation the parallel operation running it, or null
     * @param body      the combine's own work
     * @param <T>       what it returns
     * @return what {@code body} returned
     */
    @SuppressWarnings("unchecked")
    private static <T> T inCombine(@Nullable ParallelForEach operation, Supplier<T> body) {
        if (operation == null) {
            return body.get();
        }
        Object[] result = new Object[1];
        operation.combined(() -> result[0] = body.get());
        return (T) result[0];
    }

    /**
     * Records that {@code result} is {@code receiver} with one more woven stage, {@code slot}, or
     * with none for a stage that runs no lambda, such as {@code boxed}. {@code receiver} is linked
     * now and can no longer be operated on, so its entry goes. The two may be different kinds of
     * stream: {@code mapToInt} turns a {@code Stream} into an {@code IntStream}, and the slots go
     * along with it.
     */
    private static <S extends BaseStream<?, ?>> S staged(BaseStream<?, ?> receiver, S result,
                                                         @Nullable StageSlot slot) {
        List<StageSlot> behind = PIPELINES.remove(receiver);
        List<StageSlot> slots = new ArrayList<>(behind == null ? List.of() : behind);
        if (slot != null) {
            slots.add(slot);
        }
        if (!slots.isEmpty()) {
            PIPELINES.put(result, slots);
        }
        return result;
    }

    /**
     * Runs one terminal operation on {@code receiver}: with a fresh {@link ParallelForEach} lent to
     * every woven stage of its pipeline when the stream is parallel and a run is in progress, and
     * with null and no slot touched otherwise, which is the call as it was.
     *
     * <p>Every terminal operation of a stream waits for the tasks it forked, short-circuiting ones
     * included, so when it returns no helper is still running a lambda of it: the slots are
     * cleared and the caller receives every helper's clock, as after {@code forEach} (#1156).
     *
     * @param receiver  the stream
     * @param operation the terminal operation, given the parallel operation or null
     * @param <T>       what it returns
     * @return what it returned
     */
    private static <T> T parallelTerminal(BaseStream<?, ?> receiver,
                                          Function<@Nullable ParallelForEach, T> operation) {
        ControlledSchedule.point();
        List<StageSlot> stages = PIPELINES.remove(receiver);
        if (!receiver.isParallel() || AsyncTestContext.agentContext() == null) {
            return operation.apply(null);
        }
        ParallelForEach parallel = new ParallelForEach();
        if (stages != null) {
            stages.forEach(slot -> slot.operation = parallel);
        }
        try {
            return operation.apply(parallel);
        } finally {
            if (stages != null) {
                stages.forEach(slot -> slot.operation = null);
            }
            parallel.joined();
        }
    }

    /**
     * Weaves {@code Stream.map}: see {@link StageSlot}. Outside a run the call is as it was.
     *
     * @param receiver the stream
     * @param mapper   the function given to each element
     * @return the mapped stream
     * @since 1.14.2
     */
    public static Stream<Object> streamMap(Stream<Object> receiver, Function<Object, Object> mapper) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.map(mapper);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.map(value -> slot.element(() -> mapper.apply(value))), slot);
    }

    /**
     * Weaves {@code Stream.filter}, as {@link #streamMap}.
     *
     * @param receiver  the stream
     * @param predicate what keeps an element
     * @return the filtered stream
     * @since 1.14.2
     */
    public static Stream<Object> streamFilter(Stream<Object> receiver, Predicate<Object> predicate) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.filter(predicate);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver,
                receiver.filter(value -> slot.element(() -> predicate.test(value))), slot);
    }

    /**
     * Weaves {@code Stream.peek}, as {@link #streamMap}.
     *
     * @param receiver the stream
     * @param action   what to run for each element as it passes
     * @return the peeked stream
     * @since 1.14.2
     */
    public static Stream<Object> streamPeek(Stream<Object> receiver, Consumer<Object> action) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.peek(action);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.peek(value -> slot.element(() -> {
            action.accept(value);
            return null;
        })), slot);
    }

    /**
     * Weaves {@code Stream.forEachOrdered}, as {@link #streamForEach}.
     *
     * @param receiver the stream
     * @param action   what to run for each element, in encounter order
     * @since 1.14.2
     */
    public static void streamForEachOrdered(Stream<Object> receiver, Consumer<Object> action) {
        parallelTerminal(receiver, operation -> {
            receiver.forEachOrdered(operation == null ? action
                    : value -> operation.element(() -> action.accept(value)));
            return null;
        });
    }

    /**
     * Weaves {@code Stream.collect(Collector)}: the pipeline's woven stages run as its elements.
     * The collector's own functions are left as they are; the JDK's run no woven code.
     *
     * @param receiver  the stream
     * @param collector what accumulates the elements, run as it is
     * @return what the collector produced
     * @since 1.14.2
     */
    public static Object streamCollect(Stream<Object> receiver, Collector<? super Object, ?, ?> collector) {
        return parallelTerminal(receiver, operation -> operation == null
                ? receiver.collect(collector) : receiver.collect(lent(operation, collector)));
    }

    /**
     * {@return {@code collector} with its supplier and accumulator run as elements of
     * {@code operation}, and its combiner as a combine (#1172)} The finisher runs on the caller
     * once the operation has joined, and the characteristics are the collector's own, so an
     * identity finish still hands back the container.
     */
    @SuppressWarnings("unchecked")
    private static Collector<Object, Object, Object> lent(ParallelForEach operation,
                                                         Collector<? super Object, ?, ?> given) {
        Collector<Object, Object, Object> collector = (Collector<Object, Object, Object>) given;
        Supplier<Object> supplier = collector.supplier();
        BiConsumer<Object, Object> accumulator = collector.accumulator();
        BinaryOperator<Object> combiner = collector.combiner();
        return new Collector<>() {
            @Override
            public Supplier<Object> supplier() {
                return () -> inElement(operation, supplier);
            }

            @Override
            public BiConsumer<Object, Object> accumulator() {
                return (container, value) -> inElement(operation, () -> {
                    accumulator.accept(container, value);
                    return null;
                });
            }

            @Override
            public BinaryOperator<Object> combiner() {
                return (left, right) -> inCombine(operation, () -> combiner.apply(left, right));
            }

            @Override
            public Function<Object, Object> finisher() {
                return collector.finisher();
            }

            @Override
            public Set<Collector.Characteristics> characteristics() {
                return collector.characteristics();
            }
        };
    }

    /**
     * Weaves {@code Stream.collect(Supplier, BiConsumer, BiConsumer)}: supplier and accumulator run
     * as elements, and the combiner, which merges containers other subtasks filled, as a combine.
     *
     * @param receiver    the stream
     * @param supplier    makes a container
     * @param accumulator adds an element to one
     * @param combiner    merges two
     * @return the container
     * @since 1.14.2
     */
    public static Object streamCollect(Stream<Object> receiver, Supplier<Object> supplier,
                                       BiConsumer<Object, Object> accumulator,
                                       BiConsumer<Object, Object> combiner) {
        return parallelTerminal(receiver, operation -> operation == null
                ? receiver.collect(supplier, accumulator, combiner)
                : receiver.collect(() -> inElement(operation, supplier),
                        (container, value) -> inElement(operation, () -> {
                            accumulator.accept(container, value);
                            return null;
                        }),
                        (left, right) -> inCombine(operation, () -> {
                            combiner.accept(left, right);
                            return null;
                        })));
    }

    /**
     * Weaves {@code Stream.reduce(BinaryOperator)}: the accumulator runs as a combine, since it
     * also merges partial results other subtasks built ({@link ParallelForEach#combined}).
     *
     * @param receiver    the stream
     * @param accumulator combines two values
     * @return the reduction, if any element
     * @since 1.14.2
     */
    public static Optional<Object> streamReduce(Stream<Object> receiver, BinaryOperator<Object> accumulator) {
        return parallelTerminal(receiver, operation -> receiver.reduce(operation == null ? accumulator
                : (left, right) -> inCombine(operation, () -> accumulator.apply(left, right))));
    }

    /**
     * Weaves {@code Stream.reduce(Object, BinaryOperator)}, as {@link #streamReduce(Stream, BinaryOperator)}.
     *
     * @param receiver    the stream
     * @param identity    the identity value
     * @param accumulator combines two values
     * @return the reduction
     * @since 1.14.2
     */
    public static Object streamReduce(Stream<Object> receiver, Object identity,
                                      BinaryOperator<Object> accumulator) {
        return parallelTerminal(receiver, operation -> receiver.reduce(identity, operation == null
                ? accumulator
                : (left, right) -> inCombine(operation, () -> accumulator.apply(left, right))));
    }

    /**
     * Weaves {@code Stream.reduce(Object, BiFunction, BinaryOperator)}: the accumulator runs as an
     * element and the combiner as a combine.
     *
     * @param receiver    the stream
     * @param identity    the identity value
     * @param accumulator folds an element into a partial result
     * @param combiner    merges two partial results
     * @return the reduction
     * @since 1.14.2
     */
    public static Object streamReduce(Stream<Object> receiver, Object identity,
                                      BiFunction<Object, Object, Object> accumulator,
                                      BinaryOperator<Object> combiner) {
        return parallelTerminal(receiver, operation -> operation == null
                ? receiver.reduce(identity, accumulator, combiner)
                : receiver.reduce(identity,
                        (partial, value) -> inElement(operation, () -> accumulator.apply(partial, value)),
                        (left, right) -> inCombine(operation, () -> combiner.apply(left, right))));
    }

    /**
     * Weaves {@code Stream.min(Comparator)}: the comparator runs as a combine, since it compares
     * elements other subtasks produced.
     *
     * @param receiver   the stream
     * @param comparator orders two values
     * @return the least, if any element
     * @since 1.14.2
     */
    public static Optional<Object> streamMin(Stream<Object> receiver, Comparator<Object> comparator) {
        return parallelTerminal(receiver, operation -> receiver.min(compared(operation, comparator)));
    }

    /**
     * Weaves {@code Stream.max(Comparator)}, as {@link #streamMin}.
     *
     * @param receiver   the stream
     * @param comparator orders two values
     * @return the greatest, if any element
     * @since 1.14.2
     */
    public static Optional<Object> streamMax(Stream<Object> receiver, Comparator<Object> comparator) {
        return parallelTerminal(receiver, operation -> receiver.max(compared(operation, comparator)));
    }

    private static Comparator<Object> compared(@Nullable ParallelForEach operation,
                                               Comparator<Object> comparator) {
        return operation == null ? comparator
                : (left, right) -> inCombine(operation, () -> comparator.compare(left, right));
    }

    /**
     * Weaves {@code Stream.anyMatch}: the predicate runs as an element.
     *
     * @param receiver  the stream
     * @param predicate the test
     * @return whether any element passed
     * @since 1.14.2
     */
    public static boolean streamAnyMatch(Stream<Object> receiver, Predicate<Object> predicate) {
        return parallelTerminal(receiver, operation -> receiver.anyMatch(tested(operation, predicate)));
    }

    /**
     * Weaves {@code Stream.allMatch}, as {@link #streamAnyMatch}.
     *
     * @param receiver  the stream
     * @param predicate the test
     * @return whether every element passed
     * @since 1.14.2
     */
    public static boolean streamAllMatch(Stream<Object> receiver, Predicate<Object> predicate) {
        return parallelTerminal(receiver, operation -> receiver.allMatch(tested(operation, predicate)));
    }

    /**
     * Weaves {@code Stream.noneMatch}, as {@link #streamAnyMatch}.
     *
     * @param receiver  the stream
     * @param predicate the test
     * @return whether no element passed
     * @since 1.14.2
     */
    public static boolean streamNoneMatch(Stream<Object> receiver, Predicate<Object> predicate) {
        return parallelTerminal(receiver, operation -> receiver.noneMatch(tested(operation, predicate)));
    }

    private static Predicate<Object> tested(@Nullable ParallelForEach operation, Predicate<Object> predicate) {
        return operation == null ? predicate
                : value -> inElement(operation, () -> predicate.test(value));
    }

    /**
     * Weaves {@code Stream.toList()}: the pipeline's woven stages run as its elements.
     *
     * @param receiver the stream
     * @return the elements
     * @since 1.14.2
     */
    public static List<Object> streamToList(Stream<Object> receiver) {
        return parallelTerminal(receiver, operation -> receiver.toList());
    }

    /**
     * Weaves {@code Stream.toArray()}, as {@link #streamToList}.
     *
     * @param receiver the stream
     * @return the elements
     * @since 1.14.2
     */
    public static Object[] streamToArray(Stream<Object> receiver) {
        return parallelTerminal(receiver, operation -> receiver.toArray());
    }

    /**
     * Weaves {@code Stream.toArray(IntFunction)}, as {@link #streamToList}; the generator is the
     * array constructor nearly always and is left as it is.
     *
     * @param receiver  the stream
     * @param generator makes the array
     * @return the elements
     * @since 1.14.2
     */
    public static Object[] streamToArray(Stream<Object> receiver, IntFunction<Object[]> generator) {
        return parallelTerminal(receiver, operation -> receiver.toArray(generator));
    }

    /**
     * Weaves {@code Stream.count()}, as {@link #streamToList}.
     *
     * @param receiver the stream
     * @return how many elements
     * @since 1.14.2
     */
    public static long streamCount(Stream<Object> receiver) {
        return parallelTerminal(receiver, operation -> receiver.count());
    }

    /**
     * Weaves {@code Stream.findFirst()}, as {@link #streamToList}.
     *
     * @param receiver the stream
     * @return the first element, if any
     * @since 1.14.2
     */
    public static Optional<Object> streamFindFirst(Stream<Object> receiver) {
        return parallelTerminal(receiver, operation -> receiver.findFirst());
    }

    /**
     * Weaves {@code Stream.findAny()}, as {@link #streamToList}.
     *
     * @param receiver the stream
     * @return some element, if any
     * @since 1.14.2
     */
    public static Optional<Object> streamFindAny(Stream<Object> receiver) {
        return parallelTerminal(receiver, operation -> receiver.findAny());
    }

    /**
     * Weaves {@code IntStream.forEach}, as {@link #streamForEach}.
     *
     * @param receiver the stream
     * @param action   what to run for each element
     * @since 1.14.1
     */
    public static void intStreamForEach(IntStream receiver, IntConsumer action) {
        parallelTerminal(receiver, operation -> {
            receiver.forEach(operation == null ? action
                    : value -> operation.element(() -> action.accept(value)));
            return null;
        });
    }

    /**
     * Weaves {@code LongStream.forEach}, as {@link #streamForEach}.
     *
     * @param receiver the stream
     * @param action   what to run for each element
     * @since 1.14.1
     */
    public static void longStreamForEach(LongStream receiver, LongConsumer action) {
        parallelTerminal(receiver, operation -> {
            receiver.forEach(operation == null ? action
                    : value -> operation.element(() -> action.accept(value)));
            return null;
        });
    }

    /**
     * Weaves {@code DoubleStream.forEach}, as {@link #streamForEach}.
     *
     * @param receiver the stream
     * @param action   what to run for each element
     * @since 1.14.1
     */
    public static void doubleStreamForEach(DoubleStream receiver, DoubleConsumer action) {
        parallelTerminal(receiver, operation -> {
            receiver.forEach(operation == null ? action
                    : value -> operation.element(() -> action.accept(value)));
            return null;
        });
    }

    // --- Stages that run no lambda, and the remaining lambda stages (#1172) ---------------------

    /**
     * Weaves {@code Stream.sorted}: it runs no lambda of its own and carries the woven stages behind
     * it to the stream it returns, so the terminal operation still lends them the run.
     *
     * @param receiver the stream
     * @return the stream
     * @since 1.14.2
     */
    public static Stream<Object> streamSorted(Stream<Object> receiver) {
        ControlledSchedule.point();
        Stream<Object> result = receiver.sorted();
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code Stream.distinct}: it runs no lambda of its own and carries the woven stages behind
     * it to the stream it returns, so the terminal operation still lends them the run.
     *
     * @param receiver the stream
     * @return the stream
     * @since 1.14.2
     */
    public static Stream<Object> streamDistinct(Stream<Object> receiver) {
        ControlledSchedule.point();
        Stream<Object> result = receiver.distinct();
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code Stream.limit}: it runs no lambda of its own and carries the woven stages behind
     * it to the stream it returns, so the terminal operation still lends them the run.
     *
     * @param receiver the stream
     * @param maxSize how many to keep
     * @return the stream
     * @since 1.14.2
     */
    public static Stream<Object> streamLimit(Stream<Object> receiver, long maxSize) {
        ControlledSchedule.point();
        Stream<Object> result = receiver.limit(maxSize);
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code Stream.skip}: it runs no lambda of its own and carries the woven stages behind
     * it to the stream it returns, so the terminal operation still lends them the run.
     *
     * @param receiver the stream
     * @param n how many to drop
     * @return the stream
     * @since 1.14.2
     */
    public static Stream<Object> streamSkip(Stream<Object> receiver, long n) {
        ControlledSchedule.point();
        Stream<Object> result = receiver.skip(n);
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code Stream.parallel}: it runs no lambda of its own and carries the woven stages behind
     * it to the stream it returns, so the terminal operation still lends them the run.
     *
     * @param receiver the stream
     * @return the stream; declared by {@code BaseStream}, so the call site expects one
     * @since 1.14.2
     */
    public static BaseStream<?, ?> streamParallel(Stream<Object> receiver) {
        ControlledSchedule.point();
        BaseStream<?, ?> result = receiver.parallel();
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code Stream.sequential}: it runs no lambda of its own and carries the woven stages behind
     * it to the stream it returns, so the terminal operation still lends them the run.
     *
     * @param receiver the stream
     * @return the stream; declared by {@code BaseStream}, so the call site expects one
     * @since 1.14.2
     */
    public static BaseStream<?, ?> streamSequential(Stream<Object> receiver) {
        ControlledSchedule.point();
        BaseStream<?, ?> result = receiver.sequential();
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code Stream.unordered}: it runs no lambda of its own and carries the woven stages behind
     * it to the stream it returns, so the terminal operation still lends them the run.
     *
     * @param receiver the stream
     * @return the stream; declared by {@code BaseStream}, so the call site expects one
     * @since 1.14.2
     */
    public static BaseStream<?, ?> streamUnordered(Stream<Object> receiver) {
        ControlledSchedule.point();
        BaseStream<?, ?> result = receiver.unordered();
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code Stream.sorted}: the comparator runs as a combine, since a parallel sort compares elements other subtasks produced.
     *
     * @param receiver the stream
     * @param comparator orders two elements; the sort calls it on pool workers
     * @return the stream it returns
     * @since 1.14.2
     */
    public static Stream<Object> streamSorted(Stream<Object> receiver, Comparator<Object> comparator) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.sorted(comparator);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.sorted((value, other) -> slot.combine(() -> comparator.compare(value, other))), slot);
    }

    /**
     * Weaves {@code Stream.flatMap}: the mapper runs as an element; the stream it returns is consumed inside that element.
     *
     * @param receiver the stream
     * @param mapper the lambda given to each element
     * @return the stream it returns
     * @since 1.14.2
     */
    public static Stream<Object> streamFlatMap(Stream<Object> receiver, Function<Object, Stream<Object>> mapper) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.flatMap(mapper);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.flatMap(value -> slot.element(() -> mapper.apply(value))), slot);
    }

    /**
     * Weaves {@code Stream.mapMulti}: the mapper runs as an element, and the values it passes on
     * go downstream inside it (#1172).
     *
     * @param receiver the stream
     * @param mapper   the function given each element and the sink for what it maps to
     * @return the stream it returns
     * @since 1.14.3
     */
    public static Stream<Object> streamMapMulti(Stream<Object> receiver, BiConsumer<Object, Consumer<Object>> mapper) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.mapMulti(mapper);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.<Object>mapMulti((value, sink) -> slot.element(() -> {
            mapper.accept(value, sink);
            return null;
        })), slot);
    }

    /**
     * Weaves {@code Stream.mapMultiToInt}, as {@link #streamMapMulti}.
     *
     * @param receiver the stream
     * @param mapper   the function given each element and the sink for what it maps to
     * @return the stream it returns
     * @since 1.14.3
     */
    public static IntStream streamMapMultiToInt(Stream<Object> receiver, BiConsumer<Object, IntConsumer> mapper) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.mapMultiToInt(mapper);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.mapMultiToInt((value, sink) -> slot.element(() -> {
            mapper.accept(value, sink);
            return null;
        })), slot);
    }

    /**
     * Weaves {@code Stream.mapMultiToLong}, as {@link #streamMapMulti}.
     *
     * @param receiver the stream
     * @param mapper   the function given each element and the sink for what it maps to
     * @return the stream it returns
     * @since 1.14.3
     */
    public static LongStream streamMapMultiToLong(Stream<Object> receiver, BiConsumer<Object, LongConsumer> mapper) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.mapMultiToLong(mapper);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.mapMultiToLong((value, sink) -> slot.element(() -> {
            mapper.accept(value, sink);
            return null;
        })), slot);
    }

    /**
     * Weaves {@code Stream.mapMultiToDouble}, as {@link #streamMapMulti}.
     *
     * @param receiver the stream
     * @param mapper   the function given each element and the sink for what it maps to
     * @return the stream it returns
     * @since 1.14.3
     */
    public static DoubleStream streamMapMultiToDouble(Stream<Object> receiver,
                                                      BiConsumer<Object, DoubleConsumer> mapper) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.mapMultiToDouble(mapper);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.mapMultiToDouble((value, sink) -> slot.element(() -> {
            mapper.accept(value, sink);
            return null;
        })), slot);
    }

    /**
     * Weaves {@code IntStream.mapMulti}, as {@link #streamMapMulti}.
     *
     * @param receiver the stream
     * @param mapper   the function given each element and the sink for what it maps to
     * @return the stream it returns
     * @since 1.14.3
     */
    public static IntStream intStreamMapMulti(IntStream receiver, IntStream.IntMapMultiConsumer mapper) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.mapMulti(mapper);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.mapMulti((value, sink) -> slot.element(() -> {
            mapper.accept(value, sink);
            return null;
        })), slot);
    }

    /**
     * Weaves {@code LongStream.mapMulti}, as {@link #streamMapMulti}.
     *
     * @param receiver the stream
     * @param mapper   the function given each element and the sink for what it maps to
     * @return the stream it returns
     * @since 1.14.3
     */
    public static LongStream longStreamMapMulti(LongStream receiver, LongStream.LongMapMultiConsumer mapper) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.mapMulti(mapper);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.mapMulti((value, sink) -> slot.element(() -> {
            mapper.accept(value, sink);
            return null;
        })), slot);
    }

    /**
     * Weaves {@code DoubleStream.mapMulti}, as {@link #streamMapMulti}.
     *
     * @param receiver the stream
     * @param mapper   the function given each element and the sink for what it maps to
     * @return the stream it returns
     * @since 1.14.3
     */
    public static DoubleStream doubleStreamMapMulti(DoubleStream receiver, DoubleStream.DoubleMapMultiConsumer mapper) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.mapMulti(mapper);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.mapMulti((value, sink) -> slot.element(() -> {
            mapper.accept(value, sink);
            return null;
        })), slot);
    }

    /**
     * Weaves {@code IntStream.unordered}, which {@code IntStream} inherits from {@code BaseStream}:
     * it runs no lambda of its own and carries the woven stages behind it (#1172).
     *
     * @param receiver the stream
     * @return the stream it returns
     * @since 1.14.3
     */
    public static BaseStream<?, ?> intStreamUnordered(IntStream receiver) {
        ControlledSchedule.point();
        IntStream result = receiver.unordered();
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code LongStream.unordered}, as {@link #intStreamUnordered}.
     *
     * @param receiver the stream
     * @return the stream it returns
     * @since 1.14.3
     */
    public static BaseStream<?, ?> longStreamUnordered(LongStream receiver) {
        ControlledSchedule.point();
        LongStream result = receiver.unordered();
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code DoubleStream.unordered}, as {@link #intStreamUnordered}.
     *
     * @param receiver the stream
     * @return the stream it returns
     * @since 1.14.3
     */
    public static BaseStream<?, ?> doubleStreamUnordered(DoubleStream receiver) {
        ControlledSchedule.point();
        DoubleStream result = receiver.unordered();
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code Stream.onClose}: the close handler runs at {@code close()}, not in the
     * pipeline, so it carries the woven stages behind it like a stage with no lambda (#1172).
     *
     * @param receiver     the stream
     * @param closeHandler what {@code close()} runs
     * @return the stream it returns
     * @since 1.14.3
     */
    public static BaseStream<?, ?> streamOnClose(Stream<Object> receiver, Runnable closeHandler) {
        ControlledSchedule.point();
        Stream<Object> result = receiver.onClose(closeHandler);
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code IntStream.onClose}, as {@link #streamOnClose}.
     *
     * @param receiver     the stream
     * @param closeHandler what {@code close()} runs
     * @return the stream it returns
     * @since 1.14.3
     */
    public static BaseStream<?, ?> intStreamOnClose(IntStream receiver, Runnable closeHandler) {
        ControlledSchedule.point();
        IntStream result = receiver.onClose(closeHandler);
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code LongStream.onClose}, as {@link #streamOnClose}.
     *
     * @param receiver     the stream
     * @param closeHandler what {@code close()} runs
     * @return the stream it returns
     * @since 1.14.3
     */
    public static BaseStream<?, ?> longStreamOnClose(LongStream receiver, Runnable closeHandler) {
        ControlledSchedule.point();
        LongStream result = receiver.onClose(closeHandler);
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code DoubleStream.onClose}, as {@link #streamOnClose}.
     *
     * @param receiver     the stream
     * @param closeHandler what {@code close()} runs
     * @return the stream it returns
     * @since 1.14.3
     */
    public static BaseStream<?, ?> doubleStreamOnClose(DoubleStream receiver, Runnable closeHandler) {
        ControlledSchedule.point();
        DoubleStream result = receiver.onClose(closeHandler);
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * The woven stages of a pipeline ended by {@code iterator()} (#1172). The pipeline runs a little
     * at a time, inside the caller's {@code hasNext} and {@code next}, and each of those returns
     * only once the work it started is done: a stateful stage such as {@code sorted} evaluates
     * everything upstream, in parallel, inside the first one. So each call lends the stages a fresh
     * {@link ParallelForEach}, which orders the elements after the caller's writes so far, and
     * joins it when the call returns, which orders the caller after them.
     */
    private static final class LentPipeline {
        private final List<StageSlot> stages;

        LentPipeline(List<StageSlot> stages) {
            this.stages = stages;
        }

        <T> T call(Supplier<T> step) {
            if (AsyncTestContext.agentContext() == null) {
                return step.get();
            }
            ParallelForEach parallel = new ParallelForEach();
            stages.forEach(slot -> slot.operation = parallel);
            try {
                return step.get();
            } finally {
                stages.forEach(slot -> slot.operation = null);
                parallel.joined();
            }
        }

        /** {@return the woven stages behind {@code receiver} to lend, or {@code null} to iterate it as it was} */
        static @Nullable LentPipeline of(BaseStream<?, ?> receiver) {
            List<StageSlot> stages = PIPELINES.remove(receiver);
            return stages == null || !receiver.isParallel() || AsyncTestContext.agentContext() == null
                    ? null : new LentPipeline(stages);
        }
    }

    /**
     * Weaves {@code Stream.iterator()}: see {@link LentPipeline}. A sequential pipeline, one with no
     * woven stage, or one outside a run is iterated as it was.
     *
     * @param receiver the stream
     * @return the iterator over its elements
     * @since 1.14.3
     */
    public static Iterator<Object> streamIterator(Stream<Object> receiver) {
        ControlledSchedule.point();
        LentPipeline lent = LentPipeline.of(receiver);
        Iterator<Object> elements = receiver.iterator();
        if (lent == null) {
            return elements;
        }
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return lent.call(elements::hasNext);
            }

            @Override
            @SuppressFBWarnings(value = "IT_NO_SUCH_ELEMENT",
                    justification = "the stream's own iterator throws it, inside the lent call")
            public Object next() {
                return lent.call(elements::next);
            }
        };
    }

    /**
     * Weaves {@code IntStream.iterator()}, as {@link #streamIterator}.
     *
     * @param receiver the stream
     * @return the iterator over its elements
     * @since 1.14.3
     */
    public static PrimitiveIterator.OfInt intStreamIterator(IntStream receiver) {
        ControlledSchedule.point();
        LentPipeline lent = LentPipeline.of(receiver);
        PrimitiveIterator.OfInt elements = receiver.iterator();
        if (lent == null) {
            return elements;
        }
        return new PrimitiveIterator.OfInt() {
            @Override
            public boolean hasNext() {
                return lent.call(elements::hasNext);
            }

            @Override
            public int nextInt() {
                return lent.call(elements::nextInt);
            }
        };
    }

    /**
     * Weaves {@code LongStream.iterator()}, as {@link #streamIterator}.
     *
     * @param receiver the stream
     * @return the iterator over its elements
     * @since 1.14.3
     */
    public static PrimitiveIterator.OfLong longStreamIterator(LongStream receiver) {
        ControlledSchedule.point();
        LentPipeline lent = LentPipeline.of(receiver);
        PrimitiveIterator.OfLong elements = receiver.iterator();
        if (lent == null) {
            return elements;
        }
        return new PrimitiveIterator.OfLong() {
            @Override
            public boolean hasNext() {
                return lent.call(elements::hasNext);
            }

            @Override
            public long nextLong() {
                return lent.call(elements::nextLong);
            }
        };
    }

    /**
     * Weaves {@code DoubleStream.iterator()}, as {@link #streamIterator}.
     *
     * @param receiver the stream
     * @return the iterator over its elements
     * @since 1.14.3
     */
    public static PrimitiveIterator.OfDouble doubleStreamIterator(DoubleStream receiver) {
        ControlledSchedule.point();
        LentPipeline lent = LentPipeline.of(receiver);
        PrimitiveIterator.OfDouble elements = receiver.iterator();
        if (lent == null) {
            return elements;
        }
        return new PrimitiveIterator.OfDouble() {
            @Override
            public boolean hasNext() {
                return lent.call(elements::hasNext);
            }

            @Override
            public double nextDouble() {
                return lent.call(elements::nextDouble);
            }
        };
    }

    /**
     * Weaves {@code Stream.takeWhile}: the predicate runs as an element.
     *
     * @param receiver the stream
     * @param predicate the lambda given to each element
     * @return the stream it returns
     * @since 1.14.2
     */
    public static Stream<Object> streamTakeWhile(Stream<Object> receiver, Predicate<Object> predicate) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.takeWhile(predicate);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.takeWhile(value -> slot.element(() -> predicate.test(value))), slot);
    }

    /**
     * Weaves {@code Stream.dropWhile}: the predicate runs as an element.
     *
     * @param receiver the stream
     * @param predicate the lambda given to each element
     * @return the stream it returns
     * @since 1.14.2
     */
    public static Stream<Object> streamDropWhile(Stream<Object> receiver, Predicate<Object> predicate) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.dropWhile(predicate);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.dropWhile(value -> slot.element(() -> predicate.test(value))), slot);
    }

    /**
     * Weaves {@code IntStream.sorted}: it runs no lambda of its own and carries the woven stages behind
     * it to the stream it returns, so the terminal operation still lends them the run.
     *
     * @param receiver the stream
     * @return the stream
     * @since 1.14.2
     */
    public static IntStream intStreamSorted(IntStream receiver) {
        ControlledSchedule.point();
        IntStream result = receiver.sorted();
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code IntStream.distinct}: it runs no lambda of its own and carries the woven stages behind
     * it to the stream it returns, so the terminal operation still lends them the run.
     *
     * @param receiver the stream
     * @return the stream
     * @since 1.14.2
     */
    public static IntStream intStreamDistinct(IntStream receiver) {
        ControlledSchedule.point();
        IntStream result = receiver.distinct();
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code IntStream.limit}: it runs no lambda of its own and carries the woven stages behind
     * it to the stream it returns, so the terminal operation still lends them the run.
     *
     * @param receiver the stream
     * @param maxSize how many to keep
     * @return the stream
     * @since 1.14.2
     */
    public static IntStream intStreamLimit(IntStream receiver, long maxSize) {
        ControlledSchedule.point();
        IntStream result = receiver.limit(maxSize);
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code IntStream.skip}: it runs no lambda of its own and carries the woven stages behind
     * it to the stream it returns, so the terminal operation still lends them the run.
     *
     * @param receiver the stream
     * @param n how many to drop
     * @return the stream
     * @since 1.14.2
     */
    public static IntStream intStreamSkip(IntStream receiver, long n) {
        ControlledSchedule.point();
        IntStream result = receiver.skip(n);
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code IntStream.parallel}: it runs no lambda of its own and carries the woven stages behind
     * it to the stream it returns, so the terminal operation still lends them the run.
     *
     * @param receiver the stream
     * @return the stream
     * @since 1.14.2
     */
    public static IntStream intStreamParallel(IntStream receiver) {
        ControlledSchedule.point();
        IntStream result = receiver.parallel();
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code IntStream.sequential}: it runs no lambda of its own and carries the woven stages behind
     * it to the stream it returns, so the terminal operation still lends them the run.
     *
     * @param receiver the stream
     * @return the stream
     * @since 1.14.2
     */
    public static IntStream intStreamSequential(IntStream receiver) {
        ControlledSchedule.point();
        IntStream result = receiver.sequential();
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code IntStream.flatMap}: the mapper runs as an element; the stream it returns is consumed inside that element.
     *
     * @param receiver the stream
     * @param mapper the lambda given to each element
     * @return the stream it returns
     * @since 1.14.2
     */
    public static IntStream intStreamFlatMap(IntStream receiver, IntFunction<IntStream> mapper) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.flatMap(mapper);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.flatMap(value -> slot.element(() -> mapper.apply(value))), slot);
    }

    /**
     * Weaves {@code IntStream.takeWhile}: the predicate runs as an element.
     *
     * @param receiver the stream
     * @param predicate the lambda given to each element
     * @return the stream it returns
     * @since 1.14.2
     */
    public static IntStream intStreamTakeWhile(IntStream receiver, IntPredicate predicate) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.takeWhile(predicate);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.takeWhile(value -> slot.element(() -> predicate.test(value))), slot);
    }

    /**
     * Weaves {@code IntStream.dropWhile}: the predicate runs as an element.
     *
     * @param receiver the stream
     * @param predicate the lambda given to each element
     * @return the stream it returns
     * @since 1.14.2
     */
    public static IntStream intStreamDropWhile(IntStream receiver, IntPredicate predicate) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.dropWhile(predicate);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.dropWhile(value -> slot.element(() -> predicate.test(value))), slot);
    }

    /**
     * Weaves {@code IntStream.mapToLong}: the mapper runs as an element, and the stages behind it stay in the pipeline the returned {@code LongStream} ends.
     *
     * @param receiver the stream
     * @param mapper the lambda given to each element
     * @return the stream it returns
     * @since 1.14.2
     */
    public static LongStream intStreamMapToLong(IntStream receiver, IntToLongFunction mapper) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.mapToLong(mapper);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.mapToLong(value -> slot.element(() -> mapper.applyAsLong(value))), slot);
    }

    /**
     * Weaves {@code IntStream.mapToDouble}: the mapper runs as an element, and the stages behind it stay in the pipeline the returned {@code DoubleStream} ends.
     *
     * @param receiver the stream
     * @param mapper the lambda given to each element
     * @return the stream it returns
     * @since 1.14.2
     */
    public static DoubleStream intStreamMapToDouble(IntStream receiver, IntToDoubleFunction mapper) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.mapToDouble(mapper);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.mapToDouble(value -> slot.element(() -> mapper.applyAsDouble(value))), slot);
    }

    /**
     * Weaves {@code IntStream.asLongStream}: it runs no lambda of its own and carries the woven stages behind
     * it to the stream it returns, so the terminal operation still lends them the run.
     *
     * @param receiver the stream
     * @return the stream
     * @since 1.14.2
     */
    public static LongStream intStreamAsLongStream(IntStream receiver) {
        ControlledSchedule.point();
        LongStream result = receiver.asLongStream();
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code IntStream.asDoubleStream}: it runs no lambda of its own and carries the woven stages behind
     * it to the stream it returns, so the terminal operation still lends them the run.
     *
     * @param receiver the stream
     * @return the stream
     * @since 1.14.2
     */
    public static DoubleStream intStreamAsDoubleStream(IntStream receiver) {
        ControlledSchedule.point();
        DoubleStream result = receiver.asDoubleStream();
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code LongStream.sorted}: it runs no lambda of its own and carries the woven stages behind
     * it to the stream it returns, so the terminal operation still lends them the run.
     *
     * @param receiver the stream
     * @return the stream
     * @since 1.14.2
     */
    public static LongStream longStreamSorted(LongStream receiver) {
        ControlledSchedule.point();
        LongStream result = receiver.sorted();
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code LongStream.distinct}: it runs no lambda of its own and carries the woven stages behind
     * it to the stream it returns, so the terminal operation still lends them the run.
     *
     * @param receiver the stream
     * @return the stream
     * @since 1.14.2
     */
    public static LongStream longStreamDistinct(LongStream receiver) {
        ControlledSchedule.point();
        LongStream result = receiver.distinct();
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code LongStream.limit}: it runs no lambda of its own and carries the woven stages behind
     * it to the stream it returns, so the terminal operation still lends them the run.
     *
     * @param receiver the stream
     * @param maxSize how many to keep
     * @return the stream
     * @since 1.14.2
     */
    public static LongStream longStreamLimit(LongStream receiver, long maxSize) {
        ControlledSchedule.point();
        LongStream result = receiver.limit(maxSize);
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code LongStream.skip}: it runs no lambda of its own and carries the woven stages behind
     * it to the stream it returns, so the terminal operation still lends them the run.
     *
     * @param receiver the stream
     * @param n how many to drop
     * @return the stream
     * @since 1.14.2
     */
    public static LongStream longStreamSkip(LongStream receiver, long n) {
        ControlledSchedule.point();
        LongStream result = receiver.skip(n);
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code LongStream.parallel}: it runs no lambda of its own and carries the woven stages behind
     * it to the stream it returns, so the terminal operation still lends them the run.
     *
     * @param receiver the stream
     * @return the stream
     * @since 1.14.2
     */
    public static LongStream longStreamParallel(LongStream receiver) {
        ControlledSchedule.point();
        LongStream result = receiver.parallel();
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code LongStream.sequential}: it runs no lambda of its own and carries the woven stages behind
     * it to the stream it returns, so the terminal operation still lends them the run.
     *
     * @param receiver the stream
     * @return the stream
     * @since 1.14.2
     */
    public static LongStream longStreamSequential(LongStream receiver) {
        ControlledSchedule.point();
        LongStream result = receiver.sequential();
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code LongStream.flatMap}: the mapper runs as an element; the stream it returns is consumed inside that element.
     *
     * @param receiver the stream
     * @param mapper the lambda given to each element
     * @return the stream it returns
     * @since 1.14.2
     */
    public static LongStream longStreamFlatMap(LongStream receiver, LongFunction<LongStream> mapper) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.flatMap(mapper);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.flatMap(value -> slot.element(() -> mapper.apply(value))), slot);
    }

    /**
     * Weaves {@code LongStream.takeWhile}: the predicate runs as an element.
     *
     * @param receiver the stream
     * @param predicate the lambda given to each element
     * @return the stream it returns
     * @since 1.14.2
     */
    public static LongStream longStreamTakeWhile(LongStream receiver, LongPredicate predicate) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.takeWhile(predicate);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.takeWhile(value -> slot.element(() -> predicate.test(value))), slot);
    }

    /**
     * Weaves {@code LongStream.dropWhile}: the predicate runs as an element.
     *
     * @param receiver the stream
     * @param predicate the lambda given to each element
     * @return the stream it returns
     * @since 1.14.2
     */
    public static LongStream longStreamDropWhile(LongStream receiver, LongPredicate predicate) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.dropWhile(predicate);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.dropWhile(value -> slot.element(() -> predicate.test(value))), slot);
    }

    /**
     * Weaves {@code LongStream.mapToInt}: the mapper runs as an element, and the stages behind it stay in the pipeline the returned {@code IntStream} ends.
     *
     * @param receiver the stream
     * @param mapper the lambda given to each element
     * @return the stream it returns
     * @since 1.14.2
     */
    public static IntStream longStreamMapToInt(LongStream receiver, LongToIntFunction mapper) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.mapToInt(mapper);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.mapToInt(value -> slot.element(() -> mapper.applyAsInt(value))), slot);
    }

    /**
     * Weaves {@code LongStream.mapToDouble}: the mapper runs as an element, and the stages behind it stay in the pipeline the returned {@code DoubleStream} ends.
     *
     * @param receiver the stream
     * @param mapper the lambda given to each element
     * @return the stream it returns
     * @since 1.14.2
     */
    public static DoubleStream longStreamMapToDouble(LongStream receiver, LongToDoubleFunction mapper) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.mapToDouble(mapper);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.mapToDouble(value -> slot.element(() -> mapper.applyAsDouble(value))), slot);
    }

    /**
     * Weaves {@code LongStream.asDoubleStream}: it runs no lambda of its own and carries the woven stages behind
     * it to the stream it returns, so the terminal operation still lends them the run.
     *
     * @param receiver the stream
     * @return the stream
     * @since 1.14.2
     */
    public static DoubleStream longStreamAsDoubleStream(LongStream receiver) {
        ControlledSchedule.point();
        DoubleStream result = receiver.asDoubleStream();
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code DoubleStream.sorted}: it runs no lambda of its own and carries the woven stages behind
     * it to the stream it returns, so the terminal operation still lends them the run.
     *
     * @param receiver the stream
     * @return the stream
     * @since 1.14.2
     */
    public static DoubleStream doubleStreamSorted(DoubleStream receiver) {
        ControlledSchedule.point();
        DoubleStream result = receiver.sorted();
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code DoubleStream.distinct}: it runs no lambda of its own and carries the woven stages behind
     * it to the stream it returns, so the terminal operation still lends them the run.
     *
     * @param receiver the stream
     * @return the stream
     * @since 1.14.2
     */
    public static DoubleStream doubleStreamDistinct(DoubleStream receiver) {
        ControlledSchedule.point();
        DoubleStream result = receiver.distinct();
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code DoubleStream.limit}: it runs no lambda of its own and carries the woven stages behind
     * it to the stream it returns, so the terminal operation still lends them the run.
     *
     * @param receiver the stream
     * @param maxSize how many to keep
     * @return the stream
     * @since 1.14.2
     */
    public static DoubleStream doubleStreamLimit(DoubleStream receiver, long maxSize) {
        ControlledSchedule.point();
        DoubleStream result = receiver.limit(maxSize);
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code DoubleStream.skip}: it runs no lambda of its own and carries the woven stages behind
     * it to the stream it returns, so the terminal operation still lends them the run.
     *
     * @param receiver the stream
     * @param n how many to drop
     * @return the stream
     * @since 1.14.2
     */
    public static DoubleStream doubleStreamSkip(DoubleStream receiver, long n) {
        ControlledSchedule.point();
        DoubleStream result = receiver.skip(n);
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code DoubleStream.parallel}: it runs no lambda of its own and carries the woven stages behind
     * it to the stream it returns, so the terminal operation still lends them the run.
     *
     * @param receiver the stream
     * @return the stream
     * @since 1.14.2
     */
    public static DoubleStream doubleStreamParallel(DoubleStream receiver) {
        ControlledSchedule.point();
        DoubleStream result = receiver.parallel();
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code DoubleStream.sequential}: it runs no lambda of its own and carries the woven stages behind
     * it to the stream it returns, so the terminal operation still lends them the run.
     *
     * @param receiver the stream
     * @return the stream
     * @since 1.14.2
     */
    public static DoubleStream doubleStreamSequential(DoubleStream receiver) {
        ControlledSchedule.point();
        DoubleStream result = receiver.sequential();
        return AsyncTestContext.agentContext() == null ? result : staged(receiver, result, null);
    }

    /**
     * Weaves {@code DoubleStream.flatMap}: the mapper runs as an element; the stream it returns is consumed inside that element.
     *
     * @param receiver the stream
     * @param mapper the lambda given to each element
     * @return the stream it returns
     * @since 1.14.2
     */
    public static DoubleStream doubleStreamFlatMap(DoubleStream receiver, DoubleFunction<DoubleStream> mapper) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.flatMap(mapper);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.flatMap(value -> slot.element(() -> mapper.apply(value))), slot);
    }

    /**
     * Weaves {@code DoubleStream.takeWhile}: the predicate runs as an element.
     *
     * @param receiver the stream
     * @param predicate the lambda given to each element
     * @return the stream it returns
     * @since 1.14.2
     */
    public static DoubleStream doubleStreamTakeWhile(DoubleStream receiver, DoublePredicate predicate) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.takeWhile(predicate);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.takeWhile(value -> slot.element(() -> predicate.test(value))), slot);
    }

    /**
     * Weaves {@code DoubleStream.dropWhile}: the predicate runs as an element.
     *
     * @param receiver the stream
     * @param predicate the lambda given to each element
     * @return the stream it returns
     * @since 1.14.2
     */
    public static DoubleStream doubleStreamDropWhile(DoubleStream receiver, DoublePredicate predicate) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.dropWhile(predicate);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.dropWhile(value -> slot.element(() -> predicate.test(value))), slot);
    }

    /**
     * Weaves {@code DoubleStream.mapToInt}: the mapper runs as an element, and the stages behind it stay in the pipeline the returned {@code IntStream} ends.
     *
     * @param receiver the stream
     * @param mapper the lambda given to each element
     * @return the stream it returns
     * @since 1.14.2
     */
    public static IntStream doubleStreamMapToInt(DoubleStream receiver, DoubleToIntFunction mapper) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.mapToInt(mapper);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.mapToInt(value -> slot.element(() -> mapper.applyAsInt(value))), slot);
    }

    /**
     * Weaves {@code DoubleStream.mapToLong}: the mapper runs as an element, and the stages behind it stay in the pipeline the returned {@code LongStream} ends.
     *
     * @param receiver the stream
     * @param mapper the lambda given to each element
     * @return the stream it returns
     * @since 1.14.2
     */
    public static LongStream doubleStreamMapToLong(DoubleStream receiver, DoubleToLongFunction mapper) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.mapToLong(mapper);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.mapToLong(value -> slot.element(() -> mapper.applyAsLong(value))), slot);
    }

    // --- Primitive streams, and the bridges between them and a Stream (#1172) ------------------

    /**
     * Weaves {@code Stream.mapToInt}, as {@link #streamMap}; the stages behind it stay in the
     * pipeline the returned {@code IntStream} ends.
     *
     * @param receiver the stream
     * @param mapper   the function given to each element
     * @return the mapped stream
     * @since 1.14.2
     */
    public static IntStream streamMapToInt(Stream<Object> receiver, ToIntFunction<Object> mapper) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.mapToInt(mapper);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.mapToInt(value -> slot.element(() -> mapper.applyAsInt(value))), slot);
    }

    /**
     * Weaves {@code Stream.mapToLong}, as {@link #streamMap}; the stages behind it stay in the
     * pipeline the returned {@code LongStream} ends.
     *
     * @param receiver the stream
     * @param mapper   the function given to each element
     * @return the mapped stream
     * @since 1.14.2
     */
    public static LongStream streamMapToLong(Stream<Object> receiver, ToLongFunction<Object> mapper) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.mapToLong(mapper);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.mapToLong(value -> slot.element(() -> mapper.applyAsLong(value))), slot);
    }

    /**
     * Weaves {@code Stream.mapToDouble}, as {@link #streamMap}; the stages behind it stay in the
     * pipeline the returned {@code DoubleStream} ends.
     *
     * @param receiver the stream
     * @param mapper   the function given to each element
     * @return the mapped stream
     * @since 1.14.2
     */
    public static DoubleStream streamMapToDouble(Stream<Object> receiver, ToDoubleFunction<Object> mapper) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.mapToDouble(mapper);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.mapToDouble(value -> slot.element(() -> mapper.applyAsDouble(value))), slot);
    }

    /**
     * Weaves {@code IntStream.map}, as {@link #streamMap}: the operator waits in a slot the
     * terminal operation lends the run to.
     *
     * @param receiver the stream
     * @param mapper   the operator given to each element
     * @return the mapped stream
     * @since 1.14.2
     */
    public static IntStream intStreamMap(IntStream receiver, IntUnaryOperator mapper) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.map(mapper);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.map(value -> slot.element(() -> mapper.applyAsInt(value))), slot);
    }

    /**
     * Weaves {@code IntStream.filter}, as {@link #streamFilter}.
     *
     * @param receiver  the stream
     * @param predicate what keeps an element
     * @return the filtered stream
     * @since 1.14.2
     */
    public static IntStream intStreamFilter(IntStream receiver, IntPredicate predicate) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.filter(predicate);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.filter(value -> slot.element(() -> predicate.test(value))), slot);
    }

    /**
     * Weaves {@code IntStream.peek}, as {@link #streamPeek}.
     *
     * @param receiver the stream
     * @param action   what to run for each element as it passes
     * @return the peeked stream
     * @since 1.14.2
     */
    public static IntStream intStreamPeek(IntStream receiver, IntConsumer action) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.peek(action);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.peek(value -> slot.element(() -> {
            action.accept(value);
            return null;
        })), slot);
    }

    /**
     * Weaves {@code IntStream.mapToObj}, as {@link #streamMap}; the stages behind it stay in the
     * pipeline the returned {@code Stream} ends.
     *
     * @param receiver the stream
     * @param mapper   the function given to each element
     * @return the mapped stream
     * @since 1.14.2
     */
    public static Stream<Object> intStreamMapToObj(IntStream receiver, IntFunction<Object> mapper) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.mapToObj(mapper);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.mapToObj(value -> slot.element(() -> mapper.apply(value))), slot);
    }

    /**
     * Weaves {@code IntStream.boxed}: it adds no lambda of its own, and carries the stages behind it to
     * the {@code Stream} it returns, so a woven {@code Stream} terminal operation still lends them
     * the run.
     *
     * @param receiver the stream
     * @return the elements as {@code Integer}
     * @since 1.14.2
     */
    @SuppressWarnings("unchecked")
    public static Stream<Object> intStreamBoxed(IntStream receiver) {
        ControlledSchedule.point();
        Stream<Object> boxed = (Stream<Object>) (Stream<?>) receiver.boxed();
        return AsyncTestContext.agentContext() == null ? boxed : staged(receiver, boxed, null);
    }

    /**
     * Weaves {@code IntStream.forEachOrdered}, as {@link #streamForEachOrdered}.
     *
     * @param receiver the stream
     * @param action   what to run for each element, in encounter order
     * @since 1.14.2
     */
    public static void intStreamForEachOrdered(IntStream receiver, IntConsumer action) {
        parallelTerminal(receiver, operation -> {
            receiver.forEachOrdered(operation == null ? action
                    : value -> operation.element(() -> action.accept(value)));
            return null;
        });
    }

    /**
     * Weaves {@code IntStream.reduce(int, IntBinaryOperator)}: the operator runs as a combine, since it
     * also merges partial results other subtasks built ({@link ParallelForEach#combined}).
     *
     * @param receiver the stream
     * @param identity the identity value
     * @param operator combines two values
     * @return the reduction
     * @since 1.14.2
     */
    public static int intStreamReduce(IntStream receiver, int identity, IntBinaryOperator operator) {
        return parallelTerminal(receiver, operation -> receiver.reduce(identity, intStreamCombined(operation, operator)));
    }

    /**
     * Weaves {@code IntStream.reduce(IntBinaryOperator)}, as the reduce with an identity.
     *
     * @param receiver the stream
     * @param operator combines two values
     * @return the reduction, if any element
     * @since 1.14.2
     */
    public static OptionalInt intStreamReduce(IntStream receiver, IntBinaryOperator operator) {
        return parallelTerminal(receiver, operation -> receiver.reduce(intStreamCombined(operation, operator)));
    }

    private static IntBinaryOperator intStreamCombined(@Nullable ParallelForEach operation, IntBinaryOperator operator) {
        return operation == null ? operator
                : (left, right) -> inCombine(operation, () -> operator.applyAsInt(left, right));
    }

    /**
     * Weaves {@code IntStream.collect}: supplier and accumulator run as elements, and the combiner,
     * which merges containers other subtasks filled, as a combine.
     *
     * @param receiver    the stream
     * @param supplier    makes a container
     * @param accumulator adds an element to one
     * @param combiner    merges two
     * @return the container
     * @since 1.14.2
     */
    public static Object intStreamCollect(IntStream receiver, Supplier<Object> supplier,
                                       ObjIntConsumer<Object> accumulator,
                                       BiConsumer<Object, Object> combiner) {
        return parallelTerminal(receiver, operation -> operation == null
                ? receiver.collect(supplier, accumulator, combiner)
                : receiver.collect(() -> inElement(operation, supplier),
                        (container, value) -> inElement(operation, () -> {
                            accumulator.accept(container, value);
                            return null;
                        }),
                        (left, right) -> inCombine(operation, () -> {
                            combiner.accept(left, right);
                            return null;
                        })));
    }

    /**
     * Weaves {@code IntStream.anyMatch}: the predicate runs as an element.
     *
     * @param receiver  the stream
     * @param predicate the test
     * @return whether any element passed
     * @since 1.14.2
     */
    public static boolean intStreamAnyMatch(IntStream receiver, IntPredicate predicate) {
        return parallelTerminal(receiver, operation -> receiver.anyMatch(operation == null ? predicate
                : value -> inElement(operation, () -> predicate.test(value))));
    }

    /**
     * Weaves {@code IntStream.allMatch}: the predicate runs as an element.
     *
     * @param receiver  the stream
     * @param predicate the test
     * @return whether every element passed
     * @since 1.14.2
     */
    public static boolean intStreamAllMatch(IntStream receiver, IntPredicate predicate) {
        return parallelTerminal(receiver, operation -> receiver.allMatch(operation == null ? predicate
                : value -> inElement(operation, () -> predicate.test(value))));
    }

    /**
     * Weaves {@code IntStream.noneMatch}: the predicate runs as an element.
     *
     * @param receiver  the stream
     * @param predicate the test
     * @return whether no element passed
     * @since 1.14.2
     */
    public static boolean intStreamNoneMatch(IntStream receiver, IntPredicate predicate) {
        return parallelTerminal(receiver, operation -> receiver.noneMatch(operation == null ? predicate
                : value -> inElement(operation, () -> predicate.test(value))));
    }

    /**
     * Weaves {@code IntStream.sum()}: the pipeline's woven stages run as its elements, and the caller is ordered after them once it returns.
     *
     * @param receiver the stream
     * @return the sum of the elements
     * @since 1.14.2
     */
    public static int intStreamSum(IntStream receiver) {
        return parallelTerminal(receiver, operation -> receiver.sum());
    }

    /**
     * Weaves {@code IntStream.count()}, as {@link #intStreamSum}.
     *
     * @param receiver the stream
     * @return how many elements
     * @since 1.14.2
     */
    public static long intStreamCount(IntStream receiver) {
        return parallelTerminal(receiver, operation -> receiver.count());
    }

    /**
     * Weaves {@code IntStream.average()}, as {@link #intStreamSum}.
     *
     * @param receiver the stream
     * @return the mean, if any element
     * @since 1.14.2
     */
    public static OptionalDouble intStreamAverage(IntStream receiver) {
        return parallelTerminal(receiver, operation -> receiver.average());
    }

    /**
     * Weaves {@code IntStream.min()}, as {@link #intStreamSum}.
     *
     * @param receiver the stream
     * @return the least, if any element
     * @since 1.14.2
     */
    public static OptionalInt intStreamMin(IntStream receiver) {
        return parallelTerminal(receiver, operation -> receiver.min());
    }

    /**
     * Weaves {@code IntStream.max()}, as {@link #intStreamSum}.
     *
     * @param receiver the stream
     * @return the greatest, if any element
     * @since 1.14.2
     */
    public static OptionalInt intStreamMax(IntStream receiver) {
        return parallelTerminal(receiver, operation -> receiver.max());
    }

    /**
     * Weaves {@code IntStream.summaryStatistics()}, as {@link #intStreamSum}.
     *
     * @param receiver the stream
     * @return count, sum, least, greatest and mean
     * @since 1.14.2
     */
    public static IntSummaryStatistics intStreamSummaryStatistics(IntStream receiver) {
        return parallelTerminal(receiver, operation -> receiver.summaryStatistics());
    }

    /**
     * Weaves {@code IntStream.toArray()}, as {@link #intStreamSum}.
     *
     * @param receiver the stream
     * @return the elements
     * @since 1.14.2
     */
    public static int[] intStreamToArray(IntStream receiver) {
        return parallelTerminal(receiver, operation -> receiver.toArray());
    }

    /**
     * Weaves {@code IntStream.findFirst()}, as {@link #intStreamSum}.
     *
     * @param receiver the stream
     * @return the first element, if any
     * @since 1.14.2
     */
    public static OptionalInt intStreamFindFirst(IntStream receiver) {
        return parallelTerminal(receiver, operation -> receiver.findFirst());
    }

    /**
     * Weaves {@code IntStream.findAny()}, as {@link #intStreamSum}.
     *
     * @param receiver the stream
     * @return some element, if any
     * @since 1.14.2
     */
    public static OptionalInt intStreamFindAny(IntStream receiver) {
        return parallelTerminal(receiver, operation -> receiver.findAny());
    }

    /**
     * Weaves {@code LongStream.map}, as {@link #intStreamMap}: the operator waits in a slot the
     * terminal operation lends the run to.
     *
     * @param receiver the stream
     * @param mapper   the operator given to each element
     * @return the mapped stream
     * @since 1.14.2
     */
    public static LongStream longStreamMap(LongStream receiver, LongUnaryOperator mapper) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.map(mapper);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.map(value -> slot.element(() -> mapper.applyAsLong(value))), slot);
    }

    /**
     * Weaves {@code LongStream.filter}, as {@link #intStreamFilter}.
     *
     * @param receiver  the stream
     * @param predicate what keeps an element
     * @return the filtered stream
     * @since 1.14.2
     */
    public static LongStream longStreamFilter(LongStream receiver, LongPredicate predicate) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.filter(predicate);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.filter(value -> slot.element(() -> predicate.test(value))), slot);
    }

    /**
     * Weaves {@code LongStream.peek}, as {@link #intStreamPeek}.
     *
     * @param receiver the stream
     * @param action   what to run for each element as it passes
     * @return the peeked stream
     * @since 1.14.2
     */
    public static LongStream longStreamPeek(LongStream receiver, LongConsumer action) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.peek(action);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.peek(value -> slot.element(() -> {
            action.accept(value);
            return null;
        })), slot);
    }

    /**
     * Weaves {@code LongStream.mapToObj}, as {@link #intStreamMapToObj}; the stages behind it stay in the
     * pipeline the returned {@code Stream} ends.
     *
     * @param receiver the stream
     * @param mapper   the function given to each element
     * @return the mapped stream
     * @since 1.14.2
     */
    public static Stream<Object> longStreamMapToObj(LongStream receiver, LongFunction<Object> mapper) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.mapToObj(mapper);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.mapToObj(value -> slot.element(() -> mapper.apply(value))), slot);
    }

    /**
     * Weaves {@code LongStream.boxed}: it adds no lambda of its own, and carries the stages behind it to
     * the {@code Stream} it returns, so a woven {@code Stream} terminal operation still lends them
     * the run.
     *
     * @param receiver the stream
     * @return the elements as {@code Long}
     * @since 1.14.2
     */
    @SuppressWarnings("unchecked")
    public static Stream<Object> longStreamBoxed(LongStream receiver) {
        ControlledSchedule.point();
        Stream<Object> boxed = (Stream<Object>) (Stream<?>) receiver.boxed();
        return AsyncTestContext.agentContext() == null ? boxed : staged(receiver, boxed, null);
    }

    /**
     * Weaves {@code LongStream.forEachOrdered}, as {@link #intStreamForEachOrdered}.
     *
     * @param receiver the stream
     * @param action   what to run for each element, in encounter order
     * @since 1.14.2
     */
    public static void longStreamForEachOrdered(LongStream receiver, LongConsumer action) {
        parallelTerminal(receiver, operation -> {
            receiver.forEachOrdered(operation == null ? action
                    : value -> operation.element(() -> action.accept(value)));
            return null;
        });
    }

    /**
     * Weaves {@code LongStream.reduce(long, LongBinaryOperator)}: the operator runs as a combine, since it
     * also merges partial results other subtasks built ({@link ParallelForEach#combined}).
     *
     * @param receiver the stream
     * @param identity the identity value
     * @param operator combines two values
     * @return the reduction
     * @since 1.14.2
     */
    public static long longStreamReduce(LongStream receiver, long identity, LongBinaryOperator operator) {
        return parallelTerminal(receiver, operation -> receiver.reduce(identity, longStreamCombined(operation, operator)));
    }

    /**
     * Weaves {@code LongStream.reduce(LongBinaryOperator)}, as the reduce with an identity.
     *
     * @param receiver the stream
     * @param operator combines two values
     * @return the reduction, if any element
     * @since 1.14.2
     */
    public static OptionalLong longStreamReduce(LongStream receiver, LongBinaryOperator operator) {
        return parallelTerminal(receiver, operation -> receiver.reduce(longStreamCombined(operation, operator)));
    }

    private static LongBinaryOperator longStreamCombined(@Nullable ParallelForEach operation, LongBinaryOperator operator) {
        return operation == null ? operator
                : (left, right) -> inCombine(operation, () -> operator.applyAsLong(left, right));
    }

    /**
     * Weaves {@code LongStream.collect}: supplier and accumulator run as elements, and the combiner,
     * which merges containers other subtasks filled, as a combine.
     *
     * @param receiver    the stream
     * @param supplier    makes a container
     * @param accumulator adds an element to one
     * @param combiner    merges two
     * @return the container
     * @since 1.14.2
     */
    public static Object longStreamCollect(LongStream receiver, Supplier<Object> supplier,
                                       ObjLongConsumer<Object> accumulator,
                                       BiConsumer<Object, Object> combiner) {
        return parallelTerminal(receiver, operation -> operation == null
                ? receiver.collect(supplier, accumulator, combiner)
                : receiver.collect(() -> inElement(operation, supplier),
                        (container, value) -> inElement(operation, () -> {
                            accumulator.accept(container, value);
                            return null;
                        }),
                        (left, right) -> inCombine(operation, () -> {
                            combiner.accept(left, right);
                            return null;
                        })));
    }

    /**
     * Weaves {@code LongStream.anyMatch}: the predicate runs as an element.
     *
     * @param receiver  the stream
     * @param predicate the test
     * @return whether any element passed
     * @since 1.14.2
     */
    public static boolean longStreamAnyMatch(LongStream receiver, LongPredicate predicate) {
        return parallelTerminal(receiver, operation -> receiver.anyMatch(operation == null ? predicate
                : value -> inElement(operation, () -> predicate.test(value))));
    }

    /**
     * Weaves {@code LongStream.allMatch}: the predicate runs as an element.
     *
     * @param receiver  the stream
     * @param predicate the test
     * @return whether every element passed
     * @since 1.14.2
     */
    public static boolean longStreamAllMatch(LongStream receiver, LongPredicate predicate) {
        return parallelTerminal(receiver, operation -> receiver.allMatch(operation == null ? predicate
                : value -> inElement(operation, () -> predicate.test(value))));
    }

    /**
     * Weaves {@code LongStream.noneMatch}: the predicate runs as an element.
     *
     * @param receiver  the stream
     * @param predicate the test
     * @return whether no element passed
     * @since 1.14.2
     */
    public static boolean longStreamNoneMatch(LongStream receiver, LongPredicate predicate) {
        return parallelTerminal(receiver, operation -> receiver.noneMatch(operation == null ? predicate
                : value -> inElement(operation, () -> predicate.test(value))));
    }

    /**
     * Weaves {@code LongStream.sum()}: the pipeline's woven stages run as its elements, and the caller is ordered after them once it returns.
     *
     * @param receiver the stream
     * @return the sum of the elements
     * @since 1.14.2
     */
    public static long longStreamSum(LongStream receiver) {
        return parallelTerminal(receiver, operation -> receiver.sum());
    }

    /**
     * Weaves {@code LongStream.count()}, as {@link #longStreamSum}.
     *
     * @param receiver the stream
     * @return how many elements
     * @since 1.14.2
     */
    public static long longStreamCount(LongStream receiver) {
        return parallelTerminal(receiver, operation -> receiver.count());
    }

    /**
     * Weaves {@code LongStream.average()}, as {@link #longStreamSum}.
     *
     * @param receiver the stream
     * @return the mean, if any element
     * @since 1.14.2
     */
    public static OptionalDouble longStreamAverage(LongStream receiver) {
        return parallelTerminal(receiver, operation -> receiver.average());
    }

    /**
     * Weaves {@code LongStream.min()}, as {@link #longStreamSum}.
     *
     * @param receiver the stream
     * @return the least, if any element
     * @since 1.14.2
     */
    public static OptionalLong longStreamMin(LongStream receiver) {
        return parallelTerminal(receiver, operation -> receiver.min());
    }

    /**
     * Weaves {@code LongStream.max()}, as {@link #longStreamSum}.
     *
     * @param receiver the stream
     * @return the greatest, if any element
     * @since 1.14.2
     */
    public static OptionalLong longStreamMax(LongStream receiver) {
        return parallelTerminal(receiver, operation -> receiver.max());
    }

    /**
     * Weaves {@code LongStream.summaryStatistics()}, as {@link #longStreamSum}.
     *
     * @param receiver the stream
     * @return count, sum, least, greatest and mean
     * @since 1.14.2
     */
    public static LongSummaryStatistics longStreamSummaryStatistics(LongStream receiver) {
        return parallelTerminal(receiver, operation -> receiver.summaryStatistics());
    }

    /**
     * Weaves {@code LongStream.toArray()}, as {@link #longStreamSum}.
     *
     * @param receiver the stream
     * @return the elements
     * @since 1.14.2
     */
    public static long[] longStreamToArray(LongStream receiver) {
        return parallelTerminal(receiver, operation -> receiver.toArray());
    }

    /**
     * Weaves {@code LongStream.findFirst()}, as {@link #longStreamSum}.
     *
     * @param receiver the stream
     * @return the first element, if any
     * @since 1.14.2
     */
    public static OptionalLong longStreamFindFirst(LongStream receiver) {
        return parallelTerminal(receiver, operation -> receiver.findFirst());
    }

    /**
     * Weaves {@code LongStream.findAny()}, as {@link #longStreamSum}.
     *
     * @param receiver the stream
     * @return some element, if any
     * @since 1.14.2
     */
    public static OptionalLong longStreamFindAny(LongStream receiver) {
        return parallelTerminal(receiver, operation -> receiver.findAny());
    }

    /**
     * Weaves {@code DoubleStream.map}, as {@link #intStreamMap}: the operator waits in a slot the
     * terminal operation lends the run to.
     *
     * @param receiver the stream
     * @param mapper   the operator given to each element
     * @return the mapped stream
     * @since 1.14.2
     */
    public static DoubleStream doubleStreamMap(DoubleStream receiver, DoubleUnaryOperator mapper) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.map(mapper);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.map(value -> slot.element(() -> mapper.applyAsDouble(value))), slot);
    }

    /**
     * Weaves {@code DoubleStream.filter}, as {@link #intStreamFilter}.
     *
     * @param receiver  the stream
     * @param predicate what keeps an element
     * @return the filtered stream
     * @since 1.14.2
     */
    public static DoubleStream doubleStreamFilter(DoubleStream receiver, DoublePredicate predicate) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.filter(predicate);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.filter(value -> slot.element(() -> predicate.test(value))), slot);
    }

    /**
     * Weaves {@code DoubleStream.peek}, as {@link #intStreamPeek}.
     *
     * @param receiver the stream
     * @param action   what to run for each element as it passes
     * @return the peeked stream
     * @since 1.14.2
     */
    public static DoubleStream doubleStreamPeek(DoubleStream receiver, DoubleConsumer action) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.peek(action);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.peek(value -> slot.element(() -> {
            action.accept(value);
            return null;
        })), slot);
    }

    /**
     * Weaves {@code DoubleStream.mapToObj}, as {@link #intStreamMapToObj}; the stages behind it stay in the
     * pipeline the returned {@code Stream} ends.
     *
     * @param receiver the stream
     * @param mapper   the function given to each element
     * @return the mapped stream
     * @since 1.14.2
     */
    public static Stream<Object> doubleStreamMapToObj(DoubleStream receiver, DoubleFunction<Object> mapper) {
        ControlledSchedule.point();
        if (AsyncTestContext.agentContext() == null) {
            return receiver.mapToObj(mapper);
        }
        StageSlot slot = new StageSlot();
        return staged(receiver, receiver.mapToObj(value -> slot.element(() -> mapper.apply(value))), slot);
    }

    /**
     * Weaves {@code DoubleStream.boxed}: it adds no lambda of its own, and carries the stages behind it to
     * the {@code Stream} it returns, so a woven {@code Stream} terminal operation still lends them
     * the run.
     *
     * @param receiver the stream
     * @return the elements as {@code Double}
     * @since 1.14.2
     */
    @SuppressWarnings("unchecked")
    public static Stream<Object> doubleStreamBoxed(DoubleStream receiver) {
        ControlledSchedule.point();
        Stream<Object> boxed = (Stream<Object>) (Stream<?>) receiver.boxed();
        return AsyncTestContext.agentContext() == null ? boxed : staged(receiver, boxed, null);
    }

    /**
     * Weaves {@code DoubleStream.forEachOrdered}, as {@link #intStreamForEachOrdered}.
     *
     * @param receiver the stream
     * @param action   what to run for each element, in encounter order
     * @since 1.14.2
     */
    public static void doubleStreamForEachOrdered(DoubleStream receiver, DoubleConsumer action) {
        parallelTerminal(receiver, operation -> {
            receiver.forEachOrdered(operation == null ? action
                    : value -> operation.element(() -> action.accept(value)));
            return null;
        });
    }

    /**
     * Weaves {@code DoubleStream.reduce(double, DoubleBinaryOperator)}: the operator runs as a combine, since it
     * also merges partial results other subtasks built ({@link ParallelForEach#combined}).
     *
     * @param receiver the stream
     * @param identity the identity value
     * @param operator combines two values
     * @return the reduction
     * @since 1.14.2
     */
    public static double doubleStreamReduce(DoubleStream receiver, double identity, DoubleBinaryOperator operator) {
        return parallelTerminal(receiver, operation -> receiver.reduce(identity, doubleStreamCombined(operation, operator)));
    }

    /**
     * Weaves {@code DoubleStream.reduce(DoubleBinaryOperator)}, as the reduce with an identity.
     *
     * @param receiver the stream
     * @param operator combines two values
     * @return the reduction, if any element
     * @since 1.14.2
     */
    public static OptionalDouble doubleStreamReduce(DoubleStream receiver, DoubleBinaryOperator operator) {
        return parallelTerminal(receiver, operation -> receiver.reduce(doubleStreamCombined(operation, operator)));
    }

    private static DoubleBinaryOperator doubleStreamCombined(@Nullable ParallelForEach operation, DoubleBinaryOperator operator) {
        return operation == null ? operator
                : (left, right) -> inCombine(operation, () -> operator.applyAsDouble(left, right));
    }

    /**
     * Weaves {@code DoubleStream.collect}: supplier and accumulator run as elements, and the combiner,
     * which merges containers other subtasks filled, as a combine.
     *
     * @param receiver    the stream
     * @param supplier    makes a container
     * @param accumulator adds an element to one
     * @param combiner    merges two
     * @return the container
     * @since 1.14.2
     */
    public static Object doubleStreamCollect(DoubleStream receiver, Supplier<Object> supplier,
                                       ObjDoubleConsumer<Object> accumulator,
                                       BiConsumer<Object, Object> combiner) {
        return parallelTerminal(receiver, operation -> operation == null
                ? receiver.collect(supplier, accumulator, combiner)
                : receiver.collect(() -> inElement(operation, supplier),
                        (container, value) -> inElement(operation, () -> {
                            accumulator.accept(container, value);
                            return null;
                        }),
                        (left, right) -> inCombine(operation, () -> {
                            combiner.accept(left, right);
                            return null;
                        })));
    }

    /**
     * Weaves {@code DoubleStream.anyMatch}: the predicate runs as an element.
     *
     * @param receiver  the stream
     * @param predicate the test
     * @return whether any element passed
     * @since 1.14.2
     */
    public static boolean doubleStreamAnyMatch(DoubleStream receiver, DoublePredicate predicate) {
        return parallelTerminal(receiver, operation -> receiver.anyMatch(operation == null ? predicate
                : value -> inElement(operation, () -> predicate.test(value))));
    }

    /**
     * Weaves {@code DoubleStream.allMatch}: the predicate runs as an element.
     *
     * @param receiver  the stream
     * @param predicate the test
     * @return whether every element passed
     * @since 1.14.2
     */
    public static boolean doubleStreamAllMatch(DoubleStream receiver, DoublePredicate predicate) {
        return parallelTerminal(receiver, operation -> receiver.allMatch(operation == null ? predicate
                : value -> inElement(operation, () -> predicate.test(value))));
    }

    /**
     * Weaves {@code DoubleStream.noneMatch}: the predicate runs as an element.
     *
     * @param receiver  the stream
     * @param predicate the test
     * @return whether no element passed
     * @since 1.14.2
     */
    public static boolean doubleStreamNoneMatch(DoubleStream receiver, DoublePredicate predicate) {
        return parallelTerminal(receiver, operation -> receiver.noneMatch(operation == null ? predicate
                : value -> inElement(operation, () -> predicate.test(value))));
    }

    /**
     * Weaves {@code DoubleStream.sum()}: the pipeline's woven stages run as its elements, and the caller is ordered after them once it returns.
     *
     * @param receiver the stream
     * @return the sum of the elements
     * @since 1.14.2
     */
    public static double doubleStreamSum(DoubleStream receiver) {
        return parallelTerminal(receiver, operation -> receiver.sum());
    }

    /**
     * Weaves {@code DoubleStream.count()}, as {@link #doubleStreamSum}.
     *
     * @param receiver the stream
     * @return how many elements
     * @since 1.14.2
     */
    public static long doubleStreamCount(DoubleStream receiver) {
        return parallelTerminal(receiver, operation -> receiver.count());
    }

    /**
     * Weaves {@code DoubleStream.average()}, as {@link #doubleStreamSum}.
     *
     * @param receiver the stream
     * @return the mean, if any element
     * @since 1.14.2
     */
    public static OptionalDouble doubleStreamAverage(DoubleStream receiver) {
        return parallelTerminal(receiver, operation -> receiver.average());
    }

    /**
     * Weaves {@code DoubleStream.min()}, as {@link #doubleStreamSum}.
     *
     * @param receiver the stream
     * @return the least, if any element
     * @since 1.14.2
     */
    public static OptionalDouble doubleStreamMin(DoubleStream receiver) {
        return parallelTerminal(receiver, operation -> receiver.min());
    }

    /**
     * Weaves {@code DoubleStream.max()}, as {@link #doubleStreamSum}.
     *
     * @param receiver the stream
     * @return the greatest, if any element
     * @since 1.14.2
     */
    public static OptionalDouble doubleStreamMax(DoubleStream receiver) {
        return parallelTerminal(receiver, operation -> receiver.max());
    }

    /**
     * Weaves {@code DoubleStream.summaryStatistics()}, as {@link #doubleStreamSum}.
     *
     * @param receiver the stream
     * @return count, sum, least, greatest and mean
     * @since 1.14.2
     */
    public static DoubleSummaryStatistics doubleStreamSummaryStatistics(DoubleStream receiver) {
        return parallelTerminal(receiver, operation -> receiver.summaryStatistics());
    }

    /**
     * Weaves {@code DoubleStream.toArray()}, as {@link #doubleStreamSum}.
     *
     * @param receiver the stream
     * @return the elements
     * @since 1.14.2
     */
    public static double[] doubleStreamToArray(DoubleStream receiver) {
        return parallelTerminal(receiver, operation -> receiver.toArray());
    }

    /**
     * Weaves {@code DoubleStream.findFirst()}, as {@link #doubleStreamSum}.
     *
     * @param receiver the stream
     * @return the first element, if any
     * @since 1.14.2
     */
    public static OptionalDouble doubleStreamFindFirst(DoubleStream receiver) {
        return parallelTerminal(receiver, operation -> receiver.findFirst());
    }

    /**
     * Weaves {@code DoubleStream.findAny()}, as {@link #doubleStreamSum}.
     *
     * @param receiver the stream
     * @return some element, if any
     * @since 1.14.2
     */
    public static OptionalDouble doubleStreamFindAny(DoubleStream receiver) {
        return parallelTerminal(receiver, operation -> receiver.findAny());
    }

    /**
     * Weaves {@code ExecutorService.shutdownNow()}: what comes back is the tasks {@link #execute}
     * was given, not the wrappers it queued (#1155), so code that re-queues or inspects them sees
     * its own. A list with no wrapper in it is returned as the executor made it.
     *
     * @param receiver the executor the call site invoked
     * @return the tasks that never started
     * @since 1.14.1
     */
    public static List<Runnable> shutdownNow(ExecutorService receiver) {
        ControlledSchedule.point();
        List<Runnable> queued = receiver.shutdownNow();
        List<Runnable> unwrapped = null;
        for (int i = 0; i < queued.size(); i++) {
            if (queued.get(i) instanceof HandedTask handed && handed.task instanceof Runnable task) {
                handed.abandoned();
                if (unwrapped == null) {
                    unwrapped = new ArrayList<>(queued);
                }
                unwrapped.set(i, task);
            }
        }
        return unwrapped == null ? queued : unwrapped;
    }

    /**
     * Weaves {@code ExecutorService.awaitTermination(long, TimeUnit)} (#1266): a {@code true}
     * return means every task the pool ran has ended, so the caller is ordered after what each
     * task a woven submit or execute wrapped did; each released into the pool as it ended. A
     * {@code false} return orders nothing. See {@link #poolTerminated} for which pools count.
     *
     * @param receiver the executor
     * @param timeout  how long to wait
     * @param unit     the unit of {@code timeout}
     * @return whether the executor terminated
     * @throws InterruptedException if interrupted while waiting
     * @since 1.14.3
     */
    public static boolean awaitTermination(ExecutorService receiver, long timeout, TimeUnit unit)
            throws InterruptedException {
        ControlledSchedule.point();
        awaited(receiver);
        boolean terminated = receiver.awaitTermination(timeout, unit);
        if (terminated) {
            poolTerminated(receiver);
        }
        return terminated;
    }

    /**
     * Weaves {@code ExecutorService.close()} (#1266), the end of a try-with-resources block over
     * an executor: it returns once every task has ended, so the caller is ordered after them as
     * after {@link #awaitTermination}. The common pool's {@code close} does nothing, which
     * {@link #poolTerminated} sees.
     *
     * @param receiver the executor
     * @since 1.14.3
     */
    public static void closeExecutor(ExecutorService receiver) {
        ControlledSchedule.point();
        awaited(receiver);
        receiver.close();
        poolTerminated(receiver);
    }

    /**
     * Tells the shutdown detector that the run waited for {@code pool} to end (#1271), so a pool
     * still running at analysis is reported as a wait that timed out, not as one nobody made. It
     * changes only that wording: the detector judges by the pool's own state, and ignores a pool
     * the run did not create.
     */
    private static void awaited(ExecutorService pool) {
        ExecutorShutdownDetector detector = AsyncTestContext.currentExecutorShutdownDetector();
        if (detector != null) {
            detector.recordAwaitTerminationCalled(pool);
        }
    }

    /**
     * The acquire half of a pool's termination: what every wrapped task released into
     * {@code pool} as it ended, when the pool reports itself terminated. Asked of the JDK's
     * executors and of a {@code ThreadPoolExecutor} subclass only, whose tasks are the only ones
     * wrapped, so no user executor is asked anything its caller did not ask. The common pool never
     * terminates, so its {@code close}, which waits for nothing, and its {@code awaitTermination},
     * which only waits for quiet, acquire nothing.
     */
    private static void poolTerminated(ExecutorService pool) {
        if ((pool instanceof ThreadPoolExecutor
                || pool.getClass().getName().startsWith("java.util.concurrent."))
                && pool.isTerminated()) {
            HappensBefore.acquire(pool);
        }
    }

    /**
     * Weaves {@code ThreadPoolExecutor.remove(Runnable)}: the caller names the task it gave to
     * {@link #execute}, and the queue holds the wrapper around it (#1155).
     *
     * @param pool the pool the call site invoked
     * @param task the task to remove
     * @return whether it was removed
     * @since 1.14.1
     */
    public static boolean removeFromPool(ThreadPoolExecutor pool, Runnable task) {
        ControlledSchedule.point();
        for (Runnable queued : pool.getQueue()) {
            if (queued instanceof HandedTask handed && Objects.equals(task, handed.task)) {
                boolean removed = pool.remove(queued);
                if (removed) {
                    handed.abandoned();
                }
                return removed;
            }
        }
        return pool.remove(task);
    }

    /**
     * Weaves {@code ThreadPoolExecutor.getQueue()} (#1173): where the pool queues wrappers around
     * the tasks {@link #execute} was given, woven code reads the queue through a view that holds
     * the caller's own tasks, removes them by those tasks, and tells the schedule a task taken out
     * of it will never run. Anywhere else the pool's own queue is returned, so its identity is
     * unchanged outside a run.
     *
     * @param pool the pool the call site invoked
     * @return the pool's queue, or a view of it that unwraps the tasks
     * @since 1.14.3
     */
    public static BlockingQueue<Runnable> poolQueue(ThreadPoolExecutor pool) {
        ControlledSchedule.point();
        BlockingQueue<Runnable> queue = pool.getQueue();
        return executesInvisibly(pool) ? new UnwrappingTaskQueue(queue) : queue;
    }

    /**
     * {@return the task the caller gave, where {@code task} is the wrapper {@link #execute} queued
     * around it, or {@code task} itself} The agent inserts this at the entry of a
     * {@code ThreadPoolExecutor} subclass's {@code beforeExecute} and {@code afterExecute}, and of a
     * {@code RejectedExecutionHandler}'s {@code rejectedExecution}, storing the result back into
     * the parameter, so the override is handed what the caller gave (#1173). Not a schedule point:
     * the pool, not the caller, calls these.
     *
     * @param task the task the pool handed the method
     * @since 1.14.3
     */
    public static Runnable poolTask(Runnable task) {
        return task instanceof HandedTask handed && handed.task instanceof Runnable given ? given : task;
    }

    /**
     * Records that the agent rewrote {@code className}'s {@code beforeExecute},
     * {@code afterExecute} or {@code rejectedExecution} with {@link #poolTask} (#1173), so
     * {@link #execute} may wrap a task for a pool that overrides them or uses that handler. Called
     * by the agent while it weaves the class, before the class can be used; a class it never
     * reports keeps its pool's tasks unwrapped, the direction that cannot report correct code.
     *
     * @param className the binary name of the rewritten class
     * @since 1.14.3
     */
    public static void poolTaskOverrideWoven(String className) {
        if (className != null) {
            TASK_UNWRAPPING_CLASSES.add(className);
        }
    }

    /**
     * Records a rejected submission with the run's {@code ThreadPoolMonitor} and hands the same
     * exception back to be rethrown unchanged (#1055). A rejection that propagates fails the test
     * anyway; one the calling code catches and drops loses the task silently, and this record is
     * the only trace of it. The pool is not registered: nothing here knows its bounds, and the
     * monitor skips its bound rules for a pool it only heard of through a rejection, where declaring
     * zeros would report "All 0 threads busy" (#501). The exception itself is handed over, so the
     * report grades the finding as the executor's own answer rather than the test's word (#1061).
     */
    private static RejectedExecutionException rejected(Object executor, RejectedExecutionException e) {
        ThreadPoolMonitor pools = AsyncTestContext.currentThreadPoolMonitor();
        if (pools != null) {
            pools.recordRejectedExecution(executor, e);
        }
        return e;
    }

    /** The task the calling thread is running, for a get to compare its pool with the future's. */
    static final ThreadLocal<@Nullable HandedTask> RUNNING_HANDED = new ThreadLocal<>();

    /**
     * Tells {@code EXECUTOR_DEADLOCK} when a task is about to block on a task of its own pool
     * (#1160), and {@return that pool, for {@link #siblingWaitEnds}, or {@code null}} when this get
     * is anything else. A {@code ForkJoinPool} is left out: a waiting worker runs other tasks.
     */
    @SuppressWarnings("ReferenceEquality") // the same pool, by identity
    private static @Nullable Object siblingWaitBegins(Future<?> future) {
        HandedTask running = RUNNING_HANDED.get();
        if (running == null || running.executor == null || future.isDone()
                || !(Submissions.peek(future) instanceof HandedTask nested)
                || nested.executor != running.executor) { // NOPMD CompareObjectsWithEquals - identity
            return null;
        }
        return recordSiblingWait(running.executor, 1);
    }

    /**
     * Records that a task of {@code pool} now waits on tasks of it, and {@return {@code pool}, or
     * {@code null} when it has no bound this can read}.
     *
     * @param pool       the pool the caller is running on
     * @param notStarted how many of those tasks cannot be running, for a pool whose queue is not read
     */
    private static @Nullable Object recordSiblingWait(Object pool, int notStarted) {
        ExecutorDeadlockDetector detector = AsyncTestContext.currentExecutorDeadlockDetector();
        if (detector == null) {
            return null;
        }
        if (pool instanceof ThreadPoolExecutor bounded) {
            // A pool only grows past its core once the queue refuses a task.
            int threads = bounded.getQueue().remainingCapacity() > 0
                    ? bounded.getCorePoolSize() : bounded.getMaximumPoolSize();
            detector.agentSiblingWait(pool, threads, Math.max(bounded.getQueue().size(), notStarted));
            return pool;
        }
        if (SINGLE_THREAD_WRAPPERS.contains(pool.getClass().getName())) {
            // The only thread is this one, so a task it waits for, which is not done, is queued.
            detector.agentSiblingWait(pool, 1, 1);
            return pool;
        }
        return null;
    }

    /** {@return the executor of the task this thread runs, when it is {@code pool}, else {@code null}} */
    @SuppressWarnings("ReferenceEquality") // the same pool, by identity
    private static @Nullable Object runningOn(ExecutorService pool) {
        HandedTask running = RUNNING_HANDED.get();
        return running != null && running.executor == pool ? pool : null; // NOPMD CompareObjectsWithEquals - identity
    }

    /**
     * Weaves {@code ExecutorService.invokeAll(Collection)}. A task of this pool that calls it waits
     * for tasks of its own pool, as a {@code get} on one does (#1175).
     *
     * @param receiver the executor
     * @param tasks    the tasks to run and wait for
     * @return their futures
     * @throws InterruptedException if interrupted while waiting
     * @since 1.14.1
     */
    public static List<Future<Object>> invokeAll(ExecutorService receiver,
                                                 Collection<? extends Callable<Object>> tasks)
            throws InterruptedException {
        ControlledSchedule.point();
        Object pool = runningOn(receiver) == null ? null : recordSiblingWait(receiver, tasks.size());
        try {
            return receiver.invokeAll(tasks);
        } finally {
            siblingWaitEnds(pool);
        }
    }

    /**
     * Weaves {@code ExecutorService.invokeAny(Collection)}, as
     * {@link #invokeAll(ExecutorService, Collection)}.
     *
     * @param receiver the executor
     * @param tasks    the tasks to run, one of which must finish
     * @return the result of one that did
     * @throws InterruptedException if interrupted while waiting
     * @throws ExecutionException   if none finished normally
     * @since 1.14.1
     */
    public static @Nullable Object invokeAny(ExecutorService receiver,
                                             Collection<? extends Callable<Object>> tasks)
            throws InterruptedException, ExecutionException {
        ControlledSchedule.point();
        Object pool = runningOn(receiver) == null ? null : recordSiblingWait(receiver, tasks.size());
        try {
            return receiver.invokeAny(tasks);
        } finally {
            siblingWaitEnds(pool);
        }
    }

    /**
     * Weaves {@code ExecutorService.invokeAny(Collection, long, TimeUnit)}, as
     * {@link #invokeAll(ExecutorService, Collection)}.
     *
     * @param receiver the executor
     * @param tasks    the tasks to run, one of which must finish
     * @param timeout  how long to wait
     * @param unit     the unit of {@code timeout}
     * @return the result of one that did
     * @throws InterruptedException if interrupted while waiting
     * @throws ExecutionException   if none finished normally
     * @throws TimeoutException     if the wait timed out
     * @since 1.14.1
     */
    public static @Nullable Object invokeAny(ExecutorService receiver,
                                             Collection<? extends Callable<Object>> tasks,
                                             long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        ControlledSchedule.point();
        Object pool = runningOn(receiver) == null ? null : recordSiblingWait(receiver, tasks.size());
        try {
            return receiver.invokeAny(tasks, timeout, unit);
        } finally {
            siblingWaitEnds(pool);
        }
    }

    /**
     * Weaves {@code ExecutorService.invokeAll(Collection, long, TimeUnit)}, as
     * {@link #invokeAll(ExecutorService, Collection)}.
     *
     * @param receiver the executor
     * @param tasks    the tasks to run and wait for
     * @param timeout  how long to wait
     * @param unit     the unit of {@code timeout}
     * @return their futures
     * @throws InterruptedException if interrupted while waiting
     * @since 1.14.1
     */
    public static List<Future<Object>> invokeAll(ExecutorService receiver,
                                                 Collection<? extends Callable<Object>> tasks,
                                                 long timeout, TimeUnit unit)
            throws InterruptedException {
        ControlledSchedule.point();
        Object pool = runningOn(receiver) == null ? null : recordSiblingWait(receiver, tasks.size());
        try {
            return receiver.invokeAll(tasks, timeout, unit);
        } finally {
            siblingWaitEnds(pool);
        }
    }

    /** What {@code Executors.newSingleThreadExecutor()} returns, on the JDKs this library supports. */
    private static final Set<String> SINGLE_THREAD_WRAPPERS = Set.of(
            "java.util.concurrent.Executors$AutoShutdownDelegatedExecutorService",
            "java.util.concurrent.Executors$FinalizableDelegatedExecutorService");

    /** Ends the wait {@link #siblingWaitBegins} recorded, if it recorded one. */
    private static void siblingWaitEnds(@Nullable Object pool) {
        if (pool == null) {
            return;
        }
        ExecutorDeadlockDetector detector = AsyncTestContext.currentExecutorDeadlockDetector();
        if (detector != null) {
            detector.recordSiblingWaitEnded(pool);
        }
    }

    /**
     * {@return the controlled schedule's adoption of the task behind {@code future}, or
     * {@code null}}: what a wait for its result hands the schedule, so the wait happens inside it
     * (#1049). Only a future this thread got from a woven submission is known.
     */
    private static @Nullable Object adoptionBehind(Object future) {
        return Submissions.peek(future) instanceof HandedTask handed ? handed.adoption : null;
    }

    /**
     * The methods of a {@code ThreadPoolExecutor} that are handed a task the pool was given, or a
     * list of them: an override of any would see the wrapper {@link #execute} queues (#1173).
     */
    private static final List<Map.Entry<String, Class<?>[]>> TASK_METHODS = List.of(
            Map.entry("execute", new Class<?>[] {Runnable.class}),
            Map.entry("beforeExecute", new Class<?>[] {Thread.class, Runnable.class}),
            Map.entry("afterExecute", new Class<?>[] {Runnable.class, Throwable.class}),
            Map.entry("remove", new Class<?>[] {Runnable.class}),
            Map.entry("shutdownNow", new Class<?>[0]));

    /**
     * The methods of {@link #TASK_METHODS} the agent rewrites to be handed the caller's task
     * (#1173): an override of one of these in a class {@link #poolTaskOverrideWoven} heard of sees
     * no wrapper.
     */
    private static final Set<String> UNWRAPPED_TASK_METHODS = Set.of("beforeExecute", "afterExecute");

    /**
     * Names of the classes whose {@code beforeExecute}, {@code afterExecute} or
     * {@code rejectedExecution} the agent rewrote to hand over the caller's task (#1173).
     */
    private static final Set<String> TASK_UNWRAPPING_CLASSES = ConcurrentHashMap.newKeySet();

    /**
     * Per {@code ThreadPoolExecutor} subclass: whether no class between it and the pool declares
     * one of {@link #TASK_METHODS} that would be handed the wrapper; a {@code beforeExecute} or
     * {@code afterExecute} in a class the agent rewrote is handed the caller's task instead
     * (#1173). A class reflection cannot read counts as overriding.
     */
    private static final ClassValue<Boolean> NO_TASK_OVERRIDES = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                for (Class<?> c = type; c != ThreadPoolExecutor.class; c = c.getSuperclass()) {
                    boolean unwraps = TASK_UNWRAPPING_CLASSES.contains(c.getName());
                    for (java.lang.reflect.Method declared : c.getDeclaredMethods()) {
                        for (Map.Entry<String, Class<?>[]> method : TASK_METHODS) {
                            if (declared.getName().equals(method.getKey())
                                    && Arrays.equals(declared.getParameterTypes(), method.getValue())
                                    && !(unwraps && UNWRAPPED_TASK_METHODS.contains(method.getKey()))) {
                                return false;
                            }
                        }
                    }
                }
                return true;
            } catch (RuntimeException | LinkageError unreadable) { // NOPMD - unreadable means "may override"
                return false;
            }
        }
    };

    /**
     * The JDK's own rejection policies, by exact class. They run, drop or name a rejected task and
     * never look at what it is; a handler of the caller's own may, so its pool is not wrapped for
     * (#1173).
     */
    private static final Set<Class<?>> JDK_REJECTION_POLICIES = Set.of(
            ThreadPoolExecutor.AbortPolicy.class, ThreadPoolExecutor.CallerRunsPolicy.class,
            ThreadPoolExecutor.DiscardPolicy.class, ThreadPoolExecutor.DiscardOldestPolicy.class);

    /**
     * Per rejection handler class: whether it is one of the JDK's policies, or its
     * {@code rejectedExecution} is declared in a class the agent rewrote to hand over the caller's
     * task (#1173). A class reflection cannot read counts as looking at the task.
     */
    private static final ClassValue<Boolean> HANDLER_SEES_NO_WRAPPER = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            if (JDK_REJECTION_POLICIES.contains(type)) {
                return true;
            }
            try {
                return TASK_UNWRAPPING_CLASSES.contains(type.getMethod("rejectedExecution",
                        Runnable.class, ThreadPoolExecutor.class).getDeclaringClass().getName());
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unreadable) { // NOPMD - unreadable means "may look"
                return false;
            }
        }
    };

    /**
     * {@return whether {@code execute} wraps its task for {@code executor}}
     *
     * <p>A {@code ThreadPoolExecutor} qualifies inside a run (#1155), as does a subclass that
     * overrides none of the methods a task reaches (#1173): a {@code beforeExecute},
     * {@code afterExecute}, {@code execute}, {@code remove} or {@code shutdownNow} of its own would
     * be handed the wrapper. A {@code beforeExecute} or {@code afterExecute} the agent rewrote is
     * handed the caller's task instead ({@link #poolTask}), so it does not count. Either needs a
     * rejection handler that never sees the wrapper: one of the JDK's policies, or one whose
     * {@code rejectedExecution} the agent rewrote the same way. {@link #shutdownNow},
     * {@link #removeFromPool} and {@link #poolQueue} keep the wrapper from showing to woven code;
     * outside a run nothing is wrapped.
     *
     * <p>An {@code Executors.unconfigurable*} wrapper hands back only what the executor behind it
     * hands back, so it is judged by that executor, when a woven call made it (#1107). A wrapper a
     * factory builds inside the JDK, such as the one {@code Executors.newSingleThreadExecutor()}
     * returns, is judged the same way by the executor its {@code e} field holds, where
     * {@code java.util.concurrent} is open to this library (#1269). Both are consulted only for
     * the JDK's own wrapper classes, so no other execute pays for the lookup.
     */
    private static boolean executesInvisibly(Executor executor) {
        Class<?> type = executor.getClass();
        if (type == ForkJoinPool.class || type == ScheduledThreadPoolExecutor.class
                || "java.util.concurrent.ThreadPerTaskExecutor".equals(type.getName())) {
            return true;
        }
        if (type == ThreadPoolExecutor.class
                || (ThreadPoolExecutor.class.isAssignableFrom(type) && NO_TASK_OVERRIDES.get(type))) {
            return AsyncTestContext.agentContext() != null
                    && HANDLER_SEES_NO_WRAPPER.get(
                            ((ThreadPoolExecutor) executor).getRejectedExecutionHandler().getClass());
        }
        if (!type.getName().startsWith("java.util.concurrent.Executors$")) {
            return false;
        }
        Executor behind = UNCONFIGURABLE.get(executor);
        if (behind == null) {
            behind = delegate(executor);
        }
        return behind != null && executesInvisibly(behind);
    }

    /**
     * {@code Executors$DelegatedExecutorService}'s private {@code e} field, the executor every
     * wrapper an {@code Executors} factory builds forwards to, or {@code null} until it has been
     * opened for reading, which needs {@code java.util.concurrent} open to this library. The agent
     * opens it under collection weaving, the mode that weaves {@code execute} (#1269).
     */
    private static volatile @Nullable Field delegateField;

    /** {@return the executor {@code wrapper} forwards to, or {@code null} if it cannot be read} */
    private static @Nullable Executor delegate(Executor wrapper) {
        Field field = delegateField;
        if (field == null) {
            try {
                field = Class.forName("java.util.concurrent.Executors$DelegatedExecutorService")
                        .getDeclaredField("e");
                if (!ExecutorService.class.isAssignableFrom(field.getType()) || !field.trySetAccessible()) {
                    return null;
                }
                delegateField = field;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { // NOPMD - a closed package or another JDK's layout leaves the wrapper unjudged
                return null;
            }
        }
        try {
            return field.getDeclaringClass().isInstance(wrapper) ? (Executor) field.get(wrapper) : null;
        } catch (IllegalAccessException | RuntimeException e) { // NOPMD - unreadable means unwrapped, as before #1269
            return null;
        }
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
        observed(future, Submissions.forget(future));
    }

    /** What {@link #completionObserved} does, given the work known to be behind {@code future}. */
    static void observed(Object future, @Nullable Handed handed) {
        if (future instanceof CompletableFuture) {
            HappensBefore.acquire(future);
        } else if (futureTaskRunDirectly && future instanceof FutureTask) {
            // A task its caller ran itself released at the end of that run (#1307).
            awaitDirectRun(future);
            HappensBefore.acquire(future);
        }
        if (handed instanceof HandedAll all) {
            all.observed();
        }
        if (handed != null) {
            HappensBefore.receive(handed.finished());
        }
        if (handed instanceof HandedStage stage && stage.composed != null) {
            // A composed stage completes when the stage its function returned does, so what was
            // released to that one is observed too (#741).
            HappensBefore.acquire(stage.composed);
        }
    }

    /** Ids for the pooled tasks the run hands over, one per task execution (#995). */
    private static final AtomicLong NEXT_TASK = new AtomicLong();

    /** The id of the handed task running on this thread, or 0 outside one (#995). */
    private static final ThreadLocal<long[]> RUNNING_TASK = ThreadLocal.withInitial(() -> new long[1]);

    /** {@return the id of the task this thread was running before}, after starting a new one */
    static long enterTask() {
        long[] running = RUNNING_TASK.get();
        long before = running[0];
        running[0] = NEXT_TASK.incrementAndGet();
        return before;
    }

    /** Restores the task id {@link #enterTask()} replaced. */
    static void leaveTask(long before) {
        RUNNING_TASK.get()[0] = before;
    }

    /**
     * Weaves {@code ThreadLocal.get()}: inside a pooled task the run handed over, the read is
     * recorded on {@code THREAD_LOCAL_LEAKS} with the task, so a value an earlier task on the thread
     * set and left behind is told from this task's own (#995).
     *
     * @param receiver the thread-local
     * @return what the get returns
     * @since 1.13.2
     */
    public static @Nullable Object threadLocalGet(ThreadLocal<?> receiver) {
        ControlledSchedule.point();
        Object value = receiver.get();
        long task = RUNNING_TASK.get()[0];
        if (task != 0) {
            ThreadLocalMonitor monitor = AsyncTestContext.currentThreadLocalMonitor();
            if (monitor != null) {
                monitor.agentTaskRead(receiver, task);
            }
        }
        return value;
    }

    /**
     * Weaves {@code ThreadLocal.set(Object)}, as {@link #threadLocalGet}: inside a pooled task the
     * set is recorded with the task (#995).
     *
     * @param receiver the thread-local
     * @param value    the value to set
     * @since 1.13.2
     */
    @SuppressWarnings("unchecked")
    public static void threadLocalSet(ThreadLocal<?> receiver, @Nullable Object value) {
        ControlledSchedule.point();
        ((ThreadLocal<Object>) receiver).set(value);
        long task = RUNNING_TASK.get()[0];
        if (task != 0) {
            ThreadLocalMonitor monitor = AsyncTestContext.currentThreadLocalMonitor();
            if (monitor != null) {
                monitor.agentTaskSet(receiver, task);
            }
        }
    }

    /**
     * Weaves {@code ThreadLocal.remove()}: the next task on this thread starts clean (#995).
     *
     * @param receiver the thread-local
     * @since 1.13.2
     */
    public static void threadLocalRemove(ThreadLocal<?> receiver) {
        ControlledSchedule.point();
        receiver.remove();
        ThreadLocalMonitor monitor = AsyncTestContext.currentThreadLocalMonitor();
        if (monitor != null) {
            monitor.agentRemove(receiver);
        }
    }

    /**
     * A woven read of an {@code AtomicInteger} or {@code AtomicLong} (#1157).
     *
     * @param atomic the atomic the call site read
     */
    public static void atomicRead(Object atomic) {
        AtomicNonAtomicUpdateDetector detector = AsyncTestContext.currentAtomicNonAtomicUpdateDetector();
        if (detector != null) {
            detector.agentRead(atomic);
        }
    }

    /**
     * A woven plain store to one of those atomics (#1157).
     *
     * @param atomic the atomic the call site wrote
     */
    public static void atomicStored(Object atomic) {
        AtomicNonAtomicUpdateDetector detector = AsyncTestContext.currentAtomicNonAtomicUpdateDetector();
        if (detector != null) {
            detector.agentStore(atomic);
        }
    }

    /**
     * A woven arithmetic update of one of those atomics, the act half of a check-then-act when this
     * thread read the atomic just before (#1249).
     *
     * @param atomic the atomic the call site updated
     */
    public static void atomicIncremented(Object atomic) {
        AtomicNonAtomicUpdateDetector detector = AsyncTestContext.currentAtomicNonAtomicUpdateDetector();
        if (detector != null) {
            detector.agentIncrement(atomic);
        }
    }

    /**
     * A woven store to an {@code AtomicReference} (#1174). Only a stored object can have been built
     * from the read, so an enum or a {@code Boolean}, which is a status, or {@code null}, ends the
     * read instead.
     *
     * @param reference the reference the call site wrote
     * @param stored    what it stored
     */
    public static void atomicReferenceStored(Object reference, @Nullable Object stored) {
        AtomicNonAtomicUpdateDetector detector = AsyncTestContext.currentAtomicNonAtomicUpdateDetector();
        if (detector != null) {
            detector.agentReferenceStore(reference, stored);
        }
    }

    /**
     * A woven atomic update of one of those atomics, which needs no read before it (#1157).
     *
     * @param atomic the atomic the call site updated
     */
    public static void atomicSwapped(Object atomic) {
        AtomicNonAtomicUpdateDetector detector = AsyncTestContext.currentAtomicNonAtomicUpdateDetector();
        if (detector != null) {
            detector.agentSwap(atomic);
        }
    }

    /**
     * Snapshots the MDC at the start of a pooled task the run handed over, for
     * {@code MDC_CONTEXT_LEAK} (#995): a key the task leaves behind is in the end snapshot and not in
     * this one. Inside the loan, so the detector is the submitting run's.
     */
    static void mdcAtTaskStart() {
        MdcContextLeakDetector detector = AsyncTestContext.currentMdcContextLeakDetector();
        if (detector != null) {
            detector.recordTaskStart(Thread.currentThread(), mdcCopy());
        }
    }

    /** The end of the task {@link #mdcAtTaskStart()} began, before the loan is returned (#995). */
    static void mdcAtTaskEnd() {
        MdcContextLeakDetector detector = AsyncTestContext.currentMdcContextLeakDetector();
        if (detector != null) {
            detector.recordTaskEnd(Thread.currentThread(), mdcCopy());
        }
    }

    /** {@code MDC.getCopyOfContextMap()}, or {@code null} where the backend refuses. */
    private static @Nullable Map<String, String> mdcCopy() {
        try {
            return org.slf4j.MDC.getCopyOfContextMap();
        } catch (RuntimeException | LinkageError e) { // NOPMD - a broken backend must not fail the task
            return Map.of();
        }
    }
}
