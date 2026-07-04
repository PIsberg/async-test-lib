package se.deversity.asynctest.telemetry;

import java.util.Set;

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
 *       {@code ConcurrentHashMap}-backed set, which forbids {@code null}. The
 *       {@code activate} signature still accepts a {@link VisibilityMonitor} for API
 *       symmetry and forward compatibility, but the bridge never calls it; you may pass
 *       {@code null}.</li>
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
 * {@link #activate(VisibilityMonitor, AtomicityValidator, Set)} registers the bridge as the
 * registry callback and returns it; {@link #close()} (equivalently {@link #deactivate()})
 * detaches it by restoring the registry's no-op callback. {@code close()} is idempotent, so
 * the bridge is usable with try-with-resources:
 *
 * <pre>{@code
 * Set<Long> workerIds = ...;            // ids of the stress-test worker threads
 * AtomicityValidator av = ...;          // the live per-test detector
 * try (TelemetryBridge bridge = TelemetryBridge.activate(null, av, workerIds)) {
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
    private final Set<Long> workerThreadIds;

    /**
     * Enabled flag. Written by {@link #close()} on a test thread and read by
     * {@link #onEvent} on the drain thread, hence {@code volatile}. Once cleared the bridge
     * forwards no further events even if the registry has not yet swapped the callback.
     */
    private volatile boolean active;

    private TelemetryBridge(AtomicityValidator atomicityValidator, Set<Long> workerThreadIds) {
        this.atomicityValidator = atomicityValidator;
        this.workerThreadIds = Set.copyOf(workerThreadIds);
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
     * @param visibilityMonitor accepted for API symmetry but <em>not</em> used — see the
     *                           class Javadoc routing decision; may be {@code null}
     * @param atomicityValidator the live detector to feed; must not be {@code null}
     * @param workerThreadIds    ids of the stress-test worker threads whose events should be
     *                           forwarded; must not be {@code null} (may be empty, which
     *                           forwards nothing)
     * @return the activated bridge, registered as the drain callback
     * @throws NullPointerException if {@code atomicityValidator} or {@code workerThreadIds}
     *                              is {@code null}
     * @since 1.7.0
     */
    public static TelemetryBridge activate(VisibilityMonitor visibilityMonitor,
                                           AtomicityValidator atomicityValidator,
                                           Set<Long> workerThreadIds) {
        if (atomicityValidator == null) {
            throw new NullPointerException("atomicityValidator must not be null");
        }
        if (workerThreadIds == null) {
            throw new NullPointerException("workerThreadIds must not be null");
        }
        // visibilityMonitor is intentionally unused: the bridge cannot feed it (no values).
        TelemetryBridge bridge = new TelemetryBridge(atomicityValidator, workerThreadIds);
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
     * worker-thread ids. Because the bridge does not route to a {@link VisibilityMonitor}
     * (see the class routing decision), {@code null} is passed for it.
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
        return activate(null, AsyncTestContext.atomicityValidator(), workerThreadIds);
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
    public void onEvent(long threadId, String qualifiedName, boolean isWrite) {
        if (!active) {
            return;
        }
        if (!workerThreadIds.contains(threadId)) {
            return;
        }
        atomicityValidator.recordFieldAccess(qualifiedName, null, isWrite, threadId);
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
     * stops forwarding) and restores the registry's no-op callback; subsequent calls return
     * immediately. Mirrors the idempotency pattern of
     * {@code AsyncTestListenerRegistry.Registration.close()}.
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
        TelemetryRegistry.setCallback(null);
    }
}
