package se.deversity.asynctest.example.service;

/**
 * Stores per-request authentication context in a ThreadLocal so that any
 * code running on the same thread can access the current user without
 * explicit parameter passing.
 *
 * BUG: beginRequest() sets the ThreadLocal value, but there is no
 * corresponding endRequest() call to remove it. When a thread pool reuses
 * a thread for the next request, the stale userId from the previous request
 * is still present in the ThreadLocal. The next request reads the wrong user
 * unless it explicitly calls beginRequest() first — but callers that only
 * call getRequestUserId() will silently receive stale auth data.
 *
 * ThreadLocalMonitor flags the "REQUEST_USER" ThreadLocal as initialized
 * without cleanup across multiple threads.
 *
 * FIX: Always call endRequest() in a finally block to guarantee the
 * ThreadLocal is removed before the thread returns to the pool.
 */
public class RequestContextService {

    private static final ThreadLocal<String> CURRENT_USER =
            ThreadLocal.withInitial(() -> null);

    /**
     * Sets the authenticated user for the current request.
     * BUG: No matching endRequest() is enforced, so the value leaks
     * to the next task on the same pooled thread.
     */
    public void beginRequest(String userId) {
        CURRENT_USER.set(userId);
    }

    /**
     * Returns the user ID associated with the current request.
     * BUG: If endRequest() was never called by a previous request, this
     * returns the stale user ID from that request instead of null.
     */
    public String getRequestUserId() {
        return CURRENT_USER.get();
    }

    /**
     * Clears the ThreadLocal for the current thread.
     * This method exists but is never called in the buggy flow,
     * illustrating that cleanup must be enforced, not just provided.
     */
    public void endRequest() {
        CURRENT_USER.remove();
    }

    /**
     * Returns the raw ThreadLocal for monitoring purposes.
     * Used by the test to pass to ThreadLocalMonitor.
     */
    public static ThreadLocal<String> threadLocal() {
        return CURRENT_USER;
    }
}
