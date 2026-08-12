package se.deversity.asynctest.telemetry;

import org.jspecify.annotations.Nullable;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private static @Nullable ScheduledExecutorService drainExecutor = null;
    private static @Nullable Thread shutdownHook = null;

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
        if (STOPPED.get()) {
            return;
        }
        BUFFER.publish(threadId, qualifiedName, isWrite);
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
     * Starts the background drain thread.  Idempotent — safe to call multiple times.
     *
     * @param callback consumer invoked on each drained event; may be {@code null} to
     *                 use a no-op default (events are simply discarded after drain)
     */
    // The scheduleAtFixedRate ScheduledFuture is intentionally not retained: the periodic
    // drain is stopped by shutting down drainExecutor in stop(), not by cancelling the Future.
    @SuppressWarnings("FutureReturnValueIgnored")
    public static void start(TelemetryEventBuffer.@Nullable DrainCallback callback) {
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
    public static synchronized boolean clearCallbackIf(
            TelemetryEventBuffer.@Nullable DrainCallback expected) {
        if (drainCallback == expected) {
            drainCallback = null;
            return true;
        }
        return false;
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
