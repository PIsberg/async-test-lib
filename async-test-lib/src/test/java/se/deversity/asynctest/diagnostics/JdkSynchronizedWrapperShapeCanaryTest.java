package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Canary for the JDK internal {@link SynchronizedMutexes} reads to find a synchronized wrapper's
 * monitor (#996).
 *
 * <p>The read is silent by design: on a JDK where {@code Collections.SynchronizedCollection.mutex}
 * moves, every mutex reads as unknown and the iteration detector goes back to skipping synchronized
 * sets it did not see being made. Nothing else would notice, so this fails the build, naming what
 * moved. It reads declared members only, which needs no opened package, and runs on each JDK in
 * the matrix.
 */
class JdkSynchronizedWrapperShapeCanaryTest {

    private static final String HOW_TO_FIX = " SynchronizedMutexes reads this to judge an iteration "
            + "against the wrapper's real lock; on this JDK it would silently read as unknown. Update "
            + "SynchronizedMutexes before shipping on " + Runtime.version() + ".";

    @Test
    @DisplayName("SynchronizedCollection keeps an instance Object field named mutex")
    void theMutexFieldKeepsItsShape() throws ClassNotFoundException {
        Class<?> wrapper = Class.forName(SynchronizedMutexes.WRAPPER_CLASS);
        Field field;
        try {
            field = wrapper.getDeclaredField(SynchronizedMutexes.MUTEX_FIELD);
        } catch (NoSuchFieldException e) {
            fail(wrapper.getName() + " has no field '" + SynchronizedMutexes.MUTEX_FIELD + "' any more."
                    + HOW_TO_FIX, e);
            return;
        }
        assertFalse(Modifier.isStatic(field.getModifiers()), "mutex is now static." + HOW_TO_FIX);
        assertEquals(Object.class, field.getType(), "mutex is now a " + field.getType().getName() + "."
                + HOW_TO_FIX);
        assertTrue(wrapper.isInstance(Collections.synchronizedSet(new HashSet<>())),
                "synchronizedSet no longer extends " + wrapper.getName() + "." + HOW_TO_FIX);
        assertTrue(wrapper.isInstance(Collections.synchronizedMap(new java.util.HashMap<>()).keySet()),
                "a synchronized map's key set no longer extends " + wrapper.getName() + "." + HOW_TO_FIX);
    }
}
