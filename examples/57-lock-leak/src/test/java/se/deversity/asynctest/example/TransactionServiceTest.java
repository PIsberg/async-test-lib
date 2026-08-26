package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TransactionService demonstrating the LockLeakDetector.
 *
 * The concurrent test shows how acquiring a lock in beginTransaction() without
 * a guaranteed release in commitTransaction() is flagged as a lock leak.
 */
class TransactionServiceTest {

    private TransactionService service;

    @BeforeEach
    void setUp() {
        service = new TransactionService();
    }

    @Test
    void test_singleThread_executeSucceeds() {
        assertDoesNotThrow(() -> service.execute(() -> {
            // normal work — no exception
        }));
    }

    @Test
    void test_singleThread_lockIsReleasedAfterSuccess() {
        service.execute(() -> {});
        // After a successful execution the lock must not be held
        assertTrue(service.lock.tryLock(), "Lock should be free after successful execute");
        service.lock.unlock();
    }

    @Disabled("Remove @Disabled: the round times out because the leaked lock is never released, and the failure "
            + "names LockLeakDetector's finding")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectLockLeaks = true, failOn = FailOn.LOW)
    void test_concurrent_detectsBug() {
        // Register the lock and record acquire/release to let the detector track it
        AsyncTestContext.lockLeakMonitor().registerLock(service.lock, "TransactionService.lock");
        AsyncTestContext.lockLeakMonitor().recordLockAcquired(service.lock, "TransactionService.lock");

        try {
            // Simulate work that may throw — causing commitTransaction() to be skipped
            service.execute(() -> {
                // Occasional failure to demonstrate the leak path
                if (Thread.currentThread().getId() % 3 == 0) {
                    throw new RuntimeException("Simulated work failure");
                }
            });
            AsyncTestContext.lockLeakMonitor().recordLockReleased(service.lock, "TransactionService.lock");
        } catch (RuntimeException ignored) {
            // Lock was never released — this is the bug
        }
    }
}
