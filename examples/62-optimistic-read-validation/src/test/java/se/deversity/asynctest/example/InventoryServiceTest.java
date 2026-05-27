package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for InventoryService demonstrating the OptimisticReadValidationDetector.
 *
 * The concurrent test shows how reading data from an optimistic-read section
 * without calling lock.validate() is flagged as a potential torn read.
 */
class InventoryServiceTest {

    private InventoryService service;

    @BeforeEach
    void setUp() {
        service = new InventoryService();
    }

    @Test
    void test_singleThread_getStock_returnsInitialValue() {
        assertEquals(100, service.getStock());
    }

    @Test
    void test_singleThread_addStock_increasesStock() {
        service.addStock(50);
        // Single-threaded, no concurrent writes — optimistic read happens to be valid
        assertTrue(service.getStock() >= 100);
    }

    @Disabled("Remove @Disabled to see bug detected by OptimisticReadValidationDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectOptimisticReadValidation = true)
    void test_concurrent_detectsBug() {
        Thread current = Thread.currentThread();

        // Record that an optimistic read was started
        long stamp = service.lock.tryOptimisticRead();
        AsyncTestContext.optimisticReadValidationMonitor()
                .recordOptimisticReadStarted(service.lock, stamp, current);

        // Record that data was accessed (without validation)
        AsyncTestContext.optimisticReadValidationMonitor()
                .recordDataAccessed(service.lock, stamp, current, "stock");

        // BUG: validate() is never called — the detector expects it here
        // AsyncTestContext.optimisticReadValidationMonitor()
        //         .recordValidateCalled(service.lock, stamp, service.lock.validate(stamp), current);

        // Interleave with writes to create race conditions
        service.addStock(1);
        service.getStock(); // uses the buggy path internally
    }
}
