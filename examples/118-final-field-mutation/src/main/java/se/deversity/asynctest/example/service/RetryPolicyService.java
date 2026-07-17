package se.deversity.asynctest.example.service;

import java.lang.reflect.Field;

/**
 * A service whose retry policy is held in a {@code final} field — and a helper
 * that "overrides" it reflectively, the exact pattern JEP 500 (JDK 26) warns
 * about and a future JDK release will deny.
 *
 * <p>The JMM guarantees that a thread which sees a reference to this object sees
 * {@code maxRetries} fully initialized <em>without synchronization</em> — but only
 * for the constructor's write. The reflective write in
 * {@link #overrideMaxRetriesReflectively(int)} has no such fence: concurrent
 * readers may observe the stale value forever (final reads can be constant-folded
 * by the JIT).
 */
public final class RetryPolicyService {

    private final int maxRetries;

    public RetryPolicyService(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int maxRetries() {
        return maxRetries;
    }

    /**
     * BUG: mutates the {@code final} field via deep reflection. Works (with a
     * warning) on JDK 26; throws once {@code --illegal-final-field-mutation}
     * defaults to {@code deny}; and is a silent memory-model violation on every
     * JDK version today.
     */
    public void overrideMaxRetriesReflectively(int newValue) throws ReflectiveOperationException {
        Field f = RetryPolicyService.class.getDeclaredField("maxRetries");
        f.setAccessible(true);
        f.setInt(this, newValue);
    }
}
