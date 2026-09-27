package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.diagnostics.OptimisticReadValidationDetector;
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
 *
 * Calling validate() is not the whole idiom: its answer has to be acted on. The
 * second pair of tests forces a restock between the optimistic read and validate(),
 * so validate() returns false every time, and records where the value is used with
 * recordValuesUsed. Using the value read under the failed stamp is reported; re-reading
 * under the read lock and using that stamp is the fix, and is silent.
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

    /**
     * The bug: validate() said the value may be torn, and it is used anyway.
     */
    @Test
    void testOptimisticReadDetector_valueUsedAfterFailedValidate_reports() throws InterruptedException {
        var detector = new OptimisticReadValidationDetector();
        Thread current = Thread.currentThread();

        long stamp = service.lock.tryOptimisticRead();
        detector.recordOptimisticReadStarted(service.lock, stamp, current);
        int stock = service.getStock();
        detector.recordDataAccessed(service.lock, stamp, current, "stock");
        restockOnAnotherThread();
        boolean valid = service.lock.validate(stamp);
        detector.recordValidateCalled(service.lock, stamp, valid, current);
        detector.recordValuesUsed(service.lock, stamp, current); // BUG: the stale value is used

        assertFalse(valid, "the restock landed between the read and validate()");
        assertEquals(100, stock, "the value in hand is the one from before the restock");
        assertTrue(detector.analyze().hasIssues(),
                "a value read under a stamp that failed validate() was used");
    }

    /**
     * The fix, under the same failed validation: re-read under the read lock and use that value.
     * The stamp passed to recordValuesUsed is the read-lock stamp the used value was read under.
     */
    @Test
    void testOptimisticReadDetector_rereadUnderReadLockAfterFailedValidate_isSilent() throws InterruptedException {
        var detector = new OptimisticReadValidationDetector();
        Thread current = Thread.currentThread();

        long stamp = service.lock.tryOptimisticRead();
        detector.recordOptimisticReadStarted(service.lock, stamp, current);
        int stock = service.getStock();
        detector.recordDataAccessed(service.lock, stamp, current, "stock");
        restockOnAnotherThread();
        boolean valid = service.lock.validate(stamp);
        detector.recordValidateCalled(service.lock, stamp, valid, current);
        if (!valid) {
            stamp = service.lock.readLock();
            try {
                stock = service.getStock();
            } finally {
                service.lock.unlockRead(stamp);
            }
        }
        detector.recordValuesUsed(service.lock, stamp, current);

        assertFalse(valid, "the restock landed between the read and validate()");
        assertEquals(105, stock, "the re-read sees the restock");
        assertFalse(detector.analyze().hasIssues(),
                "the value used was re-read under the read lock: " + detector.analyze());
    }

    /** Restocks on a second thread and waits for it, so the write lands exactly here. */
    private void restockOnAnotherThread() throws InterruptedException {
        Thread restock = new Thread(() -> service.addStock(5), "restock");
        restock.start();
        restock.join(2000);
    }

    @Disabled("Remove @Disabled to see bug detected by OptimisticReadValidationDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectOptimisticReadValidation = true, failOn = FailOn.LOW)
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

    /**
     * Half the executions restock while the other half read optimistically, validate, and use
     * the value whatever validate() answered. Any read a restock overtook is reported.
     */
    @Disabled("Remove @Disabled to see a value used after a failed validate() detected by OptimisticReadValidationDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectOptimisticReadValidation = true, failOn = FailOn.LOW)
    void test_concurrent_detectsValueUsedAfterFailedValidate() throws InterruptedException {
        Thread current = Thread.currentThread();
        if (current.threadId() % 2 == 0) {
            service.addStock(1);
            return;
        }
        var monitor = AsyncTestContext.optimisticReadValidationMonitor();

        long stamp = service.lock.tryOptimisticRead();
        monitor.recordOptimisticReadStarted(service.lock, stamp, current);
        int stock = service.getStock();
        monitor.recordDataAccessed(service.lock, stamp, current, "stock");
        Thread.sleep(1); // widens the window a restock has to land in
        boolean valid = service.lock.validate(stamp);
        monitor.recordValidateCalled(service.lock, stamp, valid, current);

        // BUG: validate() is called, but a false answer changes nothing - the value is used as read.
        // The fix: if (!valid) { stamp = lock.readLock(); try { re-read } finally { unlockRead } }
        monitor.recordValuesUsed(service.lock, stamp, current);
        assertTrue(stock >= 100);
    }
}
