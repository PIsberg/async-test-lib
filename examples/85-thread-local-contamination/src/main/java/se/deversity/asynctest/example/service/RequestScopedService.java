package se.deversity.asynctest.example.service;

/**
 * Simulates a request-scoped service that stores the active request ID in a
 * {@link ThreadLocal} for downstream components to read without passing it
 * explicitly through every method signature.
 *
 * <p>BUG: {@link #endRequest()} does not call {@code REQUEST_ID.remove()}.
 * When the thread-pool reuses this thread for the next request, the stale ID
 * is still present and {@link #getCurrentId()} returns the wrong value.
 */
public class RequestScopedService {

    public static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();

    /**
     * Begin a new request scope. Stores {@code id} in the current thread's
     * {@code ThreadLocal} slot.
     */
    public void startRequest(String id) {
        REQUEST_ID.set(id);
    }

    /**
     * Return the request ID bound to the current thread.
     */
    public String getCurrentId() {
        return REQUEST_ID.get();
    }

    /**
     * End the request scope.
     *
     * <p>BUG: {@code REQUEST_ID.remove()} is not called, so the value leaks
     * into the next task executed on this thread.
     */
    public void endRequest() {
        // Intentionally missing: REQUEST_ID.remove();
    }

    /**
     * Process the current request. Reads the ID from the ThreadLocal.
     */
    public String processRequest() {
        String id = getCurrentId();
        if (id == null) {
            throw new IllegalStateException("No active request — startRequest() was not called");
        }
        return "Processed: " + id;
    }
}
