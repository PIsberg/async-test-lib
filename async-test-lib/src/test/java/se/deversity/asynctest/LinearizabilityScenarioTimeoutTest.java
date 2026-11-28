package se.deversity.asynctest;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An enumerated scenario whose probe run times out fails as "failed to run" (#1512). Until now
 * only a throwing operation pinned that path; a timeout reached the same catch by construction.
 */
class LinearizabilityScenarioTimeoutTest {

    private static final SequentialSpec<int[]> COUNTER = SequentialSpec.of(
            () -> new int[1], int[]::clone,
            (state, op, arg) -> op.equals("increment") ? ++state[0] : state[0]);

    @Test
    void aScenarioWhoseRunTimesOutFailsNamingTheScenario() {
        CountDownLatch never = new CountDownLatch(1);
        OperationHistory<AtomicInteger> history = OperationHistory.of(AtomicInteger::new)
                .operation("hang", random -> null, (counter, arg) -> {
                    try {
                        never.await(); // the runner interrupts it when the round times out
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted by the timed-out round", e);
                    }
                    return 0;
                });
        history.probeTimeoutMs = 200;
        try {
            AssertionError e = assertThrows(AssertionError.class,
                    () -> history.assertLinearizableForAllScenarios(COUNTER, 1, 1));
            assertTrue(e.getMessage().contains("failed to run") && e.getMessage().contains(": hang()"),
                    "the scenario is named: " + e.getMessage());
            String causes = causes(e);
            assertTrue(causes.contains("timed out"), "a timed-out run is the scenario's failure, not a pass: " + causes);
        } finally {
            never.countDown();
        }
    }

    private static String causes(Throwable e) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            all.append(t).append(" | ");
        }
        return all.toString();
    }
}
