package se.deversity.asynctest.agent;

import com.example.agentfixture.DirectFieldMutationBean;
import com.example.agentfixture.SharedCounterBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.AtomicityValidator;
import se.deversity.asynctest.telemetry.TelemetryBridge;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the automatic detection path end to end with the real agent attached.
 *
 * <p><strong>What was untested.</strong> Every piece of this chain had a test and the chain itself
 * had none. {@code SelfAttachTest} proves the weaver emits an event and stops at the identifier
 * string. {@code TelemetryBridgeTest} and {@code AgentTelemetryReachesDetectorsTest} prove the
 * bridge forwards events and that the runner attaches it, but both publish the events by hand,
 * because the library module cannot have byte-buddy on its classpath. So nothing ran the real
 * weaver's output through the real bridge into a real detector, which is the only thing a user with
 * {@code -javaagent} actually cares about.
 *
 * <p>That gap mattered because the two halves make an assumption about each other. The advice
 * identifies an access as {@code declaringType.methodName}, and {@code TelemetryBridge} maps that to
 * a field by stripping the accessor prefix, so a getter and its setter land under one key and
 * {@link AtomicityValidator} can see a field that one thread read and another wrote. If the weaver's
 * identifier format were anything other than what the mapping expects, the mapping would quietly do
 * nothing, reads and writes would stay in separate buckets, and the finding would never fire while
 * every individual test still passed.
 *
 * <p>These are deliberately not {@code @AsyncTest} methods. Driving the threads directly keeps them
 * independent of the JUnit engine and of {@code ConcurrencyRunner}'s wiring, so a failure points at
 * the agent-to-detector chain rather than at the runner.
 */
class AgentFeedsDetectorEndToEndTest {

    @BeforeAll
    static void attachOnce() {
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            supported = false;
        }
        assumeTrue(supported,
                "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");

        AsyncTestAgent.selfAttach("includes=com.example.agentfixture");
    }

    @AfterEach
    void stopRegistry() {
        TelemetryRegistry.stop();
    }

    @Test
    @DisplayName("a field one thread reads and another writes is reported, via the real weaver")
    void weavedAccessorsFromTwoThreadsProduceAnAtomicityFinding() throws Exception {
        AtomicityValidator validator = new AtomicityValidator();
        Set<Long> workerThreadIds = ConcurrentHashMap.newKeySet();
        SharedCounterBean bean = new SharedCounterBean();

        CountDownLatch done = new CountDownLatch(2);
        try (TelemetryBridge bridge =
                     TelemetryBridge.activateWithFilter(validator, workerThreadIds::contains)) {

            // One thread only reads, the other only writes. Neither touches the field directly:
            // every access goes through the woven accessors, which is all the agent can see.
            Thread reader = new Thread(() -> {
                workerThreadIds.add(Thread.currentThread().threadId());
                for (int i = 0; i < 50; i++) {
                    bean.getValue();
                }
                done.countDown();
            }, "reader");
            Thread writer = new Thread(() -> {
                workerThreadIds.add(Thread.currentThread().threadId());
                for (int i = 0; i < 50; i++) {
                    bean.setValue(i);
                }
                done.countDown();
            }, "writer");

            reader.start();
            writer.start();
            assertTrue(done.await(10, TimeUnit.SECONDS), "worker threads did not finish");
            reader.join();
            writer.join();

            // Drain what they published before the bridge detaches, exactly as the runner does.
            TelemetryRegistry.flush();
        }

        AtomicityValidator.AtomicityReport report = validator.analyzeAtomicity();

        assertTrue(report.unsafeFieldAccesses.stream()
                        .anyMatch(f -> f.contains("SharedCounterBean.value")),
                "The agent wove getValue and setValue, one thread called each, and the bridge maps "
                        + "both onto the field they access, so AtomicityValidator should report "
                        + "SharedCounterBean.value as read and written across threads. Nothing "
                        + "matched, which means the chain is broken somewhere between the weaver "
                        + "and the detector: check that the advice still emits "
                        + "declaringType.methodName, that TelemetryBridge.fieldIdentifier still "
                        + "recognises the accessor prefix, and that the flush still drains before "
                        + "the bridge detaches. Findings were: " + report.unsafeFieldAccesses);
    }

    /**
     * Pins the agent's observation boundary: a field touched only from inside a method body is
     * invisible to it.
     *
     * <p>The assertion that matters here is a negative one, and a negative is worthless on its own
     * because it also holds when the agent never attached, the bridge never forwarded, or the flush
     * never ran. Each thread therefore also exercises a woven accessor on a control bean, and the
     * test requires that control to be reported. Only once the pipeline has demonstrably worked in
     * this very run does the absence of a finding for the directly-mutated field mean anything.
     */
    @Test
    @DisplayName("a field mutated inside a method is invisible to the agent, which is the boundary")
    void directFieldMutationProducesNoFindingWhileTheControlIsReported() throws Exception {
        AtomicityValidator validator = new AtomicityValidator();
        Set<Long> workerThreadIds = ConcurrentHashMap.newKeySet();
        DirectFieldMutationBean invisible = new DirectFieldMutationBean();
        SharedCounterBean control = new SharedCounterBean();

        CountDownLatch done = new CountDownLatch(2);
        try (TelemetryBridge bridge =
                     TelemetryBridge.activateWithFilter(validator, workerThreadIds::contains)) {
            for (int t = 0; t < 2; t++) {
                final boolean writes = t == 0;
                new Thread(() -> {
                    workerThreadIds.add(Thread.currentThread().threadId());
                    for (int i = 0; i < 200; i++) {
                        invisible.increment();          // racy, and unobservable
                        if (writes) {                   // control: goes through a woven accessor
                            control.setValue(i);
                        } else {
                            control.getValue();
                        }
                    }
                    done.countDown();
                }, "mutator-" + t).start();
            }
            assertTrue(done.await(10, TimeUnit.SECONDS), "worker threads did not finish");
            TelemetryRegistry.flush();
        }

        AtomicityValidator.AtomicityReport report = validator.analyzeAtomicity();

        assertTrue(report.unsafeFieldAccesses.stream()
                        .anyMatch(f -> f.contains("SharedCounterBean.value")),
                "The control was not reported, so this run proves nothing about what the agent "
                        + "cannot see: the pipeline itself was not working. Fix that first, then "
                        + "read the assertion below. Findings were: " + report.unsafeFieldAccesses);

        assertFalse(report.unsafeFieldAccesses.stream()
                        .anyMatch(f -> f.contains("DirectFieldMutationBean")),
                "This pins a limitation rather than a capability, and the control above shows the "
                        + "pipeline was live while it held. increment() is neither a getter nor a "
                        + "setter, so the weaver does not touch it and the count++ inside it "
                        + "produces no event however racy it is. That is the most common shape of a "
                        + "real race, and it is why the agent is not a substitute for the recording "
                        + "hooks. If this now finds something the observation surface widened: make "
                        + "it a positive assertion and update AGENT.md and the AsyncTestAgent class "
                        + "javadoc, both of which currently tell users this case is not covered. "
                        + "Findings were: " + report.unsafeFieldAccesses);
    }
}
