package se.deversity.asynctest.telemetry;

import se.deversity.asynctest.diagnostics.HeldLocks;
import org.jspecify.annotations.Nullable;

import java.lang.invoke.VarHandle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.IntBinaryOperator;
import java.util.function.IntUnaryOperator;

/**
 * Global registry that routes field-access events from producer threads into a shared
 * {@link TelemetryEventBuffer} and drains them asynchronously via a background thread.
 *
 * <p>Called from the {@code AsyncTestAgent} advice on every intercepted field access.
 * The advice hot path uses {@link #recordAccess(long, String, boolean)}, which must
 * remain allocation-free and lock-free: it receives an already-combined identifier
 * (a constant-pool string produced by {@code @Advice.Origin}) and a pre-decided
 * {@code isWrite} flag, so it performs no string work.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>The agent calls {@link #start()} once at JVM startup (from {@code premain}).</li>
 *   <li>Every intercepted field access calls {@link #recordAccess}.</li>
 *   <li>A background drain thread flushes the ring buffer every millisecond and
 *       forwards events to registered {@link TelemetryEventBuffer.DrainCallback}s.</li>
 *   <li>{@link #stop()} is called on JVM shutdown to flush and terminate the drain thread.</li>
 * </ol>
 *
 * @since 1.6.0
 */
public final class TelemetryRegistry {

    private static final int BUFFER_CAPACITY = 1 << 14; // 16 384 slots

    /** How long {@link #flush()} waits for the drain thread before giving up on it. */
    private static final long FLUSH_TIMEOUT_SECONDS = 1L;
    private static final TelemetryEventBuffer BUFFER = new TelemetryEventBuffer(BUFFER_CAPACITY);
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    /**
     * True from {@link #stop()} until the next {@link #start}. Distinct from
     * {@code !RUNNING.get()}: before the first start the registry buffers events for the
     * drain thread that {@code premain} is about to create, but after stop() no drain
     * thread will ever exist again, so {@link #recordAccess(long, String, boolean)}
     * discards instead of filling a ring nobody empties (see its Javadoc for why that
     * distinction is load-bearing during JVM shutdown).
     */
    private static final AtomicBoolean STOPPED = new AtomicBoolean(false);

    private static volatile TelemetryEventBuffer.@Nullable DrainCallback drainCallback = null;

    /**
     * Guards the compare-and-clear in {@link #clearCallbackIf}. Private so no other code can hold
     * it — a {@code static synchronized} method would lock the class object instead, which any
     * caller can also lock.
     */
    private static final Object CALLBACK_LOCK = new Object();
    private static @Nullable ScheduledExecutorService drainExecutor = null;
    private static @Nullable Thread shutdownHook = null;

    /** Fields bound to a VarHandle or an atomic updater. Static facts; grows only. */
    private static final Set<String> ATOMICALLY_MANAGED =
            ConcurrentHashMap.newKeySet();

    /** Fields a volatile write publishes, as the weaver found them. Static facts; grows only. */
    private static final Set<String> PUBLISHED_BY_VOLATILE =
            ConcurrentHashMap.newKeySet();

    private TelemetryRegistry() {}

    /**
     * Records a field access from the instrumented thread — the agent advice hot path.
     *
     * <p>Allocation-free and lock-free: it forwards the already-combined identifier and
     * the pre-decided {@code isWrite} flag straight to the ring buffer with no string
     * concatenation or prefix inspection. The read/write decision is bound at
     * instrumentation time by {@code AsyncTestAgent}'s split getter/setter advice.
     *
     * <p>After {@link #stop()} the event is discarded instead of buffered: no drain thread
     * will ever exist again, so buffering would only fill the ring until every publishing
     * (woven) thread in the JVM stalled against it — which is exactly what happened during
     * JVM shutdown, where application threads still run woven accessors after the shutdown
     * hook has stopped the registry. Events published <em>before</em> {@link #start()} are
     * still buffered, preserving the pre-start capture window.
     *
     * @param threadId       {@code Thread.currentThread().threadId()}
     * @param qualifiedName  combined {@code declaringClass.methodName} identifier
     *                       (a constant-pool string produced by {@code @Advice.Origin})
     * @param isWrite        {@code true} for a write access (setter), {@code false} for
     *                       a read access (getter)
     * @since 1.7.0
     */
    public static void recordAccess(long threadId, String qualifiedName, boolean isWrite) {
        recordAccess(threadId, qualifiedName, isWrite, false);
    }

    /**
     * Records a field access, saying whether the field is declared {@code volatile}.
     *
     * <p>Same hot path as the three-argument form; the flag is a constant the weaver resolved and
     * baked in, so this allocates nothing and computes nothing extra. It matters because a volatile
     * field whose writes all happened under one lock is the double-checked-locking idiom, which is
     * correct, and indistinguishable from a check-then-act bug without knowing the field is
     * volatile.
     *
     * @param threadId      {@code Thread.currentThread().threadId()}
     * @param qualifiedName combined {@code declaringClass.field} identifier
     * @param isWrite       {@code true} for a write access
     * @param volatileField whether the field is declared {@code volatile}
     * @since 1.9.8
     */
    public static void recordAccess(long threadId, String qualifiedName, boolean isWrite,
                                    boolean volatileField) {
        recordAccess(threadId, qualifiedName, isWrite, volatileField, Integer.MIN_VALUE);
    }

    /**
     * Records a field access, also saying what a constant write stored.
     *
     * <p>The weaver supplies the tag only for a write whose value came from a constant instruction
     * in a method that had not read the field. Both halves matter: a field written the same
     * constant by every thread cannot change what any of them decides, while a write preceded by a
     * read of the same field might be {@code if (!initialized) initialized = true}, which is a real
     * bug and keeps its finding.
     *
     * @param threadId      {@code Thread.currentThread().threadId()}
     * @param qualifiedName combined {@code declaringClass.field} identifier
     * @param isWrite       {@code true} for a write access
     * @param volatileField whether the field is declared {@code volatile}
     * @param constantTag   the constant stored, or {@code Integer.MIN_VALUE} for "not a constant"
     * @since 1.9.8
     */
    public static void recordAccess(long threadId, String qualifiedName, boolean isWrite,
                                    boolean volatileField, int constantTag) {
        recordAccess(0, threadId, qualifiedName, isWrite, volatileField, constantTag);
    }

    /**
     * Records a field access on a named instance, the agent's widest hot path.
     *
     * <p>{@code identity} is {@code System.identityHashCode} of the object the field belongs to,
     * or 0 for a static field. It is what separates two threads racing on one object from two
     * threads each using their own: without it a per-call object such as a hasher or an iterator
     * aggregates by field name and reads as shared, which reports code that never shared anything.
     *
     * @param identity      {@code System.identityHashCode(receiver)}, or 0 for a static field
     * @param threadId      {@code Thread.currentThread().threadId()}
     * @param qualifiedName combined {@code declaringClass.field} identifier
     * @param isWrite       {@code true} for a write access
     * @param volatileField whether the field is declared {@code volatile}
     * @param constantTag   the constant stored, or {@code Integer.MIN_VALUE} for "not a constant"
     * @since 1.9.8
     */
    public static void recordAccess(int identity, long threadId, String qualifiedName,
                                    boolean isWrite, boolean volatileField, int constantTag) {
        recordAccess(identity, threadId, qualifiedName, isWrite, volatileField, constantTag, false);
    }

    /**
     * Records a field access, saying also whether the method had already read a volatile field of
     * the same object.
     *
     * <p>That bit is one half of the publish-via-volatile idiom; the other arrives through
     * {@link #publishedByVolatile(String)}. Together they describe a plain field written under a
     * lock, published by a volatile write, and read only after the volatile read that orders it.
     *
     * @param identity          {@code System.identityHashCode(receiver)}, 0 for a static field
     * @param threadId          {@code Thread.currentThread().threadId()}
     * @param qualifiedName     combined {@code declaringClass.field} identifier
     * @param isWrite           {@code true} for a write access
     * @param volatileField     whether the field is declared {@code volatile}
     * @param constantTag       the constant stored, {@code Integer.MIN_VALUE} for none
     * @param afterVolatileRead whether a volatile field of the owner was read first
     * @since 1.9.8
     */
    public static void recordAccess(int identity, long threadId, String qualifiedName,
                                    boolean isWrite, boolean volatileField, int constantTag,
                                    boolean afterVolatileRead) {
        if (STOPPED.get()) {
            return;
        }
        // The lockset lives on this thread and this thread only, so the question has to be asked
        // here: by the time the drain thread replays the event it holds nothing the producer held.
        // Reading it is a walk over a small per-thread array, allocation-free and lock-free, which
        // is what the producer path requires - a heavier capture here would change the scheduling
        // this whole buffer exists to leave alone.
        BUFFER.publish(threadId, qualifiedName, isWrite, HeldLocks.lockFingerprint(),
                volatileField, constantTag, identity, afterVolatileRead);
    }

