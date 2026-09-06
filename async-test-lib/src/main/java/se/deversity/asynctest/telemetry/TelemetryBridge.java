package se.deversity.asynctest.telemetry;

import java.util.Set;
import java.util.function.LongPredicate;

import org.jspecify.annotations.Nullable;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.diagnostics.AtomicityValidator;
import se.deversity.asynctest.diagnostics.VisibilityMonitor;

/**
 * Bridges agent-captured field-access telemetry into the library's live per-test
 * detectors, closing the loop between the {@code AsyncTestAgent} instrumentation pipeline
 * and concurrency analysis.
 *
 * <p>The agent weaves getter/setter accessors so that every intercepted access publishes
 * a {@code (threadId, qualifiedName, isWrite)} event into the {@link TelemetryEventBuffer}.
 * A background drain thread flushes those events via {@link TelemetryRegistry}. Without a
 * consumer the events are discarded; this bridge is that consumer. Registered as the
 * registry's {@link TelemetryEventBuffer.DrainCallback}, it forwards the events, filtered
 * to the stress-test worker threads, into an {@link AtomicityValidator} so that
 * agent-observed accesses participate in the same cross-thread analysis as manually
 * recorded ones — with no {@code recordFieldAccess()} calls in the code under test.
 *
 * <h2>Routing decision</h2>
 * The agent has method-name granularity only: it knows <em>that</em> a getter/setter ran
 * and on which thread, but it has no field <em>value</em>. Events are therefore routed to
 * exactly one detector:
 * <ul>
 *   <li><b>{@link AtomicityValidator}</b> — routed. Its cross-thread mixed read/write
 *       analysis ({@link AtomicityValidator#analyzeAtomicity()}) depends only on the
 *       thread id and the read/write flag, both of which the agent supplies, and it
 *       tolerates a {@code null} value. Events are forwarded through the explicit-thread-id
 *       overload {@link AtomicityValidator#recordFieldAccess(String, Object, boolean, long)}
 *       so the access is attributed to the originating <em>worker</em> thread and not to
 *       the drain thread that replays it.</li>
 *   <li><b>{@link VisibilityMonitor}</b> — <em>not</em> routed. Its visibility analysis is
 *       value-equality based (it flags a field whose observed values diverge across
 *       invocation rounds), so an access stream with no values carries no signal for it.
 *       Worse, {@code VisibilityMonitor.recordFieldAccess(id, null)} throws
 *       {@link NullPointerException} because it stores the value in a
 *       {@code ConcurrentHashMap}-backed set, which forbids {@code null}. Should a future
 *       agent version capture values, a value-aware overload of {@code activate} can be
 *       added without breaking this one.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * {@link #onEvent} runs on the single telemetry drain thread, while {@link #activate}
 * and {@link #close()} are called from test threads. The bridge holds its enabled state
 * in a {@code volatile} flag, so a {@code close()} on a test thread is promptly visible to
 * the drain thread; the worker-thread-id set is immutable ({@link Set#copyOf}) and the
 * target detector is itself thread-safe. The bridge allocates nothing per event beyond the
 * detector's own bookkeeping.
 *
 * <h2>Lifecycle</h2>
 * {@link #activate(AtomicityValidator, Set)} registers the bridge as the
 * registry callback and returns it; {@link #close()} (equivalently {@link #deactivate()})
 * detaches it by restoring the registry's no-op callback. {@code close()} is idempotent, so
 * the bridge is usable with try-with-resources:
 *
 * <pre>{@code
 * Set<Long> workerIds = ...;            // ids of the stress-test worker threads
 * AtomicityValidator av = ...;          // the live per-test detector
 * try (TelemetryBridge bridge = TelemetryBridge.activate(av, workerIds)) {
 *     // ... run the code under test; agent events flow into av ...
 * }                                     // bridge detaches here
 * }</pre>
 *
 * <p>The registry holds a single callback, so at most one bridge should be active at a
 * time; {@code close()} restores the no-op callback unconditionally.
 *
 * @since 1.7.0
 */
