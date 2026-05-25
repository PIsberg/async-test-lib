package se.deversity.asynctest.example.service;

/**
 * BUGGY service that demonstrates ScopedValue misuse.
 *
 * BUG: getCurrentUser() calls USER_ID.get() without checking isBound() first.
 *      If called outside a ScopedValue.where(USER_ID, value).run(task) scope,
 *      the call throws NoSuchElementException. Under concurrent load some
 *      threads may invoke this method from an unbound context.
 *
 * FIX: Guard every get() call:
 *      return USER_ID.isBound() ? USER_ID.get() : "anonymous";
 */
public class ScopedContextService {

    // ScopedValue provides immutable, scope-bound, inheritable context
    public static final ScopedValue<String> USER_ID = ScopedValue.newInstance();

    /**
     * BUG: reads USER_ID without checking isBound().
     * Throws NoSuchElementException if called outside a binding scope.
     */
    public String getCurrentUser() {
        return USER_ID.get(); // BUG: no isBound() guard
    }

    /**
     * Run a task within a USER_ID binding scope.
     * This is the correct usage pattern.
     */
    public void runWithUser(String userId, Runnable task) {
        ScopedValue.where(USER_ID, userId).run(task);
    }

    /**
     * Process a request — intended to be called inside runWithUser().
     */
    public String processRequest() {
        return "Processing request for: " + getCurrentUser();
    }
}
