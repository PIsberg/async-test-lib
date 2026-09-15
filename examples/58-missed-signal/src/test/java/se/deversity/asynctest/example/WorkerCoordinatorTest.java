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
    void test_concurrent_detectsBug() throws InterruptedException {
        var detector = AsyncTestContext.missedSignalDetector();
        // One coordinator per execution, so the only signal this worker could receive is its own.
        WorkerCoordinator worker = new WorkerCoordinator();

        // The signaller runs first: nobody is waiting yet, so the notify() is discarded.
        detector.recordNotify(worker);
        worker.signal();

        // The worker arrives afterwards and waits with no flag to tell it the signal already came.
        // waitForSignal() would block forever; the bounded variant returns when the timeout runs out,
        // having received nothing. A notify with nobody waiting is not reported on its own, since a
        // flag-guarded waiter would never wait; this unsignalled wait after it is the finding.
        detector.recordWait(worker, false); // unguarded: no predicate loop around this wait
        worker.waitForSignal(10);
        detector.recordWakeup(worker);
    }
}
