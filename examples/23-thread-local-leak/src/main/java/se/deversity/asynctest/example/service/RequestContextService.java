package se.deversity.asynctest.example.service;

/**
 * Stores per-request authentication context in a ThreadLocal so that any
 * code running on the same thread can access the current user without
 * explicit parameter passing.
 *
 * <p>BUG: beginRequest() sets the ThreadLocal value, but there is no
 * corresponding endRequest() call to remove it. When a thread pool reuses
 * a thread for the next request, the stale userId from the previous request
 * is still present in the ThreadLocal. The next request reads the wrong user
 * unless it explicitly calls beginRequest() first, and callers that only
 * call getRequestUserId() silently receive stale auth data.
 *
 * <p>ThreadLocalMonitor flags the "REQUEST_USER" ThreadLocal as initialized
 * without cleanup across multiple threads.
 *
 * <p>FIX: Always call endRequest() in a finally block to guarantee the
 * ThreadLocal is removed before the thread returns to the pool.
 *
 * <p>INSTRUMENTATION: ThreadLocalMonitor is recording-fed, and what it is looking for is a
 * lifecycle with a set and no remove. The three hooks below report that lifecycle from the
 * points where it actually happens; they default to no-ops, so the production path never
 * touches the test library. This is the seam, not the bug.
 */
public class RequestContextService {

    private static final ThreadLocal<String> CURRENT_USER =
            ThreadLocal.withInitial(() -> null);

    private volatile Runnable onInit = () -> { };

    private volatile Runnable onAccess = () -> { };

    private volatile Runnable onCleanup = () -> { };

    /**
     * Sets the authenticated user for the current request.
     *
     * <p>BUG: No matching endRequest() is enforced, so the value leaks
     * to the next task on the same pooled thread.
     *
     * @param userId the authenticated user for this request
     */
    public void beginRequest(String userId) {
        CURRENT_USER.set(userId);
        onInit.run();
    }

    /**
     * Returns the user ID associated with the current request.
     *
     * <p>BUG: If endRequest() was never called by a previous request, this
     * returns the stale user ID from that request instead of null.
     *
     * @return the user id bound to this thread, or null if none was ever set
     */
    public String getRequestUserId() {
        onAccess.run();
        return CURRENT_USER.get();
    }

    /**
     * Clears the ThreadLocal for the current thread.
     *
     * <p>This method exists but is never called in the buggy flow,
     * illustrating that cleanup must be enforced, not just provided.
     */
    public void endRequest() {
        CURRENT_USER.remove();
        onCleanup.run();
    }

    /**
     * Installs the lifecycle hooks ThreadLocalMonitor needs. No-ops by default.
     *
     * @param onInit    called after the value is set
     * @param onAccess  called before the value is read
     * @param onCleanup called after the value is removed
     */
    public void observeLifecycle(Runnable onInit, Runnable onAccess, Runnable onCleanup) {
        this.onInit = onInit;
        this.onAccess = onAccess;
        this.onCleanup = onCleanup;
    }

    /**
     * Returns the raw ThreadLocal, which the monitor tracks by identity.
     *
     * @return the ThreadLocal holding the per-request user id
     */
    public static ThreadLocal<String> threadLocal() {
        return CURRENT_USER;
    }
}
