package se.deversity.asynctest.runner;

import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.AsyncTestListener;
import se.deversity.asynctest.AsyncTestListenerRegistry;
import se.deversity.asynctest.E2E;
import se.deversity.asynctest.FailOn;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Pins that a {@code failOn} gate failure reaches listeners as {@link AsyncTestListener#onTestFailed}.
 *
 * <p>The listener contract for findings ({@code onDetectorReport}, {@code onStructuredReport})
 * is pinned by {@code DetectorReportKeyTest}; the failure event was not: PIT's 2026-08-31
 * baseline showed "removed call to AsyncTestListenerRegistry::fireTestFailed" in
 * {@code ConcurrencyRunner.analyzeAndGate} surviving, so a CI dashboard or IDE integration
 * listening for failures would silently stop hearing about them.
 */
@E2E
class ConcurrencyRunnerFailureEventBindingTest {

    private static final List<Throwable> FAILURES = new CopyOnWriteArrayList<>();

    /**
     * Deterministically fails its own gate: every worker records a write to the same field, so
     * RaceConditionDetector reports at HIGH and {@code failOn = HIGH} turns that into a failure.
     * The same recording idiom as {@code DetectorReportKeyTest.RaceTest}, kept separate so this
     * class owns its fixture.
     */
    public static class GatedRace {
        private int counter;

        @AsyncTest(threads = 4, invocations = 20, detectAll = true, failOn = FailOn.HIGH)
        void race() {
            AsyncTestContext ctx = AsyncTestContext.get();
            if (ctx != null) {
                ctx.sharedRaceConditionDetector().recordFieldWrite(this, "counter");
            }
            counter++;
        }
    }

    @Test
    void gateFailureIsDeliveredToListenersAsTestFailed() {
        FAILURES.clear();
        AsyncTestListener capture = new AsyncTestListener() {
            @Override
            public void onTestFailed(Throwable cause) {
                FAILURES.add(cause);
            }
        };

        try (AsyncTestListenerRegistry.Registration r = AsyncTestListenerRegistry.registerScoped(capture)) {
            Events events = EngineTestKit.engine("junit-jupiter")
                    .selectors(selectClass(GatedRace.class))
                    .execute()
                    .testEvents();
            assertTrue(events.failed().count() >= 1,
                    "the fixture must fail its own failOn gate, or there is no failure to deliver");
        }

        assertFalse(FAILURES.isEmpty(),
                "a failOn gate failure must reach listeners through onTestFailed; losing this "
                        + "event silently blinds anything integrating via AsyncTestListener");
        assertTrue(FAILURES.stream().anyMatch(t -> String.valueOf(t.getMessage()).contains("detector finding")),
                "the delivered cause is the gate's own error, naming the findings: " + FAILURES);
    }
}
