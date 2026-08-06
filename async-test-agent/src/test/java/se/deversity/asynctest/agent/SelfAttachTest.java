package se.deversity.asynctest.agent;
import org.junit.jupiter.api.Tag;

import com.example.agentfixture.BeforeAttachBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises {@link AsyncTestAgent#selfAttach(String)} end-to-end inside the forked test
 * JVM.
 *
 * <p>Because self-attach installs a JVM-wide transformer exactly once per JVM, all tests
 * share a single attach performed in {@link #attachOnce()}. Surefire ({@code forkCount=1},
 * {@code reuseForks=false}) and Gradle ({@code forkEvery = 1}) give this class its own
 * fork, so the JVM-wide install does not leak into other test classes. The attach is
 * scoped with {@code includes=com.example.agentfixture} to avoid instrumenting the rest of
 * the test classpath mid-run.
 *
 * <p>Self-attachment is disabled by default on JDK&nbsp;9+; the build passes
 * {@code -Djdk.attach.allowAttachSelf=true} to the test JVM. If a run does not (for
 * example an IDE launch), {@link #attachOnce()} detects the missing capability and aborts
 * the whole class via {@link org.junit.jupiter.api.Assumptions}.
 */
@Tag("e2e")
class SelfAttachTest {

    @BeforeAll
    static void attachOnce() {
        // Capability probe: if the JVM forbids self-attach, skip the whole class rather
        // than fail. (When permitted, selfAttach() below reuses the same attach.)
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            supported = false;
        }
        assumeTrue(supported,
                "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");

        // Force BeforeAttachBean to load BEFORE the agent attaches, so that only the
        // retransformation path (not load-time weaving) can instrument its accessors.
        BeforeAttachBean warm = new BeforeAttachBean();
        warm.setValue(warm.getValue() + 1); // touch accessors while still un-woven (no events)

        // Attach the agent to this running JVM, restricted to the fixture package.
        AsyncTestAgent.selfAttach("includes=com.example.agentfixture");
    }

    @BeforeEach
    @AfterEach
    void resetRegistry() {
        TelemetryRegistry.stop();
    }

    @Test
    void setterOnClassLoadedAfterAttach_isInstrumentedAtLoadTime() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);
        TelemetryRegistry.start((tid, id, isWrite) -> {
            events.add(id + ":" + isWrite);
            latch.countDown();
        });

        // First reference -> loaded AFTER attach -> woven at load time.
        Class<?> beanClass = Class.forName("com.example.agentfixture.AfterAttachBean");
        Object bean = beanClass.getDeclaredConstructor().newInstance();
        beanClass.getMethod("setValue", int.class).invoke(bean, 42);

        assertTrue(latch.await(2, TimeUnit.SECONDS),
                "setter of a class loaded after self-attach must produce a telemetry event");
        assertTrue(events.contains("com.example.agentfixture.AfterAttachBean.setValue:true"),
                "expected the load-time-woven setter identifier; got " + events);
    }

    @Test
    void setterOnClassLoadedBeforeAttach_isRetransformed() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);
        TelemetryRegistry.start((tid, id, isWrite) -> {
            events.add(id + ":" + isWrite);
            latch.countDown();
        });

        // BeforeAttachBean was loaded in @BeforeAll, i.e. BEFORE the agent attached. If an
        // event arrives, RETRANSFORMATION + disableClassFormatChanges() re-wove the
        // already-loaded class in place.
        BeforeAttachBean bean = new BeforeAttachBean();
        bean.setValue(7);

        assertTrue(latch.await(2, TimeUnit.SECONDS),
                "setter of a class loaded BEFORE self-attach must be re-woven via retransformation");
        assertTrue(events.contains("com.example.agentfixture.BeforeAttachBean.setValue:true"),
                "expected the retransformed setter identifier; got " + events);
    }

    @Test
    void secondSelfAttach_isNoOp_singleEventPerCall() throws Exception {
        // Repeat calls (even with different args) must be no-ops: no second transformer,
        // no double weaving, no exception.
        AsyncTestAgent.selfAttach("includes=com.example.agentfixture");
        AsyncTestAgent.selfAttach();

        String target = "com.example.agentfixture.IdempotentBean.setValue:true";
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);
        TelemetryRegistry.start((tid, id, isWrite) -> {
            String event = id + ":" + isWrite;
            if (target.equals(event)) {
                events.add(event);
                latch.countDown();
            }
        });

        com.example.agentfixture.IdempotentBean bean = new com.example.agentfixture.IdempotentBean();
        bean.setValue(3);

        assertTrue(latch.await(2, TimeUnit.SECONDS),
                "the single setter call must still be instrumented after a redundant selfAttach");
        // Grace window: a double-woven setter would emit a SECOND event shortly after.
        Thread.sleep(300);
        assertEquals(1, events.size(),
                "a redundant selfAttach must not double-instrument: exactly one event per call; got "
                        + events);
    }
}
