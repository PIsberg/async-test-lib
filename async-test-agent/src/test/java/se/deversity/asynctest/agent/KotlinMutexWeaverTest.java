package se.deversity.asynctest.agent;

import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which call sites {@link KotlinMutexWeaver} substitutes (#1007). The descriptors are the ones Kotlin
 * 2.4.21 compiles against kotlinx 1.11.0, read with {@code javap} from the consumer fixture: an
 * inlined {@code withLock} calls {@code lock} and {@code unlock} through the interface, and a bare
 * {@code lock()}, {@code unlock()} or {@code tryLock()} the interface's static {@code $default} bridge.
 */
class KotlinMutexWeaverTest {

    private static final String MUTEX = "kotlinx/coroutines/sync/Mutex";

    @Test
    @DisplayName("each compiled lock, unlock and tryLock form is matched to its hook")
    void everyCompiledFormIsMatched() {
        assertEquals("kotlinMutexLock", hook(Opcodes.INVOKEINTERFACE, "lock",
                "(Ljava/lang/Object;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"));
        assertEquals("kotlinMutexLockDefault", hook(Opcodes.INVOKESTATIC, "lock$default",
                "(Lkotlinx/coroutines/sync/Mutex;Ljava/lang/Object;Lkotlin/coroutines/Continuation;ILjava/lang/Object;)Ljava/lang/Object;"));
        assertEquals("kotlinMutexUnlock", hook(Opcodes.INVOKEINTERFACE, "unlock", "(Ljava/lang/Object;)V"));
        assertEquals("kotlinMutexUnlockDefault", hook(Opcodes.INVOKESTATIC, "unlock$default",
                "(Lkotlinx/coroutines/sync/Mutex;Ljava/lang/Object;ILjava/lang/Object;)V"));
        assertEquals("kotlinMutexTryLock", hook(Opcodes.INVOKEINTERFACE, "tryLock", "(Ljava/lang/Object;)Z"));
        assertEquals("kotlinMutexTryLockDefault", hook(Opcodes.INVOKESTATIC, "tryLock$default",
                "(Lkotlinx/coroutines/sync/Mutex;Ljava/lang/Object;ILjava/lang/Object;)Z"));
    }

    @Test
    @DisplayName("a hook takes the call site's stack with each Kotlin type as Object, and returns its result")
    void hookDescriptorsKeepTheStack() {
        for (KotlinMutexWeaver.Row row : KotlinMutexWeaver.ROWS) {
            String site = row.descriptor()
                    .replace("Lkotlinx/coroutines/sync/Mutex;", "Ljava/lang/Object;")
                    .replace("Lkotlin/coroutines/Continuation;", "Ljava/lang/Object;");
            // An interface call's receiver becomes the hook's first argument.
            String expected = row.opcode() == Opcodes.INVOKEINTERFACE
                    ? "(Ljava/lang/Object;" + site.substring(1) : site;
            assertEquals(expected, row.hookDescriptor(), row.name());
            assertEquals(net.bytebuddy.jar.asm.Type.getArgumentTypes(row.hookDescriptor()).length,
                    row.hookParameters().size(), row.name());
        }
    }

    @Test
    @DisplayName("another owner, opcode or descriptor is left alone")
    void othersAreNotMatched() {
        assertNull(KotlinMutexWeaver.match(Opcodes.INVOKEINTERFACE, "kotlinx/coroutines/sync/Semaphore",
                "release", "()V"));
        assertNull(KotlinMutexWeaver.match(Opcodes.INVOKEINTERFACE, MUTEX, "isLocked", "()Z"));
        assertNull(KotlinMutexWeaver.match(Opcodes.INVOKEINTERFACE, MUTEX, "holdsLock", "(Ljava/lang/Object;)Z"));
        assertNull(KotlinMutexWeaver.match(Opcodes.INVOKEVIRTUAL, MUTEX, "unlock", "(Ljava/lang/Object;)V"));
        assertNull(KotlinMutexWeaver.match(Opcodes.INVOKEINTERFACE, MUTEX, "unlock", "(Ljava/lang/String;)V"),
                "a descriptor from another kotlinx is not substituted by a hook that could not make it");
        assertNull(KotlinMutexWeaver.match(Opcodes.INVOKEVIRTUAL, "kotlinx/coroutines/sync/MutexImpl", "lock",
                "(Ljava/lang/Object;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"));
    }

    @Test
    @DisplayName("a hooks class missing a hook is refused as a version skew")
    void missingHooksAreRefused() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> KotlinMutexWeaver.of(Object.class));
        assertTrue(e.getMessage().contains("agent and library versions disagree"), e.getMessage());
    }

    private static String hook(int opcode, String name, String descriptor) {
        KotlinMutexWeaver.Row row = KotlinMutexWeaver.match(opcode, MUTEX, name, descriptor);
        assertNotNull(row, name + descriptor);
        return row.hook();
    }
}
