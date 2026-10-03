package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.locks.AbstractOwnableSynchronizer;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Canary for the JDK internals {@link LockOwners} reads to name a {@code ReentrantLock}'s owner
 * (#855).
 *
 * <p>That read is silent by design: on a JDK where {@code ReentrantLock.sync} or
 * {@code AbstractOwnableSynchronizer.getExclusiveOwnerThread()} moves, every owner reads as
 * unknown and the detectors fall back to judging a holder by name, which misses a nameless
 * thread's leak again. Nothing else would notice, so this fails the build, naming what moved. It
 * reads declared members only, which needs no opened package, and runs on each JDK in the matrix.
 */
class JdkLockShapeCanaryTest {

    private static final String HOW_TO_FIX = " LockOwners reads this to name a lock's owner; on this JDK "
            + "owners would silently read as unknown. Update LockOwners before shipping on "
            + Runtime.version() + ".";

    @Test
    @DisplayName("ReentrantLock keeps an instance field holding an AbstractOwnableSynchronizer")
    void theSyncFieldKeepsItsShape() {
        Field field;
        try {
            field = ReentrantLock.class.getDeclaredField(LockOwners.SYNC_FIELD);
        } catch (NoSuchFieldException e) {
            fail("ReentrantLock has no field '" + LockOwners.SYNC_FIELD + "' any more." + HOW_TO_FIX, e);
            return;
        }
        assertFalse(Modifier.isStatic(field.getModifiers()), "ReentrantLock.sync is now static." + HOW_TO_FIX);
        assertTrue(AbstractOwnableSynchronizer.class.isAssignableFrom(field.getType()),
                "ReentrantLock.sync is now a " + field.getType().getName() + "." + HOW_TO_FIX);
    }

    @Test
    @DisplayName("AbstractOwnableSynchronizer still answers its exclusive owner thread")
    void theOwnerQueryKeepsItsShape() {
        Method method;
        try {
            method = AbstractOwnableSynchronizer.class.getDeclaredMethod(LockOwners.OWNER_METHOD);
        } catch (NoSuchMethodException e) {
            fail("AbstractOwnableSynchronizer has no " + LockOwners.OWNER_METHOD + "() any more." + HOW_TO_FIX, e);
            return;
        }
        assertEquals(Thread.class, method.getReturnType(), LockOwners.OWNER_METHOD + " no longer returns a Thread."
                + HOW_TO_FIX);
    }

    @Test
    @DisplayName("without the agent's opening the owner reads as unknown, never as a wrong thread")
    void withoutTheOpeningTheOwnerIsUnknown() {
        ReentrantLock lock = new ReentrantLock();
        lock.lock();
        try {
            Thread owner = LockOwners.ownerOf(lock);
            assertTrue(owner == null || owner == Thread.currentThread(),
                    "either the package is closed and nothing is read, or the read is right: " + owner);
        } finally {
            lock.unlock();
        }
    }
}
