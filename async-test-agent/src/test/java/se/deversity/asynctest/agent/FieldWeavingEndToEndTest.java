package se.deversity.asynctest.agent;

import com.example.agentfixture.DirectFieldMutationBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.AtomicityValidator;
import se.deversity.asynctest.telemetry.TelemetryBridge;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins the capability {@code fields=true} adds: a field touched only from inside a method body
 * becomes observable.
 *
 * <p><strong>Why this test exists.</strong> The README's headline example is a bare
 * {@code counter++} in a test body, and until field weaving landed nothing in the product could
 * see it — {@code AgentFeedsDetectorEndToEndTest} pins exactly that boundary, and it still does,
 * because weaving is opt-in and that test attaches without the flag. This class is the other side
 * of the same boundary: same fixture, same detector, same pipeline, one extra agent option. Read
 * the two together and the observation surface is fully specified.
 *
 * <p>Separate class rather than another method on the existing test because {@code selfAttach} is
 * at-most-once per JVM: the option string is fixed for the life of the process, so proving both
 * settings requires two processes. {@code forkEvery=1} gives each test class its own JVM, which
 * is what makes that work.
 *
 * <p>Deliberately not an {@code @AsyncTest} method, for the same reason the sibling test is not:
 * driving the threads directly keeps a failure pointing at the weaver-to-detector chain rather
 * than at {@code ConcurrencyRunner}'s wiring.
 */
@Tag("e2e")
class FieldWeavingEndToEndTest {

    @BeforeAll
    static void attachWithFieldWeaving() {
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            supported = false;
        }
        assumeTrue(supported,
                "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");

        AsyncTestAgent.selfAttach("includes=com.example.agentfixture,fields=true");
    }

    @AfterEach
    void stopRegistry() {
        TelemetryRegistry.stop();
    }

    @Test
    @DisplayName("count++ inside a method is reported when fields=true, the README's own example")
    void directFieldMutationIsReportedWhenFieldWeavingIsEnabled() throws Exception {
        AtomicityValidator validator = new AtomicityValidator();
        Set<Long> workerThreadIds = ConcurrentHashMap.newKeySet();
        DirectFieldMutationBean bean = new DirectFieldMutationBean();

        CountDownLatch done = new CountDownLatch(2);
        try (TelemetryBridge bridge =
                     TelemetryBridge.activateWithFilter(validator, workerThreadIds::contains)) {
            for (int t = 0; t < 2; t++) {
                new Thread(() -> {
                    workerThreadIds.add(Thread.currentThread().threadId());
                    for (int i = 0; i < 200; i++) {
                        // Neither a getter nor a setter. The count++ inside compiles to a
                        // GETFIELD/PUTFIELD pair with no accessor call for Advice to bind to,
                        // which is precisely why this needs instruction-level weaving.
                        bean.increment();
                    }
                    done.countDown();
                }, "mutator-" + t).start();
            }
            assertTrue(done.await(10, TimeUnit.SECONDS), "worker threads did not finish");
            TelemetryRegistry.flush();
        }

        AtomicityValidator.AtomicityReport report = validator.analyzeAtomicity();

        assertTrue(report.unsafeFieldAccesses.stream()
                        .anyMatch(f -> f.contains("DirectFieldMutationBean.count")),
                "fields=true was supplied, so the GETFIELD and PUTFIELD inside increment() should "
                        + "both have been woven and DirectFieldMutationBean.count should be "
                        + "reported as read and written across two threads. Nothing matched, "
                        + "which means the field weaver is not reaching the detector: check that "
                        + "FieldAccessWeaver.visitor() is still applied in "
                        + "AsyncTestAgent.installUnguarded when options.fields() is set, that the "
                        + "owner is not caught by FieldAccessWeaver.shouldWeave, and that the "
                        + "emitted identifier still survives TelemetryBridge.fieldIdentifier "
                        + "unchanged. Findings were: " + report.unsafeFieldAccesses);
    }

    /**
     * Guards the property that makes the weaving safe to ship: instrumenting the instruction
     * stream must not change what the program computes.
     *
     * <p>A stack-neutral insertion is easy to get subtly wrong — one leftover operand and the
     * verifier rejects the class, or worse, the field is written with the wrong value. Running
     * the racy increment single-threaded gives an exact expected value, so any corruption of the
     * operand stack shows up as a wrong count rather than as a vague failure elsewhere.
     */
    @Test
    @DisplayName("weaving preserves program semantics: single-threaded increments still add up")
    void weavingDoesNotChangeTheComputedValue() {
        DirectFieldMutationBean bean = new DirectFieldMutationBean();
        for (int i = 0; i < 1000; i++) {
            bean.increment();
        }
        assertEquals(1000, bean.observedCount(),
                "increment() was woven with an observation call before each field instruction. "
                        + "The inserted sequence must push and consume exactly the operands it "
                        + "uses, leaving the stack as it found it. A wrong value here means the "
                        + "weaver corrupted the operand stack.");
    }
}
