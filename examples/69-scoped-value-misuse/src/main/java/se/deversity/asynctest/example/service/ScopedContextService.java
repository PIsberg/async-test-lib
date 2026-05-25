package se.deversity.asynctest.example.service;

/**
 * BUGGY service that demonstrates scoped-context misuse.
 *
 * <p>BUG: {@link #getCurrentUser()} reads the per-request user ID from a
 * {@link ThreadLocal} without verifying that the current thread has a binding.
 * When called from a thread that bypassed {@link #runWithUser}, the value is
 * {@code null}, causing downstream {@link NullPointerException} or silent
 * wrong-user processing.
 *
 * <p>This reproduces the same hazard as calling {@code ScopedValue.get()}
 * without an {@code isBound()} guard — the pattern that
 * {@code ScopedValueMisuseDetector} flags.
 *
 * <p>FIX: always guard the read:
 * <pre>{@code
 * String id = USER_ID.get();
 * return id != null ? id : "anonymous";
 * }</pre>
 */
public class ScopedContextService {

    /**
     * Holds the current user ID for this execution scope.
     * Analogous to a {@code ScopedValue<String>} binding.
     */
    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();

    /**
     * BUG: returns {@code null} (and causes NPE) when called outside a
     * {@link #runWithUser} scope — same hazard as {@code ScopedValue.get()}
     * without {@code isBound()}.
     */
    public String getCurrentUser() {
        return USER_ID.get(); // BUG: may be null if no scope was bound
    }

    /**
     * Run {@code task} within a USER_ID binding scope.
     * This is the correct usage pattern, analogous to
     * {@code ScopedValue.where(USER_ID, userId).run(task)}.
     */
    public void runWithUser(String userId, Runnable task) {
        USER_ID.set(userId);
        try {
            task.run();
        } finally {
            USER_ID.remove(); // prevent ThreadLocal contamination
        }
    }

    /** Process a request — must be called inside {@link #runWithUser}. */
    public String processRequest() {
        String user = getCurrentUser();
        if (user == null) {
            throw new IllegalStateException("No user context — call runWithUser() first");
        }
        return "Processing request for: " + user;
    }
}
