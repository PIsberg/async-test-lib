package se.deversity.asynctest.example.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Simple session cache backed by a {@link ConcurrentMap}.
 *
 * <p><strong>Bug:</strong> {@link ConcurrentMap} makes each single operation atomic, but
 * {@code containsKey()}-then-{@code put()} is a compound (check-then-act) operation that is
 * <em>not</em> atomic. Two threads can both observe the key as absent and both {@code put()}
 * a freshly-created session, so one session silently overwrites and replaces the other —
 * losing a session and any state associated with it.
 *
 * <p><strong>Fix:</strong> Use an atomic compound operation such as
 * {@code sessions.computeIfAbsent(userId, id -> "session-" + System.nanoTime())} (or
 * {@code putIfAbsent} / {@code merge}), which performs the check-and-insert as a single
 * atomic step.
 */
public class SessionCache {

    // BUG: containsKey-then-put is a non-atomic compound op on a ConcurrentMap
    private final ConcurrentMap<String, String> sessions = new ConcurrentHashMap<>();

    /**
     * Returns the session for the given user, creating one if absent. Not atomic.
     */
    public String getOrCreate(String userId) {
        if (!sessions.containsKey(userId)) {        // BUG: not atomic
            sessions.put(userId, "session-" + System.nanoTime());
        }
        return sessions.get(userId);
    }

    /** Returns the raw session map for test instrumentation. */
    public ConcurrentMap<String, String> getSessions() {
        return sessions;
    }
}