    /**
     * Records a field access with the object it belongs to in hand, the agent's hot path.
     *
     * <p>Given the receiver rather than its identity hash, the hook can ask the one question the
     * woven lockset cannot answer: is that object's monitor held right now. A {@code synchronized}
     * method compiles to {@code ACC_SYNCHRONIZED} and no monitor instruction, so a class that
     * guards its fields the most ordinary way in Java left nothing for the weaver to record and
     * every such field read as unguarded. {@link Thread#holdsLock(Object)} answers for the
     * receiver, and the weaver passes the monitor of an enclosing {@code synchronized} method
     * outright, since holding it is what being inside that method means. Both travel with the
     * event as identity hashes; the receiver itself is never retained.
     *
     * <p>For a write the fingerprint leaves out locks held in shared mode, because a read lock
     * guards no write. The question is asked here, on the accessing thread, for the same reason
     * the fingerprint is.
     *
     * @param receiver          the object the field belongs to, or the declaring class for a
     *                          static field, or {@code null} when the weaver had neither
     * @param methodMonitor     the monitor of the enclosing {@code synchronized} method, else
     *                          {@code null}
     * @param threadId          {@code Thread.currentThread().threadId()}
     * @param qualifiedName     combined {@code declaringClass.field} identifier
     * @param isWrite           {@code true} for a write access
     * @param volatileField     whether the field is declared {@code volatile}
     * @param constantTag       the constant stored, {@code Integer.MIN_VALUE} for none
     * @param afterVolatileRead whether a volatile field of the owner was read first
     * @param staticField       whether the field is static, so its identity stays 0
     * @since 1.9.8
     */
    public static void recordAccess(@Nullable Object receiver, @Nullable Object methodMonitor,
                                    long threadId, String qualifiedName, boolean isWrite,
                                    boolean volatileField, int constantTag,
                                    boolean afterVolatileRead, boolean staticField) {
        if (STOPPED.get()) {
            return;
        }
        int identity = staticField || receiver == null ? 0 : System.identityHashCode(receiver);
        int ownMonitor = receiver != null && Thread.holdsLock(receiver)
                ? System.identityHashCode(receiver) : 0;
        int method = methodMonitor == null ? 0 : System.identityHashCode(methodMonitor);
        BUFFER.publish(threadId, qualifiedName, isWrite, HeldLocks.lockFingerprint(isWrite),
                volatileField, constantTag, identity, afterVolatileRead, ownMonitor, method);
    }
    /**
     * Records a field access, with the reference the write stored in hand.
     *
     * <p>The one thing an access stream cannot say is <em>what</em> was stored, and that is the
     * whole blind spot in the settled-cache excuse. A double-submit shaped like a view cache -
     * {@code if (job == null) job = submit()} - converges on the field exactly like a cache does,
     * because convergence is a property of the field and the defect is a property of the payload.
     * Given the stored reference's identity, the analysis can ask whether the published object
     * then went quiet, which is what an idempotent value does and a live job does not.
     *
     * <p>Only its identity hash travels; like the receiver, the value itself is never retained.
     * {@code stored} is {@code null} for a read, for a primitive write, and wherever the weaver
     * could not reach the value without disturbing the operand stack, and a 0 identity means "not
     * known" rather than "not immutable" - the analysis keeps its previous answer there, because
     * absence of evidence is not evidence.
     *
     * @param receiver          the object the field belongs to, or the declaring class for a
     *                          static field, or {@code null} when the weaver had neither
     * @param stored            the reference this write put in the field, or {@code null}
     * @param methodMonitor     the monitor of the enclosing {@code synchronized} method, else
     *                          {@code null}
     * @param threadId          {@code Thread.currentThread().threadId()}
     * @param qualifiedName     combined {@code declaringClass.field} identifier
     * @param isWrite           {@code true} for a write access
     * @param volatileField     whether the field is declared {@code volatile}
     * @param constantTag       the constant stored, {@code Integer.MIN_VALUE} for none
     * @param afterVolatileRead whether a volatile field of the owner was read first
     * @param staticField       whether the field is static, so its identity stays 0
     * @since 1.9.8
     */
    public static void recordAccess(@Nullable Object receiver, @Nullable Object stored,
                                    @Nullable Object methodMonitor,
                                    long threadId, String qualifiedName, boolean isWrite,
                                    boolean volatileField, int constantTag,
                                    boolean afterVolatileRead, boolean staticField) {
        if (STOPPED.get()) {
            return;
        }
        int identity = staticField || receiver == null ? 0 : System.identityHashCode(receiver);
        if (isWrite && identity != 0 && SpinLocks.isSpinField(qualifiedName)) {
            // The holder writing its spinlock flag is the release (#554). Declared before the write
            // lands, so the lock never reads as held after it truly is not.
            SpinLocks.release(receiver, qualifiedName);
        }
        int ownMonitor = receiver != null && Thread.holdsLock(receiver)
                ? System.identityHashCode(receiver) : 0;
        int method = methodMonitor == null ? 0 : System.identityHashCode(methodMonitor);
        int storedIdentity = stored == null ? 0 : System.identityHashCode(stored);
        BUFFER.publish(threadId, qualifiedName, isWrite, HeldLocks.lockFingerprint(isWrite),
                volatileField, constantTag, identity, afterVolatileRead, ownMonitor, method,
                storedIdentity);
    }

    /**
     * Records a field access from a class name and method name.
     *
     * <p>Convenience overload for callers that have the declaring class and method name
     * as separate strings (used by tests and documented examples). It composes the
     * {@code className + "#" + methodName} identifier and derives {@code isWrite} from
     * the method-name prefix, then delegates to
     * {@link #recordAccess(long, String, boolean)}. Unlike that overload it is
     * <em>not</em> allocation-free (it builds the identifier), so it must not be used on
     * the agent advice hot path.
     *
     * @param threadId    {@code Thread.currentThread().threadId()}
     * @param className   declaring class of the intercepted getter/setter
     * @param methodName  intercepted method name (e.g. {@code "getCount"}, {@code "setCount"})
     */
    public static void recordAccess(long threadId, String className, String methodName) {
        recordAccess(threadId, className + "#" + methodName,
                methodName.startsWith("set") || methodName.startsWith("put"));
    }

    /**
     * Records that the calling thread is entering a {@code synchronized} block on {@code monitor}.
     *
     * <p>Called from woven bytecode immediately before every {@code MONITORENTER}, which is the
     * only way a plain {@code synchronized} block can become visible to the detectors: the JVM
     * emits no callback for it, and a field guarded by one otherwise records identically to a
     * field being raced. What this feeds is the per-thread lockset the lock-aware detectors
     * intersect, so an access inside the block counts as guarded by this monitor.
     *
     * <p>Deliberately does <em>not</em> check {@link #stop()}: the acquire and release calls have
     * to stay balanced across a stop, or a monitor entered before the stop would never be
     * released and would read as held for the rest of the thread's life. The work is a push onto
     * a small per-thread array, with no allocation in the steady state and no lock, so leaving it
     * running costs less than the bookkeeping needed to make stopping safe.
     *
     * @param monitor the object whose monitor is being entered
     * @since 1.9.6
     */
    public static void monitorEntered(Object monitor) {
        HeldLocks.acquired(monitor);
    }

    /**
     * Records that {@code handle} is a {@code VarHandle} on {@code qualifiedName}.
     *
     * <p>Emitted by the weaver right after a {@code findVarHandle} call in a type initializer,
     * with the handle it returned. A spinlock is acquired through the handle and released by a
     * plain write to the field, and this is the only place the two names meet. A handle bound
     * before the agent attached never makes this call; {@link SpinLocks} resolves it from the
     * handle's own descriptor instead (#558).
     *
     * @param handle        the handle the binding returned
     * @param qualifiedName the field, as {@code declaringClass.field}
     * @since 1.12.1
     */
    public static void varHandleBound(@Nullable Object handle, String qualifiedName) {
        SpinLocks.bound(handle, qualifiedName);
    }

    /**
     * Records that {@code updater} is an {@code AtomicIntegerFieldUpdater} on {@code qualifiedName}.
     *
     * <p>Emitted by the weaver right after a {@code newUpdater} call that returns one, the
     * updater counterpart of {@link #varHandleBound} (#558). An updater exposes no field name; one
     * bound before the agent attached never makes this call and is resolved by reading its target
     * from the JDK's implementation instead, where the agent has opened that to this copy (#659).
     *
     * @param updater       the updater the binding returned
     * @param qualifiedName the field, as {@code declaringClass.field}
     * @since 1.12.1
     */
    public static void atomicUpdaterBound(@Nullable Object updater, String qualifiedName) {
        SpinLocks.bound(updater, qualifiedName);
    }

    /**
     * Weaves {@code VarHandle.compareAndSet(receiver, expected, update)} on an {@code int} field.
     *
     * <p>A compare-and-swap from 0 to 1 on a flag field is a spinlock, and what is written while
     * it is held is as guarded as anything written under a monitor. Caffeine's {@code StripedBuffer}
     * replaces its volatile buffer table that way, and without seeing the lock the table read as
     * a race on a class documented as thread-safe (#554). A won swap from 0 to 1 declares the
     * receiver's spinlock held; a won swap from 1 to 0 releases it. Every other swap only swaps.
     * The hold is re-confirmed against the flag whenever the lockset is read, so a release the
     * weaver does not see cannot leave it declared (#558; see {@link SpinLocks}).
     *
     * <p>Performs the original operation and propagates its exceptions unchanged. The declaration
     * follows a won acquire and precedes nothing it could make look guarded early. The call goes
     * through {@code withInvokeBehavior()} because this hook's erased signature can only match a
     * handle that adapts its argument types, which is what a handle without exact invoke
     * behaviour does at every call site.
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is swapped
     * @param expected the value the field must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.12.1
     */
    public static boolean compareAndSetInt(VarHandle handle, Object receiver, int expected,
                                           int update) {
        if (expected == 0 && update == 1) {
            SpinLocks.aboutToAcquire(receiver, handle);
        }
        boolean won = handle.withInvokeBehavior().compareAndSet(receiver, expected, update);
        if (won && receiver != null && (expected == 0 && update == 1 || expected == 1 && update == 0)) {
            String field = SpinLocks.fieldOf(handle);
            if (field == null) {
                // Neither the binding nor the handle names the field, so a plain write releasing
                // it could not be matched. Declaring nothing reports rather than hides.
                return won;
            }
            if (update == 1) {
                SpinLocks.acquire(receiver, field, handle);
            } else {
                SpinLocks.release(receiver, field);
            }
        }
        return won;
    }

