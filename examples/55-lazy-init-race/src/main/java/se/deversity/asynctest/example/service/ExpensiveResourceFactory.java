package se.deversity.asynctest.example.service;

/**
 * A factory that lazily initializes an expensive resource without synchronization.
 *
 * BUG: The null check and assignment are not atomic. Multiple threads can observe
 * {@code resource == null} simultaneously and each create a new instance.
 * The field is also not {@code volatile}, so writes may not be visible across threads.
 */
public class ExpensiveResourceFactory {

    // BUG: not volatile — writes may not be visible to other threads
    private ExpensiveResource resource;

    /**
     * Returns the shared resource, lazily initializing it on first call.
     *
     * BUG: unsynchronized — multiple threads can both see null and each
     * call {@code new ExpensiveResource()}, producing multiple instances.
     */
    public ExpensiveResource getResource() {
        if (resource == null) {                    // <-- race: multiple threads pass here
            resource = new ExpensiveResource();    // <-- multiple instances created
        }
        return resource;
    }

    /**
     * Resets the resource for testing purposes.
     */
    public void reset() {
        resource = null;
    }

    // -------------------------------------------------------------------------
    // Inner class representing the expensive-to-create resource
    // -------------------------------------------------------------------------
    public static class ExpensiveResource {
        private final long createdAt = System.nanoTime();

        public long getCreatedAt() {
            return createdAt;
        }

        public String doWork() {
            return "result-from-" + createdAt;
        }
    }
}
