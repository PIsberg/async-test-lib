package com.example.agentfixture;

import jakarta.persistence.EntityManager;

/**
 * Uses a JPA {@code EntityManager} it is handed the way a repository does, recording nothing. Woven
 * with {@code collections=true}, each call on the handle passes its receiver to the persistence
 * hook first; the calls cover the zero-, one- and two-argument stack shapes the weaver copies the
 * receiver out of.
 */
public class PersistenceHandleBean {

    /**
     * Looks an entity up, persists one and flushes, on {@code em}.
     *
     * @param em the handle to use
     * @return what {@code find} returned
     */
    public Object findPersistAndFlush(EntityManager em) {
        Object found = em.find(Object.class, 1L);
        em.persist(new Object());
        em.flush();
        return found;
    }
}
