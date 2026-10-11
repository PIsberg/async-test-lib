package se.deversity.asynctest.agent;

import com.example.agentfixture.InterruptHandlingShapes;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AgentThreadHooks;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which {@code catch (InterruptedException e)} shapes the handler weaver calls swallowing.
 *
 * <p>Woven against a stub that keeps what the inserted call hands it, so the assertion is about
 * the bytecode judgement rather than what a detector made of it. Loading each woven class is a
 * check too: the verifier runs on it, so an insertion that left the stack or the frames wrong
 * fails the load.
 */
class InterruptHandlerWeaverTest {

    /** What the inserted call was handed, in order. */
    static final ConcurrentLinkedQueue<String> ENTERED = new ConcurrentLinkedQueue<>();

    /** Stands in for {@link AgentThreadHooks}, with the one method the weaver resolves. */
    public static class StubHooks {
        public static void interruptSwallowingMethodEntered(String locations) {
            ENTERED.add(locations);
        }
    }

    @BeforeEach
    void clear() {
        ENTERED.clear();
    }

    private static Class<?> woven(Class<?> type) {
        return new ByteBuddy()
                .redefine(type)
                .visit(InterruptHandlerWeaver.of(StubHooks.class))
                .make()
                .load(InterruptHandlerWeaverTest.class.getClassLoader(),
                        ClassLoadingStrategy.Default.CHILD_FIRST)
                .getLoaded();
    }

    private static String entered(Object sample, String method) throws Exception {
        ENTERED.clear();
        sample.getClass().getMethod(method).invoke(sample);
        return String.join(";", ENTERED);
    }

    @Test
    @DisplayName("the hook resolves against the real hooks class")
    void realHooksClassHasTheHook() {
        InterruptHandlerWeaver.of(AgentThreadHooks.class);
    }

    @Test
    @DisplayName("each swallowing shape is named once per call, with its method and line")
    void swallowingShapesAreNamed() throws Exception {
        Object sample = woven(InterruptHandlingShapes.class).getConstructor().newInstance();
        String owner = InterruptHandlingShapes.class.getName();

        for (String method : List.of("swallowEmpty", "swallowLogged", "swallowByReturning",
                "swallowInAMultiCatch")) {
            String entered = entered(sample, method);
            assertTrue(entered.startsWith(owner + "." + method + ":"),
                    method + " neither rethrows nor restores the flag, so its method start must "
                            + "hand its location to the hook; got '" + entered + "'");
            int line = Integer.parseInt(entered.substring(entered.lastIndexOf(':') + 1));
            assertTrue(line > 0, "the location carries the handler's line: " + entered);
            assertEquals(1, ENTERED.size(), method + " has one swallowing handler: " + ENTERED);
        }
    }

    @Test
    @DisplayName("restoring, rethrowing and unrelated handlers are left alone")
    void handledShapesAreNotNamed() throws Exception {
        Object sample = woven(InterruptHandlingShapes.class).getConstructor().newInstance();

        for (String method : List.of("restoreTheFlag", "restoreThroughAHelper", "wrapAndThrow",
                "rethrow", "catchesSomethingElse", "restoreLaterInFinally")) {
            assertEquals("", entered(sample, method),
                    method + " rethrows or restores the flag, or catches something else, so "
                            + "nothing may be inserted for it");
        }
        assertEquals(6, sample.getClass().getField("calls").getInt(sample),
                "the handled shapes still ran their bodies once each");
    }

    @Test
    @DisplayName("a run() that returns on interrupt is a thread's own policy and is not named")
    void threadRunExitIsNotNamed() throws Exception {
        Runnable worker = (Runnable) woven(InterruptHandlingShapes.Worker.class)
                .getConstructor().newInstance();
        worker.run();
        assertTrue(ENTERED.isEmpty(),
                "Worker.run returns when interrupted, which ends the thread's own loop: the one "
                        + "place an interrupt may be consumed. Got " + ENTERED);
    }
}
