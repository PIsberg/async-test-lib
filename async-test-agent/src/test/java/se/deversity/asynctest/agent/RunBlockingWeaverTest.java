package se.deversity.asynctest.agent;

import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which call sites {@link RunBlockingWeaver} treats as a Kotlin {@code runBlocking} (#1007).
 *
 * <p>The first version matched {@code runBlocking} and {@code runBlocking$default} only, and the
 * Kotlin fixture showed Kotlin 2.4.21 against kotlinx 1.11.0 compiling {@code runBlocking { }} to
 * {@code runBlockingK$default}, which it never saw.
 */
class RunBlockingWeaverTest {

    private static final String FACADE = "kotlinx/coroutines/BuildersKt";

    @Test
    @DisplayName("every name a compiled runBlocking call site uses is matched, on the facade only")
    void everyCompiledFormIsMatched() {
        for (String name : new String[] {"runBlockingK", "runBlockingK$default", "runBlocking",
                "runBlocking$default"}) {
            assertTrue(RunBlockingWeaver.isRunBlocking(Opcodes.INVOKESTATIC, FACADE, name), name);
            assertFalse(RunBlockingWeaver.isRunBlocking(Opcodes.INVOKESTATIC,
                    "kotlinx/coroutines/BuildersKt__Builders_concurrentKt", name), name);
        }
    }

    @Test
    @DisplayName("kotlinx's internal runBlockingImpl and a non-static call are not runBlocking")
    void internalsAreNotMatched() {
        assertFalse(RunBlockingWeaver.isRunBlocking(Opcodes.INVOKESTATIC, FACADE, "runBlockingImpl"));
        assertFalse(RunBlockingWeaver.isRunBlocking(Opcodes.INVOKEVIRTUAL, FACADE, "runBlockingK"));
    }
}