    /** Weaves {@code VarHandle.set} on an {@code int} field. @param handle the handle @param receiver the owner @param value the value */
    public static void setInt(VarHandle handle, Object receiver, int value) {
        releaseIfSpinLockField(handle, receiver);
        handle.withInvokeBehavior().set(receiver, value);
    }

    /** Weaves {@code VarHandle.setVolatile} on an {@code int} field. @param handle the handle @param receiver the owner @param value the value */
    public static void setVolatileInt(VarHandle handle, Object receiver, int value) {
        releaseIfSpinLockField(handle, receiver);
        handle.withInvokeBehavior().setVolatile(receiver, value);
    }

    /** Weaves {@code VarHandle.setRelease} on an {@code int} field. @param handle the handle @param receiver the owner @param value the value */
    public static void setReleaseInt(VarHandle handle, Object receiver, int value) {
        releaseIfSpinLockField(handle, receiver);
        handle.withInvokeBehavior().setRelease(receiver, value);
    }

    /** Weaves {@code VarHandle.setOpaque} on an {@code int} field. @param handle the handle @param receiver the owner @param value the value */
    public static void setOpaqueInt(VarHandle handle, Object receiver, int value) {
        releaseIfSpinLockField(handle, receiver);
        handle.withInvokeBehavior().setOpaque(receiver, value);
    }

    /** A holder that stores into its spinlock flag through the handle has released it. */
    private static void releaseIfSpinLockField(VarHandle handle, Object receiver) {
        if (receiver == null || !SpinLocks.anySpinField()) {
            // Checked before the handle is resolved, so an int set through a handle no spinlock
            // uses never pays for describing it.
            return;
        }
        String field = SpinLocks.fieldOf(handle);
        if (field != null && SpinLocks.isSpinField(field)) {
            SpinLocks.release(receiver, field);
        }
    }

    /**
     * Weaves {@code AtomicIntegerFieldUpdater.compareAndSet(receiver, expected, update)}.
     *
     * <p>The updater form of {@link #compareAndSetInt}, the spinlock shape older netty and
     * JDK-style code uses (#558): a won swap from 0 to 1 declares the receiver's flag held, a won
     * swap from 1 to 0 releases it, and the hold is re-confirmed through the updater whenever the
     * lockset is read. Only an updater whose {@code newUpdater} call the weaver saw names a field;
     * a swap through any other updater only swaps.
     *
     * <p>Performs the original operation first, so its exceptions, including the
     * {@code ClassCastException} for a receiver of the wrong type, propagate unchanged.
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field is swapped
     * @param expected the value the field must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.12.1
     */
    public static boolean compareAndSetIntUpdater(AtomicIntegerFieldUpdater<?> updater,
                                                  Object receiver, int expected, int update) {
        if (expected == 0 && update == 1) {
            SpinLocks.aboutToAcquire(receiver, updater);
        }
        AtomicIntegerFieldUpdater<Object> target = erased(updater);
        boolean won = target.compareAndSet(receiver, expected, update);
        if (won && receiver != null && ((expected == 0 && update == 1) || (expected == 1 && update == 0))) {
            String field = SpinLocks.fieldOf(updater);
            if (field == null) {
                return won;
            }
            if (update == 1) {
                SpinLocks.acquire(receiver, field, target);
            } else {
                SpinLocks.release(receiver, field);
            }
        }
        return won;
    }

    /** Weaves {@code AtomicIntegerFieldUpdater.set}, a release when the holder stores its flag. @param updater the updater @param receiver the owner @param value the value @since 1.12.1 */
    public static void setIntUpdater(AtomicIntegerFieldUpdater<?> updater, Object receiver,
                                     int value) {
        releaseIfSpinLockField(updater, receiver);
        erased(updater).set(receiver, value);
    }

    /** Weaves {@code AtomicIntegerFieldUpdater.lazySet}, a release when the holder stores its flag. @param updater the updater @param receiver the owner @param value the value @since 1.12.1 */
    public static void lazySetIntUpdater(AtomicIntegerFieldUpdater<?> updater, Object receiver,
                                         int value) {
        releaseIfSpinLockField(updater, receiver);
        erased(updater).lazySet(receiver, value);
    }

    private static void releaseIfSpinLockField(AtomicIntegerFieldUpdater<?> updater,
                                               Object receiver) {
        if (receiver == null || updater == null) {
            return;
        }
        String field = SpinLocks.fieldOf(updater);
        if (field != null && SpinLocks.isSpinField(field)) {
            SpinLocks.release(receiver, field);
        }
    }

    @SuppressWarnings("unchecked")
    private static AtomicIntegerFieldUpdater<Object> erased(AtomicIntegerFieldUpdater<?> updater) {
        return (AtomicIntegerFieldUpdater<Object>) updater;
    }

    /**
     * Weaves {@code AtomicBoolean.compareAndSet(expected, update)}, where the atomic is the lock.
     *
     * <p>{@code busy.compareAndSet(false, true)} guards what follows exactly as a monitor would, and
     * the lock is the atomic object itself rather than a field of the receiver (#558). A won swap to
     * {@code true} declares it held, a won swap to {@code false} releases it, and the hold is
     * re-confirmed with {@code get()} whenever the lockset is read, so a one-shot
     * {@code started.compareAndSet(false, true)} that is never reset only ever reads as held by the
     * one thread that won it, which cannot make two threads look excluded.
     *
     * @param flag     the atomic the call site invoked
     * @param expected the value it must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.12.1
     */
    public static boolean compareAndSetAtomicBoolean(AtomicBoolean flag, boolean expected,
                                                     boolean update) {
        if (!expected && update) {
            SpinLocks.aboutToAcquire(flag);
        }
        boolean won = flag.compareAndSet(expected, update);
        if (won && expected != update) {
            if (update) {
                SpinLocks.acquire(flag);
            } else {
                SpinLocks.release(flag);
            }
        }
        return won;
    }

    /**
     * Weaves {@code AtomicBoolean.getAndSet(value)}: {@code !busy.getAndSet(true)} is a won acquire,
     * and storing {@code false} is a release. @param flag the atomic @param value the value to
     * store @return the previous value @since 1.12.1
     */
    public static boolean getAndSetAtomicBoolean(AtomicBoolean flag, boolean value) {
        if (!value) {
            SpinLocks.release(flag);
        } else {
            SpinLocks.aboutToAcquire(flag);
        }
        boolean previous = flag.getAndSet(value);
        if (value && !previous) {
            SpinLocks.acquire(flag);
        }
        return previous;
    }

    /** Weaves {@code AtomicBoolean.set}; storing {@code false} releases. @param flag the atomic @param value the value @since 1.12.1 */
    public static void setAtomicBoolean(AtomicBoolean flag, boolean value) {
        if (!value) {
            SpinLocks.release(flag);
        }
        flag.set(value);
    }

    /** Weaves {@code AtomicBoolean.lazySet}; storing {@code false} releases. @param flag the atomic @param value the value @since 1.12.1 */
    public static void lazySetAtomicBoolean(AtomicBoolean flag, boolean value) {
        if (!value) {
            SpinLocks.release(flag);
        }
        flag.lazySet(value);
    }

    /**
     * Weaves {@code AtomicInteger.compareAndSet(expected, update)}: a won swap from 0 to 1 takes the
     * atomic as a lock and one from 1 to 0 releases it (#558). A counter that happens to pass
     * through 0 and 1 reads as held only while it stays at 1 with this thread its last winner, which
     * is mutual exclusion in fact. @param count the atomic @param expected the value it must hold
     * @param update the value to store @return whether the swap happened @since 1.12.1
     */
    public static boolean compareAndSetAtomicInteger(AtomicInteger count, int expected, int update) {
        if (expected == 0 && update == 1) {
            SpinLocks.aboutToAcquire(count);
        }
        boolean won = count.compareAndSet(expected, update);
        if (won && expected == 0 && update == 1) {
            SpinLocks.acquire(count);
        } else if (won && expected == 1 && update == 0) {
            SpinLocks.release(count);
        }
        return won;
    }

    /** Weaves {@code AtomicInteger.set}; storing 0 releases. @param count the atomic @param value the value @since 1.12.1 */
    public static void setAtomicInteger(AtomicInteger count, int value) {
        if (value == 0) {
            SpinLocks.release(count);
        }
        count.set(value);
    }

    /** Weaves {@code AtomicInteger.lazySet}; storing 0 releases. @param count the atomic @param value the value @since 1.12.1 */
    public static void lazySetAtomicInteger(AtomicInteger count, int value) {
        if (value == 0) {
            SpinLocks.release(count);
        }
        count.lazySet(value);
    }

    // ---- Releases through the value-returning forms (#658) --------------------------------------
    //
    // A holder that ended its hold through one of these used to be invisible: a contender that had
    // just read the flag locked could land its swap before the stale hold was re-confirmed, and the
    // old holder then passed re-confirmation until the contender wrote its stamp (#658). Each hook
    // performs the original operation first and returns its result unchanged, then releases on the
    // calling thread when the operation left the flag at anything but the locked value. Releasing
    // after the operation opens no window of its own: the releasing thread is inside the hook, so
    // it records no access between the two, and a release only ever clears this thread's own hold.

    /**
     * Weaves {@code VarHandle.getAndSet} on an {@code int} field; storing anything but 1 releases (#658).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is written
     * @param value    the value to store
     * @return the previous value
     * @since 1.12.1
     */
    public static int getAndSetInt(VarHandle handle, Object receiver, int value) {
        int previous = (int) handle.withInvokeBehavior().getAndSet(receiver, value);
        if (value != 1) {
            releaseIfSpinLockField(handle, receiver);
        }
        return previous;
    }

