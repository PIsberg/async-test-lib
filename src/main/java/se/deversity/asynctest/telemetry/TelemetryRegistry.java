package se.deversity.asynctest.telemetry;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Global registry that routes field-access events from producer threads into a shared
 * {@link TelemetryEventBuffer} and drains them asynchronously via a background thread.
 *
 * <p>Called from the {@code AsyncTestAgent} advice on every intercepted field access.
 * The path through {@link #recordAccess} must remain allocation-free and lock-free.
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
    private static final TelemetryEventBuffer BUFFER = new TelemetryEventBuffer(BUFFER_CAPACITY);
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private static volatile TelemetryEventBuffer.DrainCallback drainCallback = null;
    private static ScheduledExecutorService drainExecutor = null;
    private static Thread shutdownHook = null;

    private TelemetryRegistry() {}

    /**
     * Records a field access from the instrumented thread.
     *
     * <p>Hot path — must remain allocation-free and lock-free.
     *
     * @param threadId    {@code Thread.currentThread().threadId()}
     * @param className   declaring class of the intercepted getter/setter
     * @param methodName  intercepted method name (e.g. {@code "getCount"}, {@code "setCount"})
     */
    public static void recordAccess(long threadId, String className, String methodName) {
        BUFFER.publish(threadId, className + "#" + methodName,
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
    public static void start(TelemetryEventBuffer.DrainCallback callback) {
        if (!RUNNING.compareAndSet(false, true)) {
            // Already running, but allow updating the callback.
            drainCallback = callback;
            return;
        }
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

    /** Starts the registry with a no-op drain callback (events counted but not forwarded). */
    public static void start() {
        start(null);
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

    /** Exposes the shared buffer for testing and advanced consumers. */
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
        } catch (RuntimeException e) { // NOPMD EmptyCatchBlock — best-effort drain must survive a misbehaving callback
            // scheduleAtFixedRate cancels all future executions if the task throws;
            // telemetry is best-effort, so swallow and keep the periodic drainer alive.
        }
    }
}
