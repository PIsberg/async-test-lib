package se.deversity.asynctest;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import org.jspecify.annotations.Nullable;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AIIdempotent;
import se.deversity.vibetags.annotations.AIPublicAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Thread-safe registry for {@link AsyncTestListener} instances.
 *
 * <p>This class manages a global (JVM-wide) list of listeners that receive
 * callbacks for async-test lifecycle events. Listeners are stored in a
 * {@link CopyOnWriteArrayList} to allow concurrent iteration without locking.
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

    private static final Logger log = LoggerFactory.getLogger(AsyncTestListenerRegistry.class);
    private static final List<AsyncTestListener> LISTENERS = new CopyOnWriteArrayList<>();

    /** ANSI SGR sequences, as {@link IssueSeverity#format()} renders severity markers. */
    private static final Pattern ANSI = Pattern.compile("\\e\\[[;\\d]*m");

    // Prevent instantiation
    private AsyncTestListenerRegistry() {}

    /**
     * Registers a listener to receive async-test events.
     *
     * @param listener the listener to register (must not be null)
     * @throws IllegalArgumentException if listener is null
     */
    public static void register(AsyncTestListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener must not be null");
        }
        LISTENERS.add(listener);
    }

    /**
     * Unregisters a previously registered listener.
     *
     * @param listener the listener to unregister
     * @return true if the listener was registered and has been removed
     */
    @AIIdempotent(reason = "Backed by List.remove which is a no-op when the listener is absent; second call returns false but produces no observable side effect.")
    public static boolean unregister(AsyncTestListener listener) {
        return LISTENERS.remove(listener);
    }

    /**
     * Fires the {@code onInvocationStarted} event to all registered listeners.
     *
     * @param round the invocation round number
     * @param threads the number of threads
     */
    public static void fireInvocationStarted(int round, int threads) {
        for (AsyncTestListener listener : LISTENERS) {
            try {
                listener.onInvocationStarted(round, threads);
            } catch (RuntimeException e) {
                // Log but don't propagate listener exceptions
                log.warn("AsyncTestListener.onInvocationStarted threw: {}", e.toString(), e);
            }
        }
    }

    /**
     * Fires the {@code onInvocationCompleted} event to all registered listeners.
     *
     * @param round the invocation round number
     * @param durationMs the duration in milliseconds
     */
    public static void fireInvocationCompleted(int round, long durationMs) {
        for (AsyncTestListener listener : LISTENERS) {
            try {
                listener.onInvocationCompleted(round, durationMs);
            } catch (RuntimeException e) {
                log.warn("AsyncTestListener.onInvocationCompleted threw: {}", e.toString(), e);
            }
        }
    }

    /**
     * Fires the {@code onTestFailed} event to all registered listeners.
     *
     * @param cause the failure cause
     */
    public static void fireTestFailed(Throwable cause) {
        for (AsyncTestListener listener : LISTENERS) {
            try {
                listener.onTestFailed(cause);
            } catch (RuntimeException e) {
                log.warn("AsyncTestListener.onTestFailed threw: {}", e.toString(), e);
            }
        }
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
        IssueSeverity severity = parseSeverity(report);
        for (AsyncTestListener listener : LISTENERS) {
            try {
                listener.onDetectorReport(detectorName, report);
            } catch (RuntimeException e) {
                log.warn("AsyncTestListener.onDetectorReport threw: {}", e.toString(), e);
            }
            try {
                listener.onStructuredReport(detectorName, severity, report);
            } catch (RuntimeException e) {
                log.warn("AsyncTestListener.onStructuredReport threw: {}", e.toString(), e);
            }
        }
        fireViolation(toViolation(detectorName, severity, report));
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
        if (violation == null) return;
        for (AsyncTestListener listener : LISTENERS) {
            try {
                listener.onViolation(violation);
            } catch (RuntimeException e) {
                log.warn("AsyncTestListener.onViolation threw: {}", e.toString(), e);
            }
        }
    }

    /**
     * Builds the structured form of a text report: the detector as reported, the parsed
     * severity, the report's first non-blank line as the message (severity markers are
     * rendered with ANSI colour, which is stripped), and the whole report kept under the
     * {@code "report"} attribute so nothing is lost in the conversion.
     *
     * @return the violation, or {@code null} if one could not be built — a listener callback
     *         is not worth failing a test run over
     */
    private static @Nullable Violation toViolation(String detectorName, IssueSeverity severity, String report) {
        try {
            String detector = (detectorName == null || detectorName.isBlank())
                    ? "UnknownDetector" : detectorName;
            String text = (report == null) ? "" : report;
            String message = firstMeaningfulLine(text);
            return new Violation(
                    detector,
                    severity,
                    message.isEmpty() ? detector + " reported a finding" : message,
                    List.of(),
                    Map.of("report", text),
                    Instant.now());
        } catch (RuntimeException e) {
            log.warn("Could not build a Violation for detector {}: {}", detectorName, e.toString(), e);
            return null;
        }
    }

    private static String firstMeaningfulLine(String report) {
        for (String line : report.split("\n", -1)) {
            String stripped = ANSI.matcher(line).replaceAll("").trim();
            if (!stripped.isEmpty()) {
                return stripped;
            }
        }
        return "";
    }

    private static IssueSeverity parseSeverity(String report) {
        return IssueSeverity.fromReport(report);
    }

    /**
     * Fires the {@code onTimeout} event to all registered listeners.
     *
     * @param timeoutMs the timeout in milliseconds
     */
    public static void fireTimeout(long timeoutMs) {
        for (AsyncTestListener listener : LISTENERS) {
            try {
                listener.onTimeout(timeoutMs);
            } catch (RuntimeException e) {
                log.warn("AsyncTestListener.onTimeout threw: {}", e.toString(), e);
            }
        }
    }

    /**
     * Returns the number of currently registered listeners.
     *
     * @return the listener count
     */
    public static int getListenerCount() {
        return LISTENERS.size();
    }

    /**
     * Clears all registered listeners.
     *
     * <p>Useful for test cleanup to avoid listener leakage between tests.
     */
    @AIIdempotent(reason = "List.clear() on an already-empty list is a no-op; repeated calls have identical observable effect (empty registry).")
    public static void clearAll() {
        LISTENERS.clear();
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
        return new Snapshot(List.copyOf(LISTENERS));
    }

    /**
     * Restores the registry to the state captured by {@link #snapshot()}.
     * Listeners added since the snapshot are removed; listeners removed are re-added.
     *
     * @param snapshot a snapshot previously obtained from {@link #snapshot()}
     * @since 1.6.0
     */
    public static void restoreSnapshot(Snapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("Snapshot must not be null");
        LISTENERS.clear();
        LISTENERS.addAll(snapshot.listeners);
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
