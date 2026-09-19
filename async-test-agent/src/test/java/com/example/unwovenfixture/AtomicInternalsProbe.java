package com.example.unwovenfixture;

/**
 * Asks, from whichever module defined this copy, whether code there can reflect into
 * {@code java.util.concurrent.atomic} (#668).
 *
 * <p>The test defines a copy of this class in a loader of its own, so the answer is that loader's
 * unnamed module's, which is what the agent's opening may or may not reach.
 */
public final class AtomicInternalsProbe {

    private AtomicInternalsProbe() {
    }

    /** {@return whether this module can open the JDK updater implementation's private state} */
    public static boolean canReadUpdaterInternals() {
        try {
            Class<?> impl = Class.forName(
                    "java.util.concurrent.atomic.AtomicIntegerFieldUpdater$AtomicIntegerFieldUpdaterImpl");
            return impl.getDeclaredField("offset").trySetAccessible();
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}