    /**
     * Weaves {@code VarHandle.getAndAdd} on an {@code int} field; a result other than 1 releases (#658).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is written
     * @param delta    the value to add
     * @return the previous value
     * @since 1.12.1
     */
    public static int getAndAddInt(VarHandle handle, Object receiver, int delta) {
        int previous = (int) handle.withInvokeBehavior().getAndAdd(receiver, delta);
        if (previous + delta != 1) {
            releaseIfSpinLockField(handle, receiver);
        }
        return previous;
    }

    /**
     * Weaves {@code VarHandle.compareAndExchange} on an {@code int} field; a won exchange to
     * anything but 1 releases (#658).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is swapped
     * @param expected the value the field must hold
     * @param update   the value to store
     * @return the witness value
     * @since 1.12.1
     */
    public static int compareAndExchangeInt(VarHandle handle, Object receiver, int expected,
                                            int update) {
        int witness = (int) handle.withInvokeBehavior().compareAndExchange(receiver, expected, update);
        if (witness == expected && update != 1) {
            releaseIfSpinLockField(handle, receiver);
        }
        return witness;
    }

    /**
     * Weaves {@code VarHandle.weakCompareAndSet} on an {@code int} field; a won swap to anything
     * but 1 releases (#658).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is swapped
     * @param expected the value the field must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.12.1
     */
    public static boolean weakCompareAndSetInt(VarHandle handle, Object receiver, int expected,
                                               int update) {
        boolean won = handle.withInvokeBehavior().weakCompareAndSet(receiver, expected, update);
        if (won && update != 1) {
            releaseIfSpinLockField(handle, receiver);
        }
        return won;
    }

    /**
     * Weaves {@code VarHandle.weakCompareAndSetPlain} on an {@code int} field; a won swap to
     * anything but 1 releases (#658).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is swapped
     * @param expected the value the field must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.12.1
     */
    public static boolean weakCompareAndSetPlainInt(VarHandle handle, Object receiver, int expected,
                                                    int update) {
        boolean won = handle.withInvokeBehavior().weakCompareAndSetPlain(receiver, expected, update);
        if (won && update != 1) {
            releaseIfSpinLockField(handle, receiver);
        }
        return won;
    }

    /**
     * Weaves {@code AtomicIntegerFieldUpdater.getAndSet}; storing anything but 1 releases (#658).
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field is written
     * @param value    the value to store
     * @return the previous value
     * @since 1.12.1
     */
    public static int getAndSetIntUpdater(AtomicIntegerFieldUpdater<?> updater, Object receiver,
                                          int value) {
        int previous = erased(updater).getAndSet(receiver, value);
        if (value != 1) {
            releaseIfSpinLockField(updater, receiver);
        }
        return previous;
    }

    /**
     * Weaves {@code AtomicIntegerFieldUpdater.getAndAdd}; a result other than 1 releases (#658).
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field is written
     * @param delta    the value to add
     * @return the previous value
     * @since 1.12.1
     */
    public static int getAndAddIntUpdater(AtomicIntegerFieldUpdater<?> updater, Object receiver,
                                          int delta) {
        int previous = erased(updater).getAndAdd(receiver, delta);
        if (previous + delta != 1) {
            releaseIfSpinLockField(updater, receiver);
        }
        return previous;
    }

    /**
     * Weaves {@code AtomicIntegerFieldUpdater.addAndGet}; a result other than 1 releases (#658).
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field is written
     * @param delta    the value to add
     * @return the updated value
     * @since 1.12.1
     */
    public static int addAndGetIntUpdater(AtomicIntegerFieldUpdater<?> updater, Object receiver,
                                          int delta) {
        int current = erased(updater).addAndGet(receiver, delta);
        if (current != 1) {
            releaseIfSpinLockField(updater, receiver);
        }
        return current;
    }

    /**
     * Weaves {@code AtomicIntegerFieldUpdater.getAndDecrement}; a result other than 1 releases (#658).
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field is written
     * @return the previous value
     * @since 1.12.1
     */
    public static int getAndDecrementIntUpdater(AtomicIntegerFieldUpdater<?> updater,
                                                Object receiver) {
        int previous = erased(updater).getAndDecrement(receiver);
        if (previous != 2) {
            releaseIfSpinLockField(updater, receiver);
        }
        return previous;
    }

    /**
     * Weaves {@code AtomicIntegerFieldUpdater.decrementAndGet}; a result other than 1 releases (#658).
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field is written
     * @return the updated value
     * @since 1.12.1
     */
    public static int decrementAndGetIntUpdater(AtomicIntegerFieldUpdater<?> updater,
                                                Object receiver) {
        int current = erased(updater).decrementAndGet(receiver);
        if (current != 1) {
            releaseIfSpinLockField(updater, receiver);
        }
        return current;
    }

    /**
     * Weaves {@code AtomicIntegerFieldUpdater.weakCompareAndSet}; a won swap to anything but 1
     * releases (#658).
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field is swapped
     * @param expected the value the field must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.12.1
     */
    public static boolean weakCompareAndSetIntUpdater(AtomicIntegerFieldUpdater<?> updater,
                                                      Object receiver, int expected, int update) {
        boolean won = erased(updater).weakCompareAndSet(receiver, expected, update);
        if (won && update != 1) {
            releaseIfSpinLockField(updater, receiver);
        }
        return won;
    }

    /**
     * Weaves {@code AtomicInteger.getAndSet}; storing anything but 1 releases (#658).
     *
     * @param count the atomic the call site invoked
     * @param value the value to store
     * @return the previous value
     * @since 1.12.1
     */
    public static int getAndSetAtomicInteger(AtomicInteger count, int value) {
        int previous = count.getAndSet(value);
        if (value != 1) {
            SpinLocks.release(count);
        }
        return previous;
    }

    /**
     * Weaves {@code AtomicInteger.getAndAdd}; a result other than 1 releases (#658).
     *
     * @param count the atomic the call site invoked
     * @param delta the value to add
     * @return the previous value
     * @since 1.12.1
     */
    public static int getAndAddAtomicInteger(AtomicInteger count, int delta) {
        int previous = count.getAndAdd(delta);
        if (previous + delta != 1) {
            SpinLocks.release(count);
        }
        return previous;
    }

    /**
     * Weaves {@code AtomicInteger.addAndGet}; a result other than 1 releases (#658).
     *
     * @param count the atomic the call site invoked
     * @param delta the value to add
     * @return the updated value
     * @since 1.12.1
     */
    public static int addAndGetAtomicInteger(AtomicInteger count, int delta) {
        int current = count.addAndGet(delta);
        if (current != 1) {
            SpinLocks.release(count);
        }
        return current;
    }

    /**
     * Weaves {@code AtomicInteger.getAndDecrement}; a result other than 1 releases (#658).
     *
     * @param count the atomic the call site invoked
     * @return the previous value
     * @since 1.12.1
     */
    public static int getAndDecrementAtomicInteger(AtomicInteger count) {
        int previous = count.getAndDecrement();
        if (previous != 2) {
            SpinLocks.release(count);
        }
        return previous;
    }

    /**
     * Weaves {@code AtomicInteger.decrementAndGet}; a result other than 1 releases (#658).
     *
     * @param count the atomic the call site invoked
     * @return the updated value
     * @since 1.12.1
     */
    public static int decrementAndGetAtomicInteger(AtomicInteger count) {
        int current = count.decrementAndGet();
        if (current != 1) {
            SpinLocks.release(count);
        }
        return current;
    }

    /**
     * Weaves {@code AtomicInteger.compareAndExchange}; a won exchange to anything but 1 releases (#658).
     *
     * @param count    the atomic the call site invoked
     * @param expected the value it must hold
     * @param update   the value to store
     * @return the witness value
     * @since 1.12.1
     */
    public static int compareAndExchangeAtomicInteger(AtomicInteger count, int expected, int update) {
        int witness = count.compareAndExchange(expected, update);
        if (witness == expected && update != 1) {
            SpinLocks.release(count);
        }
        return witness;
    }

    /**
     * Weaves {@code AtomicInteger.weakCompareAndSetPlain}; a won swap to anything but 1 releases (#658).
     *
     * @param count    the atomic the call site invoked
     * @param expected the value it must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.12.1
     */
    public static boolean weakCompareAndSetPlainAtomicInteger(AtomicInteger count, int expected,
                                                              int update) {
        boolean won = count.weakCompareAndSetPlain(expected, update);
        if (won && update != 1) {
            SpinLocks.release(count);
        }
        return won;
    }

    /**
     * Weaves {@code AtomicInteger.weakCompareAndSetVolatile}; a won swap to anything but 1
     * releases (#658).
     *
     * @param count    the atomic the call site invoked
     * @param expected the value it must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.12.1
     */
    public static boolean weakCompareAndSetVolatileAtomicInteger(AtomicInteger count, int expected,
                                                                 int update) {
        boolean won = count.weakCompareAndSetVolatile(expected, update);
        if (won && update != 1) {
            SpinLocks.release(count);
        }
        return won;
    }

    /**
     * Weaves {@code AtomicBoolean.compareAndExchange}; a won exchange to {@code false} releases (#658).
     *
     * @param flag     the atomic the call site invoked
     * @param expected the value it must hold
     * @param update   the value to store
     * @return the witness value
     * @since 1.12.1
     */
    public static boolean compareAndExchangeAtomicBoolean(AtomicBoolean flag, boolean expected,
                                                          boolean update) {
        boolean witness = flag.compareAndExchange(expected, update);
        if (witness == expected && !update) {
            SpinLocks.release(flag);
        }
        return witness;
    }

    /**
     * Weaves {@code AtomicBoolean.weakCompareAndSetPlain}; a won swap to {@code false} releases (#658).
     *
     * @param flag     the atomic the call site invoked
     * @param expected the value it must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.12.1
     */
    public static boolean weakCompareAndSetPlainAtomicBoolean(AtomicBoolean flag, boolean expected,
                                                              boolean update) {
        boolean won = flag.weakCompareAndSetPlain(expected, update);
        if (won && !update) {
            SpinLocks.release(flag);
        }
        return won;
    }

