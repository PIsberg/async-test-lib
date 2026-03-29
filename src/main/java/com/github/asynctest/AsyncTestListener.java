package com.github.asynctest;

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
}
