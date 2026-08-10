package se.deversity.asynctest;

import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;
import se.deversity.asynctest.report.Violation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Proves the collector sees a real run's findings, not just hand-fired registry events:
 * an {@code @AsyncTest} executed by the engine must leave structured violations behind.
 */
@E2E
class AsyncFindingsE2eTest {

    @Test
    void aRealAsyncTestRun_populatesTheCollector() {
        try (AsyncFindings findings = AsyncFindings.collect()) {
            Events events = EngineTestKit.engine("junit-jupiter")
                    .selectors(selectClass(ReportOnlyRaceTest.class))
                    .execute()
                    .testEvents();

            assertEquals(0, events.failed().count(),
                    "failOn = NONE means the findings are reported, not thrown");

            findings.assertReported("RaceConditionDetector");

            List<Violation> races = findings.violationsFrom("RaceConditionDetector");
            assertFalse(races.isEmpty());
            Violation race = races.get(0);
            assertFalse(race.message().isBlank(), "A violation must carry a message");
            assertTrue(race.attributes().containsKey("report"),
                    "The full report must remain reachable from the violation");
        }
    }

    /** Same shape as {@code DetectAllIntegrationTest.RaceTestWithDetectAll}, but report-only. */
    public static class ReportOnlyRaceTest {
        private int counter = 0;

        @AsyncTest(threads = 2, invocations = 10, detectAll = true, failOn = FailOn.NONE)
        void race() {
            AsyncTestContext ctx = AsyncTestContext.get();
            if (ctx != null) {
                ctx.sharedRaceConditionDetector().recordFieldWrite(this, "counter");
            }
            counter++;
        }
    }
}
