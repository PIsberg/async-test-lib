package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.locks.AbstractOwnableSynchronizer;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Reads the thread that owns a {@code ReentrantLock} (#855).
 *
 * <p>A lock names its holder only by name in {@code toString()}, and every unnamed virtual thread is
 * {@code ""}, so a hold a nameless thread leaked could not be told from one a nameless thread is
 * still working under. The owner itself is {@code AbstractOwnableSynchronizer.getExclusiveOwnerThread()}
 * on the lock's private {@code sync}, which needs {@code java.util.concurrent.locks} open to this
 * library. The agent opens it, in every mode; without the agent, or before it attached, the owner is
 * {@code null} and callers judge by name as before. The handles are looked up again until they can be
 * used, since the agent may attach after this class was first asked, and this runs at analysis, never
 * on a record path. {@code JdkLockShapeCanaryTest} fails the build on a JDK that changes the shape read.
 */
final class LockOwners {

    /** {@code ReentrantLock}'s private field holding its synchronizer. */
    static final String SYNC_FIELD = "sync";

    /** The protected owner query every {@code ReentrantLock} synchronizer inherits. */
    static final String OWNER_METHOD = "getExclusiveOwnerThread";

    /** The two reflective handles, set together once the package is open to this library. */
    private record Handles(Field sync, Method owner) { }

    private static volatile @Nullable Handles handles;

    private LockOwners() {
    }

    /**
     * {@return the thread holding {@code lock} now, or {@code null} when it is free or its owner
     * cannot be read}
     *
     * @param lock the lock to ask
     */
    static @Nullable Thread ownerOf(ReentrantLock lock) {
        Handles usable = handles();
        if (usable == null) {
            return null;
        }
        try {
            Object synchronizer = usable.sync().get(lock);
            return synchronizer == null ? null : (Thread) usable.owner().invoke(synchronizer);
        } catch (ReflectiveOperationException | RuntimeException e) { // NOPMD - unreadable is judged by name
            return null;
        }
    }

    /** {@return the handles, set up now if the package has been opened since the last ask} */
    private static @Nullable Handles handles() {
        Handles known = handles;
        if (known != null) {
            return known;
        }
        try {
            Field field = ReentrantLock.class.getDeclaredField(SYNC_FIELD);
            Method method = AbstractOwnableSynchronizer.class.getDeclaredMethod(OWNER_METHOD);
            if (!field.trySetAccessible() || !method.trySetAccessible()) {
                return null;
            }
            known = new Handles(field, method);
            handles = known;
            return known;
        } catch (ReflectiveOperationException | RuntimeException e) { // NOPMD - a closed package is the usual case
            return null;
        }
    }
}