public final class TelemetryBridge implements TelemetryEventBuffer.DrainCallback, AutoCloseable {

    private final AtomicityValidator atomicityValidator;
    private final LongPredicate workerFilter;

    /**
     * Enabled flag. Written by {@link #close()} on a test thread and read by
     * {@link #onEvent} on the drain thread, hence {@code volatile}. Once cleared the bridge
     * forwards no further events even if the registry has not yet swapped the callback.
     */
    private volatile boolean active;

    /**
     * Accesses dropped because the thread that made them is not one of the runner's workers.
     *
     * <p>A test body that submits its racing work to its own executor, or starts
     * {@code new Thread(...)}, produces accesses on threads the runner never registered, and the
     * filter above discards them. That is the honest thing to do - the bridge cannot attribute
     * them to a round - but a clean atomicity report then means "nothing was observed" rather
     * than "nothing was wrong", and nothing used to say so. Counting them lets the runner
     * announce the gap (#500).
     */
    private final java.util.concurrent.atomic.LongAdder droppedNonWorkerEvents =
            new java.util.concurrent.atomic.LongAdder();

    private TelemetryBridge(AtomicityValidator atomicityValidator, LongPredicate workerFilter) {
        this.atomicityValidator = atomicityValidator;
        this.workerFilter = workerFilter;
    }

    /**
     * {@return an active bridge that {@link TelemetryRegistry} has never been told about}
     *
     * <p>Every public factory ends in {@link TelemetryRegistry#start}, because a bridge only earns
     * its keep once agent events flow into it. That also makes a bridge impossible to exercise on
     * its own: obtaining one installs it as the process-wide drain callback, so a test that puts a
     * bridge under contention hijacks the telemetry of the run driving the test. This builds one
     * the registry never sees, so the bridge's own behaviour, in particular that {@link #close()}
     * is a hard stop, can be put under contention without touching global state.
     *
     * <p>Package-private on purpose. This is a seam for the tests in this package, not API: a
     * bridge that forwards nowhere is of no use to a caller outside them.
     *
     * @param atomicityValidator the detector to feed
     * @param workerFilter       which thread ids to forward
     */
    static TelemetryBridge detached(AtomicityValidator atomicityValidator,
                                    LongPredicate workerFilter) {
        TelemetryBridge bridge = new TelemetryBridge(atomicityValidator, workerFilter);
        bridge.active = true;
        return bridge;
    }

    /**
     * Creates a bridge and registers it as the {@link TelemetryRegistry} drain callback so
     * that agent field-access events begin flowing into {@code atomicityValidator}.
     *
     * <p>Only events whose {@code threadId} is in {@code workerThreadIds} are forwarded;
     * accesses from other application threads during the round are treated as noise for
     * per-round analysis and dropped. The set is defensively copied
     * ({@link Set#copyOf}) so later mutation of the caller's set has no effect.
     *
     * @param atomicityValidator the live detector to feed; must not be {@code null}
     * @param workerThreadIds    ids of the stress-test worker threads whose events should be
     *                           forwarded; must not be {@code null} (may be empty, which
     *                           forwards nothing)
     * @return the activated bridge, registered as the drain callback
     * @throws NullPointerException if {@code atomicityValidator} or {@code workerThreadIds}
     *                              is {@code null}
     * @since 1.7.0
     */
    public static TelemetryBridge activate(AtomicityValidator atomicityValidator,
                                           Set<Long> workerThreadIds) {
        if (atomicityValidator == null) {
            throw new NullPointerException("atomicityValidator must not be null");
        }
        if (workerThreadIds == null) {
            throw new NullPointerException("workerThreadIds must not be null");
        }
        TelemetryBridge bridge = new TelemetryBridge(atomicityValidator, Set.copyOf(workerThreadIds)::contains);
        bridge.active = true;
        TelemetryRegistry.start(bridge);
        return bridge;
    }

