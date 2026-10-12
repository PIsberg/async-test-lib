package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Field;

/**
 * Reads the {@code PipedInputStream} a {@code PipedOutputStream} writes into (#994).
 *
 * <p>The pair is linked by a constructor or {@code connect}, and the writing end keeps its reading
 * end in a private {@code sink} field, the only place the link is recorded. The field is in
 * {@code java.io}, which the agent opens to this library under collection weaving; without the
 * agent, or before it attached, the sink is {@code null} and a woven write is not paired. The
 * handle is looked up again until it can be used. {@code JdkPipeShapeCanaryTest} fails the build
 * on a JDK that moves the field.
 */
final class PipeSinks {

    /** {@code PipedOutputStream}'s private field holding the connected reading end. */
    static final String SINK_FIELD = "sink";

    private static volatile @Nullable Field sink;

    private PipeSinks() {
    }

    /**
     * {@return the reading end {@code out} is connected to, or {@code null} when it is unconnected
     * or the field cannot be read}
     *
     * @param out the writing end
     */
    static @Nullable PipedInputStream sinkOf(PipedOutputStream out) {
        Field usable = field();
        if (usable == null) {
            return null;
        }
        try {
            return (PipedInputStream) usable.get(out);
        } catch (ReflectiveOperationException | RuntimeException e) { // NOPMD - unreadable leaves the write unpaired
            return null;
        }
    }

    /** {@return the field, made accessible now if {@code java.io} has been opened since the last ask} */
    private static @Nullable Field field() {
        Field known = sink;
        if (known != null) {
            return known;
        }
        try {
            Field field = PipedOutputStream.class.getDeclaredField(SINK_FIELD);
            if (!field.trySetAccessible()) {
                return null;
            }
            sink = field;
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) { // NOPMD - a closed package is the usual case
            return null;
        }
    }
}
