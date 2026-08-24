package se.deversity.asynctest.agent;

import com.example.agentfixture.BeforeAttachBean;
import com.example.agentfixture.UnretransformableBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * One class the JVM refuses to re-weave must not cost every other class its weaving.
 *
 * <p>Byte Buddy's default hands every already-loaded class to
 * {@link Instrumentation#retransformClasses} in a single call, and that call is all-or-nothing:
 * the JVM either re-weaves the whole batch or none of it. The default
 * {@code RedefinitionStrategy.Listener} then swallows the failure, so the agent came up looking
 * installed while weaving only what loaded from that moment on. The corpus eval hit this when four
 * libraries joined its classpath: two Netty logger adapters that the JVM will not re-verify took
 * instrumented types from 1074 down to 200 and detection of the documented-unsafe group from all
 * of it to none of it, with nothing logged.
 *
 * <p>The refusal is injected rather than reproduced. What made Netty's adapters fail is a missing
 * log4j class at re-verification time, which is not something a test can arrange without shipping
 * a broken jar; what the fix has to survive is only that one class in the batch throws.
 * {@link PoisoningInstrumentation} narrows the loaded-class list to two fixtures and throws for
 * any batch containing {@link UnretransformableBean}, which is the real JVM behaviour with the
 * cause left out.
 *
 * <p>Its own fork, like {@code SelfAttachTest}: the install gate is once per JVM.
 */
@Tag("e2e")
class RetransformBatchIsolationTest {

    private static PoisoningInstrumentation instrumentation;

    @BeforeAll
    static void attachOnce() {
        Instrumentation real;
        try {
            real = ByteBuddyAgent.install();
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            real = null;
        }
        assumeTrue(real != null,
                "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");

        // Both loaded and touched BEFORE the install, so only retransformation can weave them.
        BeforeAttachBean innocent = new BeforeAttachBean();
        innocent.setValue(innocent.getValue() + 1);
        UnretransformableBean poison = new UnretransformableBean();
        poison.setValue(poison.getValue() + 1);

        instrumentation = new PoisoningInstrumentation(
                real, UnretransformableBean.class,
                List.of(UnretransformableBean.class, BeforeAttachBean.class));

        // agentmain is the dynamic-attach entry point, which is the one that retransforms.
        AsyncTestAgent.agentmain("includes=com.example.agentfixture", instrumentation);
    }

    @BeforeEach
    @AfterEach
    void resetRegistry() {
        TelemetryRegistry.stop();
    }

    @Test
    @DisplayName("a class the JVM refuses to re-weave does not take the rest of its batch with it")
    void aRefusedClassDoesNotStarveTheOthers() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);
        TelemetryRegistry.start((tid, id, isWrite) -> {
            events.add(id + ":" + isWrite);
            latch.countDown();
        });

        BeforeAttachBean bean = new BeforeAttachBean();
        bean.setValue(7);

        assertTrue(latch.await(2, TimeUnit.SECONDS),
                "BeforeAttachBean was loaded before the install and shared a retransformation "
                        + "batch with a class the JVM refuses; it must still have been re-woven. "
                        + "Nothing arrived, which is the all-or-nothing batch failing silently. "
                        + "Events: " + events);
        assertTrue(events.contains("com.example.agentfixture.BeforeAttachBean.setValue:true"),
                "expected the retransformed setter identifier; got " + events);
    }

    @Test
    @DisplayName("the refused class is the only one left out")
    void theRefusedClassIsTheOnlyOneLeftOut() {
        assertTrue(instrumentation.refusedBatches() > 0,
                "the test's premise is that at least one batch was refused; none was, so this "
                        + "test is no longer measuring what it claims");
        assertTrue(instrumentation.retransformed().contains(BeforeAttachBean.class),
                "the innocent class must have reached the JVM in a batch of its own after the "
                        + "reallocator split the failing one; it did not");
        assertFalse(instrumentation.retransformed().contains(UnretransformableBean.class),
                "the refused class cannot have been re-woven; if it was, the poison is not "
                        + "working and the first assertion proves nothing");
    }
}
