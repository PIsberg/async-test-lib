package se.deversity.asynctest.example.service;

import java.util.function.Consumer;

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

    private volatile Runnable onSet = () -> { };

    private volatile Consumer<String> onGet = value -> { };

    /**
     * Begin a new request scope. Stores {@code id} in the current thread's
     * {@code ThreadLocal} slot.
     */
    public void startRequest(String id) {
        REQUEST_ID.set(id);
        onSet.run();
    }

    /**
     * Return the request ID bound to the current thread.
     */
    public String getCurrentId() {
        String value = REQUEST_ID.get();
        onGet.accept(value);
        return value;
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
     * End the request scope, properly.
     *
     * <p>The one line the buggy version leaves out. On a pooled thread this is the difference
     * between the next request seeing its own id and seeing the previous one's.
     */
    public void endRequestFixed() {
        REQUEST_ID.remove();
    }

    /**
     * Installs the hooks ThreadLocalContaminationDetector needs. No-ops by default, so
     * production behaviour is unchanged whether or not a test is watching.
     *
     * @param set called after the value is bound to the thread
     * @param get called with whatever the read returned, including null
     */
    public void observeContext(Runnable set, Consumer<String> get) {
        this.onSet = set;
        this.onGet = get;
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
