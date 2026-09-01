package se.deversity.asynctest;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import org.jspecify.annotations.Nullable;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AIIdempotent;
import se.deversity.vibetags.annotations.AIPublicAPI;

import java.util.List;

/**
 * Thread-safe registry for {@link AsyncTestListener} instances.
 *
 * <p>This class manages a global (JVM-wide) list of listeners that receive
 * callbacks for async-test lifecycle events. It is the static face of a single
 * {@link ListenerRegistryCore}, which holds the listener set as one immutable list swapped
 * atomically, so a fire never observes a half-applied change.
 *
 * <p><strong>⚠ Lifetime warning:</strong> The listener list is <em>JVM-wide</em>
 * static state. A listener registered in one test will continue to fire in every
 * subsequent test that runs in the same JVM unless explicitly unregistered. Prefer
 * one of the scoped patterns below to avoid cross-test leakage:
 *
 * <ul>
 *   <li>{@link #registerScoped(AsyncTestListener)} returns an {@link AutoCloseable}
 *       you can use with try-with-resources to scope a listener to a single test.</li>
 *   <li>{@link #snapshot()} / {@link #restoreSnapshot(Snapshot)} let you save and
 *       restore the full registry around a block (useful in {@code @BeforeEach} /
 *       {@code @AfterEach}).</li>
 * </ul>
 *
 * <p><strong>Recommended usage (scoped):</strong>
 * <pre>{@code
 * try (var ignored = AsyncTestListenerRegistry.registerScoped(myListener)) {
 *     // ... run code; listener fires only inside this block
 * }
 * }</pre>
 *
 * <p><strong>Legacy usage (unscoped — caller responsible for cleanup):</strong>
 * <pre>{@code
 * AsyncTestListenerRegistry.register(new MyCustomListener());
 * // ... later ...
 * AsyncTestListenerRegistry.unregister(myListener);
 * }</pre>
 *
 * <p><strong>Default Behavior:</strong> If no listeners are registered, the
 * framework prints detector reports to {@code System.err} (backward-compatible behavior).
 *
 * <p><strong>Opt-out:</strong> To silence all output, register a
 * {@link NoopAsyncTestListener} instance.
 *
 * @see AsyncTestListener
 * @see NoopAsyncTestListener
 */
@AIContract(reason = "Public API for registering and unregistering AsyncTestListener instances. register(), unregister(), clearAll(), and fireXxx() methods are called by user code and infrastructure — signatures must not change.")
@AIPublicAPI
@API(status = Status.STABLE)
public final class AsyncTestListenerRegistry {

    /** The one listener set this JVM has. Everything below is a name for an operation on it. */
    private static final ListenerRegistryCore GLOBAL = new ListenerRegistryCore();

    // Prevent instantiation
    private AsyncTestListenerRegistry() {}

    /**
     * Registers a listener to receive async-test events.
     *
     * @param listener the listener to register (must not be null)
     * @throws IllegalArgumentException if listener is null
     */
    public static void register(AsyncTestListener listener) {
        GLOBAL.register(listener);
    }

    /**
     * Unregisters a previously registered listener.
     *
     * @param listener the listener to unregister
     * @return true if the listener was registered and has been removed
     */
    @AIIdempotent(reason = "Removes one occurrence when present and is a no-op when absent; a second call returns false but produces no observable side effect.")
    public static boolean unregister(AsyncTestListener listener) {
        return GLOBAL.unregister(listener);
    }

    /**
     * Fires the {@code onInvocationStarted} event to all registered listeners.
     *
     * @param round the invocation round number
     * @param threads the number of threads
     */
    public static void fireInvocationStarted(int round, int threads) {
        GLOBAL.fireInvocationStarted(round, threads);
    }

    /**
     * Fires the {@code onInvocationCompleted} event to all registered listeners.
     *
     * @param round the invocation round number
     * @param durationMs the duration in milliseconds
     */
    public static void fireInvocationCompleted(int round, long durationMs) {
        GLOBAL.fireInvocationCompleted(round, durationMs);
    }

    /**
     * Fires the {@code onTestFailed} event to all registered listeners.
     *
     * @param cause the failure cause
     */
    public static void fireTestFailed(Throwable cause) {
        GLOBAL.fireTestFailed(cause);
    }