    /**
     * Creates a bridge that forwards events from any thread the supplied filter accepts, and
     * registers it as the {@link TelemetryRegistry} drain callback.
     *
     * <p>Unlike {@link #activate(AtomicityValidator, Set)}, which snapshots the ids up front,
     * this overload consults {@code workerFilter} per event. That is what the runner needs:
     * a round's worker threads do not exist yet when the bridge has to be attached, and with
     * virtual threads each round brings new ones. The runner therefore passes a filter backed
     * by a concurrent set that each worker adds itself to as it starts, so a thread begins
     * being observed the moment it joins the run.
     *
     * <p>{@code workerFilter} is called on the telemetry drain thread, once per drained
     * event, so it must be thread-safe and cheap. A {@code Set::contains} on a concurrent
     * set is both.
     *
     * @param atomicityValidator the live detector to feed; must not be {@code null}
     * @param workerFilter       accepts the thread ids whose events should be forwarded;
     *                           must not be {@code null}
     * @return the activated bridge, registered as the drain callback
     * @throws NullPointerException if either argument is {@code null}
     * @since 1.7.0
     */
    public static TelemetryBridge activateWithFilter(AtomicityValidator atomicityValidator,
                                                     LongPredicate workerFilter) {
        if (atomicityValidator == null) {
            throw new NullPointerException("atomicityValidator must not be null");
        }
        if (workerFilter == null) {
            throw new NullPointerException("workerFilter must not be null");
        }
        TelemetryBridge bridge = new TelemetryBridge(atomicityValidator, workerFilter);
        bridge.active = true;
        TelemetryRegistry.start(bridge);
        return bridge;
    }

    /**
     * Convenience factory that wires a bridge to the detectors of the {@code @AsyncTest}
     * context active on the current thread, for use inside a per-invocation hook.
     *
     * <p>Resolves the live {@link AtomicityValidator} via
     * {@link AsyncTestContext#atomicityValidator()} and activates a bridge for the supplied
     * worker-thread ids.
     *
     * @param workerThreadIds ids of the stress-test worker threads whose events should be
     *                        forwarded; must not be {@code null}
     * @return the activated bridge
     * @throws IllegalStateException if there is no active {@code @AsyncTest} context on the
     *                               current thread, or {@code detectAtomicityViolations} is
     *                               disabled for it
     * @throws NullPointerException  if {@code workerThreadIds} is {@code null}
     * @since 1.7.0
     */
    public static TelemetryBridge forCurrentContext(Set<Long> workerThreadIds) {
        return activate(AsyncTestContext.atomicityValidator(), workerThreadIds);
    }

    /**
     * Forwards a single drained agent event into the target detector, filtered by worker
     * thread id. Invoked by the telemetry drain thread.
     *
     * <p>No-op once the bridge is {@linkplain #close() closed}, and no-op for events whose
     * {@code threadId} is not among the configured worker threads. Forwarded events carry a
     * {@code null} value and are attributed to {@code threadId} via the explicit-thread-id
     * overload, so analysis reflects the originating worker thread rather than this drain
     * thread.
     *
     * @param threadId      the id of the thread that performed the access
     * @param qualifiedName the {@code declaringClass.methodName} accessor identifier
     * @param isWrite       {@code true} for a setter (write), {@code false} for a getter (read)
     */
    @Override
    public void onEvent(long threadId, @Nullable String qualifiedName, boolean isWrite) {
        onEvent(threadId, qualifiedName, isWrite, 0L);
    }

    /**
     * Routes one drained access, carrying the locks the worker held when it happened.
     *
     * <p>The lock information cannot be recovered here: this runs on the drain thread, which
     * holds none of what the worker held. It is captured at publish time on the worker and rides
     * through the ring buffer as a fingerprint, which is what that allocation-free producer path
     * can carry. A field always accessed under the same locks is not reported; one accessed under
     * differing locks, or none, is.
     *
     * @param threadId        worker thread the access came from
     * @param qualifiedName   the {@code declaringClass.methodName} accessor identifier
     * @param isWrite         {@code true} for a setter (write), {@code false} for a getter (read)
     * @param lockFingerprint identifies the locks held at the access, 0 for none
     * @since 1.9.6
     */
    @Override
    public void onEvent(long threadId, @Nullable String qualifiedName, boolean isWrite,
                        long lockFingerprint) {
        onEvent(threadId, qualifiedName, isWrite, lockFingerprint, false);
    }

