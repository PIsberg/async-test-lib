package se.deversity.asynctest.runner;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.E2E;
import se.deversity.asynctest.OperationHistory;
import se.deversity.asynctest.SequentialSpec;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An enumerated scenario whose probe run the licence gate refuses fails as "failed to run"
 * (#1512), rather than passing as the scenario that never ran. The suite runs with
 * {@code license.mock.mode=true} JVM-wide, so this class clears it for its one enumeration and
 * puts it back; {@code @E2E} like {@link ConcurrencyRunnerLicenseGateBindingTest}, which mutates the
 * same properties. The key has no provider coordinates, which the gate refuses offline.
 */
@E2E
class LinearizabilityScenarioLicenceDenialTest {

    private static final SequentialSpec<int[]> COUNTER = SequentialSpec.of(
            () -> new int[1], int[]::clone,
            (state, op, arg) -> op.equals("increment") ? ++state[0] : state[0]);

    @Test
    void aScenarioWhoseRunTheLicenceGateRefusesFails() {
        String mock = System.getProperty("license.mock.mode");
        String key = System.getProperty("license.key");
        System.setProperty("license.mock.mode", "false");
        System.setProperty("license.key", "INVALID-TEST-KEY-0000");
        LicenseGuard.resetForTesting();
        try {
            OperationHistory<AtomicInteger> history = OperationHistory.of(AtomicInteger::new)
                    .operation("increment", random -> null, (counter, arg) -> counter.incrementAndGet());
            AssertionError e = assertThrows(AssertionError.class,
                    () -> history.assertLinearizableForAllScenarios(COUNTER, 1, 1));
            assertTrue(e.getMessage().contains("failed to run"), "a refused run fails the scenario: " + e.getMessage());
            assertTrue(e.getCause() instanceof SecurityException,
                    "the refusal is what failed it, not something incidental: " + e.getCause());
        } finally {
            restore("license.mock.mode", mock);
            restore("license.key", key);
            LicenseGuard.resetForTesting();
        }
    }

    private static void restore(String property, String previous) {
        if (previous == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, previous);
        }
    }
}