    /**
     * Weaves {@code AtomicBoolean.weakCompareAndSetVolatile}; a won swap to {@code false} releases (#658).
     *
     * @param flag     the atomic the call site invoked
     * @param expected the value it must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.12.1
     */
    public static boolean weakCompareAndSetVolatileAtomicBoolean(AtomicBoolean flag,
                                                                 boolean expected, boolean update) {
        boolean won = flag.weakCompareAndSetVolatile(expected, update);
        if (won && !update) {
            SpinLocks.release(flag);
        }
        return won;
    }

    // ---- Releases through the remaining int forms (#667) ----------------------------------------
    //
    // The same contract as the #658 hooks above: perform the original operation, return its result
    // unchanged, and release on the calling thread when the operation left the flag at anything
    // but the locked value. A release only ever clears this thread's own hold, so reading "not
    // locked" wrongly can only end a hold early, which reports rather than hides.

    /**
     * Weaves {@code VarHandle.getAndSetAcquire} on an {@code int} field; storing anything but 1 releases (#667).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is written
     * @param value    the value to store
     * @return the previous value
     * @since 1.12.2
     */
    public static int getAndSetAcquireInt(VarHandle handle, Object receiver, int value) {
        int previous = (int) handle.withInvokeBehavior().getAndSetAcquire(receiver, value);
        releaseUnlessLocked(handle, receiver, value);
        return previous;
    }

    /**
     * Weaves {@code VarHandle.getAndSetRelease} on an {@code int} field; storing anything but 1 releases (#667).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is written
     * @param value    the value to store
     * @return the previous value
     * @since 1.12.2
     */
    public static int getAndSetReleaseInt(VarHandle handle, Object receiver, int value) {
        int previous = (int) handle.withInvokeBehavior().getAndSetRelease(receiver, value);
        releaseUnlessLocked(handle, receiver, value);
        return previous;
    }

    /**
     * Weaves {@code VarHandle.getAndAddAcquire} on an {@code int} field; a result other than 1 releases (#667).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is written
     * @param delta    the value to add
     * @return the previous value
     * @since 1.12.2
     */
    public static int getAndAddAcquireInt(VarHandle handle, Object receiver, int delta) {
        int previous = (int) handle.withInvokeBehavior().getAndAddAcquire(receiver, delta);
        releaseUnlessLocked(handle, receiver, previous + delta);
        return previous;
    }

    /**
     * Weaves {@code VarHandle.getAndAddRelease} on an {@code int} field; a result other than 1 releases (#667).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is written
     * @param delta    the value to add
     * @return the previous value
     * @since 1.12.2
     */
    public static int getAndAddReleaseInt(VarHandle handle, Object receiver, int delta) {
        int previous = (int) handle.withInvokeBehavior().getAndAddRelease(receiver, delta);
        releaseUnlessLocked(handle, receiver, previous + delta);
        return previous;
    }

    /**
     * Weaves {@code VarHandle.compareAndExchangeAcquire} on an {@code int} field; a won exchange to
     * anything but 1 releases (#667).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is swapped
     * @param expected the value the field must hold
     * @param update   the value to store
     * @return the witness value
     * @since 1.12.2
     */
    public static int compareAndExchangeAcquireInt(VarHandle handle, Object receiver, int expected,
                                                   int update) {
        int witness = (int) handle.withInvokeBehavior()
                .compareAndExchangeAcquire(receiver, expected, update);
        if (witness == expected) {
            releaseUnlessLocked(handle, receiver, update);
        }
        return witness;
    }

    /**
     * Weaves {@code VarHandle.compareAndExchangeRelease} on an {@code int} field; a won exchange to
     * anything but 1 releases (#667).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is swapped
     * @param expected the value the field must hold
     * @param update   the value to store
     * @return the witness value
     * @since 1.12.2
     */
    public static int compareAndExchangeReleaseInt(VarHandle handle, Object receiver, int expected,
                                                   int update) {
        int witness = (int) handle.withInvokeBehavior()
                .compareAndExchangeRelease(receiver, expected, update);
        if (witness == expected) {
            releaseUnlessLocked(handle, receiver, update);
        }
        return witness;
    }

    /**
     * Weaves {@code VarHandle.weakCompareAndSetAcquire} on an {@code int} field; a won swap to
     * anything but 1 releases (#667).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is swapped
     * @param expected the value the field must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.12.2
     */
    public static boolean weakCompareAndSetAcquireInt(VarHandle handle, Object receiver, int expected,
                                                      int update) {
        boolean won = handle.withInvokeBehavior().weakCompareAndSetAcquire(receiver, expected, update);
        if (won) {
            releaseUnlessLocked(handle, receiver, update);
        }
        return won;
    }

    /**
     * Weaves {@code VarHandle.weakCompareAndSetRelease} on an {@code int} field; a won swap to
     * anything but 1 releases (#667).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is swapped
     * @param expected the value the field must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.12.2
     */
    public static boolean weakCompareAndSetReleaseInt(VarHandle handle, Object receiver, int expected,
                                                      int update) {
        boolean won = handle.withInvokeBehavior().weakCompareAndSetRelease(receiver, expected, update);
        if (won) {
            releaseUnlessLocked(handle, receiver, update);
        }
        return won;
    }

    /**
     * Weaves {@code VarHandle.getAndBitwiseOr} on an {@code int} field; a result other than 1 releases (#667).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is written
     * @param mask     the bits to set
     * @return the previous value
     * @since 1.12.2
     */
    public static int getAndBitwiseOrInt(VarHandle handle, Object receiver, int mask) {
        int previous = (int) handle.withInvokeBehavior().getAndBitwiseOr(receiver, mask);
        releaseUnlessLocked(handle, receiver, previous | mask);
        return previous;
    }

    /**
     * Weaves {@code VarHandle.getAndBitwiseOrAcquire} on an {@code int} field; a result other than 1 releases (#667).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is written
     * @param mask     the bits to set
     * @return the previous value
     * @since 1.12.2
     */
    public static int getAndBitwiseOrAcquireInt(VarHandle handle, Object receiver, int mask) {
        int previous = (int) handle.withInvokeBehavior().getAndBitwiseOrAcquire(receiver, mask);
        releaseUnlessLocked(handle, receiver, previous | mask);
        return previous;
    }

    /**
     * Weaves {@code VarHandle.getAndBitwiseOrRelease} on an {@code int} field; a result other than 1 releases (#667).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is written
     * @param mask     the bits to set
     * @return the previous value
     * @since 1.12.2
     */
    public static int getAndBitwiseOrReleaseInt(VarHandle handle, Object receiver, int mask) {
        int previous = (int) handle.withInvokeBehavior().getAndBitwiseOrRelease(receiver, mask);
        releaseUnlessLocked(handle, receiver, previous | mask);
        return previous;
    }

    /**
     * Weaves {@code VarHandle.getAndBitwiseAnd} on an {@code int} field; a result other than 1 releases (#667).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is written
     * @param mask     the bits to keep
     * @return the previous value
     * @since 1.12.2
     */
    public static int getAndBitwiseAndInt(VarHandle handle, Object receiver, int mask) {
        int previous = (int) handle.withInvokeBehavior().getAndBitwiseAnd(receiver, mask);
        releaseUnlessLocked(handle, receiver, previous & mask);
        return previous;
    }

    /**
     * Weaves {@code VarHandle.getAndBitwiseAndAcquire} on an {@code int} field; a result other than 1 releases (#667).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is written
     * @param mask     the bits to keep
     * @return the previous value
     * @since 1.12.2
     */
    public static int getAndBitwiseAndAcquireInt(VarHandle handle, Object receiver, int mask) {
        int previous = (int) handle.withInvokeBehavior().getAndBitwiseAndAcquire(receiver, mask);
        releaseUnlessLocked(handle, receiver, previous & mask);
        return previous;
    }

    /**
     * Weaves {@code VarHandle.getAndBitwiseAndRelease} on an {@code int} field; a result other than 1 releases (#667).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is written
     * @param mask     the bits to keep
     * @return the previous value
     * @since 1.12.2
     */
    public static int getAndBitwiseAndReleaseInt(VarHandle handle, Object receiver, int mask) {
        int previous = (int) handle.withInvokeBehavior().getAndBitwiseAndRelease(receiver, mask);
        releaseUnlessLocked(handle, receiver, previous & mask);
        return previous;
    }

    /**
     * Weaves {@code VarHandle.getAndBitwiseXor} on an {@code int} field; a result other than 1 releases (#667).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is written
     * @param mask     the bits to flip
     * @return the previous value
     * @since 1.12.2
     */
    public static int getAndBitwiseXorInt(VarHandle handle, Object receiver, int mask) {
        int previous = (int) handle.withInvokeBehavior().getAndBitwiseXor(receiver, mask);
        releaseUnlessLocked(handle, receiver, previous ^ mask);
        return previous;
    }

    /**
     * Weaves {@code VarHandle.getAndBitwiseXorAcquire} on an {@code int} field; a result other than 1 releases (#667).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is written
     * @param mask     the bits to flip
     * @return the previous value
     * @since 1.12.2
     */
    public static int getAndBitwiseXorAcquireInt(VarHandle handle, Object receiver, int mask) {
        int previous = (int) handle.withInvokeBehavior().getAndBitwiseXorAcquire(receiver, mask);
        releaseUnlessLocked(handle, receiver, previous ^ mask);
        return previous;
    }

    /**
     * Weaves {@code VarHandle.getAndBitwiseXorRelease} on an {@code int} field; a result other than 1 releases (#667).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is written
     * @param mask     the bits to flip
     * @return the previous value
     * @since 1.12.2
     */
    public static int getAndBitwiseXorReleaseInt(VarHandle handle, Object receiver, int mask) {
        int previous = (int) handle.withInvokeBehavior().getAndBitwiseXorRelease(receiver, mask);
        releaseUnlessLocked(handle, receiver, previous ^ mask);
        return previous;
    }

