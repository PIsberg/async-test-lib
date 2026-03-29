package com.github.asynctest;

/**
 * A no-op implementation of {@link AsyncTestListener} that ignores all events.
 *
 * <p>Register this listener to silence all async-test output:
 * <pre>{@code
 * AsyncTestListenerRegistry.register(new NoopAsyncTestListener());
 * }</pre>
 *
 * <p>This is useful when you want to:
 * <ul>
 *   <li>Disable the default stderr output</li>
 *   <li>Provide your own custom listener without also receiving default output</li>
 *   <li>Temporarily mute async-test events during specific tests</li>
 * </ul>
 *
 * <p><strong>Note:</strong> This listener does NOT prevent the default
 * {@code System.err} output from detectors. To fully suppress output, register
 * this listener AND ensure no other listeners are registered that print to stderr.
 *
 * @see AsyncTestListenerRegistry#register(AsyncTestListener)
 */
public class NoopAsyncTestListener implements AsyncTestListener {

    /**
     * Creates a no-op listener instance.
     */
    public NoopAsyncTestListener() {}

    @Override
    public void onInvocationStarted(int round, int threads) {
        // No-op
    }

    @Override
    public void onInvocationCompleted(int round, long durationMs) {
        // No-op
    }

    @Override
    public void onTestFailed(Throwable cause) {
        // No-op
    }

    @Override
    public void onDetectorReport(String detectorName, String report) {
        // No-op
    }

    @Override
    public void onTimeout(long timeoutMs) {
        // No-op
    }
}
