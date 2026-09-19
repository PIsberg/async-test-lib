package se.deversity.asynctest.telemetry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Canary for the JDK internals {@link SpinLocks} reads to resolve an updater bound before the agent
 * attached (#659, #668).
 *
 * <p>That read is silent by design: on a JDK where the implementation class, its {@code offset} or
 * {@code tclass} field, or its three-argument constructor is gone or has changed type, every such
 * updater quietly stays unresolved and its spinlock is reported instead of excused. Nothing else
 * in the build would notice, so this class fails it, naming what moved. It needs no agent and no
 * opened package: declared members are visible without access, which is all it checks. It runs
 * in every build, and so on each JDK in the CI matrix.
 */
class JdkUpdaterShapeCanaryTest {

    /** A flag to make a real updater on, so the implementation class is the one the JDK hands out. */
    static final class Flag {
        volatile int busy;
    }

    private static final String HOW_TO_FIX = " SpinLocks.describe(AtomicIntegerFieldUpdater) reads this "
            + "to resolve pre-attach updaters; on this JDK they would silently stay unresolved. Update "
            + "SpinLocks to the new shape (or retire the read) before shipping on "
            + Runtime.version() + ".";

    @Test
    @DisplayName("the JDK still hands out the updater implementation SpinLocks reads")
    void theUpdaterImplementationIsTheOneRead() {
        Class<?> impl = AtomicIntegerFieldUpdater.newUpdater(Flag.class, "busy").getClass();
        assertEquals(SpinLocks.UPDATER_IMPL, impl.getName(),
                "AtomicIntegerFieldUpdater.newUpdater now returns " + impl.getName() + "." + HOW_TO_FIX);
        assertEquals(null, impl.getClassLoader(),
                "the implementation is no longer a bootstrap class." + HOW_TO_FIX);
    }

    @Test
    @DisplayName("the implementation keeps a long offset field and a Class tclass field")
    void theOffsetAndTargetFieldsKeepTheirTypes() {
        Class<?> impl = implementation();
        assertInstanceField(impl, SpinLocks.UPDATER_OFFSET, long.class);
        assertInstanceField(impl, SpinLocks.UPDATER_TARGET, Class.class);
    }

    @Test
    @DisplayName("the implementation keeps its (Class, String, Class) constructor")
    void theConstructorKeepsItsShape() {
        try {
            implementation().getDeclaredConstructor(Class.class, String.class, Class.class);
        } catch (NoSuchMethodException e) {
            fail(SpinLocks.UPDATER_IMPL + " has no (Class, String, Class) constructor any more." + HOW_TO_FIX, e);
        }
    }

    private static Class<?> implementation() {
        try {
            return Class.forName(SpinLocks.UPDATER_IMPL);
        } catch (ClassNotFoundException e) {
            return fail(SpinLocks.UPDATER_IMPL + " no longer exists." + HOW_TO_FIX, e);
        }
    }

    private static void assertInstanceField(Class<?> impl, String name, Class<?> type) {
        Field field;
        try {
            field = impl.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            fail(impl.getName() + " has no field '" + name + "' any more." + HOW_TO_FIX, e);
            return;
        }
        assertEquals(type, field.getType(),
                impl.getName() + "." + name + " is now " + field.getType().getName() + ", not "
                        + type.getName() + "." + HOW_TO_FIX);
        assertFalse(Modifier.isStatic(field.getModifiers()),
                impl.getName() + "." + name + " is now static." + HOW_TO_FIX);
    }
}
