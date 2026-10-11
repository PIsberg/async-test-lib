package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.InterruptMonitor;
import se.deversity.asynctest.diagnostics.InterruptSwallowingDetector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A swallowing {@code InterruptedException} handler the agent found, reaching both interrupt
 * detectors through the hook alone.
 *
 * <p>The agent inserts the call at the start of a method whose handler neither rethrows nor
 * restores the flag. {@code INTERRUPT_MISHANDLING} is in the default preset and
 * {@code INTERRUPT_SWALLOWING} is not, so both hear it; each reports a location once however often
 * the method runs.
 */
class AgentInterruptHooksTest {

    private static AsyncTestContext installed() {
        AsyncTestContext context =
                new AsyncTestContext(AsyncTestConfig.builder().detectAll(true).build());
        AsyncTestContext.install(context);
        return context;
    }

    @Test
    @DisplayName("a swallowing handler's method entered in a run is reported by both detectors, once")
    void bothDetectorsReportTheLocationOnce() throws InterruptedException {
        installed();
        try {
            String locations = "com.example.Poller.poll:42,com.example.Poller.drain:57";
            AgentThreadHooks.interruptSwallowingMethodEntered(locations);
            AgentThreadHooks.interruptSwallowingMethodEntered(locations);
            Thread other = new Thread(() -> { }, "other");
            other.start();
            other.join();

            InterruptSwallowingDetector.InterruptSwallowingReport swallowing =
                    AsyncTestContext.interruptSwallowingDetector().analyze();
            assertTrue(swallowing.hasIssues(), swallowing.toString());
            assertEquals(2, swallowing.structuredViolations.size(),
                    "two handlers, each once, however often the method ran: " + swallowing);
            assertTrue(swallowing.toString().contains("com.example.Poller.poll:42"),
                    swallowing.toString());

            InterruptMonitor.InterruptReport mishandling =
                    AsyncTestContext.interruptMonitor().analyze();
            assertEquals(2, mishandling.ignoredInterrupts.size(),
                    "INTERRUPT_MISHANDLING, the one the default preset runs, names each handler "
                            + "once too: " + mishandling);
            assertTrue(mishandling.toString().contains("com.example.Poller.drain:57"),
                    mishandling.toString());
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("outside a run the hook does nothing")
    void outsideARunNothingIsRecorded() {
        AgentThreadHooks.interruptSwallowingMethodEntered("com.example.Poller.poll:42");
        AsyncTestContext context = installed();
        try {
            assertFalse(AsyncTestContext.interruptSwallowingDetector().analyze().hasIssues(),
                    "woven code runs in plenty of places with no test in progress, and this "
                            + "run's detector was not even built then");
        } finally {
            AsyncTestContext.uninstall();
        }
    }
}
