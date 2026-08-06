package se.deversity.asynctest.runner;
import se.deversity.asynctest.E2E;

import org.junit.jupiter.api.Test;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.diagnostics.IssueSeverity;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@code @AsyncTest(failOn = ...)} severity gate:
 * <ul>
 *   <li>{@link FailOn#triggeredBy} threshold mapping,</li>
 *   <li>a passing test body fails when a detector finding meets the threshold,</li>
 *   <li>the default {@link FailOn#NONE} keeps the legacy report-only behavior.</li>
 * </ul>
 *
 * <p>The fixtures deterministically trigger {@code SharedMessageDigestDetector}
 * (severity HIGH) by recording the same {@link MessageDigest} instance from
 * every worker thread — also exercising {@code includes = {...}} end-to-end.
 */
@E2E
class FailOnGateTest {

    @Test
    void triggeredByMatchesThresholdSemantics() {
        assertFalse(FailOn.NONE.triggeredBy(IssueSeverity.CRITICAL));
        assertFalse(FailOn.NONE.triggeredBy(IssueSeverity.LOW));

        assertTrue(FailOn.LOW.triggeredBy(IssueSeverity.LOW));
        assertTrue(FailOn.LOW.triggeredBy(IssueSeverity.CRITICAL));

        assertFalse(FailOn.MEDIUM.triggeredBy(IssueSeverity.LOW));
        assertTrue(FailOn.MEDIUM.triggeredBy(IssueSeverity.MEDIUM));

        assertFalse(FailOn.HIGH.triggeredBy(IssueSeverity.MEDIUM));
        assertTrue(FailOn.HIGH.triggeredBy(IssueSeverity.HIGH));
        assertTrue(FailOn.HIGH.triggeredBy(IssueSeverity.CRITICAL));

        assertFalse(FailOn.CRITICAL.triggeredBy(IssueSeverity.HIGH));
        assertTrue(FailOn.CRITICAL.triggeredBy(IssueSeverity.CRITICAL));

        assertFalse(FailOn.LOW.triggeredBy(null));
    }

    @Test
    void findingAtThresholdFailsThePassingTest() {
        Events tests = EngineTestKit.engine("junit-jupiter")
                .selectors(DiscoverySelectors.selectClass(FailOnHighFixture.class))
                .execute()
                .testEvents();

        tests.assertStatistics(s -> s.started(1).succeeded(0).failed(1));
    }

    @Test
    void defaultFailOnNoneKeepsReportOnlyBehavior() {
        Events tests = EngineTestKit.engine("junit-jupiter")
                .selectors(DiscoverySelectors.selectClass(ReportOnlyFixture.class))
                .execute()
                .testEvents();

        tests.assertStatistics(s -> s.started(1).succeeded(1).failed(0));
    }

    @Test
    void findingBelowThresholdDoesNotFail() {
        Events tests = EngineTestKit.engine("junit-jupiter")
                .selectors(DiscoverySelectors.selectClass(FailOnCriticalFixture.class))
                .execute()
                .testEvents();

        // SharedMessageDigest findings are HIGH — below the CRITICAL threshold.
        tests.assertStatistics(s -> s.started(1).succeeded(1).failed(0));
    }

    // ---- Fixtures driven through JUnit-platform-testkit ----

    private static MessageDigest sharedDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static class FailOnHighFixture {
        private final MessageDigest shared = sharedDigest();

        @AsyncTest(threads = 2, invocations = 2, timeoutMs = 10_000,
                includes = {DetectorType.SHARED_MESSAGE_DIGEST},
                failOn = FailOn.HIGH, licenseMockMode = true)
        void sharedDigestAcrossThreads() {
            AsyncTestContext.sharedMessageDigestDetector()
                    .recordAccess(shared, "shared-sha256", Thread.currentThread());
        }
    }

    static class ReportOnlyFixture {
        private final MessageDigest shared = sharedDigest();

        @AsyncTest(threads = 2, invocations = 2, timeoutMs = 10_000,
                includes = {DetectorType.SHARED_MESSAGE_DIGEST},
                licenseMockMode = true)
        void sharedDigestAcrossThreads() {
            AsyncTestContext.sharedMessageDigestDetector()
                    .recordAccess(shared, "shared-sha256", Thread.currentThread());
        }
    }

    static class FailOnCriticalFixture {
        private final MessageDigest shared = sharedDigest();

        @AsyncTest(threads = 2, invocations = 2, timeoutMs = 10_000,
                includes = {DetectorType.SHARED_MESSAGE_DIGEST},
                failOn = FailOn.CRITICAL, licenseMockMode = true)
        void sharedDigestAcrossThreads() {
            AsyncTestContext.sharedMessageDigestDetector()
                    .recordAccess(shared, "shared-sha256", Thread.currentThread());
        }
    }
}
