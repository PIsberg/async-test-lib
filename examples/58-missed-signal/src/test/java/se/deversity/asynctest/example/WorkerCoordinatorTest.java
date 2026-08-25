package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.WorkerCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for WorkerCoordinator demonstrating the MissedSignalDetector.
 *
 * The concurrent test shows how calling notify() without a persistent flag
 * is flagged when the signal can be sent before wait() is entered.
 */
class WorkerCoordinatorTest {

    private WorkerCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new WorkerCoordinator();
    }

    @Test
    void test_singleThread_signalDoesNotThrow() {
        assertDoesNotThrow(() -> coordinator.signal());
    }

    @Test
    void test_singleThread_signalAllDoesNotThrow() {
        assertDoesNotThrow(() -> coordinator.signalAll());
    }

    @Disabled("Remove @Disabled to see bug detected by MissedSignalDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectMissedSignals = true, failOn = FailOn.LOW)
    void test_concurrent_detectsBug() {
        // Record a notify() being sent on the "ready" condition
        AsyncTestContext.missedSignalDetector()
                .recordNotify("WorkerCoordinator.monitor");

        // Record that a thread is about to wait — the signal may already be gone
        AsyncTestContext.missedSignalDetector()
                .recordWait("WorkerCoordinator.monitor");

        // Simulate a wakeup (in real code this would be conditional)
        AsyncTestContext.missedSignalDetector()
                .recordWakeup("WorkerCoordinator.monitor");

        // The actual coordinator signal path
        coordinator.signal();
    }
}
