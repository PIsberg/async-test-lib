package se.deversity.asynctest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.report.Violation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The findings collector is the assertion surface for detector output: before it, the only
 * programmatic hook was {@code onDetectorReport(String, String)}, so a test that wanted to
 * assert "this run reported a race" had to substring-match a human-readable report.
 */
class AsyncFindingsTest {

    private static final String RACE_REPORT = "🔴 CRITICAL: race detected on field counter";

    @AfterEach
    void noListenerLeak() {
        AsyncTestListenerRegistry.clearAll();
    }

    @Test
    void collect_recordsAStructuredViolationPerDetectorReport() {
        try (AsyncFindings findings = AsyncFindings.collect()) {
            AsyncTestListenerRegistry.fireDetectorReport("RaceConditionDetector", RACE_REPORT);

            List<Violation> violations = findings.violations();
            assertEquals(1, violations.size());
            Violation v = violations.get(0);
            assertEquals("RaceConditionDetector", v.detector());
            assertEquals(IssueSeverity.CRITICAL, v.severity());
            assertTrue(v.message().contains("race detected on field counter"),
                    "The message must carry the detector's own words: " + v.message());
            assertEquals(RACE_REPORT, v.attributes().get("report"),
                    "The full report text must stay reachable for assertions the record cannot express");
        }
    }

    @Test
    void assertReported_passesForTheDetectorThatFired_andFailsForOneThatDidNot() {
        try (AsyncFindings findings = AsyncFindings.collect()) {
            AsyncTestListenerRegistry.fireDetectorReport("RaceConditionDetector", RACE_REPORT);

            findings.assertReported("RaceConditionDetector");
            findings.assertReported("RaceCondition");   // prefix of the simple name
            findings.assertReported("raceconditiondetector"); // case-insensitive

            AssertionError error = assertThrows(AssertionError.class,
                    () -> findings.assertReported("DeadlockDetector"));
            assertTrue(error.getMessage().contains("DeadlockDetector"), error.getMessage());
            assertTrue(error.getMessage().contains("RaceConditionDetector"),
                    "The failure must list what was reported instead: " + error.getMessage());
        }
    }

    @Test
    void assertReported_withSeverity_distinguishesTheSeverityThatFired() {
        try (AsyncFindings findings = AsyncFindings.collect()) {
            AsyncTestListenerRegistry.fireDetectorReport("BusyWaitDetector", "🟡 MEDIUM: spin loop");

            findings.assertReported("BusyWaitDetector", IssueSeverity.MEDIUM);
            assertThrows(AssertionError.class,
                    () -> findings.assertReported("BusyWaitDetector", IssueSeverity.CRITICAL));
        }
    }

    @Test
    void assertNotReported_andAssertNone_failWhenSomethingWasReported() {
        try (AsyncFindings findings = AsyncFindings.collect()) {
            findings.assertNone();
            findings.assertNotReported("RaceConditionDetector");

            AsyncTestListenerRegistry.fireDetectorReport("RaceConditionDetector", RACE_REPORT);

            assertThrows(AssertionError.class, findings::assertNone);
            assertThrows(AssertionError.class, () -> findings.assertNotReported("RaceConditionDetector"));
        }
    }

    @Test
    void violationsFrom_selectsOneDetector_andClearResetsTheCollector() {
        try (AsyncFindings findings = AsyncFindings.collect()) {
            AsyncTestListenerRegistry.fireDetectorReport("RaceConditionDetector", RACE_REPORT);
            AsyncTestListenerRegistry.fireDetectorReport("DeadlockDetector", "🔴 CRITICAL: cycle");

            assertEquals(2, findings.violations().size());
            assertEquals(1, findings.violationsFrom("DeadlockDetector").size());

            findings.clear();
            findings.assertNone();
        }
    }

    @Test
    void close_unregistersTheCollector_andIsIdempotent() {
        int before = AsyncTestListenerRegistry.getListenerCount();
        AsyncFindings findings = AsyncFindings.collect();
        assertEquals(before + 1, AsyncTestListenerRegistry.getListenerCount());

        findings.close();
        findings.close();
        assertEquals(before, AsyncTestListenerRegistry.getListenerCount());

        AsyncTestListenerRegistry.fireDetectorReport("RaceConditionDetector", RACE_REPORT);
        assertTrue(findings.violations().isEmpty(),
                "A closed collector must stop recording, or it leaks findings into the next test");
    }

    @Test
    void violations_isAnImmutableSnapshot() {
        try (AsyncFindings findings = AsyncFindings.collect()) {
            AsyncTestListenerRegistry.fireDetectorReport("RaceConditionDetector", RACE_REPORT);
            List<Violation> snapshot = findings.violations();

            AsyncTestListenerRegistry.fireDetectorReport("DeadlockDetector", "🔴 CRITICAL: cycle");

            assertEquals(1, snapshot.size(), "The returned list must not track later findings");
            assertEquals(2, findings.violations().size());
            assertThrows(UnsupportedOperationException.class, () -> snapshot.add(null));
        }
    }

    @Test
    void aListenerThatThrows_doesNotStopTheCollector() {
        AsyncTestListenerRegistry.register(new AsyncTestListener() {
            @Override
            public void onViolation(Violation violation) {
                throw new IllegalStateException("bad listener");
            }
        });
        try (AsyncFindings findings = AsyncFindings.collect()) {
            AsyncTestListenerRegistry.fireDetectorReport("RaceConditionDetector", RACE_REPORT);
            assertFalse(findings.violations().isEmpty(),
                    "One throwing listener must not swallow every other listener's callback");
        }
    }
}