    /** Releases {@code receiver}'s flag through {@code handle} unless {@code now} is the locked value. */
    private static void releaseUnlessLocked(VarHandle handle, Object receiver, int now) {
        if (now != 1) {
            releaseIfSpinLockField(handle, receiver);
        }
    }

    /**
     * Weaves {@code AtomicIntegerFieldUpdater.getAndUpdate}; the flag reading anything but 1 after
     * the call releases (#667).
     *
     * <p>The function's result is not on the stack, and running it again could have side effects,
     * so the flag is read once the call has returned. Another thread's acquire landing in between
     * reads as still locked and skips the release, which leaves the hold to re-confirmation, the
     * behaviour before this hook existed.
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field is updated
     * @param function the update function
     * @return the previous value
     * @since 1.12.2
     */
    public static int getAndUpdateIntUpdater(AtomicIntegerFieldUpdater<?> updater, Object receiver,
                                             IntUnaryOperator function) {
        AtomicIntegerFieldUpdater<Object> target = erased(updater);
        int previous = target.getAndUpdate(receiver, function);
        if (target.get(receiver) != 1) {
            releaseIfSpinLockField(updater, receiver);
        }
        return previous;
    }

    /**
     * Weaves {@code AtomicIntegerFieldUpdater.updateAndGet}; a result other than 1 releases (#667).
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field is updated
     * @param function the update function
     * @return the updated value
     * @since 1.12.2
     */
    public static int updateAndGetIntUpdater(AtomicIntegerFieldUpdater<?> updater, Object receiver,
                                             IntUnaryOperator function) {
        int current = erased(updater).updateAndGet(receiver, function);
        if (current != 1) {
            releaseIfSpinLockField(updater, receiver);
        }
        return current;
    }

    /**
     * Weaves {@code AtomicIntegerFieldUpdater.getAndAccumulate}; the flag reading anything but 1
     * after the call releases (#667), for the reason {@link #getAndUpdateIntUpdater} gives.
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field is updated
     * @param operand  the value to accumulate
     * @param function the accumulator
     * @return the previous value
     * @since 1.12.2
     */
    public static int getAndAccumulateIntUpdater(AtomicIntegerFieldUpdater<?> updater,
                                                 Object receiver, int operand,
                                                 IntBinaryOperator function) {
        AtomicIntegerFieldUpdater<Object> target = erased(updater);
        int previous = target.getAndAccumulate(receiver, operand, function);
        if (target.get(receiver) != 1) {
            releaseIfSpinLockField(updater, receiver);
        }
        return previous;
    }

    /**
     * Weaves {@code AtomicIntegerFieldUpdater.accumulateAndGet}; a result other than 1 releases (#667).
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field is updated
     * @param operand  the value to accumulate
     * @param function the accumulator
     * @return the updated value
     * @since 1.12.2
     */
    public static int accumulateAndGetIntUpdater(AtomicIntegerFieldUpdater<?> updater,
                                                 Object receiver, int operand,
                                                 IntBinaryOperator function) {
        int current = erased(updater).accumulateAndGet(receiver, operand, function);
        if (current != 1) {
            releaseIfSpinLockField(updater, receiver);
        }
        return current;
    }

    /**
     * Weaves {@code AtomicInteger.setPlain}; storing 0 releases (#667).
     *
     * @param count the atomic the call site invoked
     * @param value the value to store
     * @since 1.12.2
     */
    public static void setPlainAtomicInteger(AtomicInteger count, int value) {
        if (value == 0) {
            SpinLocks.release(count);
        }
        count.setPlain(value);
    }

    /**
     * Weaves {@code AtomicInteger.setOpaque}; storing 0 releases (#667).
     *
     * @param count the atomic the call site invoked
     * @param value the value to store
     * @since 1.12.2
     */
    public static void setOpaqueAtomicInteger(AtomicInteger count, int value) {
        if (value == 0) {
            SpinLocks.release(count);
        }
        count.setOpaque(value);
    }

    /**
     * Weaves {@code AtomicInteger.setRelease}; storing 0 releases (#667).
     *
     * @param count the atomic the call site invoked
     * @param value the value to store
     * @since 1.12.2
     */
    public static void setReleaseAtomicInteger(AtomicInteger count, int value) {
        if (value == 0) {
            SpinLocks.release(count);
        }
        count.setRelease(value);
    }

    /**
     * Weaves the deprecated {@code AtomicInteger.weakCompareAndSet}; a won swap to anything but 1
     * releases (#667).
     *
     * @param count    the atomic the call site invoked
     * @param expected the value it must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.12.2
     */
    @SuppressWarnings("deprecation")
    public static boolean weakCompareAndSetAtomicInteger(AtomicInteger count, int expected, int update) {
        boolean won = count.weakCompareAndSet(expected, update);
        if (won && update != 1) {
            SpinLocks.release(count);
        }
        return won;
    }

    /**
     * Weaves {@code AtomicInteger.weakCompareAndSetAcquire}; a won swap to anything but 1 releases (#667).
     *
     * @param count    the atomic the call site invoked
     * @param expected the value it must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.12.2
     */
    public static boolean weakCompareAndSetAcquireAtomicInteger(AtomicInteger count, int expected,
                                                                int update) {
        boolean won = count.weakCompareAndSetAcquire(expected, update);
        if (won && update != 1) {
            SpinLocks.release(count);
        }
        return won;
    }

    /**
     * Weaves {@code AtomicInteger.weakCompareAndSetRelease}; a won swap to anything but 1 releases (#667).
     *
     * @param count    the atomic the call site invoked
     * @param expected the value it must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.12.2
     */
    public static boolean weakCompareAndSetReleaseAtomicInteger(AtomicInteger count, int expected,
                                                                int update) {
        boolean won = count.weakCompareAndSetRelease(expected, update);
        if (won && update != 1) {
            SpinLocks.release(count);
        }
        return won;
    }

    /**
     * Weaves {@code AtomicInteger.compareAndExchangeAcquire}; a won exchange to anything but 1 releases (#667).
     *
     * @param count    the atomic the call site invoked
     * @param expected the value it must hold
     * @param update   the value to store
     * @return the witness value
     * @since 1.12.2
     */
    public static int compareAndExchangeAcquireAtomicInteger(AtomicInteger count, int expected,
                                                             int update) {
        int witness = count.compareAndExchangeAcquire(expected, update);
        if (witness == expected && update != 1) {
            SpinLocks.release(count);
        }
        return witness;
    }

    /**
     * Weaves {@code AtomicInteger.compareAndExchangeRelease}; a won exchange to anything but 1 releases (#667).
     *
     * @param count    the atomic the call site invoked
     * @param expected the value it must hold
     * @param update   the value to store
     * @return the witness value
     * @since 1.12.2
     */
    public static int compareAndExchangeReleaseAtomicInteger(AtomicInteger count, int expected,
                                                             int update) {
        int witness = count.compareAndExchangeRelease(expected, update);
        if (witness == expected && update != 1) {
            SpinLocks.release(count);
        }
        return witness;
    }

    /**
     * Weaves {@code AtomicInteger.getAndUpdate}; the atomic reading anything but 1 after the call
     * releases (#667), for the reason {@link #getAndUpdateIntUpdater} gives.
     *
     * @param count    the atomic the call site invoked
     * @param function the update function
     * @return the previous value
     * @since 1.12.2
     */
    public static int getAndUpdateAtomicInteger(AtomicInteger count, IntUnaryOperator function) {
        int previous = count.getAndUpdate(function);
        if (count.get() != 1) {
            SpinLocks.release(count);
        }
        return previous;
    }

    /**
     * Weaves {@code AtomicInteger.updateAndGet}; a result other than 1 releases (#667).
     *
     * @param count    the atomic the call site invoked
     * @param function the update function
     * @return the updated value
     * @since 1.12.2
     */
    public static int updateAndGetAtomicInteger(AtomicInteger count, IntUnaryOperator function) {
        int current = count.updateAndGet(function);
        if (current != 1) {
            SpinLocks.release(count);
        }
        return current;
    }

    /**
     * Weaves {@code AtomicInteger.getAndAccumulate}; the atomic reading anything but 1 after the
     * call releases (#667), for the reason {@link #getAndUpdateIntUpdater} gives.
     *
     * @param count    the atomic the call site invoked
     * @param operand  the value to accumulate
     * @param function the accumulator
     * @return the previous value
     * @since 1.12.2
     */
    public static int getAndAccumulateAtomicInteger(AtomicInteger count, int operand,
                                                    IntBinaryOperator function) {
        int previous = count.getAndAccumulate(operand, function);
        if (count.get() != 1) {
            SpinLocks.release(count);
        }
        return previous;
    }

    /**
     * Weaves {@code AtomicInteger.accumulateAndGet}; a result other than 1 releases (#667).
     *
     * @param count    the atomic the call site invoked
     * @param operand  the value to accumulate
     * @param function the accumulator
     * @return the updated value
     * @since 1.12.2
     */
    public static int accumulateAndGetAtomicInteger(AtomicInteger count, int operand,
                                                    IntBinaryOperator function) {
        int current = count.accumulateAndGet(operand, function);
        if (current != 1) {
            SpinLocks.release(count);
        }
        return current;
    }

    /**
     * Weaves {@code AtomicBoolean.setPlain}; storing {@code false} releases (#667).
     *
     * @param flag  the atomic the call site invoked
     * @param value the value to store
     * @since 1.12.2
     */
    public static void setPlainAtomicBoolean(AtomicBoolean flag, boolean value) {
        if (!value) {
            SpinLocks.release(flag);
        }
        flag.setPlain(value);
    }

