package se.deversity.asynctest.agent;

import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which methods {@link PoolTaskWeaver} rewrites, and the local each one's task is in (#1173). */
class PoolTaskWeaverTest {

    private static final int PROTECTED = Opcodes.ACC_PROTECTED;

    @Test
    @DisplayName("a pool subclass's beforeExecute and afterExecute, and a handler's rejectedExecution, are rewritten")
    void theThreeMethodsAreSites() {
        PoolTaskWeaver.Site before = PoolTaskWeaver.site(true, false, PROTECTED, "beforeExecute",
                "(Ljava/lang/Thread;Ljava/lang/Runnable;)V");
        assertNotNull(before);
        assertEquals(2, before.slot(), "this, the worker, then the task");
        PoolTaskWeaver.Site after = PoolTaskWeaver.site(true, false, PROTECTED, "afterExecute",
                "(Ljava/lang/Runnable;Ljava/lang/Throwable;)V");
        assertNotNull(after);
        assertEquals(1, after.slot());
        PoolTaskWeaver.Site rejected = PoolTaskWeaver.site(false, true, Opcodes.ACC_PUBLIC, "rejectedExecution",
                "(Ljava/lang/Runnable;Ljava/util/concurrent/ThreadPoolExecutor;)V");
        assertNotNull(rejected);
        assertEquals(1, rejected.slot());
    }

    @Test
    @DisplayName("the same names elsewhere, static, abstract or with another descriptor are left alone")
    void othersAreNotSites() {
        assertNull(PoolTaskWeaver.site(false, true, PROTECTED, "beforeExecute",
                "(Ljava/lang/Thread;Ljava/lang/Runnable;)V"), "a handler's beforeExecute is no pool's");
        assertNull(PoolTaskWeaver.site(true, false, Opcodes.ACC_PUBLIC, "rejectedExecution",
                "(Ljava/lang/Runnable;Ljava/util/concurrent/ThreadPoolExecutor;)V"), "a pool is no handler");
        assertNull(PoolTaskWeaver.site(true, false, PROTECTED | Opcodes.ACC_STATIC, "beforeExecute",
                "(Ljava/lang/Thread;Ljava/lang/Runnable;)V"));
        assertNull(PoolTaskWeaver.site(true, false, PROTECTED | Opcodes.ACC_ABSTRACT, "afterExecute",
                "(Ljava/lang/Runnable;Ljava/lang/Throwable;)V"));
        assertNull(PoolTaskWeaver.site(true, false, PROTECTED, "afterExecute", "(Ljava/lang/Runnable;)V"));
        assertNull(PoolTaskWeaver.site(true, true, PROTECTED, "execute", "(Ljava/lang/Runnable;)V"),
                "an execute override still keeps its pool unwrapped");
    }

    @Test
    @DisplayName("a hooks class missing a hook is refused as a version skew")
    void missingHooksAreRefused() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> PoolTaskWeaver.of(Object.class));
        assertTrue(e.getMessage().contains("agent and library versions disagree"), e.getMessage());
    }
}
