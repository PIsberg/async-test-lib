package se.deversity.asynctest;

import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AIPublicAPI;

/**
 * Listener interface for observing async-test lifecycle events.
 *
 * <p>Implementations can be registered via {@link AsyncTestListenerRegistry#register(AsyncTestListener)}
 * to receive callbacks for test execution, failures, and detector reports.
 *
 * <p>All methods have default no-op implementations, allowing users to override
 * only the events they care about.
 *
 * <p><strong>Thread Safety:</strong> Listeners may be called from multiple worker
 * threads concurrently. Implementations must be thread-safe.
 *
 * @see AsyncTestListenerRegistry
 * @see NoopAsyncTestListener
 */
@AIContract(reason = "Public SPI interface for observing async-test lifecycle events. Method signatures are part of the stable API — implementors bind to these exact names and parameter types.")
@AIPublicAPI
public interface AsyncTestListener {

    /**
     * Called when an invocation round starts (before threads are forked).
     *
     * @param round the invocation round number (0-based)
     * @param threads the number of threads that will execute this round
     */
    default void onInvocationStarted(int round, int threads) {}

    /**
     * Called when an invocation round completes (all threads finished).
     *
     * @param round the invocation round number (0-based)
     * @param durationMs the duration of the round in milliseconds
     */
    default void onInvocationCompleted(int round, long durationMs) {}

    /**
     * Called when a test fails (AssertionError or other throwable).
     *
     * @param cause the failure cause
     */
    default void onTestFailed(Throwable cause) {}

    /**
     * Called when a detector reports an issue.
     *
     * @param detectorName the name of the detector (e.g., "FalseSharingDetector")
     * @param report the detector's report content
     */
    default void onDetectorReport(String detectorName, String report) {}

    /**
     * Called when a timeout occurs.
     *
     * @param timeoutMs the configured timeout in milliseconds
     */
    default void onTimeout(long timeoutMs) {}

    /**
     * Called when a detector reports an issue, with structured severity information.
     *
     * <p>This is a richer alternative to {@link #onDetectorReport} that includes the
     * parsed {@link IssueSeverity} so listeners can route or filter by priority without
     * re-parsing the report text. Both methods are fired for every detector finding.
     *
     * <p>The default implementation is a no-op, preserving backwards compatibility with
     * existing {@link AsyncTestListener} implementations.
     *
     * @param detectorName the name of the detector (e.g., "FalseSharingDetector")
     * @param severity      the severity level parsed from the detector's report
     * @param report        the detector's full report content
     * @since 1.5.0
     */
    default void onStructuredReport(String detectorName, IssueSeverity severity, String report) {}
}
