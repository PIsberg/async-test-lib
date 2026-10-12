package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Canary for the JDK internal {@link PipeSinks} reads to pair a piped writer with its reader (#994).
 *
 * <p>The read is silent by design: on a JDK where {@code PipedOutputStream.sink} moves, every woven
 * write goes unpaired and a pipe used from one thread is no longer reported. Nothing else would
 * notice, so this fails the build, naming what moved. It reads declared members only, which needs
 * no opened package, and runs on each JDK in the matrix.
 */
class JdkPipeShapeCanaryTest {

    private static final String HOW_TO_FIX = " PipeSinks reads this to pair a woven write with its "
            + "reading end; on this JDK it would silently read as unknown. Update PipeSinks before "
            + "shipping on " + Runtime.version() + ".";

    @Test
    @DisplayName("PipedOutputStream keeps an instance field holding its PipedInputStream")
    void theSinkFieldKeepsItsShape() {
        Field field;
        try {
            field = PipedOutputStream.class.getDeclaredField(PipeSinks.SINK_FIELD);
        } catch (NoSuchFieldException e) {
            fail("PipedOutputStream has no field '" + PipeSinks.SINK_FIELD + "' any more." + HOW_TO_FIX, e);
            return;
        }
        assertFalse(Modifier.isStatic(field.getModifiers()), "sink is now static." + HOW_TO_FIX);
        assertEquals(PipedInputStream.class, field.getType(), "sink is now a " + field.getType().getName()
                + "." + HOW_TO_FIX);
    }
}
