package se.deversity.asynctest.example.service;

/**
 * A mutable session object used as a HashMap key — not safe.
 *
 * BUG: {@code id} is mutable. Changing it after the session has been inserted
 * into a map invalidates its hash-bucket position, making it impossible to
 * retrieve the corresponding value with {@code get()}.
 */
public class UserSession {

    // BUG: mutable field used implicitly by identity-based hashCode/equals
    private String id;
    private long createdAt;

    public UserSession(String id) {
        this.id = id;
        this.createdAt = System.currentTimeMillis();
    }

    public String getId() {
        return id;
    }

    /**
     * BUG: mutates the field that callers treat as the key identity.
     * After this call any map lookup on this key may return null.
     */
    public void setId(String id) {
        this.id = id;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    // Intentionally no hashCode/equals override — uses Object identity,
    // which is stable. The bug manifests when callers *expect* value-based
    // equality but the mutable state breaks their assumptions about identity.
    @Override
    public String toString() {
        return "UserSession{id='" + id + "'}";
    }
}