    /**
     * Fires the {@code onDetectorReport}, {@code onStructuredReport} and {@code onViolation}
     * events to all registered listeners.
     *
     * <p>Severity is parsed from the report text using {@link IssueSeverity} markers
     * (emoji or keyword). Reports with no recognisable marker default to {@link IssueSeverity#HIGH}.
     *
     * @param detectorName the reporting detector, as it appears in the report
     * @param report the report content
     */
    public static void fireDetectorReport(String detectorName, String report) {
        GLOBAL.fireDetectorReport(detectorName, report);
    }

    /**
     * Fires the {@code onViolation} event to all registered listeners.
     *
     * <p>Called by {@link #fireDetectorReport(String, String)} for every finding; exposed for
     * detectors and SPI adapters that already hold a structured {@link Violation} and would
     * otherwise have to render it to text and let the registry parse it back.
     *
     * @param violation the finding to publish; ignored when null
     * @since 1.9.0
     */
    public static void fireViolation(@Nullable Violation violation) {
        GLOBAL.fireViolation(violation);
    }

    /**
     * Fires the {@code onTimeout} event to all registered listeners.
     *
     * @param timeoutMs the timeout in milliseconds
     */
    public static void fireTimeout(long timeoutMs) {
        GLOBAL.fireTimeout(timeoutMs);
    }

    /**
     * Returns the number of currently registered listeners.
     *
     * @return the listener count
     */
    public static int getListenerCount() {
        return GLOBAL.listenerCount();
    }

    /**
     * Clears all registered listeners.
     *
     * <p>Useful for test cleanup to avoid listener leakage between tests.
     */
    @AIIdempotent(reason = "Publishes the empty set; doing so twice has identical observable effect (empty registry).")
    public static void clearAll() {
        GLOBAL.clearAll();
    }

    /**
     * Registers a listener and returns an {@link AutoCloseable} that unregisters it
     * when closed. Use with try-with-resources to bind a listener's lifetime to a
     * block and avoid leakage into subsequent tests in the same JVM.
     *
     * <pre>{@code
     * try (var ignored = AsyncTestListenerRegistry.registerScoped(myListener)) {
     *     // ... listener fires only here
     * }
     * }</pre>
     *
     * @param listener the listener to register (must not be null)
     * @return an AutoCloseable Registration; closing it unregisters the listener
     * @throws IllegalArgumentException if listener is null
     * @since 1.6.0
     */
    public static Registration registerScoped(AsyncTestListener listener) {
        register(listener);
        return new Registration(listener);
    }

    /**
     * Captures the current set of registered listeners. Pair with
     * {@link #restoreSnapshot(Snapshot)} to scope a block of code so that any
     * listeners registered or unregistered during the block are reverted afterward.
     *
     * @return an immutable snapshot of the current listener set
     * @since 1.6.0
     */
    public static Snapshot snapshot() {
        return new Snapshot(GLOBAL.snapshot());
    }

    /**
     * Restores the registry to the state captured by {@link #snapshot()}.
     * Listeners added since the snapshot are removed; listeners removed are re-added.
     *
     * <p>The set is put back in a single write. It used to be cleared and then refilled, and a
     * fire landing between those two steps saw an empty registry and dropped the finding.
     *
     * @param snapshot a snapshot previously obtained from {@link #snapshot()}
     * @since 1.6.0
     */
    public static void restoreSnapshot(Snapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("Snapshot must not be null");
        GLOBAL.restore(snapshot.listeners);
    }

    /**
     * AutoCloseable handle returned by {@link #registerScoped(AsyncTestListener)}.
     * Closing it unregisters the listener (idempotent).
     */
    public static final class Registration implements AutoCloseable {
        private final AsyncTestListener listener;
        private volatile boolean closed;

        private Registration(AsyncTestListener listener) { this.listener = listener; }

        @AIIdempotent(reason = "Guarded by the `closed` volatile flag; second close() returns early before touching the registry. Covered by `registrationClose_isIdempotent` test.")
        @Override
        public void close() {
            if (closed) return;
            closed = true;
            unregister(listener);
        }
    }

    /** Immutable snapshot of the listener registry. */
    public static final class Snapshot {
        private final List<AsyncTestListener> listeners;
        private Snapshot(List<AsyncTestListener> listeners) { this.listeners = listeners; }
    }
}