    /**
     * Weaves {@code AtomicBoolean.setOpaque}; storing {@code false} releases (#667).
     *
     * @param flag  the atomic the call site invoked
     * @param value the value to store
     * @since 1.12.2
     */
    public static void setOpaqueAtomicBoolean(AtomicBoolean flag, boolean value) {
        if (!value) {
            SpinLocks.release(flag);
        }
        flag.setOpaque(value);
    }

    /**
     * Weaves {@code AtomicBoolean.setRelease}; storing {@code false} releases (#667).
     *
     * @param flag  the atomic the call site invoked
     * @param value the value to store
     * @since 1.12.2
     */
    public static void setReleaseAtomicBoolean(AtomicBoolean flag, boolean value) {
        if (!value) {
            SpinLocks.release(flag);
        }
        flag.setRelease(value);
    }

    /**
     * Weaves the deprecated {@code AtomicBoolean.weakCompareAndSet}; a won swap to {@code false}
     * releases (#667).
     *
     * @param flag     the atomic the call site invoked
     * @param expected the value it must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.12.2
     */
    @SuppressWarnings("deprecation")
    public static boolean weakCompareAndSetAtomicBoolean(AtomicBoolean flag, boolean expected,
                                                         boolean update) {
        boolean won = flag.weakCompareAndSet(expected, update);
        if (won && !update) {
            SpinLocks.release(flag);
        }
        return won;
    }

    /**
     * Weaves {@code AtomicBoolean.weakCompareAndSetAcquire}; a won swap to {@code false} releases (#667).
     *
     * @param flag     the atomic the call site invoked
     * @param expected the value it must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.12.2
     */
    public static boolean weakCompareAndSetAcquireAtomicBoolean(AtomicBoolean flag, boolean expected,
                                                                boolean update) {
        boolean won = flag.weakCompareAndSetAcquire(expected, update);
        if (won && !update) {
            SpinLocks.release(flag);
        }
        return won;
    }

    /**
     * Weaves {@code AtomicBoolean.weakCompareAndSetRelease}; a won swap to {@code false} releases (#667).
     *
     * @param flag     the atomic the call site invoked
     * @param expected the value it must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.12.2
     */
    public static boolean weakCompareAndSetReleaseAtomicBoolean(AtomicBoolean flag, boolean expected,
                                                                boolean update) {
        boolean won = flag.weakCompareAndSetRelease(expected, update);
        if (won && !update) {
            SpinLocks.release(flag);
        }
        return won;
    }

    /**
     * Weaves {@code AtomicBoolean.compareAndExchangeAcquire}; a won exchange to {@code false} releases (#667).
     *
     * @param flag     the atomic the call site invoked
     * @param expected the value it must hold
     * @param update   the value to store
     * @return the witness value
     * @since 1.12.2
     */
    public static boolean compareAndExchangeAcquireAtomicBoolean(AtomicBoolean flag, boolean expected,
                                                                 boolean update) {
        boolean witness = flag.compareAndExchangeAcquire(expected, update);
        if (witness == expected && !update) {
            SpinLocks.release(flag);
        }
        return witness;
    }

    /**
     * Weaves {@code AtomicBoolean.compareAndExchangeRelease}; a won exchange to {@code false} releases (#667).
     *
     * @param flag     the atomic the call site invoked
     * @param expected the value it must hold
     * @param update   the value to store
     * @return the witness value
     * @since 1.12.2
     */
    public static boolean compareAndExchangeReleaseAtomicBoolean(AtomicBoolean flag, boolean expected,
                                                                 boolean update) {
        boolean witness = flag.compareAndExchangeRelease(expected, update);
        if (witness == expected && !update) {
            SpinLocks.release(flag);
        }
        return witness;
    }

    /**
     * The target an ownership-taken event carries instead of a field identifier.
     *
     * <p>The ring buffer has one event shape, a field access, and adding a second shape to its
     * pre-allocated slots would cost every access a discriminator. A reserved name costs nothing
     * on the access path: no field identifier starts with {@code '#'}, the one other producer that
     * builds names ({@code Class#method}) never puts it first, and the bridge checks for this
     * before it treats the name as a field.
     */
    static final String OWNERSHIP_TAKEN = "#ownership-taken";

    /**
     * Records that the calling thread took {@code taken} out of a queue or an atomic slot.
     *
     * <p>Emitted by the weaver after a reference {@code getAndSet} on a {@code VarHandle},
     * {@code AtomicReference}, {@code AtomicReferenceFieldUpdater} or {@code AtomicReferenceArray},
     * and by the queue hooks after a {@code poll} that returned an element. The value that came
     * back is no longer in the slot or the queue, so the structure hands it to this thread and to
     * no other; the detector uses that to judge an object that moves between owners per owner
     * rather than across all of them (#555). A {@code null} result took nothing and records
     * nothing.
     *
     * <p>Allocation-free and non-throwing like every other hook on this path: it runs inside the
     * user's code.
     *
     * @param taken the object that left the slot or queue, or {@code null}
     * @since 1.12.1
     */
    public static void ownershipTaken(@Nullable Object taken) {
        ownershipTaken(taken, null);
    }

    /**
     * Records that the calling thread took {@code taken} out of {@code container}.
     *
     * <p>{@link #ownershipTaken(Object)} for the hooks that have the queue in hand. The container's
     * identity rides in the event's stored-identity slot, which an ownership event does not
     * otherwise use, so it can be matched against the {@link #ownershipOffered offer} that put the
     * object there (#630). The weaver's {@code getAndSet} and JCTools takes pass no container: the
     * slot or queue has left the stack by the time the taken reference is on it.
     *
     * @param taken     the object that left the queue, or {@code null}
     * @param container the queue it left, or {@code null} when unknown
     * @since 1.12.1
     */
    public static void ownershipTaken(@Nullable Object taken, @Nullable Object container) {
        if (taken == null || STOPPED.get()) {
            return;
        }
        BUFFER.publish(Thread.currentThread().threadId(), OWNERSHIP_TAKEN, false, 0L, false,
                Integer.MIN_VALUE, System.identityHashCode(taken), false, 0, 0,
                container == null ? 0 : System.identityHashCode(container));
    }

    /**
     * The target an ownership-offered event carries; see {@link #OWNERSHIP_TAKEN}.
     */
    static final String OWNERSHIP_OFFERED = "#ownership-offered";

    /**
     * Records that the calling thread is about to offer {@code offered} to {@code container}.
     *
     * <p>Emitted by the queue hooks before the structure is asked to accept the element, never
     * after: the slot this claims in the buffer is then ahead of any take that removes the element,
     * so the drain always sees the offer first. That order is what lets a take that is the first
     * event recorded for an object name the thread that handed it over (#630). An offer the queue
     * rejects still records, and is harmless: nothing takes that element out of that queue.
     *
     * <p>Allocation-free and non-throwing like every other hook on this path.
     *
     * @param offered   the element being offered, or {@code null}
     * @param container the queue it is offered to
     * @since 1.12.1
     */
    public static void ownershipOffered(@Nullable Object offered, @Nullable Object container) {
        if (offered == null || container == null || STOPPED.get()) {
            return;
        }
        BUFFER.publish(Thread.currentThread().threadId(), OWNERSHIP_OFFERED, false, 0L, false,
                Integer.MIN_VALUE, System.identityHashCode(offered), false, 0, 0,
                System.identityHashCode(container));
    }

    /**
     * Declares that a volatile write in the same method publishes {@code qualifiedName}.
     *
     * <p>Emitted by the weaver at the volatile write, once per plain field that method wrote before
     * it. The fact is static, so recording it repeatedly is harmless and the set only grows.
     *
     * @param qualifiedName the plain field a volatile write publishes
     * @since 1.9.8
     */
    public static void publishedByVolatile(String qualifiedName) {
        PUBLISHED_BY_VOLATILE.add(qualifiedName);
    }

    /**
     * Declares that {@code qualifiedName} is mutated through a {@code VarHandle} or an atomic field
     * updater.
     *
     * <p>Such a field belongs to a lock-free protocol: correctness comes from compare-and-swap and
     * from the algorithm's own argument, never from a lock. A lockset has nothing to intersect
     * there, so the honest answer for that field is silence rather than a finding on every access.
     * The weaver emits this where it sees the binding, which is a static fact about the class.
     *
     * @param qualifiedName the field bound to atomic access
     * @since 1.9.8
     */
    public static void atomicallyManaged(String qualifiedName) {
        ATOMICALLY_MANAGED.add(qualifiedName);
    }

    /**
     * {@return whether {@code qualifiedName} is mutated through atomic operations}
     *
     * @param qualifiedName the field to ask about
     * @since 1.9.8
     */
    public static boolean isAtomicallyManaged(String qualifiedName) {
        return ATOMICALLY_MANAGED.contains(qualifiedName);
    }

    /**
     * {@return whether a volatile write is known to publish {@code qualifiedName}}
     *
     * @param qualifiedName the field to ask about
     * @since 1.9.8
     */
    public static boolean isPublishedByVolatile(String qualifiedName) {
        return PUBLISHED_BY_VOLATILE.contains(qualifiedName);
    }

    /**
     * Records that the calling thread is leaving a {@code synchronized} block on {@code monitor}.
     *
     * <p>Woven before every {@code MONITOREXIT}, including the one the compiler emits on the
     * exception path out of a {@code synchronized} block, so an exception unwinding through the
     * block releases the lock here too.
     *
     * @param monitor the object whose monitor is being exited
     * @since 1.9.6
     */
    public static void monitorExited(Object monitor) {
        HeldLocks.released(monitor);
    }

