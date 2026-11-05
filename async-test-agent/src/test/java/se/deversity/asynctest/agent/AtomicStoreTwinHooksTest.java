package se.deversity.asynctest.agent;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The weaver substitutes a store whose value was not built from a read with the hook it would
 * otherwise have used, plus {@code Unrelated} (#1187). Each such twin must exist with the same
 * parameters and a {@code void} result, or the woven call site fails to link at run time.
 *
 * <p>The store forms listed here are exactly the ones the weaver's atomic tables substitute.
 */
class AtomicStoreTwinHooksTest {

    @Test
    void everyIntegerStoreFormTheWeaverSubstitutesHasAnUnrelatedTwin() throws NoSuchMethodException {
        for (String form : new String[] {"set", "lazySet", "setPlain", "setOpaque", "setRelease"}) {
            Method twin = TelemetryRegistry.class.getMethod(
                    form + "AtomicIntegerUnrelated", AtomicInteger.class, int.class);
            assertEquals(void.class, twin.getReturnType(), form + "AtomicIntegerUnrelated must be void");
        }
    }

    @Test
    void everyLongStoreFormTheWeaverSubstitutesHasAnUnrelatedTwin() throws NoSuchMethodException {
        for (String form : new String[] {"set", "lazySet", "setRelease"}) {
            Method twin = TelemetryRegistry.class.getMethod(
                    form + "AtomicLongUnrelated", AtomicLong.class, long.class);
            assertEquals(void.class, twin.getReturnType(), form + "AtomicLongUnrelated must be void");
        }
    }
}