    /**
     * Forwards a drained access that also says whether the field is declared {@code volatile}.
     *
     * @param threadId        the worker that recorded the access
     * @param qualifiedName   the field identifier the weaver emitted
     * @param isWrite         {@code true} for a write access
     * @param lockFingerprint the locks that worker held at the access, 0 for none
     * @param volatileField   whether the field is declared {@code volatile}
     * @since 1.9.8
     */
    @Override
    public void onEvent(long threadId, @Nullable String qualifiedName, boolean isWrite,
                        long lockFingerprint, boolean volatileField) {
        onEvent(threadId, qualifiedName, isWrite, lockFingerprint, volatileField, Integer.MIN_VALUE);
    }

    /**
     * Forwards a drained access that also carries what a constant write stored.
     *
     * @param threadId        the worker that recorded the access
     * @param qualifiedName   the field identifier the weaver emitted
     * @param isWrite         {@code true} for a write access
     * @param lockFingerprint the locks that worker held at the access, 0 for none
     * @param volatileField   whether the field is declared {@code volatile}
     * @param constantTag     the constant stored, or {@code Integer.MIN_VALUE} for "not a constant"
     * @since 1.9.8
     */
    @Override
    public void onEvent(long threadId, @Nullable String qualifiedName, boolean isWrite,
                        long lockFingerprint, boolean volatileField, int constantTag) {
        onEvent(threadId, qualifiedName, isWrite, lockFingerprint, volatileField, constantTag, 0);
    }

    /**
     * Forwards a drained access that also identifies the instance the field belongs to.
     *
     * @param threadId        the worker that recorded the access
     * @param qualifiedName   the field identifier the weaver emitted
     * @param isWrite         {@code true} for a write access
     * @param lockFingerprint the locks that worker held at the access, 0 for none
     * @param volatileField   whether the field is declared {@code volatile}
     * @param constantTag     the constant stored, {@code Integer.MIN_VALUE} for none
     * @param identity        {@code System.identityHashCode} of the owner, 0 for statics
     * @since 1.9.8
     */
    @Override
    public void onEvent(long threadId, @Nullable String qualifiedName, boolean isWrite,
                        long lockFingerprint, boolean volatileField, int constantTag,
                        int identity) {
        onEvent(threadId, qualifiedName, isWrite, lockFingerprint, volatileField, constantTag,
                identity, false);
    }

    /**
     * Forwards a drained access with the volatile-read ordering bit as well.
     *
     * @param threadId          the worker that recorded the access
     * @param qualifiedName     the field identifier the weaver emitted
     * @param isWrite           {@code true} for a write access
     * @param lockFingerprint   the locks that worker held, 0 for none
     * @param volatileField     whether the field is declared {@code volatile}
     * @param constantTag       the constant stored, {@code Integer.MIN_VALUE} for none
     * @param identity          identity hash of the owner, 0 for statics
     * @param afterVolatileRead whether a volatile field of the owner was read first
     * @since 1.9.8
     */
    @Override
    public void onEvent(long threadId, @Nullable String qualifiedName, boolean isWrite,
                        long lockFingerprint, boolean volatileField, int constantTag,
                        int identity, boolean afterVolatileRead) {
        onEvent(threadId, qualifiedName, isWrite, lockFingerprint, volatileField, constantTag,
                identity, afterVolatileRead, 0, 0);
    }

