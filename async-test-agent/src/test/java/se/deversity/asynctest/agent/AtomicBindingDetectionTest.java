package se.deversity.asynctest.agent;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Does the weaver notice a field being bound to a VarHandle in a static initializer? */
class AtomicBindingDetectionTest {

    /** A node whose {@code next} is mutated by CAS, the shape Guava's waiter list uses. */
    public static class CasNode {
        volatile CasNode next;

        private static final VarHandle NEXT;

        static {
            try {
                NEXT = MethodHandles.lookup().findVarHandle(CasNode.class, "next", CasNode.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        public void link(CasNode target) {
            NEXT.compareAndSet(this, null, target);
        }
    }

    @Test
    @DisplayName("a field bound to a VarHandle in <clinit> is recorded as atomically managed")
    void varHandleBindingIsSeen() throws Exception {
        Class<?> woven = new ByteBuddy()
                .redefine(CasNode.class)
                .visit(FieldAccessWeaver.visitor(true))
                .make()
                .load(getClass().getClassLoader(), ClassLoadingStrategy.Default.CHILD_FIRST)
                .getLoaded();

        // Force <clinit> to run: the binding call is what emits the registration.
        woven.getDeclaredConstructor().newInstance();

        assertTrue(TelemetryRegistry.isAtomicallyManaged(
                        "se.deversity.asynctest.agent.AtomicBindingDetectionTest$CasNode.next"),
                "the class binds next to a VarHandle in its static initializer, which is the only "
                        + "static evidence that the field belongs to a lock-free protocol. If this "
                        + "is not seen, the weaver is either skipping type initializers or losing "
                        + "the constants the binding call pushes.");
    }
}
