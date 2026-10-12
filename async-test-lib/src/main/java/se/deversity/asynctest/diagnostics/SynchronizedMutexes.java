package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;

/**
 * Reads the monitor a {@code Collections.synchronized*} collection locks on (#996).
 *
 * <p>A wrapper locks on its private {@code mutex}: itself for a plain {@code synchronizedSet}, the
 * map for one of a synchronized map's views, the parent list for a {@code subList}. Iterating it
 * safely means holding that monitor, so judging an iteration against the wrapper itself reported a
 * view guarded by its map, which is why a set the run did not see being made used to be skipped.
 * The field is in {@code java.util}, which the agent opens to this library under collection
 * weaving; without the agent, or before it attached, the mutex is {@code null} and callers keep
 * their old rule. The handle is looked up again until it can be used, since the agent may attach
 * after this class was first asked. {@code JdkSynchronizedWrapperShapeCanaryTest} fails the build on
 * a JDK that moves the field.
 */
final class SynchronizedMutexes {

    /** The class every synchronized collection wrapper extends. */
    static final String WRAPPER_CLASS = "java.util.Collections$SynchronizedCollection";

    /** Its field holding the monitor every method synchronizes on. */
    static final String MUTEX_FIELD = "mutex";

    private static volatile @Nullable Field mutex;

    private SynchronizedMutexes() {
    }

    /**
     * {@return the monitor {@code collection} synchronizes on, or {@code null} when it is not a
     * synchronized collection wrapper or the field cannot be read}
     *
     * @param collection a collection the caller already knows is a {@code Collections$Synchronized*}
     */
    static @Nullable Object mutexOf(Object collection) {
        Field usable = field();
        if (usable == null || !usable.getDeclaringClass().isInstance(collection)) {
            return null;
        }
        try {
            return usable.get(collection);
        } catch (ReflectiveOperationException | RuntimeException e) { // NOPMD - unreadable keeps the old rule
            return null;
        }
    }

    /** {@return the field, made accessible now if {@code java.util} has been opened since the last ask} */
    private static @Nullable Field field() {
        Field known = mutex;
        if (known != null) {
            return known;
        }
        try {
            Field field = Class.forName(WRAPPER_CLASS).getDeclaredField(MUTEX_FIELD);
            if (!field.trySetAccessible()) {
                return null;
            }
            mutex = field;
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) { // NOPMD - a closed package is the usual case
            return null;
        }
    }
}