    /**
     * Forwards an agent-captured access, with the monitors the lockset could not see, to the
     * {@link AtomicityValidator}.
     *
     * @param threadId          producer thread
     * @param qualifiedName     field identifier as the weaver emitted it
     * @param isWrite           true for a write
     * @param lockFingerprint   locks held at the access, 0 for none
     * @param volatileField     whether the field is declared {@code volatile}
     * @param constantTag       the constant stored, {@code Integer.MIN_VALUE} for none
     * @param identity          identity hash of the owner, 0 for statics
     * @param afterVolatileRead whether a volatile field of the owner was read first
     * @param ownMonitor        identity hash of the receiver when its monitor was held, else 0
     * @param methodMonitor     identity hash of the enclosing synchronized method's monitor, else 0
     * @since 1.9.8
     */
    @Override
    public void onEvent(long threadId, @Nullable String qualifiedName, boolean isWrite,
                        long lockFingerprint, boolean volatileField, int constantTag,
                        int identity, boolean afterVolatileRead, int ownMonitor,
                        int methodMonitor) {
        onEvent(threadId, qualifiedName, isWrite, lockFingerprint, volatileField, constantTag,
                identity, afterVolatileRead, ownMonitor, methodMonitor, 0);
    }

    /**
     * Forwards an agent-captured access, also carrying the reference the write stored.
     *
     * @param threadId          producer thread
     * @param qualifiedName     field identifier as the weaver emitted it
     * @param isWrite           true for a write
     * @param lockFingerprint   locks held at the access, 0 for none
     * @param volatileField     whether the field is declared {@code volatile}
     * @param constantTag       the constant stored, {@code Integer.MIN_VALUE} for none
     * @param identity          identity hash of the owner, 0 for statics
     * @param afterVolatileRead whether a volatile field of the owner was read first
     * @param ownMonitor        identity hash of the receiver when its monitor was held, else 0
     * @param methodMonitor     identity hash of the enclosing synchronized method's monitor, else 0
     * @param storedIdentity    identity hash of the reference this write stored, 0 when unknown
     * @since 1.9.8
     */
    @Override
    public void onEvent(long threadId, @Nullable String qualifiedName, boolean isWrite,
                        long lockFingerprint, boolean volatileField, int constantTag,
                        int identity, boolean afterVolatileRead, int ownMonitor,
                        int methodMonitor, int storedIdentity) {
        if (!active) {
            return;
        }
        if (!workerFilter.test(threadId)) {
            droppedNonWorkerEvents.increment();
            return;
        }
        if (qualifiedName == null) return;
        String field = fieldIdentifier(qualifiedName);
        // A field under a lock-free protocol is not something a lockset can judge. Dropping the
        // event rather than passing it on keeps that honest: the detectors say nothing about the
        // field instead of saying the wrong thing about every access to it.
        //
        // Asked with both names on purpose. A field instruction reports the field itself, while a
        // woven accessor reports "Type#setNext" and only becomes "Type.next" after normalisation,
        // so checking the raw name alone silently misses every access that arrived through an
        // accessor - which is how these fields are usually touched.
        if (TelemetryRegistry.isAtomicallyManaged(qualifiedName)
                || TelemetryRegistry.isAtomicallyManaged(field)) {
            // Drop this access, and everything already recorded about the field. The binding that
            // proves the field is lock-free lives in a static initializer, which can run after the
            // first accesses were forwarded, and those early accesses are exactly what produces
            // the finding. Retracting makes the answer independent of that ordering.
            atomicityValidator.forgetField(field);
            return;
        }
        // Both halves of the publish-via-volatile idiom, resolved here so the detector sees one
        // answer rather than two facts it would have to combine itself.
        boolean safelyPublished = !isWrite
                && afterVolatileRead
                && TelemetryRegistry.isPublishedByVolatile(qualifiedName);
        atomicityValidator.recordFieldAccessUnderLocks(field, null, isWrite, threadId,
                lockFingerprint, ownMonitor, methodMonitor, volatileField || safelyPublished,
                constantTag, identity, storedIdentity);
    }

    /**
     * {@return how many accesses were dropped because their thread is not a runner worker}
     *
     * <p>Non-zero means the agent saw field accesses this run cannot attribute: they came from
     * threads the test body started itself. The atomicity evidence for those accesses is absent
     * from the report, so a clean report covers the workers and nothing else.
     *
     * @since 1.11.2
     */
    public long droppedNonWorkerEvents() {
        return droppedNonWorkerEvents.sum();
    }

