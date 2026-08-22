package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what a declared severity changes: which findings a merge gate acts on.
 *
 * <p><strong>Why this exists.</strong> A detector that writes no severity marker had its severity
 * guessed by {@code IssueSeverity.fromReport}, which returned {@code HIGH}, and 86 of the 142
 * built-in detectors write none. So {@code failOn = HIGH} failed on a resource left open exactly
 * as it failed on a lost update, and a team gating on it was gating on everything.
 *
 * <p>{@code ResourceLeakDetector} declares {@code MEDIUM}: an unclosed resource is a leak, which
 * is what {@link se.deversity.asynctest.diagnostics.IssueSeverity#MEDIUM} is defined as, not the
 * corruption {@code HIGH} claims. These two fixtures are the same leak under the two thresholds
 * either side of that, so the pair fails if the declaration stops being consulted, and fails the
 * other way if it starts overriding a threshold it should not.
 */
@E2E
class DeclaredSeverityGateTest {

    @Test
    @DisplayName("a MEDIUM finding no longer fails a HIGH gate")
    void mediumFindingDoesNotTripAHighGate() {
        run(LeakUnderHighGateDummy.class).assertStatistics(s -> s.started(1).succeeded(1).failed(0));
    }

    @Test
    @DisplayName("the same finding still fails a MEDIUM gate")
    void mediumFindingStillTripsAMediumGate() {
        Events tests = run(LeakUnderMediumGateDummy.class);
        tests.assertStatistics(s -> s.started(1).failed(1));

        List<String> messages = tests.failed().stream()
                .map(event -> event.getRequiredPayload(TestExecutionResult.class))
                .map(result -> result.getThrowable().map(Throwable::getMessage).orElse(""))
                .filter(Objects::nonNull)
                .toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("at or above failOn=")
                        && m.contains("ResourceLeakDetector")),
                "the failure must be the failOn gate naming the detector, not a fixture assertion "
                        + "or a timeout. Failures seen: " + messages);
    }

    private static Events run(Class<?> fixture) {
        return EngineTestKit.engine("junit-jupiter")
                .selectors(DiscoverySelectors.selectClass(fixture))
                .execute()
                .testEvents();
    }

    /** A resource opened and never closed, gated at HIGH. */
    public static class LeakUnderHighGateDummy {
        private final Object connection = new Object();

        @AsyncTest(threads = 2, invocations = 2, failOn = FailOn.HIGH,
                   detectAll = false, detectResourceLeaks = true)
        void leak() {
            AsyncTestContext.resourceLeakDetector().registerResource(connection, "db", "Connection");
            AsyncTestContext.resourceLeakDetector().recordResourceOpened(connection, "db");
        }
    }

    /** The same leak, gated at MEDIUM. */
    public static class LeakUnderMediumGateDummy {
        private final Object connection = new Object();

        @AsyncTest(threads = 2, invocations = 2, failOn = FailOn.MEDIUM,
                   detectAll = false, detectResourceLeaks = true)
        void leak() {
            AsyncTestContext.resourceLeakDetector().registerResource(connection, "db", "Connection");
            AsyncTestContext.resourceLeakDetector().recordResourceOpened(connection, "db");
        }
    }
}
