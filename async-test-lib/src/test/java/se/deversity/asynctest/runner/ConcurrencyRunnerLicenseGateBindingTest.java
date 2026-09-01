package se.deversity.asynctest.runner;

import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.E2E;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Pins that {@link ConcurrencyRunner#execute} actually calls the licence gate, not merely that
 * {@link LicenseGuard} works when called.
 *
 * <p>{@code LicenseGuardTest} exercises the gate directly, so every mutation-tested behaviour
 * of the gate itself is covered — but the one line wiring the gate into the runner was not:
 * PIT's 2026-08-31 baseline showed "removed call to LicenseGuard::check" in
 * {@code ConcurrencyRunner.execute} surviving, meaning the entire licence check could be
 * deleted from the execution path without a single test noticing. This class closes that hole
 * from the consumer's side: an {@code @AsyncTest} whose licence cannot be validated must fail
 * before the body runs, and one in mock mode must run normally.
 *
 * <p>The denied fixture uses the placeholder-coordinates path (a key with no account id),
 * which {@link LicenseGuard#requireProviderCoordinates} refuses deterministically and offline —
 * no provider is contacted, so the test is hermetic on any machine and in CI, where the
 * supplied key suppresses zero-config auto-mocking.
 */
@E2E
class ConcurrencyRunnerLicenseGateBindingTest {

    static class DeniedRun {
        @AsyncTest(threads = 2, invocations = 1,
                licenseMockMode = false,
                licenseKey = "INVALID-TEST-KEY-0000")
        void body() {
            // Never reached: the licence gate must refuse the run before workers start.
        }
    }

    static class MockedRun {
        @AsyncTest(threads = 2, invocations = 1, licenseMockMode = true)
        void body() { }
    }

    @Test
    void runWhoseLicenceCannotBeValidatedFailsBeforeTheBodyRuns() {
        String prev = System.getProperty("license.mock.mode");
        // Surefire and pitest set license.mock.mode=true for the whole build; leaving it on
        // would grant every run and this test would prove nothing about the gate being wired.
        System.setProperty("license.mock.mode", "false");
        LicenseGuard.resetForTesting();
        try {
            Events events = EngineTestKit.engine("junit-jupiter")
                    .selectors(selectClass(DeniedRun.class))
                    .execute()
                    .testEvents();

            assertEquals(1, events.failed().count(),
                    "the runner must refuse to execute a run whose licence cannot be validated; "
                            + "a green run here means ConcurrencyRunner.execute no longer calls "
                            + "LicenseGuard.check");
            Throwable failure = events.failed().stream()
                    .findFirst().orElseThrow()
                    .getRequiredPayload(TestExecutionResult.class)
                    .getThrowable().orElseThrow();
            assertInstanceOf(SecurityException.class, failure,
                    "the gate refuses with SecurityException, not an incidental error");
            assertTrue(failure.getMessage().contains("LICENSE MISCONFIGURED"),
                    "a key without provider coordinates is refused as misconfigured, before any "
                            + "network validation could run: " + failure.getMessage());
        } finally {
            if (prev == null) {
                System.clearProperty("license.mock.mode");
            } else {
                System.setProperty("license.mock.mode", prev);
            }
            LicenseGuard.resetForTesting();
        }
    }

    /**
     * The other direction, so the binding test cannot be satisfied by a runner that fails every
     * run: with the documented mock-mode bypass the same engine path must execute the body.
     */
    @Test
    void mockModeRunExecutesNormallyThroughTheSameGate() {
        LicenseGuard.resetForTesting();
        try {
            Events events = EngineTestKit.engine("junit-jupiter")
                    .selectors(selectClass(MockedRun.class))
                    .execute()
                    .testEvents();
            assertEquals(0, events.failed().count(),
                    "mock mode is the documented no-key path and must not be refused");
            assertTrue(events.succeeded().count() >= 1,
                    "the @AsyncTest template must actually have executed");
        } finally {
            LicenseGuard.resetForTesting();
        }
    }
}
