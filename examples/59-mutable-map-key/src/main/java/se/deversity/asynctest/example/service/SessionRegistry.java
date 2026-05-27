package se.deversity.asynctest.example.service;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks active sessions using a mutable {@link UserSession} as the map key.
 *
 * BUG: {@link UserSession} is mutable. When a session's {@code id} is changed
 * after registration, {@link #lookup(UserSession)} can no longer find the entry
 * because the hashCode may map to a different bucket.
 */
public class SessionRegistry {

    private final Map<UserSession, String> sessions = new HashMap<>();

    /**
     * Registers a session with associated data.
     */
    public void register(UserSession session, String data) {
        sessions.put(session, data);
    }

    /**
     * Looks up data for the given session.
     * Returns {@code null} if the session is not found (possibly due to key mutation).
     */
    public String lookup(UserSession session) {
        return sessions.get(session);
    }

    /**
     * Returns the current number of registered sessions.
     */
    public int size() {
        return sessions.size();
    }
}