    /**
     * Detaches the bridge from the {@link TelemetryRegistry}, restoring its no-op drain
     * callback so no further events are forwarded. Equivalent to {@link #close()}.
     *
     * @since 1.7.0
     */
    public void deactivate() {
        close();
    }

    /**
     * Detaches the bridge from the telemetry pipeline. Idempotent: the first call clears the
     * {@code volatile} enabled flag (so an in-flight {@link #onEvent} on the drain thread
     * stops forwarding) and releases the registry's callback slot <em>if this bridge is still
     * holding it</em>; subsequent calls return immediately. Mirrors the idempotency pattern of
     * {@code AsyncTestListenerRegistry.Registration.close()}.
     *
     * <p><strong>Why the release is conditional.</strong> The registry holds one callback, so
     * two {@code @AsyncTest} runs in one JVM take the slot from each other and the loser
     * under-reports — a documented trade-off. Clearing unconditionally turned that into
     * something worse: when the loser finished first it wiped the winner's callback, and the
     * run that legitimately held the slot received no further events, detected nothing, and
     * passed green. Releasing only our own registration keeps the damage on the bridge that is
     * actually shutting down.
     *
     * <p>Does not stop the registry drain thread; that lifecycle stays with
     * {@link TelemetryRegistry#stop()}.
     */
    @Override
    public void close() {
        if (!active) {
            return;
        }
        active = false;
        TelemetryRegistry.clearCallbackIf(this);
    }

    /**
     * Maps an accessor identifier to the field it accesses, so a getter and its setter
     * correlate.
     *
     * <p><strong>Why this exists.</strong> The advice identifies an access by
     * {@code declaringType.methodName}, which means {@code Account.getBalance} and
     * {@code Account.setBalance} arrive as two unrelated identifiers. {@code AtomicityValidator}
     * keys its history by identifier and reports a field seen by more than one thread with both
     * a read and a write — and a getter identifier only ever carries reads while a setter
     * identifier only ever carries writes. That finding, the one the analysis exists for, could
     * therefore never fire from agent data no matter how racy the code was. Only the weaker
     * write-only branch could.
     *
     * <p>Normalising both to {@code Account.balance} puts them in one bucket, so a field one
     * thread reads while another writes is reported as what it is.
     *
     * <p>Done here, on the drain thread, rather than in the advice: the advice prologue is
     * deliberately allocation-free and its identifier is a constant-pool string, so stripping a
     * prefix there would put string work on every intercepted access.
     *
     * <p>Conservative by design. Only {@code get}/{@code is}/{@code set} followed by an
     * upper-case letter is treated as an accessor, so {@code getter()} or {@code isolate()} —
     * which the weaver's JavaBean matchers can also select — keep their own identifier rather
     * than being folded into a nonsense field name. Anything unrecognised is returned unchanged,
     * which also leaves identifiers from manual {@code TelemetryRegistry.recordAccess} callers
     * alone.
     *
     * @param qualifiedName the {@code declaringType.methodName} identifier from the advice
     * @return the field-level identifier, or {@code qualifiedName} if it is not an accessor
     */
    static String fieldIdentifier(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        if (dot < 0 || dot == qualifiedName.length() - 1) {
            return qualifiedName;
        }
        String method = qualifiedName.substring(dot + 1);
        String property = propertyName(method);
        return property == null ? qualifiedName : qualifiedName.substring(0, dot + 1) + property;
    }

    /**
     * {@return the JavaBean property {@code method} accesses, or {@code null} if it is not a
     * bean accessor}
     */
    private static @Nullable String propertyName(String method) {
        if (method.startsWith("get") || method.startsWith("set")) {
            return afterPrefix(method, 3);
        }
        if (method.startsWith("is")) {
            return afterPrefix(method, 2);
        }
        return null;
    }

    private static @Nullable String afterPrefix(String method, int prefixLength) {
        if (method.length() <= prefixLength) {
            return null;
        }
        char first = method.charAt(prefixLength);
        if (!Character.isUpperCase(first)) {
            return null;
        }
        return Character.toLowerCase(first) + method.substring(prefixLength + 1);
    }
}
