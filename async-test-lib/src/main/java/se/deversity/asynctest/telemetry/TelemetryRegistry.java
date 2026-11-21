package se.deversity.asynctest.telemetry;

import se.deversity.asynctest.AgentConcurrencyUtilHooks;
import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;
import se.deversity.asynctest.AgentMonitorHooks;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.diagnostics.ABAProblemDetector;
import se.deversity.asynctest.diagnostics.HappensBefore;
import se.deversity.asynctest.diagnostics.HeldLocks;
import se.deversity.asynctest.diagnostics.SelfGuard;
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
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.function.BinaryOperator;
import java.util.function.IntBinaryOperator;
import java.util.function.IntUnaryOperator;
import java.util.function.LongBinaryOperator;
import java.util.function.LongUnaryOperator;
import java.util.function.UnaryOperator;

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
     *
     * <p>It guards the ring and nothing else. The happens-before model is in memory and needs no
     * drain, so the hooks update it whatever this says: when it also gated their releases and
     * acquires, one stop() in a JVM, from a test standing in for the agent, left every later woven
     * hand-off unrecorded, and kept the weekly mutation gate red, since pitest runs every test
     * class in one JVM (#891).
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
        // Before the access is recorded, and before the stop check: a woven access is a point
        // of the controlled schedule whether or not anything is draining the ring.
        ControlledSchedule.point();
        if (STOPPED.get()) {
            return;
        }
        // The lockset lives on this thread and this thread only, so the question has to be asked
        // here: by the time the drain thread replays the event it holds nothing the producer held.
        // Reading it is a walk over a small per-thread array, allocation-free and lock-free, which
        // is what the producer path requires - a heavier capture here would change the scheduling
        // this whole buffer exists to leave alone.
        // The ordering clock is read here for the same reason: only the accessing thread's
        // clock says what this access is ordered after. A thread-local read; no allocation.
        BUFFER.publish(threadId, qualifiedName, isWrite, HeldLocks.lockFingerprint(),
                volatileField, constantTag, identity, afterVolatileRead, 0, 0, 0, null,
                HappensBefore.current(), HappensBefore.round());
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
     * event as identity hashes. The receiver travels too, so that two objects sharing an identity
     * hash stay apart, but only as far as the drain: the ring clears the slot once the event has
     * been delivered, and nothing here retains it.
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
     * @param staticField       whether the field is static; its identity is then the declaring
     *                          class's, or 0 when the weaver could not load the class constant
     * @since 1.9.8
     */
    public static void recordAccess(@Nullable Object receiver, @Nullable Object methodMonitor,
                                    long threadId, String qualifiedName, boolean isWrite,
                                    boolean volatileField, int constantTag,
                                    boolean afterVolatileRead, boolean staticField) {
        ControlledSchedule.point();
        if (STOPPED.get()) {
            return;
        }
        // A static field is one per class, so its declaring class keys it as a receiver keys an
        // instance field, and keeps it out of the identity-0 bucket that pools accesses recorded
        // without a receiver, where no per-object rule judges anything (#1053).
        int identity = receiver == null ? 0 : System.identityHashCode(receiver);
        int ownMonitor = receiver != null && Thread.holdsLock(receiver)
                ? System.identityHashCode(receiver) : 0;
        int method = methodMonitor == null ? 0 : System.identityHashCode(methodMonitor);
        BUFFER.publish(threadId, qualifiedName, isWrite, HeldLocks.lockFingerprint(isWrite),
                volatileField, constantTag, identity, afterVolatileRead, ownMonitor, method, 0,
                identity == 0 ? null : receiver, HappensBefore.current(), HappensBefore.round());
    }

    /**
     * The release half of a volatile write, called by woven code with the value about to be
     * stored, after the write's {@code recordAccess} and before the write instruction.
     *
     * <p>Everything the writing thread did so far is published to a read of the same field of the
     * same object that returns this value (#742). Before the store, so the release precedes every
     * read that can return the value; with the value, so a read that returned an earlier one can
     * tell this release apart from the one it saw. One overload per stack shape, so the woven call
     * passes the value as the store's own operand and boxes nothing: {@code int} for every
     * primitive of one slot except {@code float}.
     *
     * @param owner the object the field belongs to, the declaring class for a static field, or
     *              {@code null} when the weaver had neither; {@code null} records nothing
     * @param value the value the write stores
     * @param field combined {@code declaringClass.field} identifier
     * @since 1.12.3
     */
    public static void volatileStore(@Nullable Object owner, int value, String field) {
        HappensBefore.releaseVolatile(owner, field, value);
    }

    /**
     * {@link #volatileStore(Object, int, String)} for a {@code long} field.
     *
     * @param owner the object the field belongs to, the declaring class for a static field
     * @param value the value the write stores
     * @param field combined {@code declaringClass.field} identifier
     * @since 1.12.3
     */
    public static void volatileStore(@Nullable Object owner, long value, String field) {
        HappensBefore.releaseVolatile(owner, field, value);
    }

    /**
     * {@link #volatileStore(Object, int, String)} for a {@code float} field, compared by its bits.
     *
     * @param owner the object the field belongs to, the declaring class for a static field
     * @param value the value the write stores
     * @param field combined {@code declaringClass.field} identifier
     * @since 1.12.3
     */
    public static void volatileStore(@Nullable Object owner, float value, String field) {
        HappensBefore.releaseVolatile(owner, field, Float.floatToRawIntBits(value));
    }

    /**
     * {@link #volatileStore(Object, int, String)} for a {@code double} field, compared by its bits.
     *
     * @param owner the object the field belongs to, the declaring class for a static field
     * @param value the value the write stores
     * @param field combined {@code declaringClass.field} identifier
     * @since 1.12.3
     */
    public static void volatileStore(@Nullable Object owner, double value, String field) {
        HappensBefore.releaseVolatile(owner, field, Double.doubleToRawLongBits(value));
    }

    /**
     * {@link #volatileStore(Object, int, String)} for a reference field, the stored object compared
     * by identity through a weak reference, so the model keeps nothing alive (#813).
     *
     * @param owner the object the field belongs to, the declaring class for a static field
     * @param value the reference the write stores
     * @param field combined {@code declaringClass.field} identifier
     * @since 1.12.3
     */
    public static void volatileStore(@Nullable Object owner, @Nullable Object value, String field) {
        HappensBefore.releaseVolatileReference(owner, field, value);
    }

    /**
     * The acquire half of a volatile read, called by woven code right after the read instruction
     * with the value it returned.
     *
     * <p>The reading thread receives what the write that stored this value published, and
     * nothing a write it did not see published (#742). Taken here, at the read, into the thread's
     * clock, so every later access of the thread is ordered after that write, including an access
     * to the object the read returned (#804). Overloads as for {@link #volatileStore}.
     *
     * @param owner the object the field belongs to, the declaring class for a static field, or
     *              {@code null} when the weaver had neither; {@code null} records nothing
     * @param value the value the read returned
     * @param field combined {@code declaringClass.field} identifier
     * @since 1.12.3
     */
    public static void volatileLoad(@Nullable Object owner, int value, String field) {
        HappensBefore.acquireVolatile(owner, field, value);
    }

    /**
     * {@link #volatileLoad(Object, int, String)} for a {@code long} field.
     *
     * @param owner the object the field belongs to, the declaring class for a static field
     * @param value the value the read returned
     * @param field combined {@code declaringClass.field} identifier
     * @since 1.12.3
     */
    public static void volatileLoad(@Nullable Object owner, long value, String field) {
        HappensBefore.acquireVolatile(owner, field, value);
    }

    /**
     * {@link #volatileLoad(Object, int, String)} for a {@code float} field, compared by its bits.
     *
     * @param owner the object the field belongs to, the declaring class for a static field
     * @param value the value the read returned
     * @param field combined {@code declaringClass.field} identifier
     * @since 1.12.3
     */
    public static void volatileLoad(@Nullable Object owner, float value, String field) {
        HappensBefore.acquireVolatile(owner, field, Float.floatToRawIntBits(value));
    }

    /**
     * {@link #volatileLoad(Object, int, String)} for a {@code double} field, compared by its bits.
     *
     * @param owner the object the field belongs to, the declaring class for a static field
     * @param value the value the read returned
     * @param field combined {@code declaringClass.field} identifier
     * @since 1.12.3
     */
    public static void volatileLoad(@Nullable Object owner, double value, String field) {
        HappensBefore.acquireVolatile(owner, field, Double.doubleToRawLongBits(value));
    }

    /**
     * {@link #volatileLoad(Object, int, String)} for a reference field, the returned object
     * compared by identity with the one each release stored (#813).
     *
     * @param owner the object the field belongs to, the declaring class for a static field
     * @param value the reference the read returned
     * @param field combined {@code declaringClass.field} identifier
     * @since 1.12.3
     */
    public static void volatileLoad(@Nullable Object owner, @Nullable Object value, String field) {
        HappensBefore.acquireVolatileReference(owner, field, value);
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
     * <p>Only its identity hash travels; the value itself is never retained.
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
     * @param staticField       whether the field is static; its identity is then the declaring
     *                          class's, or 0 when the weaver could not load the class constant
     * @since 1.9.8
     */
    public static void recordAccess(@Nullable Object receiver, @Nullable Object stored,
                                    @Nullable Object methodMonitor,
                                    long threadId, String qualifiedName, boolean isWrite,
                                    boolean volatileField, int constantTag,
                                    boolean afterVolatileRead, boolean staticField) {
        ControlledSchedule.point();
        if (STOPPED.get()) {
            return;
        }
        // A static field is one per class, so its declaring class keys it as a receiver keys an
        // instance field, and keeps it out of the identity-0 bucket that pools accesses recorded
        // without a receiver, where no per-object rule judges anything (#1053).
        int identity = receiver == null ? 0 : System.identityHashCode(receiver);
        if (isWrite && !staticField && identity != 0 && SpinLocks.isSpinField(qualifiedName)) {
            // The holder writing its spinlock flag is the release (#554). Declared before the write
            // lands, so the lock never reads as held after it truly is not.
            SpinLocks.release(receiver, qualifiedName);
        }
        // A volatile flag this receiver has really been locked through: its checks and its release
        // are the lock's own protocol, not data it guards (#1171). They could not be excused as a
        // constant, because the weaver leaves untagged a write whose method read the field, and a
        // contended check does read it. A flag never won by a swap has no lock and stays judged.
        if (volatileField && !staticField && receiver != null && SpinLocks.isSpinField(qualifiedName)
                && SpinLocks.lockFor(receiver, qualifiedName) != null) {
            return;
        }
        int ownMonitor = receiver != null && Thread.holdsLock(receiver)
                ? System.identityHashCode(receiver) : 0;
        int method = methodMonitor == null ? 0 : System.identityHashCode(methodMonitor);
        int storedIdentity = stored == null ? 0 : System.identityHashCode(stored);
        // The receiver rides along so the drain side can tell apart two objects whose identity
        // hashes collide; the ring lends it for one callback and then clears the slot.
        BUFFER.publish(threadId, qualifiedName, isWrite, HeldLocks.lockFingerprint(isWrite),
                volatileField, constantTag, identity, afterVolatileRead, ownMonitor, method,
                storedIdentity, identity == 0 ? null : receiver, HappensBefore.current(),
                HappensBefore.round());
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
        // A point naming the monitor: a controlled schedule hands this worker the round only once
        // no paused peer holds it (#1022). Before the lockset push, so the lockset says held only
        // once the worker is about to enter.
        ControlledSchedule.acquiring(monitor);
        HeldLocks.acquired(monitor);
        AgentMonitorHooks.lockOrderMonitorEntered(monitor); // a nesting edge, for #1243
    }

    /**
     * Array elements past this index are not recorded (#1250). A dropped access can only miss a
     * race, never invent one, and it keeps the name table and the per-element state bounded.
     */
    @API(status = Status.INTERNAL, since = "1.15.0")
    public static final int MAX_TRACKED_ARRAY_INDEX = 256;

    /** Element kind names, in the order of the array opcodes, then {@code boolean}. */
    private static final String[] ARRAY_KINDS =
            {"int", "long", "float", "double", "Object", "byte", "char", "short", "boolean"};

    /** The access name of each element, {@code int[3]}, made once and reused. */
    private static final String[][] ARRAY_ELEMENT_NAMES =
            new String[ARRAY_KINDS.length][MAX_TRACKED_ARRAY_INDEX];

    /**
     * Records a read of an array element, called by code {@code ArrayAccessWeaver} wove with
     * {@code arrays=true} just before the load instruction (#1250).
     *
     * <p>The element is recorded as an access of the array named for its kind and index,
     * {@code int[3]}, so it is judged per element, by the lockset and happens-before rules a field
     * access is judged by: each thread on its own slot is not a race, one slot bumped by every
     * thread is. Never throws: a {@code null} array or an index out of range records nothing and
     * leaves the instruction to throw.
     *
     * @param array the array, as the load instruction is about to receive it
     * @param index the element read; negative, past the array's end or past
     *              the first 256 elements records nothing
     * @param kind  the element kind, 0 to 7 in the order {@code IALOAD} to {@code SALOAD}
     * @since 1.14.3
     */
    public static void arrayRead(@Nullable Object array, int index, int kind) {
        arrayAccess(array, index, kind, false);
    }

    /**
     * Records a write of an array element, called just before the store instruction (#1250).
     * See {@link #arrayRead(Object, int, int)}.
     *
     * @param array the array, as the store instruction is about to receive it
     * @param index the element written; out of range records nothing, as for a read
     * @param kind  the element kind, 0 to 7 in the order {@code IASTORE} to {@code SASTORE}
     * @since 1.14.3
     */
    public static void arrayWrite(@Nullable Object array, int index, int kind) {
        arrayAccess(array, index, kind, true);
    }

    private static void arrayAccess(@Nullable Object array, int index, int kind, boolean write) {
        if (array == null || index < 0 || kind < 0 || kind >= ARRAY_KINDS.length - 1
                || index >= java.lang.reflect.Array.getLength(array)) {
            return;                             // the instruction throws, or it is not an element
        }
        if (index >= MAX_TRACKED_ARRAY_INDEX) {
            // A real element, past the bound: not recorded, but counted, so the run's report can
            // say it did not look (#1262).
            AsyncTestContext.arrayAccessUntracked();
            return;
        }
        // BALOAD and BASTORE serve both byte[] and boolean[].
        int named = kind == 5 && array instanceof boolean[] ? ARRAY_KINDS.length - 1 : kind;
        String[] names = ARRAY_ELEMENT_NAMES[named];
        String name = names[index];
        if (name == null) {
            // A racing first access makes an equal string; either one is fine to keep.
            name = ARRAY_KINDS[named] + '[' + index + ']';
            names[index] = name;
        }
        recordAccess(array, null, Thread.currentThread().threadId(), name, write, false,
                Integer.MIN_VALUE, false, false);
    }

    /**
     * Records that the calling thread has entered a {@code synchronized} method holding
     * {@code monitor}: {@code this}, or the class for a static method.
     *
     * <p>Woven at the entry of every {@code synchronized} method, which takes its monitor from an
     * access flag and so has no {@code MONITORENTER} for {@link #monitorEntered} to see (#822).
     * With it the monitor counts in whatever the method calls: a helper, another object's method,
     * a static method, a lambda body. Nothing is woven on an exception leaving the method, so
     * {@code HeldLocks} re-confirms the entry with {@link Thread#holdsLock} whenever the set is
     * read. Like {@link #monitorEntered}, deliberately does not check {@link #stop()}.
     *
     * @param monitor the monitor the method holds
     * @since 1.12.3
     */
    public static void methodMonitorEntered(Object monitor) {
        // Already held: the flag took it before this first instruction, so this records the hold
        // for a controlled schedule rather than being a point (#1022).
        ControlledSchedule.acquired(monitor);
        HeldLocks.methodMonitorAcquired(monitor);
        AgentMonitorHooks.lockOrderMethodMonitorEntered(monitor);
    }

    /**
     * Records that the calling thread is returning from the {@code synchronized} method whose
     * entry {@link #methodMonitorEntered} recorded, woven before each of its return instructions.
     *
     * @param monitor the monitor the method holds
     * @since 1.12.3
     */
    public static void methodMonitorExited(Object monitor) {
        ControlledSchedule.released(monitor);
        HeldLocks.methodMonitorReleased(monitor);
        AgentMonitorHooks.lockOrderMonitorExited(monitor);
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
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, SpinLocks.fieldOf(handle));
        ControlledSchedule.point();
        if (expected == 0 && update == 1) {
            SpinLocks.aboutToAcquire(receiver, handle);
        }
        boolean won = handle.withInvokeBehavior().compareAndSet(receiver, expected, update);
        if (won) {
            handleStoring(handle, receiver, update);
        }
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
        AgentConcurrencyUtilHooks.updaterStored(receiver, SpinLocks.fieldOf(handle));
        ControlledSchedule.point();
        releaseIfSpinLockField(handle, receiver);
        handle.withInvokeBehavior().set(receiver, value);
    }

    /** Weaves {@code VarHandle.setVolatile} on an {@code int} field. @param handle the handle @param receiver the owner @param value the value */
    public static void setVolatileInt(VarHandle handle, Object receiver, int value) {
        AgentConcurrencyUtilHooks.updaterStored(receiver, SpinLocks.fieldOf(handle));
        ControlledSchedule.point();
        releaseIfSpinLockField(handle, receiver);
        handleStoring(handle, receiver, value);
        handle.withInvokeBehavior().setVolatile(receiver, value);
    }

    /** Weaves {@code VarHandle.setRelease} on an {@code int} field. @param handle the handle @param receiver the owner @param value the value */
    public static void setReleaseInt(VarHandle handle, Object receiver, int value) {
        AgentConcurrencyUtilHooks.updaterStored(receiver, SpinLocks.fieldOf(handle));
        ControlledSchedule.point();
        releaseIfSpinLockField(handle, receiver);
        handleStoring(handle, receiver, value);
        handle.withInvokeBehavior().setRelease(receiver, value);
    }

    /** Weaves {@code VarHandle.setOpaque} on an {@code int} field. @param handle the handle @param receiver the owner @param value the value */
    public static void setOpaqueInt(VarHandle handle, Object receiver, int value) {
        AgentConcurrencyUtilHooks.updaterStored(receiver, SpinLocks.fieldOf(handle));
        ControlledSchedule.point();
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
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, SpinLocks.fieldOf(updater));
        ControlledSchedule.point();
        if (expected == 0 && update == 1) {
            SpinLocks.aboutToAcquire(receiver, updater);
        }
        AtomicIntegerFieldUpdater<Object> target = erased(updater);
        boolean won = target.compareAndSet(receiver, expected, update);
        if (won) {
            updaterStoring(receiver, SpinLocks.fieldOf(updater), update);
        }
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
        AgentConcurrencyUtilHooks.updaterStored(receiver, SpinLocks.fieldOf(updater));
        ControlledSchedule.point();
        releaseIfSpinLockField(updater, receiver);
        updaterStoring(receiver, SpinLocks.fieldOf(updater), value);
        erased(updater).set(receiver, value);
    }

    /** Weaves {@code AtomicIntegerFieldUpdater.lazySet}, a release when the holder stores its flag. @param updater the updater @param receiver the owner @param value the value @since 1.12.1 */
    public static void lazySetIntUpdater(AtomicIntegerFieldUpdater<?> updater, Object receiver,
                                         int value) {
        AgentConcurrencyUtilHooks.updaterStored(receiver, SpinLocks.fieldOf(updater));
        ControlledSchedule.point();
        releaseIfSpinLockField(updater, receiver);
        updaterStoring(receiver, SpinLocks.fieldOf(updater), value);
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

    /**
     * Weaves {@code AtomicLongFieldUpdater.get}: the acquire of the store it read, and the check half of a check-then-act (#1275, #1317).
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field the slot is
     * @return the field's value
     * @since 1.15.0
     */
    public static long getLongUpdater(AtomicLongFieldUpdater<?> updater, Object receiver) {
        String field = SpinLocks.longFieldOf(updater);
        AgentConcurrencyUtilHooks.updaterRead(receiver, field);
        ControlledSchedule.point();
        long value = erasedLong(updater).get(receiver);
        longUpdaterLoaded(receiver, field, value);
        return value;
    }

    /**
     * Weaves {@code AtomicLongFieldUpdater.set}: released under the field's own slot before the store (#1275, #1317).
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field the slot is
     * @param value    the value to store
     * @since 1.15.0
     */
    public static void setLongUpdater(AtomicLongFieldUpdater<?> updater, Object receiver, long value) {
        String field = SpinLocks.longFieldOf(updater);
        AgentConcurrencyUtilHooks.updaterStored(receiver, field);
        ControlledSchedule.point();
        longUpdaterStoring(receiver, field, value);
        erasedLong(updater).set(receiver, value);
    }

    /**
     * Weaves {@code AtomicLongFieldUpdater.lazySet}: released under the field's own slot before the store (#1275, #1317).
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field the slot is
     * @param value    the value to store
     * @since 1.15.0
     */
    public static void lazySetLongUpdater(AtomicLongFieldUpdater<?> updater, Object receiver, long value) {
        String field = SpinLocks.longFieldOf(updater);
        AgentConcurrencyUtilHooks.updaterStored(receiver, field);
        ControlledSchedule.point();
        longUpdaterStoring(receiver, field, value);
        erasedLong(updater).lazySet(receiver, value);
    }

    /**
     * Weaves {@code AtomicLongFieldUpdater.compareAndSet}: a won swap releases what it stored (#1275, #1317).
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field the slot is
     * @param expected the value the field must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.15.0
     */
    public static boolean compareAndSetLongUpdater(AtomicLongFieldUpdater<?> updater, Object receiver,
                                                   long expected, long update) {
        String field = SpinLocks.longFieldOf(updater);
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, field);
        ControlledSchedule.point();
        boolean won = erasedLong(updater).compareAndSet(receiver, expected, update);
        if (won) {
            longUpdaterStoring(receiver, field, update);
        }
        return won;
    }

    /**
     * Weaves {@code AtomicLongFieldUpdater.getAndSet}: the store releases and the previous value acquires (#1275, #1317).
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field the slot is
     * @param value    the value to store
     * @return the previous value
     * @since 1.15.0
     */
    public static long getAndSetLongUpdater(AtomicLongFieldUpdater<?> updater, Object receiver, long value) {
        String field = SpinLocks.longFieldOf(updater);
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, field);
        ControlledSchedule.point();
        longUpdaterStoring(receiver, field, value);
        long previous = erasedLong(updater).getAndSet(receiver, value);
        longUpdaterLoaded(receiver, field, previous);
        return previous;
    }

    /**
     * Weaves {@code AtomicLongFieldUpdater.getAndAdd}: the act half of a check-then-act, run as a releasing compare-and-set loop (#1275, #1317).
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field the slot is
     * @param delta    the value to add
     * @return the previous value
     * @since 1.15.0
     */
    public static long getAndAddLongUpdater(AtomicLongFieldUpdater<?> updater, Object receiver, long delta) {
        String field = SpinLocks.longFieldOf(updater);
        AgentConcurrencyUtilHooks.updaterIncremented(receiver, field);
        ControlledSchedule.point();
        return longUpdaterAddPublishing(erasedLong(updater), receiver, field, delta, true);
    }

    /**
     * Weaves {@code AtomicLongFieldUpdater.addAndGet}: the act half of a check-then-act, run as a releasing compare-and-set loop (#1275, #1317).
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field the slot is
     * @param delta    the value to add
     * @return the updated value
     * @since 1.15.0
     */
    public static long addAndGetLongUpdater(AtomicLongFieldUpdater<?> updater, Object receiver, long delta) {
        String field = SpinLocks.longFieldOf(updater);
        AgentConcurrencyUtilHooks.updaterIncremented(receiver, field);
        ControlledSchedule.point();
        return longUpdaterAddPublishing(erasedLong(updater), receiver, field, delta, false);
    }

    /**
     * Weaves {@code AtomicLongFieldUpdater.getAndIncrement}: the act half of a check-then-act, run as a releasing compare-and-set loop (#1275, #1317).
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field the slot is
     * @return the previous value
     * @since 1.15.0
     */
    public static long getAndIncrementLongUpdater(AtomicLongFieldUpdater<?> updater, Object receiver) {
        String field = SpinLocks.longFieldOf(updater);
        AgentConcurrencyUtilHooks.updaterIncremented(receiver, field);
        ControlledSchedule.point();
        return longUpdaterAddPublishing(erasedLong(updater), receiver, field, 1L, true);
    }

    /**
     * Weaves {@code AtomicLongFieldUpdater.incrementAndGet}: the act half of a check-then-act, run as a releasing compare-and-set loop (#1275, #1317).
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field the slot is
     * @return the updated value
     * @since 1.15.0
     */
    public static long incrementAndGetLongUpdater(AtomicLongFieldUpdater<?> updater, Object receiver) {
        String field = SpinLocks.longFieldOf(updater);
        AgentConcurrencyUtilHooks.updaterIncremented(receiver, field);
        ControlledSchedule.point();
        return longUpdaterAddPublishing(erasedLong(updater), receiver, field, 1L, false);
    }

    /**
     * Weaves {@code AtomicLongFieldUpdater.getAndDecrement}: the act half of a check-then-act, run as a releasing compare-and-set loop (#1275, #1317).
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field the slot is
     * @return the previous value
     * @since 1.15.0
     */
    public static long getAndDecrementLongUpdater(AtomicLongFieldUpdater<?> updater, Object receiver) {
        String field = SpinLocks.longFieldOf(updater);
        AgentConcurrencyUtilHooks.updaterIncremented(receiver, field);
        ControlledSchedule.point();
        return longUpdaterAddPublishing(erasedLong(updater), receiver, field, -1L, true);
    }

    /**
     * Weaves {@code AtomicLongFieldUpdater.decrementAndGet}: the act half of a check-then-act, run as a releasing compare-and-set loop (#1275, #1317).
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field the slot is
     * @return the updated value
     * @since 1.15.0
     */
    public static long decrementAndGetLongUpdater(AtomicLongFieldUpdater<?> updater, Object receiver) {
        String field = SpinLocks.longFieldOf(updater);
        AgentConcurrencyUtilHooks.updaterIncremented(receiver, field);
        ControlledSchedule.point();
        return longUpdaterAddPublishing(erasedLong(updater), receiver, field, -1L, false);
    }

    /**
     * Weaves {@code AtomicLongFieldUpdater.getAndUpdate}: run as a releasing compare-and-set loop (#1275, #1317).
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field the slot is
     * @param function the update, applied again after a lost swap as the JDK's own loop does
     * @return the previous value
     * @since 1.15.0
     */
    public static long getAndUpdateLongUpdater(AtomicLongFieldUpdater<?> updater, Object receiver,
                                     LongUnaryOperator function) {
        String field = SpinLocks.longFieldOf(updater);
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, field);
        ControlledSchedule.point();
        return longUpdaterUpdatePublishing(erasedLong(updater), receiver, field, function, true);
    }

    /**
     * Weaves {@code AtomicLongFieldUpdater.updateAndGet}: run as a releasing compare-and-set loop (#1275, #1317).
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field the slot is
     * @param function the update, applied again after a lost swap as the JDK's own loop does
     * @return the updated value
     * @since 1.15.0
     */
    public static long updateAndGetLongUpdater(AtomicLongFieldUpdater<?> updater, Object receiver,
                                     LongUnaryOperator function) {
        String field = SpinLocks.longFieldOf(updater);
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, field);
        ControlledSchedule.point();
        return longUpdaterUpdatePublishing(erasedLong(updater), receiver, field, function, false);
    }

    /**
     * Weaves {@code AtomicLongFieldUpdater.getAndAccumulate}: run as a releasing compare-and-set loop (#1275, #1317).
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field the slot is
     * @param operand  the function's second argument
     * @param function the accumulator, applied to the current value and {@code operand}
     * @return the previous value
     * @since 1.15.0
     */
    public static long getAndAccumulateLongUpdater(AtomicLongFieldUpdater<?> updater, Object receiver,
                                         long operand, LongBinaryOperator function) {
        String field = SpinLocks.longFieldOf(updater);
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, field);
        ControlledSchedule.point();
        return longUpdaterUpdatePublishing(erasedLong(updater), receiver, field,
                c -> function.applyAsLong(c, operand), true);
    }

    /**
     * Weaves {@code AtomicLongFieldUpdater.accumulateAndGet}: run as a releasing compare-and-set loop (#1275, #1317).
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field the slot is
     * @param operand  the function's second argument
     * @param function the accumulator, applied to the current value and {@code operand}
     * @return the updated value
     * @since 1.15.0
     */
    public static long accumulateAndGetLongUpdater(AtomicLongFieldUpdater<?> updater, Object receiver,
                                         long operand, LongBinaryOperator function) {
        String field = SpinLocks.longFieldOf(updater);
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, field);
        ControlledSchedule.point();
        return longUpdaterUpdatePublishing(erasedLong(updater), receiver, field,
                c -> function.applyAsLong(c, operand), false);
    }

    /** {@link #updaterStoring} for a long field (#1317). */
    private static void longUpdaterStoring(@Nullable Object receiver, @Nullable String field, long value) {
        if (receiver != null && field != null) {
            HappensBefore.releaseVolatile(receiver, field, value);
        }
    }

    /** {@link #updaterLoaded} for a long field (#1317). */
    private static void longUpdaterLoaded(@Nullable Object receiver, @Nullable String field, long value) {
        if (receiver != null && field != null) {
            HappensBefore.acquireVolatile(receiver, field, value);
        }
    }

    /**
     * {@link #updaterAddPublishing} for an {@code AtomicLongFieldUpdater} (#1317).
     *
     * @return the previous value when {@code previous}, else the updated one
     */
    private static long longUpdaterAddPublishing(AtomicLongFieldUpdater<Object> target, Object receiver,
                                                 @Nullable String field, long delta, boolean previous) {
        if (receiver == null || field == null) {
            return previous ? target.getAndAdd(receiver, delta) : target.addAndGet(receiver, delta);
        }
        for (;;) {
            long seen = target.get(receiver);
            long next = seen + delta;
            HappensBefore.releaseVolatile(receiver, field, next);
            if (target.compareAndSet(receiver, seen, next)) {
                return previous ? seen : next;
            }
            HappensBefore.withdrawVolatile(receiver, field);
        }
    }

    /**
     * {@link #updaterUpdatePublishing} for an {@code AtomicLongFieldUpdater} (#1317).
     *
     * @return the previous value when {@code previous}, else the updated one
     */
    private static long longUpdaterUpdatePublishing(AtomicLongFieldUpdater<Object> target, Object receiver,
                                                    @Nullable String field, LongUnaryOperator function,
                                                    boolean previous) {
        if (receiver == null || field == null) {
            return previous ? target.getAndUpdate(receiver, function) : target.updateAndGet(receiver, function);
        }
        for (;;) {
            long seen = target.get(receiver);
            long next = function.applyAsLong(seen);
            HappensBefore.releaseVolatile(receiver, field, next);
            if (target.compareAndSet(receiver, seen, next)) {
                return previous ? seen : next;
            }
            HappensBefore.withdrawVolatile(receiver, field);
        }
    }

    @SuppressWarnings("unchecked")
    private static AtomicLongFieldUpdater<Object> erasedLong(AtomicLongFieldUpdater<?> updater) {
        return (AtomicLongFieldUpdater<Object>) updater;
    }

    /** Which update a {@link #handleUpdatePublishing} loop applies. */
    private static final int HANDLE_ADD = 0;
    private static final int HANDLE_OR = 1;
    private static final int HANDLE_AND = 2;
    private static final int HANDLE_XOR = 3;

    /**
     * Weaves an {@code int} {@code VarHandle.getVolatile}: the acquire of the store it read, under
     * the field's own slot, and the check half of a check-then-act (#1275, #1317).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field the slot is
     * @return the field's value
     * @since 1.15.0
     */
    public static int getVolatileInt(VarHandle handle, Object receiver) {
        AgentConcurrencyUtilHooks.updaterRead(receiver, SpinLocks.fieldOf(handle));
        ControlledSchedule.point();
        int value = (int) handle.withInvokeBehavior().getVolatile(receiver);
        handleLoaded(handle, receiver, value);
        return value;
    }

    /**
     * Weaves an {@code int} {@code VarHandle.getAcquire}, as {@link #getVolatileInt} (#1275, #1317).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field the slot is
     * @return the field's value
     * @since 1.15.0
     */
    public static int getAcquireInt(VarHandle handle, Object receiver) {
        AgentConcurrencyUtilHooks.updaterRead(receiver, SpinLocks.fieldOf(handle));
        ControlledSchedule.point();
        int value = (int) handle.withInvokeBehavior().getAcquire(receiver);
        handleLoaded(handle, receiver, value);
        return value;
    }

    /**
     * The release half of a store through an {@code int} {@code VarHandle} with volatile or release
     * semantics (#1317): under the field's own name, as a direct volatile write of it, so handle and
     * direct accesses order each other. Nothing when the handle's field cannot be told.
     */
    private static void handleStoring(VarHandle handle, @Nullable Object receiver, int value) {
        String field = receiver == null ? null : SpinLocks.fieldOf(handle);
        if (field != null) {
            HappensBefore.releaseVolatile(receiver, field, value);
        }
    }

    /** The acquire half of an {@code int} {@code VarHandle} read of {@code value}, as {@link #handleStoring}. */
    private static void handleLoaded(VarHandle handle, @Nullable Object receiver, int value) {
        String field = receiver == null ? null : SpinLocks.fieldOf(handle);
        if (field != null) {
            HappensBefore.acquireVolatile(receiver, field, value);
        }
    }

    /**
     * An {@code int} {@code VarHandle} add or bitwise update as a compare-and-set loop that releases
     * the value about to be stored and withdraws a lost attempt, as
     * {@link #addPublishing(AtomicInteger, int)} runs an atomic's (#1317). The value it returns
     * acquires nothing (AtomicCounterClockTest).
     *
     * @return the previous value
     */
    private static int handleUpdatePublishing(VarHandle handle, Object receiver, String field, int op,
                                              int operand) {
        VarHandle exact = handle.withInvokeBehavior();
        for (;;) {
            int seen = (int) exact.getVolatile(receiver);
            int next = switch (op) {
                case HANDLE_OR -> seen | operand;
                case HANDLE_AND -> seen & operand;
                case HANDLE_XOR -> seen ^ operand;
                default -> seen + operand;
            };
            HappensBefore.releaseVolatile(receiver, field, next);
            if (exact.compareAndSet(receiver, seen, next)) {
                return seen;
            }
            HappensBefore.withdrawVolatile(receiver, field);
        }
    }

    // ---- AtomicIntegerArray and AtomicLongArray (#1317) ---------------------------------------

    /**
     * Weaves {@code AtomicIntegerArray.get}: the acquire of the store it read (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @return the element's value
     * @since 1.15.0
     */
    public static int getAtomicIntegerArray(AtomicIntegerArray slots, int index) {
        ControlledSchedule.point();
        int value = slots.get(index);
        arrayLoaded(slots, index, value);
        return value;
    }

    /**
     * Weaves {@code AtomicIntegerArray.getAcquire}: the acquire of the store it read (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @return the element's value
     * @since 1.15.0
     */
    public static int getAcquireAtomicIntegerArray(AtomicIntegerArray slots, int index) {
        ControlledSchedule.point();
        int value = slots.getAcquire(index);
        arrayLoaded(slots, index, value);
        return value;
    }

    /**
     * Weaves {@code AtomicIntegerArray.set}: released before the store (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param value    the value to store
     * @since 1.15.0
     */
    public static void setAtomicIntegerArray(AtomicIntegerArray slots, int index, int value) {
        ControlledSchedule.point();
        arrayStoring(slots, index, value);
        slots.set(index, value);
    }

    /**
     * Weaves {@code AtomicIntegerArray.lazySet}: released before the store (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param value    the value to store
     * @since 1.15.0
     */
    public static void lazySetAtomicIntegerArray(AtomicIntegerArray slots, int index, int value) {
        ControlledSchedule.point();
        arrayStoring(slots, index, value);
        slots.lazySet(index, value);
    }

    /**
     * Weaves {@code AtomicIntegerArray.setRelease}: released before the store (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param value    the value to store
     * @since 1.15.0
     */
    public static void setReleaseAtomicIntegerArray(AtomicIntegerArray slots, int index, int value) {
        ControlledSchedule.point();
        arrayStoring(slots, index, value);
        slots.setRelease(index, value);
    }

    /**
     * Weaves {@code AtomicIntegerArray.compareAndSet}: a won swap releases what it stored (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param expected the value the element must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.15.0
     */
    public static boolean compareAndSetAtomicIntegerArray(AtomicIntegerArray slots, int index, int expected, int update) {
        ControlledSchedule.point();
        boolean won = slots.compareAndSet(index, expected, update);
        if (won) {
            arrayStoring(slots, index, update);
        }
        return won;
    }

    /**
     * Weaves {@code AtomicIntegerArray.weakCompareAndSetVolatile}: a won swap releases what it stored (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param expected the value the element must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.15.0
     */
    public static boolean weakCompareAndSetVolatileAtomicIntegerArray(AtomicIntegerArray slots, int index, int expected, int update) {
        ControlledSchedule.point();
        boolean won = slots.weakCompareAndSetVolatile(index, expected, update);
        if (won) {
            arrayStoring(slots, index, update);
        }
        return won;
    }

    /**
     * Weaves {@code AtomicIntegerArray.weakCompareAndSetRelease}: a won swap releases what it stored (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param expected the value the element must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.15.0
     */
    public static boolean weakCompareAndSetReleaseAtomicIntegerArray(AtomicIntegerArray slots, int index, int expected, int update) {
        ControlledSchedule.point();
        boolean won = slots.weakCompareAndSetRelease(index, expected, update);
        if (won) {
            arrayStoring(slots, index, update);
        }
        return won;
    }

    /**
     * Weaves {@code AtomicIntegerArray.compareAndExchange}: the witness acquires, and a won exchange releases what it stored (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param expected the value the element must hold
     * @param update   the value to store
     * @return the witness value
     * @since 1.15.0
     */
    public static int compareAndExchangeAtomicIntegerArray(AtomicIntegerArray slots, int index, int expected, int update) {
        ControlledSchedule.point();
        int witness = slots.compareAndExchange(index, expected, update);
        arrayLoaded(slots, index, witness);
        if (witness == expected) {
            arrayStoring(slots, index, update);
        }
        return witness;
    }

    /**
     * Weaves {@code AtomicIntegerArray.compareAndExchangeAcquire}: the witness acquires (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param expected the value the element must hold
     * @param update   the value to store
     * @return the witness value
     * @since 1.15.0
     */
    public static int compareAndExchangeAcquireAtomicIntegerArray(AtomicIntegerArray slots, int index, int expected, int update) {
        ControlledSchedule.point();
        int witness = slots.compareAndExchangeAcquire(index, expected, update);
        arrayLoaded(slots, index, witness);
        return witness;
    }

    /**
     * Weaves {@code AtomicIntegerArray.compareAndExchangeRelease}: a won exchange releases what it stored (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param expected the value the element must hold
     * @param update   the value to store
     * @return the witness value
     * @since 1.15.0
     */
    public static int compareAndExchangeReleaseAtomicIntegerArray(AtomicIntegerArray slots, int index, int expected, int update) {
        ControlledSchedule.point();
        int witness = slots.compareAndExchangeRelease(index, expected, update);
        if (witness == expected) {
            arrayStoring(slots, index, update);
        }
        return witness;
    }

    /**
     * Weaves {@code AtomicIntegerArray.getAndSet}: the store releases and the previous value acquires (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param value    the value to store
     * @return the previous value
     * @since 1.15.0
     */
    public static int getAndSetAtomicIntegerArray(AtomicIntegerArray slots, int index, int value) {
        ControlledSchedule.point();
        arrayStoring(slots, index, value);
        int previous = slots.getAndSet(index, value);
        arrayLoaded(slots, index, previous);
        return previous;
    }

    /**
     * Weaves {@code AtomicIntegerArray.getAndAdd}: run as a releasing compare-and-set loop (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param delta    the value to add
     * @return the previous value
     * @since 1.15.0
     */
    public static int getAndAddAtomicIntegerArray(AtomicIntegerArray slots, int index, int delta) {
        ControlledSchedule.point();
        return arrayAddPublishing(slots, index, delta, true);
    }

    /**
     * Weaves {@code AtomicIntegerArray.addAndGet}: run as a releasing compare-and-set loop (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param delta    the value to add
     * @return the updated value
     * @since 1.15.0
     */
    public static int addAndGetAtomicIntegerArray(AtomicIntegerArray slots, int index, int delta) {
        ControlledSchedule.point();
        return arrayAddPublishing(slots, index, delta, false);
    }

    /**
     * Weaves {@code AtomicIntegerArray.getAndIncrement}: run as a releasing compare-and-set loop (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @return the previous value
     * @since 1.15.0
     */
    public static int getAndIncrementAtomicIntegerArray(AtomicIntegerArray slots, int index) {
        ControlledSchedule.point();
        return arrayAddPublishing(slots, index, 1, true);
    }

    /**
     * Weaves {@code AtomicIntegerArray.incrementAndGet}: run as a releasing compare-and-set loop (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @return the updated value
     * @since 1.15.0
     */
    public static int incrementAndGetAtomicIntegerArray(AtomicIntegerArray slots, int index) {
        ControlledSchedule.point();
        return arrayAddPublishing(slots, index, 1, false);
    }

    /**
     * Weaves {@code AtomicIntegerArray.getAndDecrement}: run as a releasing compare-and-set loop (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @return the previous value
     * @since 1.15.0
     */
    public static int getAndDecrementAtomicIntegerArray(AtomicIntegerArray slots, int index) {
        ControlledSchedule.point();
        return arrayAddPublishing(slots, index, -1, true);
    }

    /**
     * Weaves {@code AtomicIntegerArray.decrementAndGet}: run as a releasing compare-and-set loop (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @return the updated value
     * @since 1.15.0
     */
    public static int decrementAndGetAtomicIntegerArray(AtomicIntegerArray slots, int index) {
        ControlledSchedule.point();
        return arrayAddPublishing(slots, index, -1, false);
    }

    /**
     * Weaves {@code AtomicIntegerArray.getAndUpdate}: run as a releasing compare-and-set loop (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param function the update, applied again after a lost swap as the JDK's own loop does
     * @return the previous value
     * @since 1.15.0
     */
    public static int getAndUpdateAtomicIntegerArray(AtomicIntegerArray slots, int index, IntUnaryOperator function) {
        ControlledSchedule.point();
        return arrayUpdatePublishing(slots, index, function, true);
    }

    /**
     * Weaves {@code AtomicIntegerArray.updateAndGet}: run as a releasing compare-and-set loop (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param function the update, applied again after a lost swap as the JDK's own loop does
     * @return the updated value
     * @since 1.15.0
     */
    public static int updateAndGetAtomicIntegerArray(AtomicIntegerArray slots, int index, IntUnaryOperator function) {
        ControlledSchedule.point();
        return arrayUpdatePublishing(slots, index, function, false);
    }

    /**
     * Weaves {@code AtomicIntegerArray.getAndAccumulate}: run as a releasing compare-and-set loop (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param operand  the function's second argument
     * @param function the accumulator, applied to the current value and {@code operand}
     * @return the previous value
     * @since 1.15.0
     */
    public static int getAndAccumulateAtomicIntegerArray(AtomicIntegerArray slots, int index, int operand, IntBinaryOperator function) {
        ControlledSchedule.point();
        return arrayUpdatePublishing(slots, index, c -> function.applyAsInt(c, operand), true);
    }

    /**
     * Weaves {@code AtomicIntegerArray.accumulateAndGet}: run as a releasing compare-and-set loop (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param operand  the function's second argument
     * @param function the accumulator, applied to the current value and {@code operand}
     * @return the updated value
     * @since 1.15.0
     */
    public static int accumulateAndGetAtomicIntegerArray(AtomicIntegerArray slots, int index, int operand, IntBinaryOperator function) {
        ControlledSchedule.point();
        return arrayUpdatePublishing(slots, index, c -> function.applyAsInt(c, operand), false);
    }

    /** {@code AtomicIntegerArray}'s arithmetic forms as a releasing compare-and-set loop that withdraws a lost attempt (#1317). */
    private static int arrayAddPublishing(AtomicIntegerArray slots, int index, int delta, boolean previous) {
        for (;;) {
            int seen = slots.get(index);
            int next = seen + delta;
            arrayStoring(slots, index, next);
            if (slots.compareAndSet(index, seen, next)) {
                return previous ? seen : next;
            }
            HappensBefore.withdrawVolatile(slots, ReferenceSlots.element(index));
        }
    }

    /** {@code AtomicIntegerArray}'s update and accumulate forms, as {@link #arrayAddPublishing(AtomicIntegerArray, int, int, boolean)} (#1317). */
    private static int arrayUpdatePublishing(AtomicIntegerArray slots, int index, IntUnaryOperator function, boolean previous) {
        for (;;) {
            int seen = slots.get(index);
            int next = function.applyAsInt(seen);
            arrayStoring(slots, index, next);
            if (slots.compareAndSet(index, seen, next)) {
                return previous ? seen : next;
            }
            HappensBefore.withdrawVolatile(slots, ReferenceSlots.element(index));
        }
    }

    /**
     * Weaves {@code AtomicLongArray.get}: the acquire of the store it read (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @return the element's value
     * @since 1.15.0
     */
    public static long getAtomicLongArray(AtomicLongArray slots, int index) {
        ControlledSchedule.point();
        long value = slots.get(index);
        arrayLoaded(slots, index, value);
        return value;
    }

    /**
     * Weaves {@code AtomicLongArray.getAcquire}: the acquire of the store it read (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @return the element's value
     * @since 1.15.0
     */
    public static long getAcquireAtomicLongArray(AtomicLongArray slots, int index) {
        ControlledSchedule.point();
        long value = slots.getAcquire(index);
        arrayLoaded(slots, index, value);
        return value;
    }

    /**
     * Weaves {@code AtomicLongArray.set}: released before the store (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param value    the value to store
     * @since 1.15.0
     */
    public static void setAtomicLongArray(AtomicLongArray slots, int index, long value) {
        ControlledSchedule.point();
        arrayStoring(slots, index, value);
        slots.set(index, value);
    }

    /**
     * Weaves {@code AtomicLongArray.lazySet}: released before the store (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param value    the value to store
     * @since 1.15.0
     */
    public static void lazySetAtomicLongArray(AtomicLongArray slots, int index, long value) {
        ControlledSchedule.point();
        arrayStoring(slots, index, value);
        slots.lazySet(index, value);
    }

    /**
     * Weaves {@code AtomicLongArray.setRelease}: released before the store (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param value    the value to store
     * @since 1.15.0
     */
    public static void setReleaseAtomicLongArray(AtomicLongArray slots, int index, long value) {
        ControlledSchedule.point();
        arrayStoring(slots, index, value);
        slots.setRelease(index, value);
    }

    /**
     * Weaves {@code AtomicLongArray.compareAndSet}: a won swap releases what it stored (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param expected the value the element must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.15.0
     */
    public static boolean compareAndSetAtomicLongArray(AtomicLongArray slots, int index, long expected, long update) {
        ControlledSchedule.point();
        boolean won = slots.compareAndSet(index, expected, update);
        if (won) {
            arrayStoring(slots, index, update);
        }
        return won;
    }

    /**
     * Weaves {@code AtomicLongArray.weakCompareAndSetVolatile}: a won swap releases what it stored (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param expected the value the element must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.15.0
     */
    public static boolean weakCompareAndSetVolatileAtomicLongArray(AtomicLongArray slots, int index, long expected, long update) {
        ControlledSchedule.point();
        boolean won = slots.weakCompareAndSetVolatile(index, expected, update);
        if (won) {
            arrayStoring(slots, index, update);
        }
        return won;
    }

    /**
     * Weaves {@code AtomicLongArray.weakCompareAndSetRelease}: a won swap releases what it stored (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param expected the value the element must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.15.0
     */
    public static boolean weakCompareAndSetReleaseAtomicLongArray(AtomicLongArray slots, int index, long expected, long update) {
        ControlledSchedule.point();
        boolean won = slots.weakCompareAndSetRelease(index, expected, update);
        if (won) {
            arrayStoring(slots, index, update);
        }
        return won;
    }

    /**
     * Weaves {@code AtomicLongArray.compareAndExchange}: the witness acquires, and a won exchange releases what it stored (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param expected the value the element must hold
     * @param update   the value to store
     * @return the witness value
     * @since 1.15.0
     */
    public static long compareAndExchangeAtomicLongArray(AtomicLongArray slots, int index, long expected, long update) {
        ControlledSchedule.point();
        long witness = slots.compareAndExchange(index, expected, update);
        arrayLoaded(slots, index, witness);
        if (witness == expected) {
            arrayStoring(slots, index, update);
        }
        return witness;
    }

    /**
     * Weaves {@code AtomicLongArray.compareAndExchangeAcquire}: the witness acquires (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param expected the value the element must hold
     * @param update   the value to store
     * @return the witness value
     * @since 1.15.0
     */
    public static long compareAndExchangeAcquireAtomicLongArray(AtomicLongArray slots, int index, long expected, long update) {
        ControlledSchedule.point();
        long witness = slots.compareAndExchangeAcquire(index, expected, update);
        arrayLoaded(slots, index, witness);
        return witness;
    }

    /**
     * Weaves {@code AtomicLongArray.compareAndExchangeRelease}: a won exchange releases what it stored (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param expected the value the element must hold
     * @param update   the value to store
     * @return the witness value
     * @since 1.15.0
     */
    public static long compareAndExchangeReleaseAtomicLongArray(AtomicLongArray slots, int index, long expected, long update) {
        ControlledSchedule.point();
        long witness = slots.compareAndExchangeRelease(index, expected, update);
        if (witness == expected) {
            arrayStoring(slots, index, update);
        }
        return witness;
    }

    /**
     * Weaves {@code AtomicLongArray.getAndSet}: the store releases and the previous value acquires (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param value    the value to store
     * @return the previous value
     * @since 1.15.0
     */
    public static long getAndSetAtomicLongArray(AtomicLongArray slots, int index, long value) {
        ControlledSchedule.point();
        arrayStoring(slots, index, value);
        long previous = slots.getAndSet(index, value);
        arrayLoaded(slots, index, previous);
        return previous;
    }

    /**
     * Weaves {@code AtomicLongArray.getAndAdd}: run as a releasing compare-and-set loop (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param delta    the value to add
     * @return the previous value
     * @since 1.15.0
     */
    public static long getAndAddAtomicLongArray(AtomicLongArray slots, int index, long delta) {
        ControlledSchedule.point();
        return arrayAddPublishing(slots, index, delta, true);
    }

    /**
     * Weaves {@code AtomicLongArray.addAndGet}: run as a releasing compare-and-set loop (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param delta    the value to add
     * @return the updated value
     * @since 1.15.0
     */
    public static long addAndGetAtomicLongArray(AtomicLongArray slots, int index, long delta) {
        ControlledSchedule.point();
        return arrayAddPublishing(slots, index, delta, false);
    }

    /**
     * Weaves {@code AtomicLongArray.getAndIncrement}: run as a releasing compare-and-set loop (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @return the previous value
     * @since 1.15.0
     */
    public static long getAndIncrementAtomicLongArray(AtomicLongArray slots, int index) {
        ControlledSchedule.point();
        return arrayAddPublishing(slots, index, 1L, true);
    }

    /**
     * Weaves {@code AtomicLongArray.incrementAndGet}: run as a releasing compare-and-set loop (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @return the updated value
     * @since 1.15.0
     */
    public static long incrementAndGetAtomicLongArray(AtomicLongArray slots, int index) {
        ControlledSchedule.point();
        return arrayAddPublishing(slots, index, 1L, false);
    }

    /**
     * Weaves {@code AtomicLongArray.getAndDecrement}: run as a releasing compare-and-set loop (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @return the previous value
     * @since 1.15.0
     */
    public static long getAndDecrementAtomicLongArray(AtomicLongArray slots, int index) {
        ControlledSchedule.point();
        return arrayAddPublishing(slots, index, -1L, true);
    }

    /**
     * Weaves {@code AtomicLongArray.decrementAndGet}: run as a releasing compare-and-set loop (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @return the updated value
     * @since 1.15.0
     */
    public static long decrementAndGetAtomicLongArray(AtomicLongArray slots, int index) {
        ControlledSchedule.point();
        return arrayAddPublishing(slots, index, -1L, false);
    }

    /**
     * Weaves {@code AtomicLongArray.getAndUpdate}: run as a releasing compare-and-set loop (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param function the update, applied again after a lost swap as the JDK's own loop does
     * @return the previous value
     * @since 1.15.0
     */
    public static long getAndUpdateAtomicLongArray(AtomicLongArray slots, int index, LongUnaryOperator function) {
        ControlledSchedule.point();
        return arrayUpdatePublishing(slots, index, function, true);
    }

    /**
     * Weaves {@code AtomicLongArray.updateAndGet}: run as a releasing compare-and-set loop (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param function the update, applied again after a lost swap as the JDK's own loop does
     * @return the updated value
     * @since 1.15.0
     */
    public static long updateAndGetAtomicLongArray(AtomicLongArray slots, int index, LongUnaryOperator function) {
        ControlledSchedule.point();
        return arrayUpdatePublishing(slots, index, function, false);
    }

    /**
     * Weaves {@code AtomicLongArray.getAndAccumulate}: run as a releasing compare-and-set loop (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param operand  the function's second argument
     * @param function the accumulator, applied to the current value and {@code operand}
     * @return the previous value
     * @since 1.15.0
     */
    public static long getAndAccumulateAtomicLongArray(AtomicLongArray slots, int index, long operand, LongBinaryOperator function) {
        ControlledSchedule.point();
        return arrayUpdatePublishing(slots, index, c -> function.applyAsLong(c, operand), true);
    }

    /**
     * Weaves {@code AtomicLongArray.accumulateAndGet}: run as a releasing compare-and-set loop (#1317).
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param operand  the function's second argument
     * @param function the accumulator, applied to the current value and {@code operand}
     * @return the updated value
     * @since 1.15.0
     */
    public static long accumulateAndGetAtomicLongArray(AtomicLongArray slots, int index, long operand, LongBinaryOperator function) {
        ControlledSchedule.point();
        return arrayUpdatePublishing(slots, index, c -> function.applyAsLong(c, operand), false);
    }

    /** {@code AtomicLongArray}'s arithmetic forms as a releasing compare-and-set loop that withdraws a lost attempt (#1317). */
    private static long arrayAddPublishing(AtomicLongArray slots, int index, long delta, boolean previous) {
        for (;;) {
            long seen = slots.get(index);
            long next = seen + delta;
            arrayStoring(slots, index, next);
            if (slots.compareAndSet(index, seen, next)) {
                return previous ? seen : next;
            }
            HappensBefore.withdrawVolatile(slots, ReferenceSlots.element(index));
        }
    }

    /** {@code AtomicLongArray}'s update and accumulate forms, as {@link #arrayAddPublishing(AtomicLongArray, int, long, boolean)} (#1317). */
    private static long arrayUpdatePublishing(AtomicLongArray slots, int index, LongUnaryOperator function, boolean previous) {
        for (;;) {
            long seen = slots.get(index);
            long next = function.applyAsLong(seen);
            arrayStoring(slots, index, next);
            if (slots.compareAndSet(index, seen, next)) {
                return previous ? seen : next;
            }
            HappensBefore.withdrawVolatile(slots, ReferenceSlots.element(index));
        }
    }

    /**
     * The release half of a store into an atomic array element (#1317): each element is its own
     * slot, named by its index as a reference array's elements are, so a store to one element
     * publishes nothing to a read of another.
     */
    private static void arrayStoring(Object slots, int index, long value) {
        HappensBefore.releaseVolatile(slots, ReferenceSlots.element(index), value);
    }

    /** The acquire half of a read of an atomic array element, as {@link #arrayStoring} (#1317). */
    private static void arrayLoaded(Object slots, int index, long value) {
        HappensBefore.acquireVolatile(slots, ReferenceSlots.element(index), value);
    }

    // ---- long instance-field VarHandles (#1275, #1317) ----------------------------------------

    /**
     * Weaves a {@code long} {@code VarHandle.getVolatile}: the acquire of the store it read, and the check half of a check-then-act (#1275, #1317).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field the slot is
     * @return the field's value
     * @since 1.15.0
     */
    public static long getVolatileLong(VarHandle handle, Object receiver) {
        AgentConcurrencyUtilHooks.updaterRead(receiver, ReferenceSlots.fieldOf(handle));
        ControlledSchedule.point();
        long value = (long) handle.withInvokeBehavior().getVolatile(receiver);
        longHandleLoaded(handle, receiver, value);
        return value;
    }

    /**
     * Weaves a {@code long} {@code VarHandle.getAcquire}: the acquire of the store it read, and the check half of a check-then-act (#1275, #1317).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field the slot is
     * @return the field's value
     * @since 1.15.0
     */
    public static long getAcquireLong(VarHandle handle, Object receiver) {
        AgentConcurrencyUtilHooks.updaterRead(receiver, ReferenceSlots.fieldOf(handle));
        ControlledSchedule.point();
        long value = (long) handle.withInvokeBehavior().getAcquire(receiver);
        longHandleLoaded(handle, receiver, value);
        return value;
    }

    /**
     * Weaves a {@code long} {@code VarHandle.set}: a store without release semantics, which publishes nothing (#1275, #1317).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field the slot is
     * @param value    the value to store
     * @since 1.15.0
     */
    public static void setLong(VarHandle handle, Object receiver, long value) {
        AgentConcurrencyUtilHooks.updaterStored(receiver, ReferenceSlots.fieldOf(handle));
        ControlledSchedule.point();
        handle.withInvokeBehavior().set(receiver, value);
    }

    /**
     * Weaves a {@code long} {@code VarHandle.setVolatile}: released before the store (#1275, #1317).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field the slot is
     * @param value    the value to store
     * @since 1.15.0
     */
    public static void setVolatileLong(VarHandle handle, Object receiver, long value) {
        AgentConcurrencyUtilHooks.updaterStored(receiver, ReferenceSlots.fieldOf(handle));
        ControlledSchedule.point();
        longHandleStoring(handle, receiver, value);
        handle.withInvokeBehavior().setVolatile(receiver, value);
    }

    /**
     * Weaves a {@code long} {@code VarHandle.setRelease}: released before the store (#1275, #1317).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field the slot is
     * @param value    the value to store
     * @since 1.15.0
     */
    public static void setReleaseLong(VarHandle handle, Object receiver, long value) {
        AgentConcurrencyUtilHooks.updaterStored(receiver, ReferenceSlots.fieldOf(handle));
        ControlledSchedule.point();
        longHandleStoring(handle, receiver, value);
        handle.withInvokeBehavior().setRelease(receiver, value);
    }

    /**
     * Weaves a {@code long} {@code VarHandle.setOpaque}: a store without release semantics, which publishes nothing (#1275, #1317).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field the slot is
     * @param value    the value to store
     * @since 1.15.0
     */
    public static void setOpaqueLong(VarHandle handle, Object receiver, long value) {
        AgentConcurrencyUtilHooks.updaterStored(receiver, ReferenceSlots.fieldOf(handle));
        ControlledSchedule.point();
        handle.withInvokeBehavior().setOpaque(receiver, value);
    }

    /**
     * Weaves a {@code long} {@code VarHandle.compareAndSet}: a won swap releases what it stored (#1275, #1317).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field the slot is
     * @param expected the value the field must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.15.0
     */
    public static boolean compareAndSetLong(VarHandle handle, Object receiver, long expected, long update) {
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, ReferenceSlots.fieldOf(handle));
        ControlledSchedule.point();
        boolean won = handle.withInvokeBehavior().compareAndSet(receiver, expected, update);
        if (won) {
            longHandleStoring(handle, receiver, update);
        }
        return won;
    }

    /**
     * Weaves a {@code long} {@code VarHandle.weakCompareAndSet}: a won swap releases what it stored (#1275, #1317).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field the slot is
     * @param expected the value the field must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.15.0
     */
    public static boolean weakCompareAndSetLong(VarHandle handle, Object receiver, long expected, long update) {
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, ReferenceSlots.fieldOf(handle));
        ControlledSchedule.point();
        boolean won = handle.withInvokeBehavior().weakCompareAndSet(receiver, expected, update);
        if (won) {
            longHandleStoring(handle, receiver, update);
        }
        return won;
    }

    /**
     * Weaves a {@code long} {@code VarHandle.weakCompareAndSetRelease}: a won swap releases what it stored (#1275, #1317).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field the slot is
     * @param expected the value the field must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.15.0
     */
    public static boolean weakCompareAndSetReleaseLong(VarHandle handle, Object receiver, long expected, long update) {
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, ReferenceSlots.fieldOf(handle));
        ControlledSchedule.point();
        boolean won = handle.withInvokeBehavior().weakCompareAndSetRelease(receiver, expected, update);
        if (won) {
            longHandleStoring(handle, receiver, update);
        }
        return won;
    }

    /**
     * Weaves a {@code long} {@code VarHandle.weakCompareAndSetPlain}: a swap without release semantics, which publishes nothing (#1275, #1317).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field the slot is
     * @param expected the value the field must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.15.0
     */
    public static boolean weakCompareAndSetPlainLong(VarHandle handle, Object receiver, long expected, long update) {
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, ReferenceSlots.fieldOf(handle));
        ControlledSchedule.point();
        return handle.withInvokeBehavior().weakCompareAndSetPlain(receiver, expected, update);
    }

    /**
     * Weaves a {@code long} {@code VarHandle.weakCompareAndSetAcquire}: a swap without release semantics, which publishes nothing (#1275, #1317).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field the slot is
     * @param expected the value the field must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.15.0
     */
    public static boolean weakCompareAndSetAcquireLong(VarHandle handle, Object receiver, long expected, long update) {
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, ReferenceSlots.fieldOf(handle));
        ControlledSchedule.point();
        return handle.withInvokeBehavior().weakCompareAndSetAcquire(receiver, expected, update);
    }

    /**
     * Weaves a {@code long} {@code VarHandle.compareAndExchange}: the witness acquires, and a won exchange releases what it stored (#1275, #1317).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field the slot is
     * @param expected the value the field must hold
     * @param update   the value to store
     * @return the witness value
     * @since 1.15.0
     */
    public static long compareAndExchangeLong(VarHandle handle, Object receiver, long expected, long update) {
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, ReferenceSlots.fieldOf(handle));
        ControlledSchedule.point();
        long witness = (long) handle.withInvokeBehavior().compareAndExchange(receiver, expected, update);
        longHandleLoaded(handle, receiver, witness);
        if (witness == expected) {
            longHandleStoring(handle, receiver, update);
        }
        return witness;
    }

    /**
     * Weaves a {@code long} {@code VarHandle.compareAndExchangeAcquire}: the witness acquires (#1275, #1317).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field the slot is
     * @param expected the value the field must hold
     * @param update   the value to store
     * @return the witness value
     * @since 1.15.0
     */
    public static long compareAndExchangeAcquireLong(VarHandle handle, Object receiver, long expected, long update) {
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, ReferenceSlots.fieldOf(handle));
        ControlledSchedule.point();
        long witness = (long) handle.withInvokeBehavior().compareAndExchangeAcquire(receiver, expected, update);
        longHandleLoaded(handle, receiver, witness);
        return witness;
    }

    /**
     * Weaves a {@code long} {@code VarHandle.compareAndExchangeRelease}: a won exchange releases what it stored (#1275, #1317).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field the slot is
     * @param expected the value the field must hold
     * @param update   the value to store
     * @return the witness value
     * @since 1.15.0
     */
    public static long compareAndExchangeReleaseLong(VarHandle handle, Object receiver, long expected, long update) {
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, ReferenceSlots.fieldOf(handle));
        ControlledSchedule.point();
        long witness = (long) handle.withInvokeBehavior().compareAndExchangeRelease(receiver, expected, update);
        if (witness == expected) {
            longHandleStoring(handle, receiver, update);
        }
        return witness;
    }

    /**
     * Weaves a {@code long} {@code VarHandle.getAndSet}: the store releases and the previous value acquires (#1275, #1317).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field the slot is
     * @param value    the value to store
     * @return the previous value
     * @since 1.15.0
     */
    public static long getAndSetLong(VarHandle handle, Object receiver, long value) {
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, ReferenceSlots.fieldOf(handle));
        ControlledSchedule.point();
        longHandleStoring(handle, receiver, value);
        long previous = (long) handle.withInvokeBehavior().getAndSet(receiver, value);
        longHandleLoaded(handle, receiver, previous);
        return previous;
    }

    /**
     * Weaves a {@code long} {@code VarHandle.getAndSetAcquire}: the previous value acquires (#1275, #1317).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field the slot is
     * @param value    the value to store
     * @return the previous value
     * @since 1.15.0
     */
    public static long getAndSetAcquireLong(VarHandle handle, Object receiver, long value) {
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, ReferenceSlots.fieldOf(handle));
        ControlledSchedule.point();
        long previous = (long) handle.withInvokeBehavior().getAndSetAcquire(receiver, value);
        longHandleLoaded(handle, receiver, previous);
        return previous;
    }

    /**
     * Weaves a {@code long} {@code VarHandle.getAndSetRelease}: the store releases (#1275, #1317).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field the slot is
     * @param value    the value to store
     * @return the previous value
     * @since 1.15.0
     */
    public static long getAndSetReleaseLong(VarHandle handle, Object receiver, long value) {
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, ReferenceSlots.fieldOf(handle));
        ControlledSchedule.point();
        longHandleStoring(handle, receiver, value);
        return (long) handle.withInvokeBehavior().getAndSetRelease(receiver, value);
    }

    /**
     * Weaves a {@code long} {@code VarHandle.getAndAdd}: run as a releasing compare-and-set loop (#1275, #1317).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field the slot is
     * @param delta    the value to add
     * @return the previous value
     * @since 1.15.0
     */
    public static long getAndAddLong(VarHandle handle, Object receiver, long delta) {
        AgentConcurrencyUtilHooks.updaterIncremented(receiver, ReferenceSlots.fieldOf(handle));
        ControlledSchedule.point();
        String field = receiver == null ? null : ReferenceSlots.fieldOf(handle);
        return field == null
                ? (long) handle.withInvokeBehavior().getAndAdd(receiver, delta)
                : longHandleUpdatePublishing(handle, receiver, field, HANDLE_ADD, delta);
    }

    /**
     * Weaves a {@code long} {@code VarHandle.getAndAddRelease}: run as a releasing compare-and-set loop (#1275, #1317).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field the slot is
     * @param delta    the value to add
     * @return the previous value
     * @since 1.15.0
     */
    public static long getAndAddReleaseLong(VarHandle handle, Object receiver, long delta) {
        AgentConcurrencyUtilHooks.updaterIncremented(receiver, ReferenceSlots.fieldOf(handle));
        ControlledSchedule.point();
        String field = receiver == null ? null : ReferenceSlots.fieldOf(handle);
        return field == null
                ? (long) handle.withInvokeBehavior().getAndAddRelease(receiver, delta)
                : longHandleUpdatePublishing(handle, receiver, field, HANDLE_ADD, delta);
    }

    /**
     * Weaves a {@code long} {@code VarHandle.getAndAddAcquire}: an update without release semantics, which publishes nothing (#1275, #1317).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field the slot is
     * @param delta    the value to add
     * @return the previous value
     * @since 1.15.0
     */
    public static long getAndAddAcquireLong(VarHandle handle, Object receiver, long delta) {
        AgentConcurrencyUtilHooks.updaterIncremented(receiver, ReferenceSlots.fieldOf(handle));
        ControlledSchedule.point();
        return (long) handle.withInvokeBehavior().getAndAddAcquire(receiver, delta);
    }

    /**
     * Weaves a {@code long} {@code VarHandle.getAndBitwiseOr}: run as a releasing compare-and-set loop (#1275, #1317).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field the slot is
     * @param mask     the bits combined with the field's value
     * @return the previous value
     * @since 1.15.0
     */
    public static long getAndBitwiseOrLong(VarHandle handle, Object receiver, long mask) {
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, ReferenceSlots.fieldOf(handle));
        ControlledSchedule.point();
        String field = receiver == null ? null : ReferenceSlots.fieldOf(handle);
        return field == null
                ? (long) handle.withInvokeBehavior().getAndBitwiseOr(receiver, mask)
                : longHandleUpdatePublishing(handle, receiver, field, HANDLE_OR, mask);
    }

    /**
     * Weaves a {@code long} {@code VarHandle.getAndBitwiseOrRelease}: run as a releasing compare-and-set loop (#1275, #1317).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field the slot is
     * @param mask     the bits combined with the field's value
     * @return the previous value
     * @since 1.15.0
     */
    public static long getAndBitwiseOrReleaseLong(VarHandle handle, Object receiver, long mask) {
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, ReferenceSlots.fieldOf(handle));
        ControlledSchedule.point();
        String field = receiver == null ? null : ReferenceSlots.fieldOf(handle);
        return field == null
                ? (long) handle.withInvokeBehavior().getAndBitwiseOrRelease(receiver, mask)
                : longHandleUpdatePublishing(handle, receiver, field, HANDLE_OR, mask);
    }

    /**
     * Weaves a {@code long} {@code VarHandle.getAndBitwiseOrAcquire}: an update without release semantics, which publishes nothing (#1275, #1317).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field the slot is
     * @param mask     the bits combined with the field's value
     * @return the previous value
     * @since 1.15.0
     */
    public static long getAndBitwiseOrAcquireLong(VarHandle handle, Object receiver, long mask) {
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, ReferenceSlots.fieldOf(handle));
        ControlledSchedule.point();
        return (long) handle.withInvokeBehavior().getAndBitwiseOrAcquire(receiver, mask);
    }

    /**
     * Weaves a {@code long} {@code VarHandle.getAndBitwiseAnd}: run as a releasing compare-and-set loop (#1275, #1317).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field the slot is
     * @param mask     the bits combined with the field's value
     * @return the previous value
     * @since 1.15.0
     */
    public static long getAndBitwiseAndLong(VarHandle handle, Object receiver, long mask) {
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, ReferenceSlots.fieldOf(handle));
        ControlledSchedule.point();
        String field = receiver == null ? null : ReferenceSlots.fieldOf(handle);
        return field == null
                ? (long) handle.withInvokeBehavior().getAndBitwiseAnd(receiver, mask)
                : longHandleUpdatePublishing(handle, receiver, field, HANDLE_AND, mask);
    }

    /**
     * Weaves a {@code long} {@code VarHandle.getAndBitwiseAndRelease}: run as a releasing compare-and-set loop (#1275, #1317).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field the slot is
     * @param mask     the bits combined with the field's value
     * @return the previous value
     * @since 1.15.0
     */
    public static long getAndBitwiseAndReleaseLong(VarHandle handle, Object receiver, long mask) {
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, ReferenceSlots.fieldOf(handle));
        ControlledSchedule.point();
        String field = receiver == null ? null : ReferenceSlots.fieldOf(handle);
        return field == null
                ? (long) handle.withInvokeBehavior().getAndBitwiseAndRelease(receiver, mask)
                : longHandleUpdatePublishing(handle, receiver, field, HANDLE_AND, mask);
    }

    /**
     * Weaves a {@code long} {@code VarHandle.getAndBitwiseAndAcquire}: an update without release semantics, which publishes nothing (#1275, #1317).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field the slot is
     * @param mask     the bits combined with the field's value
     * @return the previous value
     * @since 1.15.0
     */
    public static long getAndBitwiseAndAcquireLong(VarHandle handle, Object receiver, long mask) {
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, ReferenceSlots.fieldOf(handle));
        ControlledSchedule.point();
        return (long) handle.withInvokeBehavior().getAndBitwiseAndAcquire(receiver, mask);
    }

    /**
     * Weaves a {@code long} {@code VarHandle.getAndBitwiseXor}: run as a releasing compare-and-set loop (#1275, #1317).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field the slot is
     * @param mask     the bits combined with the field's value
     * @return the previous value
     * @since 1.15.0
     */
    public static long getAndBitwiseXorLong(VarHandle handle, Object receiver, long mask) {
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, ReferenceSlots.fieldOf(handle));
        ControlledSchedule.point();
        String field = receiver == null ? null : ReferenceSlots.fieldOf(handle);
        return field == null
                ? (long) handle.withInvokeBehavior().getAndBitwiseXor(receiver, mask)
                : longHandleUpdatePublishing(handle, receiver, field, HANDLE_XOR, mask);
    }

    /**
     * Weaves a {@code long} {@code VarHandle.getAndBitwiseXorRelease}: run as a releasing compare-and-set loop (#1275, #1317).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field the slot is
     * @param mask     the bits combined with the field's value
     * @return the previous value
     * @since 1.15.0
     */
    public static long getAndBitwiseXorReleaseLong(VarHandle handle, Object receiver, long mask) {
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, ReferenceSlots.fieldOf(handle));
        ControlledSchedule.point();
        String field = receiver == null ? null : ReferenceSlots.fieldOf(handle);
        return field == null
                ? (long) handle.withInvokeBehavior().getAndBitwiseXorRelease(receiver, mask)
                : longHandleUpdatePublishing(handle, receiver, field, HANDLE_XOR, mask);
    }

    /**
     * Weaves a {@code long} {@code VarHandle.getAndBitwiseXorAcquire}: an update without release semantics, which publishes nothing (#1275, #1317).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field the slot is
     * @param mask     the bits combined with the field's value
     * @return the previous value
     * @since 1.15.0
     */
    public static long getAndBitwiseXorAcquireLong(VarHandle handle, Object receiver, long mask) {
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, ReferenceSlots.fieldOf(handle));
        ControlledSchedule.point();
        return (long) handle.withInvokeBehavior().getAndBitwiseXorAcquire(receiver, mask);
    }

    /** {@link #handleStoring} for a {@code long} field, resolved by {@code ReferenceSlots} (#1317). */
    private static void longHandleStoring(VarHandle handle, @Nullable Object receiver, long value) {
        String field = receiver == null ? null : ReferenceSlots.fieldOf(handle);
        if (field != null) {
            HappensBefore.releaseVolatile(receiver, field, value);
        }
    }

    /** {@link #handleLoaded} for a {@code long} field (#1317). */
    private static void longHandleLoaded(VarHandle handle, @Nullable Object receiver, long value) {
        String field = receiver == null ? null : ReferenceSlots.fieldOf(handle);
        if (field != null) {
            HappensBefore.acquireVolatile(receiver, field, value);
        }
    }

    /**
     * {@link #handleUpdatePublishing} for a {@code long} field (#1317).
     *
     * @return the previous value
     */
    private static long longHandleUpdatePublishing(VarHandle handle, Object receiver, String field, int op,
                                                   long operand) {
        VarHandle exact = handle.withInvokeBehavior();
        for (;;) {
            long seen = (long) exact.getVolatile(receiver);
            long next = switch (op) {
                case HANDLE_OR -> seen | operand;
                case HANDLE_AND -> seen & operand;
                case HANDLE_XOR -> seen ^ operand;
                default -> seen + operand;
            };
            HappensBefore.releaseVolatile(receiver, field, next);
            if (exact.compareAndSet(receiver, seen, next)) {
                return seen;
            }
            HappensBefore.withdrawVolatile(receiver, field);
        }
    }

    /**
     * The release half of a store through an {@code AtomicIntegerFieldUpdater} (#1317): published
     * under the field's own name, as a direct volatile write of it is, so an updater store and a
     * direct read of the field, or the other way round, order each other. Nothing when the updater's
     * field could not be resolved.
     */
    private static void updaterStoring(@Nullable Object receiver, @Nullable String field, int value) {
        if (receiver != null && field != null) {
            HappensBefore.releaseVolatile(receiver, field, value);
        }
    }

    /** The acquire half of an updater read of {@code value}, as {@link #updaterStoring} (#1317). */
    private static void updaterLoaded(@Nullable Object receiver, @Nullable String field, int value) {
        if (receiver != null && field != null) {
            HappensBefore.acquireVolatile(receiver, field, value);
        }
    }

    /**
     * An updater's arithmetic form as {@link #addPublishing(AtomicInteger, int)} runs an atomic's
     * (#1317): the value about to be stored is released before the swap, a lost attempt withdraws
     * its release, and the value read acquires nothing (AtomicCounterClockTest). With the field
     * unresolved it is the JDK's own call.
     *
     * @return the previous value when {@code previous}, else the updated one
     */
    private static int updaterAddPublishing(AtomicIntegerFieldUpdater<Object> target, Object receiver,
                                            @Nullable String field, int delta, boolean previous) {
        if (receiver == null || field == null) {
            return previous ? target.getAndAdd(receiver, delta) : target.addAndGet(receiver, delta);
        }
        for (;;) {
            int seen = target.get(receiver);
            int next = seen + delta;
            HappensBefore.releaseVolatile(receiver, field, next);
            if (target.compareAndSet(receiver, seen, next)) {
                return previous ? seen : next;
            }
            HappensBefore.withdrawVolatile(receiver, field);
        }
    }

    /**
     * {@link #updaterAddPublishing} for the update and accumulate forms. The function is applied
     * again after a lost swap, as the JDK's own loop may apply it.
     *
     * @return the previous value when {@code previous}, else the updated one
     */
    private static int updaterUpdatePublishing(AtomicIntegerFieldUpdater<Object> target, Object receiver,
                                               @Nullable String field, IntUnaryOperator function,
                                               boolean previous) {
        if (receiver == null || field == null) {
            return previous ? target.getAndUpdate(receiver, function) : target.updateAndGet(receiver, function);
        }
        for (;;) {
            int seen = target.get(receiver);
            int next = function.applyAsInt(seen);
            HappensBefore.releaseVolatile(receiver, field, next);
            if (target.compareAndSet(receiver, seen, next)) {
                return previous ? seen : next;
            }
            HappensBefore.withdrawVolatile(receiver, field);
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
        ControlledSchedule.point();
        if (!expected && update) {
            SpinLocks.aboutToAcquire(flag);
        }
        boolean won = flag.compareAndSet(expected, update);
        if (won) {
            primitiveSlotStoring(flag, update ? 1 : 0);
        }
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
        ControlledSchedule.point();
        if (!value) {
            SpinLocks.release(flag);
        } else {
            SpinLocks.aboutToAcquire(flag);
        }
        primitiveSlotStoring(flag, value ? 1 : 0);
        boolean previous = flag.getAndSet(value);
        HappensBefore.acquireVolatile(flag, SLOT_VALUE, previous ? 1 : 0);
        if (value && !previous) {
            SpinLocks.acquire(flag);
        }
        return previous;
    }

    /**
     * Weaves {@code AtomicBoolean.get}, a volatile read: the reading thread receives what the store
     * of the value it returned published (#1315), as {@link #getAtomicReference} does for a
     * reference. The acquire half of {@link #primitiveSlotStoring}.
     *
     * @param flag the atomic the call site invoked
     * @return what the atomic held
     * @since 1.14.3
     */
    public static boolean getAtomicBoolean(AtomicBoolean flag) {
        ControlledSchedule.point();
        boolean value = flag.get();
        HappensBefore.acquireVolatile(flag, SLOT_VALUE, value ? 1 : 0);
        return value;
    }

    /**
     * Weaves {@code AtomicBoolean.getAcquire}, an acquiring read: it takes what the store of the
     * value it returned published, as {@link #getAtomicBoolean} does (#1317).
     *
     * @param flag the flag the call site read
     * @return the flag's value
     * @since 1.15.0
     */
    public static boolean getAcquireAtomicBoolean(AtomicBoolean flag) {
        ControlledSchedule.point();
        boolean value = flag.getAcquire();
        HappensBefore.acquireVolatile(flag, SLOT_VALUE, value ? 1 : 0);
        return value;
    }

    /**
     * The release half of a store into an {@code AtomicBoolean}, {@code AtomicInteger} or
     * {@code AtomicLong}, which has volatile-write semantics: everything this thread did so far is
     * published to a {@code get} of the same atomic that returns {@code value} (#1315), the model
     * {@link #slotStored} gives an {@code AtomicReference}.
     *
     * <p>Called before the store by a {@code set}, a {@code getAndSet} and the arithmetic forms,
     * as a volatile field's release is, so it precedes every read that can return the value. A
     * {@code compareAndSet} calls it only once the swap has won, as {@code AtomicReference}'s
     * does: one that lost stored nothing, and a release for it would order a reader after a
     * thread whose writes nothing published. The price is a read that lands between a won swap
     * and its release, which acquires nothing and keeps the finding it had.
     */
    private static void primitiveSlotStoring(Object atomic, long value) {
        HappensBefore.releaseVolatile(atomic, SLOT_VALUE, value);
    }

    /**
     * {@code count.addAndGet(delta)} as a compare-and-set loop, so the value about to be stored is
     * known before the store and can be released first (#1315). An arithmetic update's result is
     * not on the stack, and a release made after the store would leave a reader that saw the new
     * value in between nothing to acquire, which a reader spinning on a completion counter does
     * often. An attempt that loses the swap has released a value it did not store, and withdraws
     * that release while it is still the atomic's latest (#1316). When the winner released after
     * it, the winner's release has folded this thread's in, and until the thread stores its own
     * value on the next turn of the loop a reader of the winner's value is ordered after this
     * thread too.
     *
     * @return the updated value
     */
    private static int addPublishing(AtomicInteger count, int delta) {
        for (;;) {
            int seen = count.get();
            int next = seen + delta;
            primitiveSlotStoring(count, next);
            if (count.compareAndSet(seen, next)) {
                return next;
            }
            HappensBefore.withdrawVolatile(count, SLOT_VALUE);
        }
    }

    /**
     * {@code getAndUpdate}, {@code updateAndGet} and the accumulate forms as
     * {@link #addPublishing(AtomicInteger, int)} does an add (#1317): the value about to be stored
     * is released before the swap. The function is re-applied after a lost swap, as the JDK's own
     * loop may re-apply it, so it must be free of side effects either way.
     *
     * <p>The value read does not acquire, here or in an add. A counter bumped by every thread
     * collects every bumper's clock in its releases, and a bump that acquired would hand that whole
     * clock to each bumping thread and to every access it records afterwards: a static counter
     * bumped once per body execution filled a 2 GB heap within seconds (AtomicCounterClockTest).
     * A {@code get} of the counter still acquires.
     *
     * @return the previous value when {@code previous}, else the updated one
     */
    private static int updatePublishing(AtomicInteger count, IntUnaryOperator function,
                                        boolean previous) {
        for (;;) {
            int seen = count.get();
            int next = function.applyAsInt(seen);
            primitiveSlotStoring(count, next);
            if (count.compareAndSet(seen, next)) {
                return previous ? seen : next;
            }
            HappensBefore.withdrawVolatile(count, SLOT_VALUE);
        }
    }

    /** {@link #addPublishing(AtomicInteger, int)} for an {@code AtomicLong}. */
    private static long addPublishing(AtomicLong count, long delta) {
        for (;;) {
            long seen = count.get();
            long next = seen + delta;
            primitiveSlotStoring(count, next);
            if (count.compareAndSet(seen, next)) {
                return next;
            }
            HappensBefore.withdrawVolatile(count, SLOT_VALUE);
        }
    }

    /** Weaves {@code AtomicBoolean.set}; storing {@code false} releases. @param flag the atomic @param value the value @since 1.12.1 */
    public static void setAtomicBoolean(AtomicBoolean flag, boolean value) {
        ControlledSchedule.point();
        if (!value) {
            SpinLocks.release(flag);
        }
        primitiveSlotStoring(flag, value ? 1 : 0);
        flag.set(value);
    }

    /** Weaves {@code AtomicBoolean.lazySet}; storing {@code false} releases. @param flag the atomic @param value the value @since 1.12.1 */
    public static void lazySetAtomicBoolean(AtomicBoolean flag, boolean value) {
        ControlledSchedule.point();
        if (!value) {
            SpinLocks.release(flag);
        }
        primitiveSlotStoring(flag, value ? 1 : 0);
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
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        ControlledSchedule.point();
        if (expected == 0 && update == 1) {
            SpinLocks.aboutToAcquire(count);
        }
        boolean won = count.compareAndSet(expected, update);
        if (won) {
            primitiveSlotStoring(count, update);
        }
        if (won && expected == 0 && update == 1) {
            SpinLocks.acquire(count);
        } else if (won && expected == 1 && update == 0) {
            SpinLocks.release(count);
        }
        return won;
    }

    /**
     * Weaves {@code AtomicInteger.get}, which only {@code ATOMIC_NON_ATOMIC_UPDATE} reads (#1157). The
     * point is what lets a seed put another thread between a get and the set after it.
     *
     * @param count the atomic the call site invoked
     * @return what the atomic held
     * @since 1.14.1
     */
    public static int getAtomicInteger(AtomicInteger count) {
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicRead(count);
        int value = count.get();
        HappensBefore.acquireVolatile(count, SLOT_VALUE, value);
        return value;
    }

    /**
     * Weaves {@code AtomicInteger.getAcquire}, an acquiring read and the check half of a
     * check-then-act like {@link #getAtomicInteger} (#1317).
     *
     * @param count the atomic the call site read
     * @return the atomic's value
     * @since 1.15.0
     */
    public static int getAcquireAtomicInteger(AtomicInteger count) {
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicRead(count);
        int value = count.getAcquire();
        HappensBefore.acquireVolatile(count, SLOT_VALUE, value);
        return value;
    }

    /**
     * Weaves {@code AtomicInteger.intValue}, which is {@code get} (#1317).
     *
     * @param count the atomic the call site read
     * @return the atomic's value
     * @since 1.15.0
     */
    public static int intValueAtomicInteger(AtomicInteger count) {
        return getAtomicInteger(count);
    }

    /**
     * Weaves {@code AtomicInteger.longValue}, which is {@code get} widened (#1317).
     *
     * @param count the atomic the call site read
     * @return the atomic's value as a {@code long}
     * @since 1.15.0
     */
    public static long longValueAtomicInteger(AtomicInteger count) {
        return getAtomicInteger(count);
    }

    /**
     * Weaves {@code AtomicInteger.incrementAndGet}, which is never a spinlock release: only
     * the act half of a check-then-act (#1249).
     *
     * @param count the atomic the call site invoked
     * @return the updated value
     * @since 1.14.3
     */
    public static int incrementAndGetAtomicInteger(AtomicInteger count) {
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicIncremented(count);
        return addPublishing(count, 1);
    }

    /**
     * Weaves {@code AtomicInteger.getAndIncrement}, as {@link #incrementAndGetAtomicInteger}.
     *
     * @param count the atomic the call site invoked
     * @return the previous value
     * @since 1.14.3
     */
    public static int getAndIncrementAtomicInteger(AtomicInteger count) {
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicIncremented(count);
        return addPublishing(count, 1) - 1;
    }

    /**
     * Weaves {@code AtomicLong.incrementAndGet}, the act half of a check-then-act (#1249).
     *
     * @param count the atomic the call site invoked
     * @return the updated value
     * @since 1.14.3
     */
    public static long incrementAndGetAtomicLong(AtomicLong count) {
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicIncremented(count);
        return addPublishing(count, 1L);
    }

    /**
     * Weaves {@code AtomicLong.getAndIncrement}, as {@link #incrementAndGetAtomicLong}.
     *
     * @param count the atomic the call site invoked
     * @return the previous value
     * @since 1.14.3
     */
    public static long getAndIncrementAtomicLong(AtomicLong count) {
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicIncremented(count);
        return addPublishing(count, 1L) - 1L;
    }

    /**
     * Weaves {@code AtomicLong.decrementAndGet}, as {@link #incrementAndGetAtomicLong}.
     *
     * @param count the atomic the call site invoked
     * @return the updated value
     * @since 1.14.3
     */
    public static long decrementAndGetAtomicLong(AtomicLong count) {
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicIncremented(count);
        return addPublishing(count, -1L);
    }

    /**
     * Weaves {@code AtomicLong.getAndDecrement}, as {@link #incrementAndGetAtomicLong}.
     *
     * @param count the atomic the call site invoked
     * @return the previous value
     * @since 1.14.3
     */
    public static long getAndDecrementAtomicLong(AtomicLong count) {
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicIncremented(count);
        return addPublishing(count, -1L) + 1L;
    }

    /**
     * Weaves {@code AtomicLong.addAndGet}, as {@link #incrementAndGetAtomicLong}.
     *
     * @param count the atomic the call site invoked
     * @param delta the value to add
     * @return the updated value
     * @since 1.14.3
     */
    public static long addAndGetAtomicLong(AtomicLong count, long delta) {
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicIncremented(count);
        return addPublishing(count, delta);
    }

    /**
     * Weaves {@code AtomicLong.getAndAdd}, as {@link #incrementAndGetAtomicLong}.
     *
     * @param count the atomic the call site invoked
     * @param delta the value to add
     * @return the previous value
     * @since 1.14.3
     */
    public static long getAndAddAtomicLong(AtomicLong count, long delta) {
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicIncremented(count);
        return addPublishing(count, delta) - delta;
    }

    /**
     * Weaves {@code AtomicLong.get}, as {@link #getAtomicInteger}. @param count the atomic
     * @return what the atomic held @since 1.14.1
     */
    public static long getAtomicLong(AtomicLong count) {
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicRead(count);
        long value = count.get();
        HappensBefore.acquireVolatile(count, SLOT_VALUE, value);
        return value;
    }

    /**
     * Weaves {@code AtomicLong.getAcquire}, an acquiring read and the check half of a
     * check-then-act like {@link #getAtomicLong} (#1317).
     *
     * @param count the atomic the call site read
     * @return the atomic's value
     * @since 1.15.0
     */
    public static long getAcquireAtomicLong(AtomicLong count) {
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicRead(count);
        long value = count.getAcquire();
        HappensBefore.acquireVolatile(count, SLOT_VALUE, value);
        return value;
    }

    /**
     * Weaves {@code AtomicLong.longValue}, which is {@code get} (#1317).
     *
     * @param count the atomic the call site read
     * @return the atomic's value
     * @since 1.15.0
     */
    public static long longValueAtomicLong(AtomicLong count) {
        return getAtomicLong(count);
    }

    /**
     * Weaves {@code AtomicLong.intValue}, which is {@code get} narrowed (#1317).
     *
     * @param count the atomic the call site read
     * @return the atomic's value narrowed to an {@code int}, as the JDK's own method does
     * @since 1.15.0
     */
    public static int intValueAtomicLong(AtomicLong count) {
        return (int) getAtomicLong(count);
    }

    /** Weaves {@code AtomicLong.set}. @param count the atomic @param value the value @since 1.14.1 */
    public static void setAtomicLong(AtomicLong count, long value) {
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicStored(count);
        primitiveSlotStoring(count, value);
        count.set(value);
    }

    /**
     * Weaves {@code AtomicLong.set} whose value was not built from an atomic read (#1187), as
     * {@link #setAtomicIntegerUnrelated}.
     *
     * @param count the atomic the call site wrote
     * @param value the value it stored
     */
    public static void setAtomicLongUnrelated(AtomicLong count, long value) {
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        ControlledSchedule.point();
        primitiveSlotStoring(count, value);
        count.set(value);
    }

    /** Weaves {@code AtomicLong.lazySet} whose value was not built from a read (#1187). */
    public static void lazySetAtomicLongUnrelated(AtomicLong count, long value) {
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        primitiveSlotStoring(count, value);
        count.lazySet(value);
    }

    /** Weaves {@code AtomicLong.setRelease} whose value was not built from a read (#1187). */
    public static void setReleaseAtomicLongUnrelated(AtomicLong count, long value) {
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        primitiveSlotStoring(count, value);
        count.setRelease(value);
    }

    /** Weaves {@code AtomicInteger.lazySet} whose value was not built from a read (#1187); 0 releases. */
    public static void lazySetAtomicIntegerUnrelated(AtomicInteger count, int value) {
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        ControlledSchedule.point();
        if (value == 0) {
            SpinLocks.release(count);
        }
        primitiveSlotStoring(count, value);
        count.lazySet(value);
    }

    /** Weaves {@code AtomicInteger.setPlain} whose value was not built from a read (#1187); 0 releases. */
    public static void setPlainAtomicIntegerUnrelated(AtomicInteger count, int value) {
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        ControlledSchedule.point();
        if (value == 0) {
            SpinLocks.release(count);
        }
        count.setPlain(value);
    }

    /** Weaves {@code AtomicInteger.setOpaque} whose value was not built from a read (#1187); 0 releases. */
    public static void setOpaqueAtomicIntegerUnrelated(AtomicInteger count, int value) {
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        ControlledSchedule.point();
        if (value == 0) {
            SpinLocks.release(count);
        }
        count.setOpaque(value);
    }

    /** Weaves {@code AtomicInteger.setRelease} whose value was not built from a read (#1187); 0 releases. */
    public static void setReleaseAtomicIntegerUnrelated(AtomicInteger count, int value) {
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        ControlledSchedule.point();
        if (value == 0) {
            SpinLocks.release(count);
        }
        primitiveSlotStoring(count, value);
        count.setRelease(value);
    }

    /** Weaves {@code AtomicLong.lazySet}. @param count the atomic @param value the value @since 1.14.1 */
    public static void lazySetAtomicLong(AtomicLong count, long value) {
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicStored(count);
        primitiveSlotStoring(count, value);
        count.lazySet(value);
    }

    /** Weaves {@code AtomicLong.setRelease}. @param count the atomic @param value the value @since 1.14.1 */
    public static void setReleaseAtomicLong(AtomicLong count, long value) {
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicStored(count);
        primitiveSlotStoring(count, value);
        count.setRelease(value);
    }

    /**
     * {@link #updatePublishing(AtomicInteger, IntUnaryOperator, boolean)} for an {@code AtomicLong}
     * (#1317): the value about to be stored is released before the swap, and a lost attempt
     * withdraws its release.
     *
     * @return the previous value when {@code previous}, else the updated one
     */
    private static long updatePublishing(AtomicLong count, LongUnaryOperator function, boolean previous) {
        for (;;) {
            long seen = count.get();
            long next = function.applyAsLong(seen);
            primitiveSlotStoring(count, next);
            if (count.compareAndSet(seen, next)) {
                return previous ? seen : next;
            }
            HappensBefore.withdrawVolatile(count, SLOT_VALUE);
        }
    }

    /**
     * Weaves {@code AtomicLong.getAndSet}: the store releases and the previous value acquires
     * what the store of it published, as {@link #getAndSetAtomicInteger} does (#1317).
     *
     * @param count the atomic the call site invoked
     * @param value the value to store
     * @return the previous value
     * @since 1.15.0
     */
    public static long getAndSetAtomicLong(AtomicLong count, long value) {
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        primitiveSlotStoring(count, value);
        long previous = count.getAndSet(value);
        HappensBefore.acquireVolatile(count, SLOT_VALUE, previous);
        return previous;
    }

    /**
     * Weaves {@code AtomicLong.compareAndExchange}: the witness acquires, and a won exchange
     * releases what it stored (#1317).
     *
     * @param count    the atomic the call site invoked
     * @param expected the value it must hold
     * @param update   the value to store
     * @return the witness value
     * @since 1.15.0
     */
    public static long compareAndExchangeAtomicLong(AtomicLong count, long expected, long update) {
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        long witness = count.compareAndExchange(expected, update);
        HappensBefore.acquireVolatile(count, SLOT_VALUE, witness);
        if (witness == expected) {
            primitiveSlotStoring(count, update);
        }
        return witness;
    }

    /**
     * Weaves {@code AtomicLong.compareAndExchangeAcquire}: the witness acquires; the store has no
     * release semantics and publishes nothing (#1317).
     *
     * @param count    the atomic the call site invoked
     * @param expected the value it must hold
     * @param update   the value to store
     * @return the witness value
     * @since 1.15.0
     */
    public static long compareAndExchangeAcquireAtomicLong(AtomicLong count, long expected, long update) {
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        long witness = count.compareAndExchangeAcquire(expected, update);
        HappensBefore.acquireVolatile(count, SLOT_VALUE, witness);
        return witness;
    }

    /**
     * Weaves {@code AtomicLong.compareAndExchangeRelease}: a won exchange releases what it
     * stored; the witness has no acquire semantics and receives nothing (#1317).
     *
     * @param count    the atomic the call site invoked
     * @param expected the value it must hold
     * @param update   the value to store
     * @return the witness value
     * @since 1.15.0
     */
    public static long compareAndExchangeReleaseAtomicLong(AtomicLong count, long expected, long update) {
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        long witness = count.compareAndExchangeRelease(expected, update);
        if (witness == expected) {
            primitiveSlotStoring(count, update);
        }
        return witness;
    }

    /**
     * Weaves {@code AtomicLong.weakCompareAndSetAcquire}: a swap with acquire semantics only,
     * which publishes nothing; woven so the check-then-act rule sees the swap (#1317).
     *
     * @param count    the atomic the call site invoked
     * @param expected the value it must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.15.0
     */
    public static boolean weakCompareAndSetAcquireAtomicLong(AtomicLong count, long expected, long update) {
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        return count.weakCompareAndSetAcquire(expected, update);
    }

    /**
     * Weaves {@code AtomicLong.weakCompareAndSetRelease}: a won swap releases what it stored (#1317).
     *
     * @param count    the atomic the call site invoked
     * @param expected the value it must hold
     * @param update   the value to store
     * @return whether the swap happened
     * @since 1.15.0
     */
    public static boolean weakCompareAndSetReleaseAtomicLong(AtomicLong count, long expected, long update) {
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        boolean won = count.weakCompareAndSetRelease(expected, update);
        if (won) {
            primitiveSlotStoring(count, update);
        }
        return won;
    }

    /**
     * Weaves {@code AtomicLong.getAndUpdate} as a compare-and-set loop that releases each value
     * before it stores it (#1317).
     *
     * @param count    the atomic the call site invoked
     * @param function the update function, applied again after a lost swap as the JDK's own loop does
     * @return the previous value
     * @since 1.15.0
     */
    public static long getAndUpdateAtomicLong(AtomicLong count, LongUnaryOperator function) {
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        return updatePublishing(count, function, true);
    }

    /**
     * Weaves {@code AtomicLong.updateAndGet}, as {@link #getAndUpdateAtomicLong} (#1317).
     *
     * @param count    the atomic the call site invoked
     * @param function the update function, applied again after a lost swap as the JDK's own loop does
     * @return the updated value
     * @since 1.15.0
     */
    public static long updateAndGetAtomicLong(AtomicLong count, LongUnaryOperator function) {
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        return updatePublishing(count, function, false);
    }

    /**
     * Weaves {@code AtomicLong.getAndAccumulate}, as {@link #getAndUpdateAtomicLong} (#1317).
     *
     * @param count    the atomic the call site invoked
     * @param operand  the function's second argument
     * @param function the accumulator, applied to the current value and {@code operand}
     * @return the previous value
     * @since 1.15.0
     */
    public static long getAndAccumulateAtomicLong(AtomicLong count, long operand, LongBinaryOperator function) {
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        return updatePublishing(count, c -> function.applyAsLong(c, operand), true);
    }

    /**
     * Weaves {@code AtomicLong.accumulateAndGet}, as {@link #getAndUpdateAtomicLong} (#1317).
     *
     * @param count    the atomic the call site invoked
     * @param operand  the function's second argument
     * @param function the accumulator, applied to the current value and {@code operand}
     * @return the updated value
     * @since 1.15.0
     */
    public static long accumulateAndGetAtomicLong(AtomicLong count, long operand, LongBinaryOperator function) {
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        return updatePublishing(count, c -> function.applyAsLong(c, operand), false);
    }

    /**
     * Weaves {@code AtomicLong.compareAndSet}. @param count the atomic @param expected the expected
     * value @param update the new value @return whether it swapped @since 1.14.1
     */
    public static boolean compareAndSetAtomicLong(AtomicLong count, long expected, long update) {
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        boolean won = count.compareAndSet(expected, update);
        if (won) {
            primitiveSlotStoring(count, update);
        }
        return won;
    }

    /**
     * Weaves {@code AtomicLong.weakCompareAndSetPlain}. @param count the atomic @param expected the
     * expected value @param update the new value @return whether it swapped @since 1.14.1
     */
    public static boolean weakCompareAndSetPlainAtomicLong(AtomicLong count, long expected, long update) {
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        return count.weakCompareAndSetPlain(expected, update);
    }

    /**
     * Weaves {@code AtomicLong.weakCompareAndSetVolatile}. @param count the atomic @param expected
     * the expected value @param update the new value @return whether it swapped @since 1.14.1
     */
    public static boolean weakCompareAndSetVolatileAtomicLong(AtomicLong count, long expected, long update) {
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        boolean won = count.weakCompareAndSetVolatile(expected, update);
        if (won) {
            primitiveSlotStoring(count, update);
        }
        return won;
    }

    /** Weaves {@code AtomicInteger.set}; storing 0 releases. @param count the atomic @param value the value @since 1.12.1 */
    public static void setAtomicInteger(AtomicInteger count, int value) {
        AgentConcurrencyUtilHooks.atomicStored(count);
        ControlledSchedule.point();
        if (value == 0) {
            SpinLocks.release(count);
        }
        primitiveSlotStoring(count, value);
        count.set(value);
    }

    /**
     * Weaves {@code AtomicInteger.set} whose value was not built from an atomic read (#1187): the
     * store cannot lose an update a read guarded, so it is an update with no read before it, which
     * is {@link #compareAndSetAtomicInteger}'s shape, not a plain store. Still a release on 0.
     *
     * @param count the atomic the call site wrote
     * @param value the value it stored
     */
    public static void setAtomicIntegerUnrelated(AtomicInteger count, int value) {
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        ControlledSchedule.point();
        if (value == 0) {
            SpinLocks.release(count);
        }
        primitiveSlotStoring(count, value);
        count.set(value);
    }

    /** Weaves {@code AtomicInteger.lazySet}; storing 0 releases. @param count the atomic @param value the value @since 1.12.1 */
    public static void lazySetAtomicInteger(AtomicInteger count, int value) {
        AgentConcurrencyUtilHooks.atomicStored(count);
        ControlledSchedule.point();
        if (value == 0) {
            SpinLocks.release(count);
        }
        primitiveSlotStoring(count, value);
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
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, SpinLocks.fieldOf(handle));
        ControlledSchedule.point();
        handleStoring(handle, receiver, value);
        int previous = (int) handle.withInvokeBehavior().getAndSet(receiver, value);
        handleLoaded(handle, receiver, previous);
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
        AgentConcurrencyUtilHooks.updaterIncremented(receiver, SpinLocks.fieldOf(handle));
        ControlledSchedule.point();
        String field = receiver == null ? null : SpinLocks.fieldOf(handle);
        int previous = field == null
                ? (int) handle.withInvokeBehavior().getAndAdd(receiver, delta)
                : handleUpdatePublishing(handle, receiver, field, HANDLE_ADD, delta);
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
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, SpinLocks.fieldOf(handle));
        ControlledSchedule.point();
        int witness = (int) handle.withInvokeBehavior().compareAndExchange(receiver, expected, update);
        handleLoaded(handle, receiver, witness);
        if (witness == expected) {
            handleStoring(handle, receiver, update);
        }
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
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, SpinLocks.fieldOf(handle));
        ControlledSchedule.point();
        boolean won = handle.withInvokeBehavior().weakCompareAndSet(receiver, expected, update);
        if (won) {
            handleStoring(handle, receiver, update);
        }
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
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, SpinLocks.fieldOf(handle));
        ControlledSchedule.point();
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
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, SpinLocks.fieldOf(updater));
        ControlledSchedule.point();
        String field = SpinLocks.fieldOf(updater);
        updaterStoring(receiver, field, value);
        int previous = erased(updater).getAndSet(receiver, value);
        updaterLoaded(receiver, field, previous);
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
        AgentConcurrencyUtilHooks.updaterIncremented(receiver, SpinLocks.fieldOf(updater));
        ControlledSchedule.point();
        int previous = updaterAddPublishing(erased(updater), receiver, SpinLocks.fieldOf(updater), delta, true);
        if (previous + delta != 1) {
            releaseIfSpinLockField(updater, receiver);
        }
        return previous;
    }

    /**
     * Weaves {@code AtomicIntegerFieldUpdater.get}, a volatile read and the check half of a
     * check-then-act on the field, as {@link #getAtomicInteger} is on an atomic (#1275).
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field is read
     * @return the field's value
     * @since 1.15.0
     */
    public static int getIntUpdater(AtomicIntegerFieldUpdater<?> updater, Object receiver) {
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.updaterRead(receiver, SpinLocks.fieldOf(updater));
        int value = erased(updater).get(receiver);
        updaterLoaded(receiver, SpinLocks.fieldOf(updater), value);
        return value;
    }

    /**
     * Weaves {@code AtomicIntegerFieldUpdater.incrementAndGet}, the act half of a check-then-act
     * on the field (#1275); a result other than 1 releases, as {@link #addAndGetIntUpdater}.
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field is written
     * @return the updated value
     * @since 1.15.0
     */
    public static int incrementAndGetIntUpdater(AtomicIntegerFieldUpdater<?> updater, Object receiver) {
        AgentConcurrencyUtilHooks.updaterIncremented(receiver, SpinLocks.fieldOf(updater));
        ControlledSchedule.point();
        int current = updaterAddPublishing(erased(updater), receiver, SpinLocks.fieldOf(updater), 1, false);
        if (current != 1) {
            releaseIfSpinLockField(updater, receiver);
        }
        return current;
    }

    /**
     * Weaves {@code AtomicIntegerFieldUpdater.getAndIncrement}, as
     * {@link #incrementAndGetIntUpdater} (#1275).
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field is written
     * @return the previous value
     * @since 1.15.0
     */
    public static int getAndIncrementIntUpdater(AtomicIntegerFieldUpdater<?> updater, Object receiver) {
        AgentConcurrencyUtilHooks.updaterIncremented(receiver, SpinLocks.fieldOf(updater));
        ControlledSchedule.point();
        int previous = updaterAddPublishing(erased(updater), receiver, SpinLocks.fieldOf(updater), 1, true);
        if (previous + 1 != 1) {
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
        AgentConcurrencyUtilHooks.updaterIncremented(receiver, SpinLocks.fieldOf(updater));
        ControlledSchedule.point();
        int current = updaterAddPublishing(erased(updater), receiver, SpinLocks.fieldOf(updater), delta, false);
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
        AgentConcurrencyUtilHooks.updaterIncremented(receiver, SpinLocks.fieldOf(updater));
        ControlledSchedule.point();
        int previous = updaterAddPublishing(erased(updater), receiver, SpinLocks.fieldOf(updater), -1, true);
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
        AgentConcurrencyUtilHooks.updaterIncremented(receiver, SpinLocks.fieldOf(updater));
        ControlledSchedule.point();
        int current = updaterAddPublishing(erased(updater), receiver, SpinLocks.fieldOf(updater), -1, false);
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
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, SpinLocks.fieldOf(updater));
        ControlledSchedule.point();
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
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        ControlledSchedule.point();
        primitiveSlotStoring(count, value);
        int previous = count.getAndSet(value);
        HappensBefore.acquireVolatile(count, SLOT_VALUE, previous);
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
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicIncremented(count);
        int previous = addPublishing(count, delta) - delta;
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
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicIncremented(count);
        int current = addPublishing(count, delta);
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
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicIncremented(count);
        int previous = addPublishing(count, -1) + 1;
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
        ControlledSchedule.point();
        AgentConcurrencyUtilHooks.atomicIncremented(count);
        int current = addPublishing(count, -1);
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
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        ControlledSchedule.point();
        int witness = count.compareAndExchange(expected, update);
        HappensBefore.acquireVolatile(count, SLOT_VALUE, witness);
        if (witness == expected) {
            primitiveSlotStoring(count, update);
        }
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
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        ControlledSchedule.point();
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
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        ControlledSchedule.point();
        boolean won = count.weakCompareAndSetVolatile(expected, update);
        if (won) {
            primitiveSlotStoring(count, update);
        }
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
        ControlledSchedule.point();
        boolean witness = flag.compareAndExchange(expected, update);
        HappensBefore.acquireVolatile(flag, SLOT_VALUE, witness ? 1 : 0);
        if (witness == expected) {
            primitiveSlotStoring(flag, update ? 1 : 0);
        }
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
        ControlledSchedule.point();
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
        ControlledSchedule.point();
        boolean won = flag.weakCompareAndSetVolatile(expected, update);
        if (won) {
            primitiveSlotStoring(flag, update ? 1 : 0);
        }
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
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, SpinLocks.fieldOf(handle));
        ControlledSchedule.point();
        int previous = (int) handle.withInvokeBehavior().getAndSetAcquire(receiver, value);
        handleLoaded(handle, receiver, previous);
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
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, SpinLocks.fieldOf(handle));
        ControlledSchedule.point();
        handleStoring(handle, receiver, value);
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
        AgentConcurrencyUtilHooks.updaterIncremented(receiver, SpinLocks.fieldOf(handle));
        ControlledSchedule.point();
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
        AgentConcurrencyUtilHooks.updaterIncremented(receiver, SpinLocks.fieldOf(handle));
        ControlledSchedule.point();
        String field = receiver == null ? null : SpinLocks.fieldOf(handle);
        int previous = field == null
                ? (int) handle.withInvokeBehavior().getAndAddRelease(receiver, delta)
                : handleUpdatePublishing(handle, receiver, field, HANDLE_ADD, delta);
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
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, SpinLocks.fieldOf(handle));
        ControlledSchedule.point();
        int witness = (int) handle.withInvokeBehavior()
                .compareAndExchangeAcquire(receiver, expected, update);
        handleLoaded(handle, receiver, witness);
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
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, SpinLocks.fieldOf(handle));
        ControlledSchedule.point();
        int witness = (int) handle.withInvokeBehavior()
                .compareAndExchangeRelease(receiver, expected, update);
        if (witness == expected) {
            handleStoring(handle, receiver, update);
        }
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
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, SpinLocks.fieldOf(handle));
        ControlledSchedule.point();
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
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, SpinLocks.fieldOf(handle));
        ControlledSchedule.point();
        boolean won = handle.withInvokeBehavior().weakCompareAndSetRelease(receiver, expected, update);
        if (won) {
            handleStoring(handle, receiver, update);
        }
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
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, SpinLocks.fieldOf(handle));
        ControlledSchedule.point();
        String field = receiver == null ? null : SpinLocks.fieldOf(handle);
        int previous = field == null
                ? (int) handle.withInvokeBehavior().getAndBitwiseOr(receiver, mask)
                : handleUpdatePublishing(handle, receiver, field, HANDLE_OR, mask);
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
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, SpinLocks.fieldOf(handle));
        ControlledSchedule.point();
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
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, SpinLocks.fieldOf(handle));
        ControlledSchedule.point();
        String field = receiver == null ? null : SpinLocks.fieldOf(handle);
        int previous = field == null
                ? (int) handle.withInvokeBehavior().getAndBitwiseOrRelease(receiver, mask)
                : handleUpdatePublishing(handle, receiver, field, HANDLE_OR, mask);
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
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, SpinLocks.fieldOf(handle));
        ControlledSchedule.point();
        String field = receiver == null ? null : SpinLocks.fieldOf(handle);
        int previous = field == null
                ? (int) handle.withInvokeBehavior().getAndBitwiseAnd(receiver, mask)
                : handleUpdatePublishing(handle, receiver, field, HANDLE_AND, mask);
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
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, SpinLocks.fieldOf(handle));
        ControlledSchedule.point();
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
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, SpinLocks.fieldOf(handle));
        ControlledSchedule.point();
        String field = receiver == null ? null : SpinLocks.fieldOf(handle);
        int previous = field == null
                ? (int) handle.withInvokeBehavior().getAndBitwiseAndRelease(receiver, mask)
                : handleUpdatePublishing(handle, receiver, field, HANDLE_AND, mask);
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
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, SpinLocks.fieldOf(handle));
        ControlledSchedule.point();
        String field = receiver == null ? null : SpinLocks.fieldOf(handle);
        int previous = field == null
                ? (int) handle.withInvokeBehavior().getAndBitwiseXor(receiver, mask)
                : handleUpdatePublishing(handle, receiver, field, HANDLE_XOR, mask);
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
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, SpinLocks.fieldOf(handle));
        ControlledSchedule.point();
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
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, SpinLocks.fieldOf(handle));
        ControlledSchedule.point();
        String field = receiver == null ? null : SpinLocks.fieldOf(handle);
        int previous = field == null
                ? (int) handle.withInvokeBehavior().getAndBitwiseXorRelease(receiver, mask)
                : handleUpdatePublishing(handle, receiver, field, HANDLE_XOR, mask);
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
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, SpinLocks.fieldOf(updater));
        ControlledSchedule.point();
        AtomicIntegerFieldUpdater<Object> target = erased(updater);
        int previous = updaterUpdatePublishing(target, receiver, SpinLocks.fieldOf(updater), function, true);
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
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, SpinLocks.fieldOf(updater));
        ControlledSchedule.point();
        int current = updaterUpdatePublishing(erased(updater), receiver, SpinLocks.fieldOf(updater), function, false);
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
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, SpinLocks.fieldOf(updater));
        ControlledSchedule.point();
        AtomicIntegerFieldUpdater<Object> target = erased(updater);
        int previous = updaterUpdatePublishing(target, receiver, SpinLocks.fieldOf(updater),
                c -> function.applyAsInt(c, operand), true);
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
        AgentConcurrencyUtilHooks.updaterSwapped(receiver, SpinLocks.fieldOf(updater));
        ControlledSchedule.point();
        int current = updaterUpdatePublishing(erased(updater), receiver, SpinLocks.fieldOf(updater),
                c -> function.applyAsInt(c, operand), false);
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
        AgentConcurrencyUtilHooks.atomicStored(count);
        ControlledSchedule.point();
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
        AgentConcurrencyUtilHooks.atomicStored(count);
        ControlledSchedule.point();
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
        AgentConcurrencyUtilHooks.atomicStored(count);
        ControlledSchedule.point();
        if (value == 0) {
            SpinLocks.release(count);
        }
        primitiveSlotStoring(count, value);
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
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        ControlledSchedule.point();
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
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        ControlledSchedule.point();
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
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        ControlledSchedule.point();
        boolean won = count.weakCompareAndSetRelease(expected, update);
        if (won) {
            primitiveSlotStoring(count, update);
        }
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
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        ControlledSchedule.point();
        int witness = count.compareAndExchangeAcquire(expected, update);
        HappensBefore.acquireVolatile(count, SLOT_VALUE, witness);
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
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        ControlledSchedule.point();
        int witness = count.compareAndExchangeRelease(expected, update);
        if (witness == expected) {
            primitiveSlotStoring(count, update);
        }
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
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        ControlledSchedule.point();
        int previous = updatePublishing(count, function, true);
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
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        ControlledSchedule.point();
        int current = updatePublishing(count, function, false);
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
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        ControlledSchedule.point();
        int previous = updatePublishing(count, c -> function.applyAsInt(c, operand), true);
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
        AgentConcurrencyUtilHooks.atomicSwapped(count);
        ControlledSchedule.point();
        int current = updatePublishing(count, c -> function.applyAsInt(c, operand), false);
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
        ControlledSchedule.point();
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
        ControlledSchedule.point();
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
        ControlledSchedule.point();
        if (!value) {
            SpinLocks.release(flag);
        }
        primitiveSlotStoring(flag, value ? 1 : 0);
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
        ControlledSchedule.point();
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
        ControlledSchedule.point();
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
        ControlledSchedule.point();
        boolean won = flag.weakCompareAndSetRelease(expected, update);
        if (won) {
            primitiveSlotStoring(flag, update ? 1 : 0);
        }
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
        ControlledSchedule.point();
        boolean witness = flag.compareAndExchangeAcquire(expected, update);
        HappensBefore.acquireVolatile(flag, SLOT_VALUE, witness ? 1 : 0);
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
        ControlledSchedule.point();
        boolean witness = flag.compareAndExchangeRelease(expected, update);
        if (witness == expected) {
            primitiveSlotStoring(flag, update ? 1 : 0);
        }
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
     * <p>Emitted by the weaver after a reference {@code getAndSet} on a {@code VarHandle} that
     * reaches a static field or an array element, where no container is on the stack. Every other
     * take names its container through {@link #ownershipTaken(Object, Object)}. The value that came
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
     * object there (#630). The reference-slot {@code getAndSet} hooks pass the slot, and the woven
     * JCTools {@code poll}/{@code relaxedPoll} passes its queue (#664); only a {@code VarHandle}
     * {@code getAndSet} on a static field or an array element passes none.
     *
     * <p>A container that orders nothing itself, an {@code ArrayDeque} (see {@link #ordersNothing}),
     * hands an element over only under a lock its callers share, so its take is flagged in the
     * event's write slot and carries the locks this thread holds; the validator drops the edge when
     * those and the matching offer's locks share no member, an empty set sharing none (#751).
     *
     * @param taken     the object that left the queue, or {@code null}
     * @param container the queue it left, or {@code null} when unknown
     * @since 1.12.1
     */
    public static void ownershipTaken(@Nullable Object taken, @Nullable Object container) {
        ownershipTaken(taken, container, null);
    }

    /**
     * {@link #ownershipTaken(Object, Object)} with the enclosing method's own monitor in hand.
     *
     * <p>A {@code synchronized} method's monitor comes from its access flag, with no instruction
     * for {@link HeldLocks} to see, so the weaver passes it to the queue hooks and it joins the
     * locks a take from a container that orders nothing carries (#796). Without it a
     * {@code synchronized}-method pool would read as unguarded. Any other instance method passes
     * its {@code this} as well, which counts only when this thread holds it: a
     * {@code synchronized} method that polls through a helper holds it there too (#751).
     *
     * @param taken     the object that left the queue, or {@code null}
     * @param container the queue it left, or {@code null} when unknown
     * @param monitor   the enclosing method's own monitor, counted when this thread holds it;
     *                  {@code null} for none
     * @since 1.12.3
     */
    public static void ownershipTaken(@Nullable Object taken, @Nullable Object container,
                                      @Nullable Object monitor) {
        if (taken == null) {
            return;
        }
        // Synchronous, on the taking thread, ahead of the buffer: the lock-aware detectors record
        // on the accessing thread as it runs, so the take must be visible to them before this
        // thread's next access rather than whenever the ring drains. Allocation-free unless the
        // run tracks some instance; see SelfGuard.Scope.ownershipTaken.
        SelfGuard.Scope.ownershipTaken(taken);
        // The acquire half of a hand-off, where the container's contract makes one (HappensBefore).
        // Before the STOPPED check, which guards only the ring below (#891).
        if (HappensBefore.publishesElements(container)) {
            HappensBefore.acquire(taken);
        }
        if (STOPPED.get()) {
            return;
        }
        boolean ordersNothing = ordersNothing(container);
        BUFFER.publish(Thread.currentThread().threadId(), OWNERSHIP_TAKEN, ordersNothing,
                ordersNothing ? HeldLocks.registeredLockFingerprint(ifHeld(monitor), true) : 0L, false,
                Integer.MIN_VALUE, System.identityHashCode(taken), false, 0, 0,
                container == null ? 0 : System.identityHashCode(container));
    }

    /**
     * The target an event carries when a thread starts a {@code StampedLock} optimistic read; see
     * {@link #OWNERSHIP_TAKEN} for why a reserved name (#740).
     */
    static final String OPTIMISTIC_READ_STARTED = "#optimistic-read";

    /**
     * The target an event carries when a thread's {@code StampedLock.validate} answered, with the
     * answer in the write slot (#740).
     */
    static final String OPTIMISTIC_READ_VALIDATED = "#optimistic-validated";

    /**
     * The target an event carries when a thread discarded the answer of the {@code validate} it
     * just made, which had refuted its speculation (#823).
     */
    static final String OPTIMISTIC_READ_IGNORED = "#optimistic-ignored";

    /**
     * Records that the calling thread discarded the answer of its last {@code validate}, which was
     * {@code false} (#823). The drain side then delivers that speculation's reads as plain reads:
     * they may be torn, and the caller used them anyway. Allocation-free and non-throwing.
     *
     * @since 1.12.4
     */
    public static void optimisticReadIgnored() {
        if (STOPPED.get()) {
            return;
        }
        BUFFER.publish(Thread.currentThread().threadId(), OPTIMISTIC_READ_IGNORED, false, 0L, false,
                Integer.MIN_VALUE, 0, false, 0, 0, 0);
    }

    /**
     * Records that the calling thread took an optimistic stamp from {@code lock} (#740).
     *
     * <p>Nothing is held: a {@code StampedLock} optimistic read is a speculation that
     * {@link #optimisticReadValidated} later confirms or refutes. The drain side holds this thread's
     * reads from here until that answer arrives, so the start is published after the stamp is
     * taken and before any read it covers. The lock's identity rides in the identity slot.
     *
     * <p>Allocation-free and non-throwing like every other hook on this path.
     *
     * @param lock the lock the stamp came from, or {@code null}
     * @since 1.12.3
     */
    public static void optimisticReadStarted(@Nullable Object lock) {
        if (lock == null || STOPPED.get()) {
            return;
        }
        BUFFER.publish(Thread.currentThread().threadId(), OPTIMISTIC_READ_STARTED, false, 0L, false,
                Integer.MIN_VALUE, System.identityHashCode(lock), false, 0, 0, 0);
    }

    /**
     * Records what the calling thread's {@code validate} on {@code lock} answered (#740).
     *
     * <p>Published after the answer, so it drains after every read of the speculation it closes.
     * {@code true} means no write lock was taken since the stamp, and the reads in between saw
     * what a reader holding the lock in shared mode would have seen; {@code false} means the
     * caller discards what it read.
     *
     * @param lock  the lock that was asked, or {@code null}
     * @param valid what {@code validate} returned
     * @since 1.12.3
     */
    public static void optimisticReadValidated(@Nullable Object lock, boolean valid) {
        if (lock == null || STOPPED.get()) {
            return;
        }
        BUFFER.publish(Thread.currentThread().threadId(), OPTIMISTIC_READ_VALIDATED, valid, 0L, false,
                Integer.MIN_VALUE, System.identityHashCode(lock), false, 0, 0, 0);
    }

    /**
     * The target an ownership-offered event carries; see {@link #OWNERSHIP_TAKEN}.
     */
    static final String OWNERSHIP_OFFERED = "#ownership-offered";

    /**
     * Records that the calling thread is about to offer {@code offered} to {@code container}.
     *
     * <p>Emitted by the queue hooks, the reference-slot store hooks and the woven JCTools
     * {@code offer}/{@code relaxedOffer} (#664) before the structure is asked to accept the element, never
     * after: the slot this claims in the buffer is then ahead of any take that removes the element,
     * so the drain always sees the offer first. That order is what lets a take that is the first
     * event recorded for an object name the thread that handed it over (#630). An offer the queue
     * rejects still records, and is harmless: nothing takes that element out of that queue. An offer
     * to a container that orders nothing is flagged and carries this thread's locks, like the
     * {@link #ownershipTaken(Object, Object) take} it has to be matched with (#751).
     * A rejected offer's happens-before release is not harmless, and the hook withdraws it with
     * {@link #ownershipRefused} (#742).
     *
     * <p>Allocation-free and non-throwing like every other hook on this path.
     *
     * @param offered   the element being offered, or {@code null}
     * @param container the queue it is offered to
     * @since 1.12.1
     */
    public static void ownershipOffered(@Nullable Object offered, @Nullable Object container) {
        ownershipOffered(offered, container, null);
    }

    /**
     * {@link #ownershipOffered(Object, Object)} with the enclosing method's own monitor in hand,
     * which joins the locks the offer carries when held; see
     * {@link #ownershipTaken(Object, Object, Object)} (#796).
     *
     * @param offered   the element being offered, or {@code null}
     * @param container the queue it is offered to
     * @param monitor   the enclosing method's own monitor, counted when this thread holds it;
     *                  {@code null} for none
     * @since 1.12.3
     */
    public static void ownershipOffered(@Nullable Object offered, @Nullable Object container,
                                        @Nullable Object monitor) {
        if (offered == null || container == null) {
            return;
        }
        // The release half: published before the container accepts the element, so every take
        // that can return it finds the release. Not behind STOPPED, which guards the ring below:
        // the model is in memory and needs no drain (#891).
        if (HappensBefore.publishesElements(container)) {
            HappensBefore.release(offered);
        }
        if (STOPPED.get()) {
            return;
        }
        boolean ordersNothing = ordersNothing(container);
        BUFFER.publish(Thread.currentThread().threadId(), OWNERSHIP_OFFERED, ordersNothing,
                ordersNothing ? HeldLocks.registeredLockFingerprint(ifHeld(monitor), true) : 0L, false,
                Integer.MIN_VALUE, System.identityHashCode(offered), false, 0, 0,
                System.identityHashCode(container));
    }

    /**
     * {@return {@code monitor} when this thread holds it, else {@code null}}
     *
     * <p>Inside a {@code synchronized} method the answer is yes by construction. Elsewhere the
     * weaver passes {@code this} on the chance that a {@code synchronized} caller holds it (#751),
     * and only asking tells the two apart. Asked only for a container that orders nothing.
     */
    private static @Nullable Object ifHeld(@Nullable Object monitor) {
        return monitor != null && Thread.holdsLock(monitor) ? monitor : null;
    }

    /**
     * {@return whether {@code container} is a {@code java.util} collection that promises no thread
     * safety, so passing an element through it is a hand-off only under a lock} (#751)
     *
     * <p>A take is an ownership edge because the structure hands the element to the taker and to
     * no other. An {@code ArrayDeque}, a {@code LinkedList} or a {@code PriorityQueue} promises that
     * only while its callers serialise it: shared with no lock, two threads can poll the same
     * element. Only the JDK's own unsynchronized collections are known to be such; a
     * {@code java.util.concurrent} type, a synchronized wrapper, and anything outside
     * {@code java.util}, a JCTools queue or a reference slot, keep their unconditional edge. The
     * JDK types are listed as {@code java.util} types that {@link HappensBefore#publishesElements}
     * rejects. {@link Class#getName()} is cached, so this allocates nothing.
     */
    private static boolean ordersNothing(@Nullable Object container) {
        return container != null
                && container.getClass().getName().startsWith("java.util.")
                && !HappensBefore.publishesElements(container);
    }

    /**
     * Records that {@code container} refused {@code offered}, right after the offer
     * {@link #ownershipOffered} announced: a bounded queue was full, a {@code compareAndSet} found
     * another value, a blocking offer was interrupted.
     *
     * <p>The offered event stays, as harmless as it always was. What goes is the happens-before
     * release published ahead of the offer: a refused offer hands nothing over, and leaving the
     * release in place would order whoever later acquires the element after this thread, hiding a
     * race (#742). Call it on the offering thread, before it records anything else.
     *
     * @param offered   the element that was refused, or {@code null}
     * @param container the container that refused it
     * @since 1.12.3
     */
    public static void ownershipRefused(@Nullable Object offered, @Nullable Object container) {
        if (offered != null && container != null && HappensBefore.publishesElements(container)) {
            HappensBefore.retract(offered);
        }
    }

    // ---- Reference slots: offers and takes with their container (#664) -------------------------
    //
    // A reference slot is a container of one: an AtomicReference, an AtomicReferenceArray element,
    // or a reference field reached through an AtomicReferenceFieldUpdater or a VarHandle. The
    // weaver substitutes these hooks for the call sites, so the slot is in hand at both ends: a
    // store publishes the stored reference as offered to the slot before it lands, and a getAndSet
    // publishes the reference it returns as taken out of the same slot. The container is the atomic
    // itself for an AtomicReference. For the other three the holder alone is too coarse (#692): two
    // reference fields of one object, or two elements of one array, would read as one container,
    // and an offer into one would name the owner of an object taken out of its sibling. So the
    // holder is combined with what selects the slot inside it, which both ends have in hand: the
    // updater or the VarHandle, one instance per field, or the element's index. A slot reached
    // through two different handles reads as two containers, which can only lose a match and fall
    // back to the #557 excuse. Each hook performs the original operation with its own exceptions
    // and result. On a thread whose test has an ABAProblemDetector, an AtomicReference hook
    // performs it through the detector's view of that atomic, which records it inside the same
    // lock (#817).

    /** {@return the container identity of the slot {@code within} selects inside {@code holder}} */
    private static int slotIdentity(Object holder, int within) {
        int identity = 31 * System.identityHashCode(holder) + within;
        return identity == 0 ? 1 : identity; // 0 means unknown to the validator
    }

    /** {@link #ownershipOffered} for a slot inside {@code holder}, keyed by field or index. */
    private static void slotOffered(@Nullable Object offered, @Nullable Object holder, int within) {
        if (offered == null || holder == null || STOPPED.get()) {
            return;
        }
        BUFFER.publish(Thread.currentThread().threadId(), OWNERSHIP_OFFERED, false, 0L, false,
                Integer.MIN_VALUE, System.identityHashCode(offered), false, 0, 0,
                slotIdentity(holder, within));
    }

    /** {@link #ownershipTaken(Object, Object)} for a slot inside {@code holder}. */
    private static void slotTaken(@Nullable Object taken, @Nullable Object holder, int within) {
        if (taken == null || STOPPED.get()) {
            return;
        }
        BUFFER.publish(Thread.currentThread().threadId(), OWNERSHIP_TAKEN, false, 0L, false,
                Integer.MIN_VALUE, System.identityHashCode(taken), false, 0, 0,
                holder == null ? 0 : slotIdentity(holder, within));
    }

    /**
     * Weaves {@code AtomicReference.set}: an offer of {@code value} to {@code slot} (#664),
     * and a release to a get that returns it (#741).
     *
     * @param slot  the atomic the call site invoked
     * @param value the reference to store
     * @since 1.12.2
     */
    public static void setAtomicReference(AtomicReference<Object> slot, @Nullable Object value) {
        AgentConcurrencyUtilHooks.atomicReferenceStored(slot, value);
        ControlledSchedule.point();
        ownershipOffered(value, slot);
        slotStored(slot, value);
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(slot);
        if (aba == null) {
            slot.set(value);
        } else {
            aba.set(slot, value);
        }
    }

    /**
     * Weaves {@code AtomicReference.lazySet}: an offer of {@code value} to {@code slot} (#664),
     * and a release to a get that returns it (#741).
     *
     * @param slot  the atomic the call site invoked
     * @param value the reference to store
     * @since 1.12.2
     */
    public static void lazySetAtomicReference(AtomicReference<Object> slot, @Nullable Object value) {
        AgentConcurrencyUtilHooks.atomicReferenceStored(slot, value);
        ControlledSchedule.point();
        ownershipOffered(value, slot);
        slotStored(slot, value);
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(slot);
        if (aba == null) {
            slot.lazySet(value);
        } else {
            aba.lazySet(slot, value);
        }
    }

    /**
     * Weaves {@code AtomicReference.setRelease}: an offer of {@code value} to {@code slot} (#664),
     * and a release to a get that returns it (#741).
     *
     * @param slot  the atomic the call site invoked
     * @param value the reference to store
     * @since 1.12.2
     */
    public static void setReleaseAtomicReference(AtomicReference<Object> slot, @Nullable Object value) {
        AgentConcurrencyUtilHooks.atomicReferenceStored(slot, value);
        ControlledSchedule.point();
        ownershipOffered(value, slot);
        slotStored(slot, value);
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(slot);
        if (aba == null) {
            slot.setRelease(value);
        } else {
            aba.setRelease(slot, value);
        }
    }

    /**
     * Weaves {@code AtomicReference.compareAndSet}: an offer of {@code update} to {@code slot}
     * (#664), published before the swap like every offer. A failed swap withdraws the offer's
     * happens-before release (#742).
     *
     * @param slot     the atomic the call site invoked
     * @param expected the reference the slot must hold
     * @param update   the reference to store
     * @return whether the swap happened
     * @since 1.12.2
     */
    public static boolean compareAndSetAtomicReference(AtomicReference<Object> slot,
                                                       @Nullable Object expected,
                                                       @Nullable Object update) {
        AgentConcurrencyUtilHooks.atomicSwapped(slot);
        ControlledSchedule.point();
        ownershipOffered(update, slot);
        HappensBefore.swapping(slot, SLOT_VALUE, update);
        try {
            ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(slot);
            boolean swapped = aba == null ? slot.compareAndSet(expected, update)
                    : aba.compareAndSet(slot, expected, update);
            if (!swapped) {
                ownershipRefused(update, slot);
            } else {
                // After the swap, not before: a slot's release cannot be withdrawn, and one a failed
                // swap left behind would match a later read of the same value stored by somebody else.
                // A read that returns the value before this lands finds the swap announced above,
                // waits for it to end below, and then finds the release (#1328).
                slotStored(slot, update);
            }
            return swapped;
        } finally {
            // A swap that throws stored nothing, and its announcement must not outlive it (#1356).
            HappensBefore.swapEnded();
        }
    }

    /**
     * Weaves {@code AtomicReference.getAndSet}: the reference returned is taken out of {@code slot}
     * (#555, #664), and the one stored is published to a get that returns it (#741).
     *
     * @param slot  the atomic the call site invoked
     * @param value the reference to store
     * @return the previous reference
     * @since 1.12.2
     */
    public static @Nullable Object getAndSetAtomicReference(AtomicReference<Object> slot,
                                                            @Nullable Object value) {
        AgentConcurrencyUtilHooks.atomicSwapped(slot);
        ControlledSchedule.point();
        slotStored(slot, value);
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(slot);
        Object previous = aba == null ? slot.getAndSet(value) : aba.getAndSet(slot, value);
        ownershipTaken(previous, slot);
        return previous;
    }

    /**
     * The name an {@code AtomicReference}'s value is kept under in the happens-before model, as
     * if it were the volatile field it is inside the JDK (#741).
     */
    private static final String SLOT_VALUE = "value";

    /**
     * The release half of a store into an {@code AtomicReference}, which has volatile-write
     * semantics: everything this thread did so far is published to a get of the same slot that
     * returns {@code value}, the model a volatile field has (#741, #742). Kept per slot rather
     * than per stored object, so the same object read out of another slot receives nothing.
     */
    private static void slotStored(AtomicReference<Object> slot, @Nullable Object value) {
        HappensBefore.releaseVolatileReference(slot, SLOT_VALUE, value);
    }

    /**
     * Weaves {@code AtomicReference.get}, a volatile read: the reading thread receives what the
     * store of the value it returned published, and nothing a store it did not see published
     * (#741). The acquire half of {@link #slotStored}, as {@link #volatileLoad} is of a
     * volatile field's store.
     *
     * @param slot the atomic the call site invoked
     * @return the reference the slot held
     * @since 1.12.3
     */
    public static @Nullable Object getAtomicReference(AtomicReference<Object> slot) {
        AgentConcurrencyUtilHooks.atomicRead(slot);
        ControlledSchedule.point();
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(slot);
        Object released = HappensBefore.releasedSoFar(slot, SLOT_VALUE);
        Object value = aba == null ? slot.get() : aba.get(slot);
        HappensBefore.acquireVolatileReference(slot, SLOT_VALUE, value, released);
        return value;
    }

    /**
     * Weaves {@code AtomicReference.getAcquire}, an acquiring read, as
     * {@link #getAtomicReference}.
     *
     * @param slot the atomic the call site invoked
     * @return the reference the slot held
     * @since 1.12.3
     */
    public static @Nullable Object getAcquireAtomicReference(AtomicReference<Object> slot) {
        AgentConcurrencyUtilHooks.atomicRead(slot);
        ControlledSchedule.point();
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(slot);
        Object released = HappensBefore.releasedSoFar(slot, SLOT_VALUE);
        Object value = aba == null ? slot.getAcquire() : aba.getAcquire(slot);
        HappensBefore.acquireVolatileReference(slot, SLOT_VALUE, value, released);
        return value;
    }

    /**
     * Weaves {@code AtomicReference.updateAndGet} as the loop the JDK runs inside it, made of the
     * woven read and the woven swap: the function's argument comes from a get that acquires what
     * the store of that value published, and the reference it returns is published by a
     * compareAndSet like any other (#1334). Unwoven, the call stored with a volatile write that
     * released nothing, so an object built in the function and read through the slot by another
     * thread looked unordered.
     *
     * @param slot   the atomic the call site invoked
     * @param update the function applied to the current reference, again after a lost swap
     * @return the reference stored
     * @since 1.14.3
     */
    public static @Nullable Object updateAndGetAtomicReference(AtomicReference<Object> slot,
                                                               UnaryOperator<Object> update) {
        for (;;) {
            Object seen = getAtomicReference(slot);
            Object next = update.apply(seen);
            if (compareAndSetAtomicReference(slot, seen, next)) {
                return next;
            }
        }
    }

    /**
     * Weaves {@code AtomicReference.getAndUpdate}, as {@link #updateAndGetAtomicReference}.
     *
     * @param slot   the atomic the call site invoked
     * @param update the function applied to the current reference, again after a lost swap
     * @return the reference replaced
     * @since 1.14.3
     */
    public static @Nullable Object getAndUpdateAtomicReference(AtomicReference<Object> slot,
                                                               UnaryOperator<Object> update) {
        for (;;) {
            Object seen = getAtomicReference(slot);
            if (compareAndSetAtomicReference(slot, seen, update.apply(seen))) {
                return seen;
            }
        }
    }

    /**
     * Weaves {@code AtomicReference.accumulateAndGet}, as {@link #updateAndGetAtomicReference}.
     *
     * @param slot        the atomic the call site invoked
     * @param given       the function's second argument
     * @param accumulator the function applied to the current reference and {@code given}
     * @return the reference stored
     * @since 1.14.3
     */
    public static @Nullable Object accumulateAndGetAtomicReference(AtomicReference<Object> slot,
                                                                   @Nullable Object given,
                                                                   BinaryOperator<Object> accumulator) {
        for (;;) {
            Object seen = getAtomicReference(slot);
            Object next = accumulator.apply(seen, given);
            if (compareAndSetAtomicReference(slot, seen, next)) {
                return next;
            }
        }
    }

    /**
     * Weaves {@code AtomicReference.getAndAccumulate}, as {@link #updateAndGetAtomicReference}.
     *
     * @param slot        the atomic the call site invoked
     * @param given       the function's second argument
     * @param accumulator the function applied to the current reference and {@code given}
     * @return the reference replaced
     * @since 1.14.3
     */
    public static @Nullable Object getAndAccumulateAtomicReference(AtomicReference<Object> slot,
                                                                   @Nullable Object given,
                                                                   BinaryOperator<Object> accumulator) {
        for (;;) {
            Object seen = getAtomicReference(slot);
            if (compareAndSetAtomicReference(slot, seen, accumulator.apply(seen, given))) {
                return seen;
            }
        }
    }

    /**
     * Weaves {@code AtomicReference.compareAndExchange}: a won exchange is the woven
     * compareAndSet, publishing {@code update}; a lost one publishes nothing and returns a
     * reference the slot held instead, read with the woven get, so it acquires what the store
     * of that reference published (#1334). The witness is read after the swap failed, not by it;
     * one equal to {@code expected} means the slot went back, and the swap is tried again.
     *
     * @param slot     the atomic the call site invoked
     * @param expected the reference the slot must hold
     * @param update   the reference to store
     * @return {@code expected} when the exchange happened, otherwise what the slot held
     * @since 1.14.3
     */
    @SuppressWarnings("ReferenceEquality") // the slot compares by identity
    public static @Nullable Object compareAndExchangeAtomicReference(AtomicReference<Object> slot,
                                                                     @Nullable Object expected,
                                                                     @Nullable Object update) {
        for (;;) {
            if (compareAndSetAtomicReference(slot, expected, update)) {
                return expected;
            }
            Object witness = getAtomicReference(slot);
            if (witness != expected) { // NOPMD CompareObjectsWithEquals - the slot compares by identity
                return witness;
            }
        }
    }

    /**
     * Weaves {@code AtomicReference.weakCompareAndSetVolatile} as the strong swap, which a weak
     * one may always be: it stores with a volatile write and publishes like
     * {@link #compareAndSetAtomicReference} (#1334). {@code weakCompareAndSetPlain} and the
     * deprecated {@code weakCompareAndSet} have plain memory semantics and stay unwoven.
     *
     * @param slot     the atomic the call site invoked
     * @param expected the reference the slot must hold
     * @param update   the reference to store
     * @return whether the swap happened
     * @since 1.14.3
     */
    public static boolean weakCompareAndSetVolatileAtomicReference(AtomicReference<Object> slot,
                                                                   @Nullable Object expected,
                                                                   @Nullable Object update) {
        return compareAndSetAtomicReference(slot, expected, update);
    }

    /**
     * Weaves {@code AtomicReferenceArray.updateAndGet} as {@link #updateAndGetAtomicReference}
     * does the atomic's: the woven get of the element, then the woven compareAndSet, so the
     * reference the function returns is published and its argument acquired (#1336).
     *
     * @param slots  the array the call site invoked
     * @param index  the element
     * @param update the function applied to the current reference, again after a lost swap
     * @return the reference stored
     * @since 1.15.0
     */
    public static @Nullable Object updateAndGetReferenceArray(AtomicReferenceArray<Object> slots, int index,
                                                              UnaryOperator<Object> update) {
        for (;;) {
            Object seen = getReferenceArray(slots, index);
            Object next = update.apply(seen);
            if (compareAndSetReferenceArray(slots, index, seen, next)) {
                return next;
            }
        }
    }

    /**
     * Weaves {@code AtomicReferenceArray.getAndUpdate}, as {@link #updateAndGetReferenceArray}.
     *
     * @param slots  the array the call site invoked
     * @param index  the element
     * @param update the function applied to the current reference, again after a lost swap
     * @return the reference replaced
     * @since 1.15.0
     */
    public static @Nullable Object getAndUpdateReferenceArray(AtomicReferenceArray<Object> slots, int index,
                                                              UnaryOperator<Object> update) {
        for (;;) {
            Object seen = getReferenceArray(slots, index);
            if (compareAndSetReferenceArray(slots, index, seen, update.apply(seen))) {
                return seen;
            }
        }
    }

    /**
     * Weaves {@code AtomicReferenceArray.accumulateAndGet}, as {@link #updateAndGetReferenceArray}.
     *
     * @param slots       the array the call site invoked
     * @param index       the element
     * @param given       the function's second argument
     * @param accumulator the function applied to the current reference and {@code given}
     * @return the reference stored
     * @since 1.15.0
     */
    public static @Nullable Object accumulateAndGetReferenceArray(AtomicReferenceArray<Object> slots, int index,
                                                                  @Nullable Object given,
                                                                  BinaryOperator<Object> accumulator) {
        for (;;) {
            Object seen = getReferenceArray(slots, index);
            Object next = accumulator.apply(seen, given);
            if (compareAndSetReferenceArray(slots, index, seen, next)) {
                return next;
            }
        }
    }

    /**
     * Weaves {@code AtomicReferenceArray.getAndAccumulate}, as {@link #updateAndGetReferenceArray}.
     *
     * @param slots       the array the call site invoked
     * @param index       the element
     * @param given       the function's second argument
     * @param accumulator the function applied to the current reference and {@code given}
     * @return the reference replaced
     * @since 1.15.0
     */
    public static @Nullable Object getAndAccumulateReferenceArray(AtomicReferenceArray<Object> slots, int index,
                                                                  @Nullable Object given,
                                                                  BinaryOperator<Object> accumulator) {
        for (;;) {
            Object seen = getReferenceArray(slots, index);
            if (compareAndSetReferenceArray(slots, index, seen, accumulator.apply(seen, given))) {
                return seen;
            }
        }
    }

    /**
     * Weaves {@code AtomicReferenceArray.compareAndExchange} as
     * {@link #compareAndExchangeAtomicReference} does the atomic's (#1336): a won exchange is the
     * woven compareAndSet; a lost one returns what the element held, read with the woven get.
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param expected the reference the element must hold
     * @param update   the reference to store
     * @return {@code expected} when the exchange happened, otherwise what the element held
     * @since 1.15.0
     */
    @SuppressWarnings("ReferenceEquality") // the element compares by identity
    public static @Nullable Object compareAndExchangeReferenceArray(AtomicReferenceArray<Object> slots, int index,
                                                                    @Nullable Object expected,
                                                                    @Nullable Object update) {
        for (;;) {
            if (compareAndSetReferenceArray(slots, index, expected, update)) {
                return expected;
            }
            Object witness = getReferenceArray(slots, index);
            if (witness != expected) { // NOPMD CompareObjectsWithEquals - the element compares by identity
                return witness;
            }
        }
    }

    /**
     * Weaves {@code AtomicReferenceArray.weakCompareAndSetVolatile} as the strong swap, which a
     * weak one may always be (#1336). {@code weakCompareAndSetPlain} and the deprecated
     * {@code weakCompareAndSet} have plain memory semantics and stay unwoven.
     *
     * @param slots    the array the call site invoked
     * @param index    the element
     * @param expected the reference the element must hold
     * @param update   the reference to store
     * @return whether the swap happened
     * @since 1.15.0
     */
    public static boolean weakCompareAndSetVolatileReferenceArray(AtomicReferenceArray<Object> slots, int index,
                                                                  @Nullable Object expected,
                                                                  @Nullable Object update) {
        return compareAndSetReferenceArray(slots, index, expected, update);
    }

    /**
     * Weaves {@code AtomicReferenceFieldUpdater.updateAndGet} as
     * {@link #updateAndGetAtomicReference} does the atomic's: the woven get of the receiver's
     * slot, then the woven compareAndSet (#1336).
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field is the slot
     * @param update   the function applied to the current reference, again after a lost swap
     * @return the reference stored
     * @since 1.15.0
     */
    public static @Nullable Object updateAndGetReferenceUpdater(AtomicReferenceFieldUpdater<Object, Object> updater,
                                                                Object receiver, UnaryOperator<Object> update) {
        for (;;) {
            Object seen = getReferenceUpdater(updater, receiver);
            Object next = update.apply(seen);
            if (compareAndSetReferenceUpdater(updater, receiver, seen, next)) {
                return next;
            }
        }
    }

    /**
     * Weaves {@code AtomicReferenceFieldUpdater.getAndUpdate}, as {@link #updateAndGetReferenceUpdater}.
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field is the slot
     * @param update   the function applied to the current reference, again after a lost swap
     * @return the reference replaced
     * @since 1.15.0
     */
    public static @Nullable Object getAndUpdateReferenceUpdater(AtomicReferenceFieldUpdater<Object, Object> updater,
                                                                Object receiver, UnaryOperator<Object> update) {
        for (;;) {
            Object seen = getReferenceUpdater(updater, receiver);
            if (compareAndSetReferenceUpdater(updater, receiver, seen, update.apply(seen))) {
                return seen;
            }
        }
    }

    /**
     * Weaves {@code AtomicReferenceFieldUpdater.accumulateAndGet}, as
     * {@link #updateAndGetReferenceUpdater}.
     *
     * @param updater     the updater the call site invoked
     * @param receiver    the object whose field is the slot
     * @param given       the function's second argument
     * @param accumulator the function applied to the current reference and {@code given}
     * @return the reference stored
     * @since 1.15.0
     */
    public static @Nullable Object accumulateAndGetReferenceUpdater(
            AtomicReferenceFieldUpdater<Object, Object> updater, Object receiver,
            @Nullable Object given, BinaryOperator<Object> accumulator) {
        for (;;) {
            Object seen = getReferenceUpdater(updater, receiver);
            Object next = accumulator.apply(seen, given);
            if (compareAndSetReferenceUpdater(updater, receiver, seen, next)) {
                return next;
            }
        }
    }

    /**
     * Weaves {@code AtomicReferenceFieldUpdater.getAndAccumulate}, as
     * {@link #updateAndGetReferenceUpdater}.
     *
     * @param updater     the updater the call site invoked
     * @param receiver    the object whose field is the slot
     * @param given       the function's second argument
     * @param accumulator the function applied to the current reference and {@code given}
     * @return the reference replaced
     * @since 1.15.0
     */
    public static @Nullable Object getAndAccumulateReferenceUpdater(
            AtomicReferenceFieldUpdater<Object, Object> updater, Object receiver,
            @Nullable Object given, BinaryOperator<Object> accumulator) {
        for (;;) {
            Object seen = getReferenceUpdater(updater, receiver);
            if (compareAndSetReferenceUpdater(updater, receiver, seen, accumulator.apply(seen, given))) {
                return seen;
            }
        }
    }

    /**
     * Weaves a reference {@code VarHandle.compareAndExchange} on an instance field as
     * {@link #compareAndExchangeAtomicReference} does the atomic's (#1336): a won exchange is the
     * woven compareAndSet; a lost one returns what the field held, read with the woven
     * {@code getVolatile}.
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is the slot
     * @param expected the reference the field must hold
     * @param update   the reference to store
     * @return {@code expected} when the exchange happened, otherwise what the field held
     * @since 1.15.0
     */
    @SuppressWarnings("ReferenceEquality") // the field compares by identity
    public static @Nullable Object compareAndExchangeReferenceHandle(VarHandle handle, Object receiver,
                                                                     @Nullable Object expected,
                                                                     @Nullable Object update) {
        for (;;) {
            if (compareAndSetReferenceHandle(handle, receiver, expected, update)) {
                return expected;
            }
            Object witness = getVolatileReferenceHandle(handle, receiver);
            if (witness != expected) { // NOPMD CompareObjectsWithEquals - the field compares by identity
                return witness;
            }
        }
    }

    /**
     * Weaves a reference {@code VarHandle.weakCompareAndSet} on an instance field, which has
     * volatile semantics on a handle, as the strong swap a weak one may always be (#1336).
     * {@code weakCompareAndSetPlain} and the acquire and release flavours stay unwoven.
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is the slot
     * @param expected the reference the field must hold
     * @param update   the reference to store
     * @return whether the swap happened
     * @since 1.15.0
     */
    public static boolean weakCompareAndSetReferenceHandle(VarHandle handle, Object receiver,
                                                           @Nullable Object expected,
                                                           @Nullable Object update) {
        return compareAndSetReferenceHandle(handle, receiver, expected, update);
    }

    /**
     * {@link #compareAndExchangeReferenceHandle} for a static field (#1336).
     *
     * @param handle   the handle the call site invoked
     * @param expected the reference the field must hold
     * @param update   the reference to store
     * @return {@code expected} when the exchange happened, otherwise what the field held
     * @since 1.15.0
     */
    @SuppressWarnings("ReferenceEquality") // the field compares by identity
    public static @Nullable Object compareAndExchangeStaticReferenceHandle(VarHandle handle,
                                                                           @Nullable Object expected,
                                                                           @Nullable Object update) {
        for (;;) {
            if (compareAndSetStaticReferenceHandle(handle, expected, update)) {
                return expected;
            }
            Object witness = getVolatileStaticReferenceHandle(handle);
            if (witness != expected) { // NOPMD CompareObjectsWithEquals - the field compares by identity
                return witness;
            }
        }
    }

    /**
     * {@link #weakCompareAndSetReferenceHandle} for a static field (#1336).
     *
     * @param handle   the handle the call site invoked
     * @param expected the reference the field must hold
     * @param update   the reference to store
     * @return whether the swap happened
     * @since 1.15.0
     */
    public static boolean weakCompareAndSetStaticReferenceHandle(VarHandle handle,
                                                                 @Nullable Object expected,
                                                                 @Nullable Object update) {
        return compareAndSetStaticReferenceHandle(handle, expected, update);
    }

    /**
     * {@link #compareAndExchangeReferenceHandle} for an array element (#1336).
     *
     * @param handle   the handle the call site invoked
     * @param array    the array whose element at {@code index} is the slot
     * @param index    the element
     * @param expected the reference the element must hold
     * @param update   the reference to store
     * @return {@code expected} when the exchange happened, otherwise what the element held
     * @since 1.15.0
     */
    @SuppressWarnings("ReferenceEquality") // the element compares by identity
    public static @Nullable Object compareAndExchangeArrayReferenceHandle(VarHandle handle, Object array,
                                                                          int index, @Nullable Object expected,
                                                                          @Nullable Object update) {
        for (;;) {
            if (compareAndSetArrayReferenceHandle(handle, array, index, expected, update)) {
                return expected;
            }
            Object witness = getVolatileArrayReferenceHandle(handle, array, index);
            if (witness != expected) { // NOPMD CompareObjectsWithEquals - the element compares by identity
                return witness;
            }
        }
    }

    /**
     * {@link #weakCompareAndSetReferenceHandle} for an array element (#1336).
     *
     * @param handle   the handle the call site invoked
     * @param array    the array whose element at {@code index} is the slot
     * @param index    the element
     * @param expected the reference the element must hold
     * @param update   the reference to store
     * @return whether the swap happened
     * @since 1.15.0
     */
    public static boolean weakCompareAndSetArrayReferenceHandle(VarHandle handle, Object array, int index,
                                                                @Nullable Object expected,
                                                                @Nullable Object update) {
        return compareAndSetArrayReferenceHandle(handle, array, index, expected, update);
    }

    /**
     * Weaves {@code AtomicReferenceFieldUpdater.set}: an offer of {@code value} to the receiver's
     * slot (#664).
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field is the slot
     * @param value    the reference to store
     * @since 1.12.2
     */
    public static void setReferenceUpdater(AtomicReferenceFieldUpdater<Object, Object> updater,
                                           Object receiver, @Nullable Object value) {
        ControlledSchedule.point();
        slotOffered(value, receiver, System.identityHashCode(updater));
        slotReleased(receiver, ReferenceSlots.fieldOf(updater), value);
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(receiver, updater);
        if (aba == null) {
            updater.set(receiver, value);
        } else {
            aba.store(receiver, updater, 0, value, (h, s, i) -> asUpdater(s).get(h), (h, s, i, v) -> asUpdater(s).set(h, v));
        }
    }

    /**
     * Weaves {@code AtomicReferenceFieldUpdater.lazySet}: an offer of {@code value} to the
     * receiver's slot (#664).
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field is the slot
     * @param value    the reference to store
     * @since 1.12.2
     */
    public static void lazySetReferenceUpdater(AtomicReferenceFieldUpdater<Object, Object> updater,
                                               Object receiver, @Nullable Object value) {
        ControlledSchedule.point();
        slotOffered(value, receiver, System.identityHashCode(updater));
        slotReleased(receiver, ReferenceSlots.fieldOf(updater), value);
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(receiver, updater);
        if (aba == null) {
            updater.lazySet(receiver, value);
        } else {
            aba.store(receiver, updater, 0, value, (h, s, i) -> asUpdater(s).get(h), (h, s, i, v) -> asUpdater(s).lazySet(h, v));
        }
    }

    /**
     * Weaves {@code AtomicReferenceFieldUpdater.compareAndSet}: an offer of {@code update} to the
     * receiver's slot (#664).
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field is the slot
     * @param expected the reference the slot must hold
     * @param update   the reference to store
     * @return whether the swap happened
     * @since 1.12.2
     */
    public static boolean compareAndSetReferenceUpdater(
            AtomicReferenceFieldUpdater<Object, Object> updater, Object receiver,
            @Nullable Object expected, @Nullable Object update) {
        ControlledSchedule.point();
        slotOffered(update, receiver, System.identityHashCode(updater));
        HappensBefore.swapping(receiver, ReferenceSlots.fieldOf(updater), update);
        try {
            ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(receiver, updater);
            boolean swapped = aba == null ? updater.compareAndSet(receiver, expected, update)
                    : aba.compareAndSet(receiver, updater, 0, expected, update, (h, s, i, e, u) -> asUpdater(s).compareAndSet(h, e, u));
            if (swapped) {
                slotReleased(receiver, ReferenceSlots.fieldOf(updater), update);
            }
            return swapped;
        } finally {
            // A swap that throws stored nothing, and its announcement must not outlive it (#1356).
            HappensBefore.swapEnded();
        }
    }

    /**
     * Weaves {@code AtomicReferenceFieldUpdater.getAndSet}: the reference returned is taken out of
     * the receiver's slot (#555, #664). netty moves a chunk out of a magazine this way.
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field is the slot
     * @param value    the reference to store
     * @return the previous reference
     * @since 1.12.2
     */
    public static @Nullable Object getAndSetReferenceUpdater(
            AtomicReferenceFieldUpdater<Object, Object> updater, Object receiver,
            @Nullable Object value) {
        ControlledSchedule.point();
        slotReleased(receiver, ReferenceSlots.fieldOf(updater), value);
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(receiver, updater);
        Object previous = aba == null ? updater.getAndSet(receiver, value)
                : aba.getAndSet(receiver, updater, 0, value, (h, s, i, v) -> asUpdater(s).getAndSet(h, v));
        slotTaken(previous, receiver, System.identityHashCode(updater));
        return previous;
    }

    /**
     * Weaves {@code AtomicReferenceArray.set}: an offer of {@code value} to the array (#664).
     *
     * @param slots the array the call site invoked
     * @param index the element's index
     * @param value the reference to store
     * @since 1.12.2
     */
    public static void setReferenceArray(AtomicReferenceArray<Object> slots, int index,
                                         @Nullable Object value) {
        ControlledSchedule.point();
        slotOffered(value, slots, index);
        slotReleased(slots, ReferenceSlots.element(index), value);
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(slots, index);
        if (aba == null) {
            slots.set(index, value);
        } else {
            aba.store(slots, null, index, value, (h, s, i) -> asArray(h).get(i), (h, s, i, v) -> asArray(h).set(i, v));
        }
    }

    /**
     * Weaves {@code AtomicReferenceArray.lazySet}: an offer of {@code value} to the array (#664).
     *
     * @param slots the array the call site invoked
     * @param index the element's index
     * @param value the reference to store
     * @since 1.12.2
     */
    public static void lazySetReferenceArray(AtomicReferenceArray<Object> slots, int index,
                                             @Nullable Object value) {
        ControlledSchedule.point();
        slotOffered(value, slots, index);
        slotReleased(slots, ReferenceSlots.element(index), value);
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(slots, index);
        if (aba == null) {
            slots.lazySet(index, value);
        } else {
            aba.store(slots, null, index, value, (h, s, i) -> asArray(h).get(i), (h, s, i, v) -> asArray(h).lazySet(i, v));
        }
    }

    /**
     * Weaves {@code AtomicReferenceArray.setRelease}: an offer of {@code value} to the array (#664).
     *
     * @param slots the array the call site invoked
     * @param index the element's index
     * @param value the reference to store
     * @since 1.12.2
     */
    public static void setReleaseReferenceArray(AtomicReferenceArray<Object> slots, int index,
                                                @Nullable Object value) {
        ControlledSchedule.point();
        slotOffered(value, slots, index);
        slotReleased(slots, ReferenceSlots.element(index), value);
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(slots, index);
        if (aba == null) {
            slots.setRelease(index, value);
        } else {
            aba.store(slots, null, index, value, (h, s, i) -> asArray(h).get(i), (h, s, i, v) -> asArray(h).setRelease(i, v));
        }
    }

    /**
     * Weaves {@code AtomicReferenceArray.compareAndSet}: an offer of {@code update} to the array (#664).
     *
     * @param slots    the array the call site invoked
     * @param index    the element's index
     * @param expected the reference the element must hold
     * @param update   the reference to store
     * @return whether the swap happened
     * @since 1.12.2
     */
    public static boolean compareAndSetReferenceArray(AtomicReferenceArray<Object> slots, int index,
                                                      @Nullable Object expected,
                                                      @Nullable Object update) {
        ControlledSchedule.point();
        slotOffered(update, slots, index);
        HappensBefore.swapping(slots, ReferenceSlots.element(index), update);
        try {
            ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(slots, index);
            boolean swapped = aba == null ? slots.compareAndSet(index, expected, update)
                    : aba.compareAndSet(slots, null, index, expected, update, (h, s, i, e, u) -> asArray(h).compareAndSet(i, e, u));
            if (swapped) {
                slotReleased(slots, ReferenceSlots.element(index), update);
            }
            return swapped;
        } finally {
            // A swap that throws stored nothing, and its announcement must not outlive it (#1356).
            HappensBefore.swapEnded();
        }
    }

    /**
     * Weaves {@code AtomicReferenceArray.getAndSet}: the reference returned is taken out of the
     * array (#555, #664).
     *
     * @param slots the array the call site invoked
     * @param index the element's index
     * @param value the reference to store
     * @return the previous reference
     * @since 1.12.2
     */
    public static @Nullable Object getAndSetReferenceArray(AtomicReferenceArray<Object> slots,
                                                           int index, @Nullable Object value) {
        ControlledSchedule.point();
        slotReleased(slots, ReferenceSlots.element(index), value);
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(slots, index);
        Object previous = aba == null ? slots.getAndSet(index, value)
                : aba.getAndSet(slots, null, index, value, (h, s, i, v) -> asArray(h).getAndSet(i, v));
        slotTaken(previous, slots, index);
        return previous;
    }

    /**
     * Weaves {@code VarHandle.set} on a reference instance field: an offer of {@code value} to the
     * receiver's slot (#664).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is the slot
     * @param value    the reference to store
     * @since 1.12.2
     */
    public static void setReferenceHandle(VarHandle handle, Object receiver, @Nullable Object value) {
        ControlledSchedule.point();
        slotOffered(value, receiver, System.identityHashCode(handle));
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(receiver, handle);
        if (aba == null) {
            handle.withInvokeBehavior().set(receiver, value);
        } else {
            aba.store(receiver, handle, 0, value, (h, s, i) -> asHandle(s).withInvokeBehavior().getVolatile(h), (h, s, i, v) -> asHandle(s).withInvokeBehavior().set(h, v));
        }
    }

    /**
     * Weaves {@code VarHandle.setVolatile} on a reference instance field: an offer (#664).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is the slot
     * @param value    the reference to store
     * @since 1.12.2
     */
    public static void setVolatileReferenceHandle(VarHandle handle, Object receiver,
                                                  @Nullable Object value) {
        ControlledSchedule.point();
        slotOffered(value, receiver, System.identityHashCode(handle));
        slotReleased(receiver, ReferenceSlots.fieldOf(handle), value);
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(receiver, handle);
        if (aba == null) {
            handle.withInvokeBehavior().setVolatile(receiver, value);
        } else {
            aba.store(receiver, handle, 0, value, (h, s, i) -> asHandle(s).withInvokeBehavior().getVolatile(h), (h, s, i, v) -> asHandle(s).withInvokeBehavior().setVolatile(h, v));
        }
    }

    /**
     * Weaves {@code VarHandle.setRelease} on a reference instance field: an offer (#664).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is the slot
     * @param value    the reference to store
     * @since 1.12.2
     */
    public static void setReleaseReferenceHandle(VarHandle handle, Object receiver,
                                                 @Nullable Object value) {
        ControlledSchedule.point();
        slotOffered(value, receiver, System.identityHashCode(handle));
        slotReleased(receiver, ReferenceSlots.fieldOf(handle), value);
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(receiver, handle);
        if (aba == null) {
            handle.withInvokeBehavior().setRelease(receiver, value);
        } else {
            aba.store(receiver, handle, 0, value, (h, s, i) -> asHandle(s).withInvokeBehavior().getVolatile(h), (h, s, i, v) -> asHandle(s).withInvokeBehavior().setRelease(h, v));
        }
    }

    /**
     * Weaves {@code VarHandle.setOpaque} on a reference instance field: an offer (#664).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is the slot
     * @param value    the reference to store
     * @since 1.12.2
     */
    public static void setOpaqueReferenceHandle(VarHandle handle, Object receiver,
                                                @Nullable Object value) {
        ControlledSchedule.point();
        slotOffered(value, receiver, System.identityHashCode(handle));
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(receiver, handle);
        if (aba == null) {
            handle.withInvokeBehavior().setOpaque(receiver, value);
        } else {
            aba.store(receiver, handle, 0, value, (h, s, i) -> asHandle(s).withInvokeBehavior().getVolatile(h), (h, s, i, v) -> asHandle(s).withInvokeBehavior().setOpaque(h, v));
        }
    }

    /**
     * Weaves {@code VarHandle.compareAndSet} on a reference instance field: an offer of
     * {@code update} to the receiver's slot (#664).
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is the slot
     * @param expected the reference the field must hold
     * @param update   the reference to store
     * @return whether the swap happened
     * @since 1.12.2
     */
    public static boolean compareAndSetReferenceHandle(VarHandle handle, Object receiver,
                                                       @Nullable Object expected,
                                                       @Nullable Object update) {
        ControlledSchedule.point();
        slotOffered(update, receiver, System.identityHashCode(handle));
        HappensBefore.swapping(receiver, ReferenceSlots.fieldOf(handle), update);
        try {
            ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(receiver, handle);
            boolean swapped = aba == null ? handle.withInvokeBehavior().compareAndSet(receiver, expected, update)
                    : aba.compareAndSet(receiver, handle, 0, expected, update, (h, s, i, e, u) -> (boolean) asHandle(s).withInvokeBehavior().compareAndSet(h, e, u));
            if (swapped) {
                slotReleased(receiver, ReferenceSlots.fieldOf(handle), update);
            }
            return swapped;
        } finally {
            // A swap that throws stored nothing, and its announcement must not outlive it (#1356).
            HappensBefore.swapEnded();
        }
    }

    /**
     * Weaves {@code VarHandle.getAndSet} on a reference instance field: the reference returned is
     * taken out of the receiver's slot (#555, #664). The weaver casts the result back to the type
     * the call site declared, which is the cast the handle's own invocation would have made.
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is the slot
     * @param value    the reference to store
     * @return the previous reference
     * @since 1.12.2
     */
    public static @Nullable Object getAndSetReferenceHandle(VarHandle handle, Object receiver,
                                                            @Nullable Object value) {
        ControlledSchedule.point();
        slotReleased(receiver, ReferenceSlots.fieldOf(handle), value);
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(receiver, handle);
        Object previous = aba == null ? handle.withInvokeBehavior().getAndSet(receiver, value)
                : aba.getAndSet(receiver, handle, 0, value, (h, s, i, v) -> asHandle(s).withInvokeBehavior().getAndSet(h, v));
        slotTaken(previous, receiver, System.identityHashCode(handle));
        return previous;
    }

    // ---- VarHandle on static fields (#692) -----------------------------------------------------

    /**
     * Weaves {@code VarHandle.set} on a reference static field: an offer of {@code value} (#692).
     *
     * @param handle the handle the call site invoked
     * @param value  the reference to store
     * @since 1.12.2
     */
    public static void setStaticReferenceHandle(VarHandle handle, @Nullable Object value) {
        ControlledSchedule.point();
        slotOffered(value, handle, 0);
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(handle);
        if (aba == null) {
            handle.withInvokeBehavior().set(value);
        } else {
            aba.store(handle, null, 0, value, (h, s, i) -> asHandle(h).withInvokeBehavior().getVolatile(), (h, s, i, v) -> asHandle(h).withInvokeBehavior().set(v));
        }
    }

    /**
     * Weaves {@code VarHandle.setVolatile} on a reference static field: an offer (#692).
     *
     * @param handle the handle the call site invoked
     * @param value  the reference to store
     * @since 1.12.2
     */
    public static void setVolatileStaticReferenceHandle(VarHandle handle, @Nullable Object value) {
        ControlledSchedule.point();
        slotOffered(value, handle, 0);
        slotReleased(handle, SLOT_VALUE, value);
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(handle);
        if (aba == null) {
            handle.withInvokeBehavior().setVolatile(value);
        } else {
            aba.store(handle, null, 0, value, (h, s, i) -> asHandle(h).withInvokeBehavior().getVolatile(), (h, s, i, v) -> asHandle(h).withInvokeBehavior().setVolatile(v));
        }
    }

    /**
     * Weaves {@code VarHandle.setRelease} on a reference static field: an offer (#692).
     *
     * @param handle the handle the call site invoked
     * @param value  the reference to store
     * @since 1.12.2
     */
    public static void setReleaseStaticReferenceHandle(VarHandle handle, @Nullable Object value) {
        ControlledSchedule.point();
        slotOffered(value, handle, 0);
        slotReleased(handle, SLOT_VALUE, value);
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(handle);
        if (aba == null) {
            handle.withInvokeBehavior().setRelease(value);
        } else {
            aba.store(handle, null, 0, value, (h, s, i) -> asHandle(h).withInvokeBehavior().getVolatile(), (h, s, i, v) -> asHandle(h).withInvokeBehavior().setRelease(v));
        }
    }

    /**
     * Weaves {@code VarHandle.setOpaque} on a reference static field: an offer (#692).
     *
     * @param handle the handle the call site invoked
     * @param value  the reference to store
     * @since 1.12.2
     */
    public static void setOpaqueStaticReferenceHandle(VarHandle handle, @Nullable Object value) {
        ControlledSchedule.point();
        slotOffered(value, handle, 0);
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(handle);
        if (aba == null) {
            handle.withInvokeBehavior().setOpaque(value);
        } else {
            aba.store(handle, null, 0, value, (h, s, i) -> asHandle(h).withInvokeBehavior().getVolatile(), (h, s, i, v) -> asHandle(h).withInvokeBehavior().setOpaque(v));
        }
    }

    /**
     * Weaves {@code VarHandle.compareAndSet} on a reference static field: an offer of
     * {@code update} (#692).
     *
     * @param handle   the handle the call site invoked
     * @param expected the reference the field must hold
     * @param update   the reference to store
     * @return whether the swap happened
     * @since 1.12.2
     */
    public static boolean compareAndSetStaticReferenceHandle(VarHandle handle,
                                                             @Nullable Object expected,
                                                             @Nullable Object update) {
        ControlledSchedule.point();
        slotOffered(update, handle, 0);
        HappensBefore.swapping(handle, SLOT_VALUE, update);
        try {
            ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(handle);
            boolean swapped = aba == null ? handle.withInvokeBehavior().compareAndSet(expected, update)
                    : aba.compareAndSet(handle, null, 0, expected, update, (h, s, i, e, u) -> (boolean) asHandle(h).withInvokeBehavior().compareAndSet(e, u));
            if (swapped) {
                slotReleased(handle, SLOT_VALUE, update);
            }
            return swapped;
        } finally {
            // A swap that throws stored nothing, and its announcement must not outlive it (#1356).
            HappensBefore.swapEnded();
        }
    }

    /**
     * Weaves {@code VarHandle.getAndSet} on a reference static field: the reference returned is
     * taken (#692).
     *
     * @param handle the handle the call site invoked
     * @param value  the reference to store
     * @return the previous reference
     * @since 1.12.2
     */
    public static @Nullable Object getAndSetStaticReferenceHandle(VarHandle handle,
                                                                 @Nullable Object value) {
        ControlledSchedule.point();
        slotReleased(handle, SLOT_VALUE, value);
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(handle);
        Object previous = aba == null ? handle.withInvokeBehavior().getAndSet(value)
                : aba.getAndSet(handle, null, 0, value, (h, s, i, v) -> asHandle(h).withInvokeBehavior().getAndSet(v));
        slotTaken(previous, handle, 0);
        return previous;
    }

    // ---- VarHandle on array elements (#692) -----------------------------------------------------

    /**
     * Weaves {@code VarHandle.set} on a reference array element: an offer (#692).
     *
     * @param handle the handle the call site invoked
     * @param array  the array whose element is the slot
     * @param index  the index of the element
     * @param value  the reference to store
     * @since 1.12.2
     */
    public static void setArrayReferenceHandle(VarHandle handle, Object array, int index,
                                               @Nullable Object value) {
        ControlledSchedule.point();
        slotOffered(value, array, index);
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(array, index);
        if (aba == null) {
            handle.withInvokeBehavior().set(array, index, value);
        } else {
            aba.store(array, handle, index, value, (h, s, i) -> asHandle(s).withInvokeBehavior().getVolatile(h, i), (h, s, i, v) -> asHandle(s).withInvokeBehavior().set(h, i, v));
        }
    }

    /**
     * Weaves {@code VarHandle.setVolatile} on a reference array element: an offer (#692).
     *
     * @param handle the handle the call site invoked
     * @param array  the array whose element is the slot
     * @param index  the index of the element
     * @param value  the reference to store
     * @since 1.12.2
     */
    public static void setVolatileArrayReferenceHandle(VarHandle handle, Object array, int index,
                                                       @Nullable Object value) {
        ControlledSchedule.point();
        slotOffered(value, array, index);
        slotReleased(array, ReferenceSlots.element(index), value);
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(array, index);
        if (aba == null) {
            handle.withInvokeBehavior().setVolatile(array, index, value);
        } else {
            aba.store(array, handle, index, value, (h, s, i) -> asHandle(s).withInvokeBehavior().getVolatile(h, i), (h, s, i, v) -> asHandle(s).withInvokeBehavior().setVolatile(h, i, v));
        }
    }

    /**
     * Weaves {@code VarHandle.setRelease} on a reference array element: an offer (#692).
     *
     * @param handle the handle the call site invoked
     * @param array  the array whose element is the slot
     * @param index  the index of the element
     * @param value  the reference to store
     * @since 1.12.2
     */
    public static void setReleaseArrayReferenceHandle(VarHandle handle, Object array, int index,
                                                      @Nullable Object value) {
        ControlledSchedule.point();
        slotOffered(value, array, index);
        slotReleased(array, ReferenceSlots.element(index), value);
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(array, index);
        if (aba == null) {
            handle.withInvokeBehavior().setRelease(array, index, value);
        } else {
            aba.store(array, handle, index, value, (h, s, i) -> asHandle(s).withInvokeBehavior().getVolatile(h, i), (h, s, i, v) -> asHandle(s).withInvokeBehavior().setRelease(h, i, v));
        }
    }

    /**
     * Weaves {@code VarHandle.setOpaque} on a reference array element: an offer (#692).
     *
     * @param handle the handle the call site invoked
     * @param array  the array whose element is the slot
     * @param index  the index of the element
     * @param value  the reference to store
     * @since 1.12.2
     */
    public static void setOpaqueArrayReferenceHandle(VarHandle handle, Object array, int index,
                                                     @Nullable Object value) {
        ControlledSchedule.point();
        slotOffered(value, array, index);
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(array, index);
        if (aba == null) {
            handle.withInvokeBehavior().setOpaque(array, index, value);
        } else {
            aba.store(array, handle, index, value, (h, s, i) -> asHandle(s).withInvokeBehavior().getVolatile(h, i), (h, s, i, v) -> asHandle(s).withInvokeBehavior().setOpaque(h, i, v));
        }
    }

    /**
     * Weaves {@code VarHandle.compareAndSet} on a reference array element: an offer (#692).
     *
     * @param handle   the handle the call site invoked
     * @param array    the array whose element is the slot
     * @param index    the index of the element
     * @param expected the reference the element must hold
     * @param update   the reference to store
     * @return whether the swap happened
     * @since 1.12.2
     */
    public static boolean compareAndSetArrayReferenceHandle(VarHandle handle, Object array,
                                                            int index, @Nullable Object expected,
                                                            @Nullable Object update) {
        ControlledSchedule.point();
        slotOffered(update, array, index);
        HappensBefore.swapping(array, ReferenceSlots.element(index), update);
        try {
            ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(array, index);
            boolean swapped = aba == null ? handle.withInvokeBehavior().compareAndSet(array, index, expected, update)
                    : aba.compareAndSet(array, handle, index, expected, update, (h, s, i, e, u) -> (boolean) asHandle(s).withInvokeBehavior().compareAndSet(h, i, e, u));
            if (swapped) {
                slotReleased(array, ReferenceSlots.element(index), update);
            }
            return swapped;
        } finally {
            // A swap that throws stored nothing, and its announcement must not outlive it (#1356).
            HappensBefore.swapEnded();
        }
    }

    /**
     * Weaves {@code VarHandle.getAndSet} on a reference array element: a take (#692).
     *
     * @param handle the handle the call site invoked
     * @param array  the array whose element is the slot
     * @param index  the index of the element
     * @param value  the reference to store
     * @return the previous reference
     * @since 1.12.2
     */
    public static @Nullable Object getAndSetArrayReferenceHandle(VarHandle handle, Object array,
                                                                 int index, @Nullable Object value) {
        ControlledSchedule.point();
        slotReleased(array, ReferenceSlots.element(index), value);
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(array, index);
        Object previous = aba == null ? handle.withInvokeBehavior().getAndSet(array, index, value)
                : aba.getAndSet(array, handle, index, value, (h, s, i, v) -> asHandle(s).withInvokeBehavior().getAndSet(h, i, v));
        slotTaken(previous, array, index);
        return previous;
    }

    // ---- AtomicStampedReference (#817) ---------------------------------------------------------
    //
    // Its value is a (reference, stamp) pair. A store releases the slot and a read acquires the
    // store whose reference it returned, as an AtomicReference's do; on a thread whose test has an
    // ABAProblemDetector each operation runs through the detector's view of the pair.

    /**
     * Weaves {@code AtomicStampedReference.get(int[])}.
     *
     * @param slot        the atomic the call site invoked
     * @param stampHolder receives the stamp
     * @return the reference
     * @since 1.12.4
     */
    public static @Nullable Object getStampedReference(AtomicStampedReference<Object> slot, int[] stampHolder) {
        ControlledSchedule.point();
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(slot);
        Object released = HappensBefore.releasedSoFar(slot, SLOT_VALUE);
        Object value = aba == null ? slot.get(stampHolder) : aba.getStamped(slot, stampHolder);
        HappensBefore.acquireVolatileReference(slot, SLOT_VALUE, value, released);
        return value;
    }

    /**
     * Weaves {@code AtomicStampedReference.getReference()}.
     *
     * @param slot the atomic the call site invoked
     * @return the reference
     * @since 1.12.4
     */
    public static @Nullable Object getReferenceStampedReference(AtomicStampedReference<Object> slot) {
        ControlledSchedule.point();
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(slot);
        Object released = HappensBefore.releasedSoFar(slot, SLOT_VALUE);
        Object value = aba == null ? slot.getReference() : aba.getStampedReference(slot);
        HappensBefore.acquireVolatileReference(slot, SLOT_VALUE, value, released);
        return value;
    }

    /**
     * Weaves {@code AtomicStampedReference.set}.
     *
     * @param slot  the atomic the call site invoked
     * @param value the reference to store
     * @param stamp the stamp to store
     * @since 1.12.4
     */
    public static void setStampedReference(AtomicStampedReference<Object> slot, @Nullable Object value, int stamp) {
        ControlledSchedule.point();
        HappensBefore.releaseVolatileReference(slot, SLOT_VALUE, value);
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(slot);
        if (aba == null) {
            slot.set(value, stamp);
        } else {
            aba.setStamped(slot, value, stamp);
        }
    }

    /**
     * Weaves {@code AtomicStampedReference.compareAndSet}.
     *
     * @param slot          the atomic the call site invoked
     * @param expected      the reference it must hold
     * @param update        the reference to store
     * @param expectedStamp the stamp it must hold
     * @param newStamp      the stamp to store
     * @return whether it swapped
     * @since 1.12.4
     */
    public static boolean compareAndSetStampedReference(AtomicStampedReference<Object> slot, @Nullable Object expected,
                                                        @Nullable Object update, int expectedStamp, int newStamp) {
        ControlledSchedule.point();
        return stampedSwap(slot, expected, update, expectedStamp, newStamp, false);
    }

    /**
     * Weaves {@code AtomicStampedReference.weakCompareAndSet}.
     *
     * @param slot          the atomic the call site invoked
     * @param expected      the reference it must hold
     * @param update        the reference to store
     * @param expectedStamp the stamp it must hold
     * @param newStamp      the stamp to store
     * @return whether it swapped
     * @since 1.12.4
     */
    public static boolean weakCompareAndSetStampedReference(AtomicStampedReference<Object> slot,
                                                            @Nullable Object expected, @Nullable Object update,
                                                            int expectedStamp, int newStamp) {
        ControlledSchedule.point();
        return stampedSwap(slot, expected, update, expectedStamp, newStamp, true);
    }

    private static boolean stampedSwap(AtomicStampedReference<Object> slot, @Nullable Object expected,
                                       @Nullable Object update, int expectedStamp, int newStamp, boolean weak) {
        HappensBefore.swapping(slot, SLOT_VALUE, update);
        try {
            ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(slot);
            boolean swapped;
            if (aba == null) {
                swapped = weak ? slot.weakCompareAndSet(expected, update, expectedStamp, newStamp)
                        : slot.compareAndSet(expected, update, expectedStamp, newStamp);
            } else {
                swapped = aba.compareAndSetStamped(slot, expected, update, expectedStamp, newStamp, weak);
            }
            if (swapped) {
                HappensBefore.releaseVolatileReference(slot, SLOT_VALUE, update);
            }
            return swapped;
        } finally {
            // A swap that throws stored nothing, and its announcement must not outlive it (#1356).
            HappensBefore.swapEnded();
        }
    }

    /**
     * Weaves {@code AtomicStampedReference.attemptStamp}.
     *
     * @param slot     the atomic the call site invoked
     * @param expected the reference it must hold
     * @param newStamp the stamp to store
     * @return whether the stamp was set
     * @since 1.12.4
     */
    public static boolean attemptStampStampedReference(AtomicStampedReference<Object> slot, @Nullable Object expected,
                                                       int newStamp) {
        ControlledSchedule.point();
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(slot);
        boolean set = aba == null ? slot.attemptStamp(expected, newStamp) : aba.attemptStamp(slot, expected, newStamp);
        if (set) {
            HappensBefore.releaseVolatileReference(slot, SLOT_VALUE, expected);
        }
        return set;
    }

    // ---- The slot operations the hooks above hand ABAProblemDetector (#817) ---------------------
    //
    // Each lambda passed to an AgentSlot is non-capturing, so it is one shared instance and the hook
    // allocates nothing for it; these name the selector or holder back as its real type.

    @SuppressWarnings("unchecked") // the hook that passed it holds the updater as this type
    private static AtomicReferenceFieldUpdater<Object, Object> asUpdater(@Nullable Object selector) {
        if (selector == null) {
            throw new IllegalStateException("an updater slot was handed no updater");
        }
        return (AtomicReferenceFieldUpdater<Object, Object>) selector;
    }

    @SuppressWarnings("unchecked") // the hook that passed it holds the array as this type
    private static AtomicReferenceArray<Object> asArray(Object holder) {
        return (AtomicReferenceArray<Object>) holder;
    }

    private static VarHandle asHandle(@Nullable Object selector) {
        if (selector == null) {
            throw new IllegalStateException("a handle slot was handed no handle");
        }
        return (VarHandle) selector;
    }

    // ---- Acquiring reads of the reference slots above (#741) ------------------------------------
    //
    // A store into one of these slots releases what the storing thread did so far to the slot's
    // own volatile clock, as an AtomicReference's does, and an acquiring read takes what the store
    // whose value it returned published. Plain and opaque accesses order nothing and stay unwoven
    // or unreleased. A slot is named by the field it reaches, as a direct access to that field is
    // (#813), or by its index; one whose field cannot be told keeps no clock.

    /** The release half of a store into a reference slot; nothing when the slot cannot be named. */
    private static void slotReleased(@Nullable Object owner, @Nullable String field,
                                     @Nullable Object value) {
        if (owner != null && field != null) {
            HappensBefore.releaseVolatileReference(owner, field, value);
        }
    }

    /**
     * The acquire half of {@link #slotReleased}, for the value an acquiring read returned and
     * what the slot had released before that read ({@link HappensBefore#releasedSoFar}, #1328).
     */
    private static void slotAcquired(@Nullable Object owner, @Nullable String field,
                                     @Nullable Object value, @Nullable Object released) {
        if (owner != null && field != null) {
            HappensBefore.acquireVolatileReference(owner, field, value, released);
        }
    }

    /**
     * Weaves {@code AtomicReferenceFieldUpdater.get}, a volatile read of the receiver's field.
     *
     * @param updater  the updater the call site invoked
     * @param receiver the object whose field is the slot
     * @return the reference the field held
     * @since 1.12.4
     */
    public static @Nullable Object getReferenceUpdater(AtomicReferenceFieldUpdater<Object, Object> updater,
                                                       Object receiver) {
        ControlledSchedule.point();
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(receiver, updater);
        Object released = HappensBefore.releasedSoFar(receiver, ReferenceSlots.fieldOf(updater));
        Object value = aba == null ? updater.get(receiver)
                : aba.read(receiver, updater, 0, (h, s, i) -> asUpdater(s).get(h));
        slotAcquired(receiver, ReferenceSlots.fieldOf(updater), value, released);
        return value;
    }

    /**
     * Weaves {@code AtomicReferenceArray.get}, a volatile read of one element.
     *
     * @param slots the array the call site invoked
     * @param index the element's index
     * @return the reference the element held
     * @since 1.12.4
     */
    public static @Nullable Object getReferenceArray(AtomicReferenceArray<Object> slots, int index) {
        ControlledSchedule.point();
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(slots, index);
        Object released = HappensBefore.releasedSoFar(slots, ReferenceSlots.element(index));
        Object value = aba == null ? slots.get(index)
                : aba.read(slots, null, index, (h, s, i) -> asArray(h).get(i));
        slotAcquired(slots, ReferenceSlots.element(index), value, released);
        return value;
    }

    /**
     * Weaves {@code AtomicReferenceArray.getAcquire}, an acquiring read of one element.
     *
     * @param slots the array the call site invoked
     * @param index the element's index
     * @return the reference the element held
     * @since 1.12.4
     */
    public static @Nullable Object getAcquireReferenceArray(AtomicReferenceArray<Object> slots, int index) {
        ControlledSchedule.point();
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(slots, index);
        Object released = HappensBefore.releasedSoFar(slots, ReferenceSlots.element(index));
        Object value = aba == null ? slots.getAcquire(index)
                : aba.read(slots, null, index, (h, s, i) -> asArray(h).getAcquire(i));
        slotAcquired(slots, ReferenceSlots.element(index), value, released);
        return value;
    }

    /**
     * Weaves {@code VarHandle.getVolatile} on a reference instance field.
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is the slot
     * @return the reference the field held
     * @since 1.12.4
     */
    public static @Nullable Object getVolatileReferenceHandle(VarHandle handle, Object receiver) {
        ControlledSchedule.point();
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(receiver, handle);
        Object released = HappensBefore.releasedSoFar(receiver, ReferenceSlots.fieldOf(handle));
        Object value = aba == null ? handle.withInvokeBehavior().getVolatile(receiver)
                : aba.read(receiver, handle, 0, (h, s, i) -> asHandle(s).withInvokeBehavior().getVolatile(h));
        slotAcquired(receiver, ReferenceSlots.fieldOf(handle), value, released);
        return value;
    }

    /**
     * Weaves {@code VarHandle.getAcquire} on a reference instance field.
     *
     * @param handle   the handle the call site invoked
     * @param receiver the object whose field is the slot
     * @return the reference the field held
     * @since 1.12.4
     */
    public static @Nullable Object getAcquireReferenceHandle(VarHandle handle, Object receiver) {
        ControlledSchedule.point();
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(receiver, handle);
        Object released = HappensBefore.releasedSoFar(receiver, ReferenceSlots.fieldOf(handle));
        Object value = aba == null ? handle.withInvokeBehavior().getAcquire(receiver)
                : aba.read(receiver, handle, 0, (h, s, i) -> asHandle(s).withInvokeBehavior().getAcquire(h));
        slotAcquired(receiver, ReferenceSlots.fieldOf(handle), value, released);
        return value;
    }

    /**
     * Weaves {@code VarHandle.getVolatile} on a reference static field, keyed on the handle.
     *
     * @param handle the handle the call site invoked
     * @return the reference the field held
     * @since 1.12.4
     */
    public static @Nullable Object getVolatileStaticReferenceHandle(VarHandle handle) {
        ControlledSchedule.point();
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(handle);
        Object released = HappensBefore.releasedSoFar(handle, SLOT_VALUE);
        Object value = aba == null ? handle.withInvokeBehavior().getVolatile()
                : aba.read(handle, null, 0, (h, s, i) -> asHandle(h).withInvokeBehavior().getVolatile());
        slotAcquired(handle, SLOT_VALUE, value, released);
        return value;
    }

    /**
     * Weaves {@code VarHandle.getAcquire} on a reference static field, keyed on the handle.
     *
     * @param handle the handle the call site invoked
     * @return the reference the field held
     * @since 1.12.4
     */
    public static @Nullable Object getAcquireStaticReferenceHandle(VarHandle handle) {
        ControlledSchedule.point();
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(handle);
        Object released = HappensBefore.releasedSoFar(handle, SLOT_VALUE);
        Object value = aba == null ? handle.withInvokeBehavior().getAcquire()
                : aba.read(handle, null, 0, (h, s, i) -> asHandle(h).withInvokeBehavior().getAcquire());
        slotAcquired(handle, SLOT_VALUE, value, released);
        return value;
    }

    /**
     * Weaves {@code VarHandle.getVolatile} on a reference array element.
     *
     * @param handle the handle the call site invoked
     * @param array  the array whose element is the slot, keyed with the index
     * @param index  the element's index
     * @return the reference the element held
     * @since 1.12.4
     */
    public static @Nullable Object getVolatileArrayReferenceHandle(VarHandle handle, Object array, int index) {
        ControlledSchedule.point();
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(array, index);
        Object released = HappensBefore.releasedSoFar(array, ReferenceSlots.element(index));
        Object value = aba == null ? handle.withInvokeBehavior().getVolatile(array, index)
                : aba.read(array, handle, index, (h, s, i) -> asHandle(s).withInvokeBehavior().getVolatile(h, i));
        slotAcquired(array, ReferenceSlots.element(index), value, released);
        return value;
    }

    /**
     * Weaves {@code VarHandle.getAcquire} on a reference array element.
     *
     * @param handle the handle the call site invoked
     * @param array  the array whose element is the slot, keyed with the index
     * @param index  the element's index
     * @return the reference the element held
     * @since 1.12.4
     */
    public static @Nullable Object getAcquireArrayReferenceHandle(VarHandle handle, Object array, int index) {
        ControlledSchedule.point();
        ABAProblemDetector.AgentSlot aba = AgentConcurrencyUtilHooks.abaSlot(array, index);
        Object released = HappensBefore.releasedSoFar(array, ReferenceSlots.element(index));
        Object value = aba == null ? handle.withInvokeBehavior().getAcquire(array, index)
                : aba.read(array, handle, index, (h, s, i) -> asHandle(s).withInvokeBehavior().getAcquire(h, i));
        slotAcquired(array, ReferenceSlots.element(index), value, released);
        return value;
    }

    /**
     * The target a container-drained event carries instead of a field identifier (#664); see
     * {@link #OWNERSHIP_TAKEN} for why a reserved name costs nothing on the access path.
     */
    static final String OWNERSHIP_DRAINED = "#ownership-drained";

    /**
     * Records that the calling thread drained {@code container}, removing elements the event does
     * not name one by one.
     *
     * <p>Emitted by the {@code BlockingQueue.drainTo} hooks after the drain returns. Every offer
     * recorded into that container before this event is stale from here on, and the detector drops
     * them, so an element drained and put back through a call nothing observes cannot keep naming
     * its first offerer as owner (#664). Published after the drain, so an offer that drains before
     * it is dropped even when its element was not drained, which can only withhold a finding.
     *
     * <p>Allocation-free and non-throwing like every other hook on this path.
     *
     * @param container the queue that was drained, or {@code null}
     * @since 1.12.2
     */
    public static void ownershipDrained(@Nullable Object container) {
        if (container == null || STOPPED.get()) {
            return;
        }
        int identity = System.identityHashCode(container);
        BUFFER.publish(Thread.currentThread().threadId(), OWNERSHIP_DRAINED, false, 0L, false,
                Integer.MIN_VALUE, identity, false, 0, 0, identity);
    }

    /**
     * The target an event carries when a thread is about to start another, with the child's id in
     * the lock-fingerprint slot (#745); see {@link #OWNERSHIP_TAKEN} for why a reserved name.
     */
    static final String THREAD_STARTING = "#thread-starting";

    /**
     * The target an event carries when a thread hands a task to an executor, with the task's
     * token in the lock-fingerprint slot (#745).
     */
    static final String TASK_SUBMITTED = "#task-submitted";

    /**
     * The target an event carries when a thread begins a handed task, with its token (#745) and,
     * in the constant slot, how many handed tasks the thread was already running (#834).
     */
    static final String TASK_STARTED = "#task-started";

    /**
     * The target an event carries when a thread has finished the handed task it began (#745),
     * with how many it is still running in the constant slot (#834).
     */
    static final String TASK_ENDED = "#task-ended";

    /**
     * Records that the calling thread is about to start {@code child} (#745).
     *
     * <p>The bridge filters by thread, and the threads it knows are the runner's workers, so a
     * thread a worker starts used to be dropped with every access it made. This event tells the
     * bridge which thread started which: a thread started by one the bridge forwards is forwarded
     * too, from its first access. Published before the start, so it drains ahead of anything the
     * child publishes. Which run a thread belongs to is decided on the drain side, by the bridge of
     * the run that sees this event, so nothing here is left behind on the thread for a later run
     * to find.
     *
     * <p>Allocation-free and non-throwing like every other hook on this path.
     *
     * @param child the thread about to be started, not yet started; {@code null} records nothing
     * @since 1.12.3
     */
    public static void threadStarting(@Nullable Thread child) {
        if (child == null || STOPPED.get()) {
            return;
        }
        BUFFER.publish(Thread.currentThread().threadId(), THREAD_STARTING, false, child.threadId());
    }

    /**
     * Records that the calling thread hands the task {@code token} names to an executor (#745).
     *
     * <p>The executor starts its threads in code the agent does not weave, and the same thread runs
     * tasks for anyone, so a pool thread is attributed per task rather than per thread: a task
     * submitted by a thread the bridge forwards is forwarded while it runs, and nothing the thread
     * does before or after it is.
     *
     * @param token the task's token, unique in the JVM; see {@link #taskStarted}
     * @since 1.12.3
     */
    public static void taskSubmitted(long token) {
        if (STOPPED.get()) {
            return;
        }
        BUFFER.publish(Thread.currentThread().threadId(), TASK_SUBMITTED, false, token);
    }

    /**
     * How many handed tasks each thread is running, one inside another (#834). Kept whether or not
     * the registry runs, so the count stays true across a stop and a start.
     */
    private static final ThreadLocal<int[]> TASK_DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    /**
     * Records that the calling thread begins running the task {@code token} names (#745); must be
     * followed by {@link #taskEnded()} on the same thread when the task ends, however it ends.
     *
     * <p>The event also carries how many handed tasks the thread was already running (#834). The
     * ring gives up on an event only after a sustained stall, and a lost end used to leave a pool
     * thread working for the run that submitted the task until that run ended; the next start or
     * end on the thread now says how deep it really is, and the bridge drops what it missed.
     *
     * @param token the token {@link #taskSubmitted} published for the task
     * @since 1.12.3
     */
    public static void taskStarted(long token) {
        int[] depth = TASK_DEPTH.get();
        int running = depth[0];
        depth[0] = running + 1;
        if (STOPPED.get()) {
            return;
        }
        BUFFER.publish(Thread.currentThread().threadId(), TASK_STARTED, false, token, false,
                running);
    }

    /**
     * Records that the calling thread finished the task it began last (#745), and how many handed
     * tasks it is still running (#834).
     *
     * @since 1.12.3
     */
    public static void taskEnded() {
        int[] depth = TASK_DEPTH.get();
        int running = Math.max(depth[0] - 1, 0);
        depth[0] = running;
        if (STOPPED.get()) {
            return;
        }
        BUFFER.publish(Thread.currentThread().threadId(), TASK_ENDED, false, 0L, false, running);
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
        ControlledSchedule.released(monitor);
        HeldLocks.released(monitor);
        AgentMonitorHooks.lockOrderMonitorExited(monitor);
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
     * <p>The callback field is {@code volatile} and the drain reads it for every event, so an
     * event recorded after the swap goes to the new callback even when a drain was already under
     * way. Passing {@code null} restores the no-op
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
            executor.submit(TelemetryRegistry::drainAndSettle).get(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
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

    /**
     * {@link #drainOnce()}, then tells the callback the flush it asked for is complete, on the
     * drain thread as every callback method runs (#740). Only a requested flush settles: the
     * periodic drain runs while producers are still running, and settling there would close a
     * speculation whose validation had not been published yet.
     */
    private static void drainAndSettle() {
        drainOnce();
        TelemetryEventBuffer.DrainCallback cb = drainCallback;
        if (cb == null) {
            return;
        }
        try {
            cb.onFlush();
        } catch (RuntimeException | StackOverflowError ignored) { // NOPMD EmptyCatchBlock - same containment rule as drainOnce
            // Best-effort like the drain itself: a failing consumer must not fail the flush.
        }
    }

    /**
     * Delivers each drained event to the callback installed when that event is drained, not the
     * one installed when the drain began. A drain runs until the ring is empty, so one that read
     * the callback once and was then preempted gave every event the next run published meanwhile
     * to the callback that run had replaced, and the run's detectors never saw them
     * (VolatilePublicationWeavingTest silent on CI, 2026-10-08). Read per event, the callback is
     * the one whose installation happened before the producer published: the run installs its
     * bridge, then starts the threads whose events it wants, and the drain acquires each event.
     */
    private static final TelemetryEventBuffer.DrainCallback CURRENT = new TelemetryEventBuffer.DrainCallback() {
        @Override
        public void onEvent(long threadId, @Nullable String targetField, boolean isWrite) {
            TelemetryEventBuffer.DrainCallback cb = drainCallback;
            if (cb != null) {
                cb.onEvent(threadId, targetField, isWrite);
            }
        }

        @Override
        public void onEvent(long threadId, @Nullable String targetField, boolean isWrite,
                            long lockFingerprint, boolean volatileField, int constantTag,
                            int identity, boolean afterVolatileRead, int ownMonitor,
                            int methodMonitor, int storedIdentity, @Nullable Object receiver,
                            HappensBefore.@Nullable Stamp stamp, long round) {
            TelemetryEventBuffer.DrainCallback cb = drainCallback;
            if (cb != null) {
                cb.onEvent(threadId, targetField, isWrite, lockFingerprint, volatileField, constantTag,
                        identity, afterVolatileRead, ownMonitor, methodMonitor, storedIdentity,
                        receiver, stamp, round);
            }
        }
    };

    private static void drainOnce() {
        try {
            BUFFER.drain(CURRENT);
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
