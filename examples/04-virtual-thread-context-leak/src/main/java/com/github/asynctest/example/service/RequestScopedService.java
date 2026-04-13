package com.github.asynctest.example.service;

/**
 * A request-scoped service that stores per-request context in a ThreadLocal.
 *
 * <p>This pattern is very common in web frameworks (Spring's {@code RequestContextHolder},
 * Slf4j's MDC, etc.). When migrating to virtual threads, it introduces a subtle bug:
 * ThreadLocal values set in one virtual-thread task may "leak" into subsequent tasks
 * if not explicitly removed.
 *
 * <p>========================================================================
 * DETECTED BY: VirtualThreadContextLeakDetector
 * ========================================================================
 *
 * <p><b>THE BUG:</b> {@link #setCurrentUser} stores the user ID in a {@code ThreadLocal}
 * but {@link #clearCurrentUser} is never called in the error path. When virtual threads
 * are reused (which the JVM may do with carrier threads), a future request running on
 * the same virtual thread sees the previous request's user ID.
 *
 * <p>Under a plain {@code @Test}, this appears to work correctly because:
 * <ul>
 *   <li>Only one "request" runs at a time</li>
 *   <li>The ThreadLocal is reset on the next call anyway</li>
 *   <li>No cross-request contamination is observable</li>
 * </ul>
 *
 * <p>Under {@code @AsyncTest} with virtual threads, the concurrency stress reveals:
 * <ul>
 *   <li>Multiple requests run simultaneously on virtual threads</li>
 *   <li>An exception in one task causes {@code clearCurrentUser()} to be skipped</li>
 *   <li>{@code VirtualThreadContextLeakDetector} reports the unreleased ThreadLocal</li>
 *   <li>In production: audit logs may record the wrong user ID, causing security issues</li>
 * </ul>
 *
 * <p><b>ROOT CAUSE:</b> Missing {@code finally} block around {@link #clearCurrentUser()}.
 *
 * <p><b>SOLUTION:</b> Always clear ThreadLocals in a {@code finally} block, or switch to
 * {@code ScopedValue} (Java 21+) which is automatically scoped and cannot leak.
 */
public class RequestScopedService {

    // ========================================================================
    // BUG: This ThreadLocal is not always cleared when using virtual threads.
    // ========================================================================
    private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();

    /**
     * Set the current user for this request.
     * Must be paired with {@link #clearCurrentUser()} in a {@code finally} block.
     */
    public void setCurrentUser(String userId) {
        CURRENT_USER.set(userId);
    }

    /**
     * Clear the current user context.
     * <b>BUG:</b> In production code, this is sometimes not called when an exception occurs.
     */
    public void clearCurrentUser() {
        CURRENT_USER.remove();
    }

    /**
     * Get the current user ID.
     *
     * @return the user ID, or {@code null} if not set (indicates a context leak)
     */
    public String getCurrentUser() {
        return CURRENT_USER.get();
    }

    /**
     * Process a request for the given user.
     *
     * <p><b>BUG:</b> If an exception is thrown inside {@code processOrder()},
     * {@code clearCurrentUser()} is never called and the ThreadLocal leaks.
     *
     * <p>Fix: wrap the entire method body in try-finally:
     * <pre>{@code
     * setCurrentUser(userId);
     * try {
     *     return processOrder(orderId);
     * } finally {
     *     clearCurrentUser(); // ← always runs, even on exception
     * }
     * }</pre>
     */
    public String processRequest(String userId, String orderId) {
        setCurrentUser(userId);
        // BUG: clearCurrentUser() is missing from the exception path
        try {
            return processOrder(orderId);
        } catch (Exception e) {
            // ThreadLocal is NOT cleared here — the leak happens
            throw new RuntimeException("Order processing failed for user " + userId, e);
        }
        // clearCurrentUser() only reached on success — exception path leaks the context
        // FIXED VERSION would be:
        //   } finally { clearCurrentUser(); }
    }

    private String processOrder(String orderId) {
        String user = CURRENT_USER.get();
        if (user == null) {
            throw new IllegalStateException("No user context — ThreadLocal was not set");
        }
        // Simulate occasional failures to trigger the leak path
        if (orderId == null || orderId.isEmpty()) {
            throw new IllegalArgumentException("Invalid order ID");
        }
        return "Processed order " + orderId + " for user " + user;
    }
}
