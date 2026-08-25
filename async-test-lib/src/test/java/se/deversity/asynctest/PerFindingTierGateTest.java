package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

import se.deversity.asynctest.diagnostics.DetectorTrust;
import se.deversity.asynctest.diagnostics.TrustTier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the thing per-finding grades exist for: a verdict-grade finding failing a verdict-only gate
 * even though the detector that produced it is not a verdict-grade detector.
 *
 * <p><strong>Why this exists.</strong> A trust tier is a property of the detector and carries the
 * weakest grade it can produce, so a gate on {@code minTrust = VERDICT} cannot admit a finding the
 * library will not stand behind. `RecordMutableComponentLeakDetector` produces both kinds: an
 * observed mutation of a shared record's component is a fact, and a note that a shared record
 * merely holds a mutable component is a prompt. Rated as one detector it carries `PROMPT`, so
 * before per-finding grades a verdict-only gate stayed green on shared mutable state that had been
 * seen being mutated. That is the false negative this pair catches.
 */
@E2E
class PerFindingTierGateTest {

    @Test
    @DisplayName("the detector is still rated PROMPT, which is what makes this test meaningful")
    void theDetectorItselfIsNotAVerdictGradeDetector() {
        assertEquals(TrustTier.PROMPT, DetectorTrust.tierOf(DetectorType.RECORD_MUTABLE_COMPONENT_LEAK),
                "if this detector is ever promoted, the fixture below stops proving that a "
                        + "per-finding grade is what fails the gate, and needs a different subject");
    }

    @Test
    @DisplayName("an observed mutation fails a VERDICT-only gate, though its detector is PROMPT")
    void observedMutationTripsAVerdictOnlyGate() {
        Events tests = run(ObservedMutationDummy.class);
        tests.assertStatistics(s -> s.started(1).failed(1));

        List<String> messages = tests.failed().stream()
                .map(event -> event.getRequiredPayload(TestExecutionResult.class))
                .map(result -> result.getThrowable().map(Throwable::getMessage).orElse(""))
                .filter(Objects::nonNull)
                .toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("at or above failOn=")
                        && m.contains("RecordMutableComponentLeakDetector")),
                "the failure must be the failOn gate naming the detector, not a fixture assertion. "
                        + "Failures seen: " + messages);
    }

    @Test
    @DisplayName("a structural-risk-only finding still does not fail a VERDICT-only gate")
    void structuralRiskAloneDoesNotTripAVerdictOnlyGate() {
        run(StructuralRiskOnlyDummy.class).assertStatistics(s -> s.started(1).succeeded(1).failed(0));
    }

    private static Events run(Class<?> fixture) {
        return EngineTestKit.engine("junit-jupiter")
                .selectors(DiscoverySelectors.selectClass(fixture))
                .execute()
                .testEvents();
    }

    /**
     * A record whose list component is genuinely mutated while two threads share it.
     *
     * <p>The list is synchronized on purpose. With a bare {@code ArrayList} the fixture raced
     * itself: two threads calling {@code add} can leave the size counter and the backing array
     * disagreeing, and the body then died with {@code Index 1 out of bounds for length 0} before
     * the {@code failOn} gate could speak — so the assertion below saw a fixture exception
     * instead of the gate message, and this test failed precisely when its subject misbehaved
     * most. The detector wants to see a write through a shared record's mutable component; it
     * does not need that write to be unsafe. See issue #353.
     */
    public static class ObservedMutationDummy {
        private final List<String> items = Collections.synchronizedList(new ArrayList<>());
        private final Order order = new Order("A-1", items);
        private final AtomicInteger seq = new AtomicInteger();

        @AsyncTest(threads = 2, invocations = 2, failOn = FailOn.HIGH, minTrust = TrustTier.VERDICT,
                   detectAll = false, detectRecordMutableComponentLeak = true)
        void mutateTheSharedRecord() {
            AsyncTestContext.recordMutableComponentLeakDetector()
                    .recordShared(order, "order", Thread.currentThread());
            order.items().add("item-" + seq.getAndIncrement());
        }
    }

    /** The same record shape, shared but never written through. */
    public static class StructuralRiskOnlyDummy {
        private final Order order = new Order("A-2", new ArrayList<>(List.of("fixed")));

        @AsyncTest(threads = 2, invocations = 2, failOn = FailOn.HIGH, minTrust = TrustTier.VERDICT,
                   detectAll = false, detectRecordMutableComponentLeak = true)
        void shareWithoutMutating() {
            AsyncTestContext.recordMutableComponentLeakDetector()
                    .recordShared(order, "order", Thread.currentThread());
            order.id();
        }
    }

    /** Shallowly immutable: the list reference is final, the list is not. */
    public record Order(String id, List<String> items) { }
}
