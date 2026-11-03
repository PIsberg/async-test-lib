package jakarta.persistence;

/**
 * The part of JPA's handle the agent-pair lane calls, under the name the agent matches.
 *
 * <p>{@code PersistenceHandleWeaver} matches this type by name because neither JPA nor Hibernate may
 * be on the agent's class path, so a stub of the same name reaches the same woven call site without
 * putting a persistence provider on this module's. Its contract is the real one's javadoc: an
 * {@code EntityManager} "is not required to be threadsafe".
 */
public interface EntityManager {

    /**
     * Looks an entity up.
     *
     * @param <T>         the entity type
     * @param entityClass the entity type
     * @param primaryKey  its key
     * @return the entity, or {@code null}
     */
    <T> T find(Class<T> entityClass, Object primaryKey);
}
