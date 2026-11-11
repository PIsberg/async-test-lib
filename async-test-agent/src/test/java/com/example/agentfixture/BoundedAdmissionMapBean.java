package com.example.agentfixture;

import java.util.concurrent.ConcurrentMap;

/**
 * Admits members into a {@link ConcurrentMap} up to a limit, the way a bounded registry is
 * usually written: {@code size}, then {@code put}.
 *
 * <p>Each call is atomic and the pair is not, so two callers that both see the map one below its
 * limit both put, and it ends one over. No key is shared, so only the size check ties the two
 * calls together (#1249). {@link #admitUnderTheMapsMonitor} is the same pair inside
 * {@code synchronized (map)}, which makes it atomic.
 */
public class BoundedAdmissionMapBean {

    private static final int LIMIT = 1_000;

    private final ConcurrentMap<String, Object> members;

    /**
     * Admits into {@code members}; its type names the map in a report, which tells two beans apart.
     *
     * @param members the map to admit into
     */
    public BoundedAdmissionMapBean(ConcurrentMap<String, Object> members) {
        this.members = members;
    }

    /**
     * Checks the size, runs {@code afterCheck}, and admits {@code who} if the check found room.
     *
     * @param who        the member to admit
     * @param afterCheck run between the check and the put, which is where a racing caller lands
     */
    public void admit(String who, Runnable afterCheck) {
        boolean room = members.size() < LIMIT;
        afterCheck.run();
        if (room) {
            members.put(who, new Object());
        }
    }

    /**
     * The same check and put, both under the map's own monitor.
     *
     * @param who the member to admit
     */
    public void admitUnderTheMapsMonitor(String who) {
        synchronized (members) {
            if (members.size() < LIMIT) {
                members.put(who, new Object());
            }
        }
    }
}