    /**
     * Starts the background drain thread.  Idempotent — safe to call multiple times.
     *
     * @param callback consumer invoked on each drained event; may be {@code null} to
     *                 use a no-op default (events are simply discarded after drain)
     */
    // The scheduleAtFixedRate ScheduledFuture is intentionally not retained: the periodic
    // drain is stopped by shutting down drainExecutor in stop(), not by cancelling the Future.
    @SuppressWarnings("FutureReturnValueIgnored")
    public static void start(TelemetryEventBuffer.@Nullable DrainCallback callback) {
        // A run starts here, whichever path installs its consumer. Locksets registered for an
        // earlier run's fingerprints were resolved as that run's events arrived, so the table can
        // start empty; a worker thread that cached a registration re-registers on its next access.
        HeldLocks.forgetRegisteredLocksets();
        if (!RUNNING.compareAndSet(false, true)) {
            // Already running, but allow updating the callback.
            setCallback(callback);
            return;
        }
        // Re-arm recordAccess before the drain exists: events published in this window
        // are buffered (the pre-start capture behavior), not dropped.
        STOPPED.set(false);
        drainCallback = callback;
        drainExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "async-test-telemetry-drain");
            t.setDaemon(true);
            return t;
        });
        drainExecutor.scheduleAtFixedRate(TelemetryRegistry::drainOnce, 0, 1, TimeUnit.MILLISECONDS);
        shutdownHook = new Thread(TelemetryRegistry::stop, "async-test-telemetry-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    /**
     * Starts the registry with a no-op drain callback (events counted but not forwarded).
     */
    public static void start() {
        start(null);
    }

    /**
     * Replaces the active drain callback without affecting the running/stopped state of the
     * registry.
     *
     * <p>The callback field is {@code volatile}, so a swap is immediately visible to the
     * single drain thread on its next cycle. Passing {@code null} restores the no-op
     * default (drained events are discarded). This is the clear, intention-revealing hook
     * that {@link #start(TelemetryEventBuffer.DrainCallback)} delegates to when it is called
     * while the registry is already running, and the mechanism
     * {@code se.deversity.asynctest.telemetry.TelemetryBridge} uses to attach and detach
     * itself.
     *
     * <p>Because the registry holds a single callback, callers share it: the last
     * {@code setCallback} wins. It does not start or stop the drain thread — pair it with
     * {@link #start()} / {@link #stop()} for lifecycle control.
     *
     * @param callback the new drain callback, or {@code null} for the no-op default
     * @since 1.7.0
     */
    public static void setCallback(TelemetryEventBuffer.@Nullable DrainCallback callback) {
        drainCallback = callback;
    }

    /**
     * Clears the callback only if it is still {@code expected}, and reports whether it was.
     *
     * <p><strong>Why a conditional clear.</strong> The registry holds one callback, so two
     * {@code @AsyncTest} runs sharing a JVM take it from each other. That much is a documented,
     * accepted trade-off: the per-run thread filter means the run that loses the slot
     * under-reports rather than mis-attributing another run's threads. The unacceptable part was
     * the teardown. An unconditional {@code setCallback(null)} let the run that lost the slot
     * clear the callback belonging to the run that won it, whenever the loser happened to finish
     * first — so the <em>winner</em> went blind for the rest of its execution, its detectors saw
     * nothing, and its test passed green with no warning. The absence hint could not fire either,
     * because the drain thread was still running.
     *
     * <p>Comparing by identity before clearing makes teardown affect only the registration the
     * caller actually made, which turns that silent failure into the documented under-report.
     *
     * @param expected the callback the caller believes it registered
     * @return {@code true} if the callback was cleared, {@code false} if another caller had
     *         already replaced it — in which case the current holder is left untouched
     * @since 1.9.2
     */
    // Identity, not equals: the question is "is this the exact registration I made", and a
    // callback that merely compares equal to ours is a different registration whose slot we have
    // no business clearing. Value equality here would reintroduce the bug this method fixes.
    @SuppressWarnings("ReferenceEquality")
    public static boolean clearCallbackIf(
            TelemetryEventBuffer.@Nullable DrainCallback expected) {
        // A private lock rather than `static synchronized`. The latter takes the monitor of the
        // class object, which any code holding TelemetryRegistry.class can also take, so an
        // unrelated caller could stall this compare-and-clear. It runs during test teardown,
        // where a stall is a hang in somebody's suite rather than a slow method.
        synchronized (CALLBACK_LOCK) {
            if (drainCallback == expected) { // NOPMD CompareObjectsWithEquals - identity is the point
                drainCallback = null;
                return true;
            }
            return false;
        }
    }

    /**
     * {@return whether the drain thread is running} True between {@link #start()} and
     * {@link #stop()}, which in practice means "the agent is attached", since
     * {@code AsyncTestAgent.premain} is what starts the registry. Callers that only want to
     * do telemetry work when there is telemetry to do — {@code ConcurrencyRunner} deciding
     * whether to attach a {@link TelemetryBridge} for a run — can gate on this rather than
     * paying for a bridge nothing will ever feed.
     *
     * @since 1.7.0
     */
    public static boolean isRunning() {
        return RUNNING.get();
    }

    /**
     * {@return how many access events this JVM published and then threw away}
     *
     * <p>The ring buffer is bounded. When it stays full and the drain makes no progress,
     * {@link TelemetryEventBuffer#publish} eventually drops the event rather than holding a
     * worker thread hostage, which is the right trade for the program under test and the wrong
     * one to keep quiet about: every dropped event is an access a detector never saw, so a run
     * with a nonzero count here has weaker evidence than its finding list suggests, in both
     * directions. A missing write can hide a race; a missing lock acquisition can invent one.
     *
     * <p>Cumulative for the life of the JVM and never reset, so a harness that reports it should
     * read it once at the end of the run.
     *
     * @since 1.9.8
     */
    public static long droppedEvents() {
        return BUFFER.droppedCount();
    }

    /**
     * {@return how many access events this JVM published}
     *
     * <p>The denominator for {@link #droppedEvents()}, and the first thing to compare when two
     * runs of the same suite reach different conclusions: two runs that published the same number
     * of events and dropped none of them saw the same evidence, so a difference between them is
     * in the analysis rather than in the reach of the weaving.
     *
     * <p>Cumulative for the life of the JVM.
     *
     * @since 1.9.8
     */
    public static long publishedEvents() {
        return BUFFER.publishedCount();
    }

    /**
     * Drains everything published so far to the active callback, and returns once that
     * drain has completed.
     *
     * <p>The buffer is MPSC: {@link TelemetryEventBuffer#drain} may only ever run on one
     * thread. This method therefore does not drain on the calling thread — it submits the
     * drain to the same single-threaded executor that runs the periodic one and waits for
     * it, so the single-consumer contract still holds with the caller blocked rather than
     * competing.
     *
     * <p>The reason it exists: the periodic drain runs every millisecond, so at the moment a
     * run finishes its last round there is up to a millisecond of captured accesses still
     * sitting in the buffer. Analysis that reads the detectors before those arrive sees a
     * truncated picture, and which accesses made it would depend on timing. Flushing
     * immediately before analysis makes the result deterministic.
     *
     * <p>Best-effort and never throws: if the registry is not running there is nothing to
     * drain, and a drain that is rejected, interrupted or slow leaves the pending events for
     * the next periodic cycle rather than failing the test that asked for the flush.
     *
     * @since 1.7.0
     */
    public static void flush() {
        ScheduledExecutorService executor = drainExecutor;
        if (!RUNNING.get() || executor == null) {
            return;
        }
        try {
            executor.submit(TelemetryRegistry::drainOnce).get(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RejectedExecutionException ignored) { // NOPMD EmptyCatchBlock — best-effort flush, same rule as drainOnce below
            // The periodic drain will pick these up on its next cycle. Failing here would turn a
            // telemetry hiccup into a test failure, which is the wrong trade for a detector feed.
        }
    }

    /**
     * Flushes remaining events and shuts down the drain thread.  Idempotent.
     *
     * <p>The reads and writes of {@code shutdownHook} / {@code drainExecutor} are not
     * protected by classic singleton synchronization because the {@code RUNNING} CAS
     * above serializes start/stop transitions — only one thread can pass the gate per
     * transition, so the seemingly racy null-check + null-out pattern is safe here.
     */
    @SuppressWarnings("PMD.NonThreadSafeSingleton")
    public static void stop() {
        if (!RUNNING.compareAndSet(true, false)) {
            return;
        }
        // Set before tearing anything down so producers stop feeding the ring as early
        // as possible; recordAccess discards from here on.
        STOPPED.set(true);
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM shutdown in progress — hook cannot be removed, which is fine.
                shutdownHook = null;
            }
            shutdownHook = null;
        }
        if (drainExecutor != null) {
            drainExecutor.shutdown();
            try {
                drainExecutor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            drainExecutor = null;
        }
        drainOnce(); // final flush
    }

    /**
     * Exposes the shared buffer for testing and advanced consumers.
     *
     * @return the buffer producers publish into
     */
    public static TelemetryEventBuffer buffer() {
        return BUFFER;
    }

    private static void drainOnce() {
        TelemetryEventBuffer.DrainCallback cb = drainCallback;
        try {
            if (cb != null) {
                BUFFER.drain(cb);
            } else {
                BUFFER.drain((tid, field, write) -> { /* discard */ });
            }
        } catch (RuntimeException | StackOverflowError ignored) { // NOPMD EmptyCatchBlock — best-effort drain must survive a misbehaving callback
            // scheduleAtFixedRate cancels all future executions if the task throws;
            // telemetry is best-effort, so swallow and keep the periodic drainer alive.
            // StackOverflowError is included (same containment rule as DetectorRegistry
            // .ifIssue): callbacks feed detector code that accumulates user-driven state,
            // and one blown stack must not kill the drain for the rest of the JVM —
            // undrained events would then stall every instrumented thread at the buffer.
        }
    }
}
