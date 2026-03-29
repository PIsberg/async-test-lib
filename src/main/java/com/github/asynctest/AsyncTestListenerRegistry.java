package com.github.asynctest;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe registry for {@link AsyncTestListener} instances.
 *
 * <p>This class manages a global (JVM-wide) list of listeners that receive
 * callbacks for async-test lifecycle events. Listeners are stored in a
 * {@link CopyOnWriteArrayList} to allow concurrent iteration without locking.
 *
 * <p><strong>Usage:</strong>
 * <pre>{@code
 * // Register a custom listener
 * AsyncTestListenerRegistry.register(new MyCustomListener());
 *
 * // Optionally unregister later
 * AsyncTestListenerRegistry.unregister(myListener);
 * }</pre>
 *
 * <p><strong>Default Behavior:</strong> If no listeners are registered, a
 * built-in {@link DefaultStderrListener} prints detector reports to
 * {@code System.err} (backward-compatible behavior).
 *
 * <p><strong>Opt-out:</strong> To silence all output, register a
 * {@link NoopAsyncTestListener} instance.
 *
 * @see AsyncTestListener
 * @see NoopAsyncTestListener
 */
public final class AsyncTestListenerRegistry {

    private static final List<AsyncTestListener> LISTENERS = new CopyOnWriteArrayList<>();

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
                System.err.println("Warning: AsyncTestListener.onInvocationStarted threw: " + e.getMessage());
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
                System.err.println("Warning: AsyncTestListener.onInvocationCompleted threw: " + e.getMessage());
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
                System.err.println("Warning: AsyncTestListener.onTestFailed threw: " + e.getMessage());
            }
        }
    }

    /**
     * Fires the {@code onDetectorReport} event to all registered listeners.
     *
     * @param detectorName the detector name
     * @param report the report content
     */
    public static void fireDetectorReport(String detectorName, String report) {
        for (AsyncTestListener listener : LISTENERS) {
            try {
                listener.onDetectorReport(detectorName, report);
            } catch (RuntimeException e) {
                System.err.println("Warning: AsyncTestListener.onDetectorReport threw: " + e.getMessage());
            }
        }
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
                System.err.println("Warning: AsyncTestListener.onTimeout threw: " + e.getMessage());
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
    public static void clearAll() {
        LISTENERS.clear();
    }
}
