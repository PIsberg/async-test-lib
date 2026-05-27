package se.deversity.asynctest.example.service;

/**
 * Propagates a per-request ID to child threads via {@link InheritableThreadLocal}.
 *
 * <p><strong>Bug:</strong> {@code InheritableThreadLocal} copies the parent
 * thread's value into newly created child threads. When a thread pool reuses a
 * worker thread, that thread already carries the context from the request that
 * originally created it. Subsequent requests that reuse the worker see stale
 * context unless the value is explicitly cleared before each use.
 *
 * <p><strong>Fix:</strong> Use plain {@link ThreadLocal} and explicitly propagate
 * the value into each task via a wrapper, or use {@link java.lang.ScopedValue}
 * (Java 21+) which does not propagate through thread-pool reuse.
 */
public class RequestContextHolder {

    // BUG: InheritableThreadLocal propagates stale context through pooled threads
    private static final InheritableThreadLocal<String> REQUEST_ID =
            new InheritableThreadLocal<>();

    /**
     * Sets the request ID for the current thread.
     *
     * @param id the request identifier
     */
    public static void setRequestId(String id) {
        REQUEST_ID.set(id);
    }

    /**
     * Returns the request ID for the current thread, or {@code null} if none
     * has been set.
     *
     * @return the current request ID
     */
    public static String getRequestId() {
        return REQUEST_ID.get();
    }

    /**
     * Clears the request ID for the current thread. Must be called at the end of
     * each request to prevent context leaks — but is easily forgotten.
     */
    public static void clear() {
        REQUEST_ID.remove();
    }

    /** Returns the underlying {@link InheritableThreadLocal} for instrumentation. */
    public static InheritableThreadLocal<String> getThreadLocal() {
        return REQUEST_ID;
    }
}
