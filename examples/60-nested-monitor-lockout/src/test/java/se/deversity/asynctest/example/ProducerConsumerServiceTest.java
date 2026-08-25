package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.ProducerConsumerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ProducerConsumerService demonstrating the NestedMonitorLockoutDetector.
 *
 * The concurrent test shows how acquiring lockB while inside a synchronized(lockA)
 * block and calling wait() is flagged as a potential nested monitor lockout.
 */
class ProducerConsumerServiceTest {

    private ProducerConsumerService service;

    @BeforeEach
    void setUp() {
        service = new ProducerConsumerService();
    }

    @Test
    void test_singleThread_produceAndConsume_works() throws InterruptedException {
        service.produce("item1");
        String result = service.consume();
        // May be null if queue was already drained — but no exception
        assertDoesNotThrow(() -> service.produce("item2"));
    }

    @Test
    void test_singleThread_consumeOnEmptyQueue_returnsNull() {
        assertNull(service.consume());
    }

    @Disabled("Remove @Disabled to see bug detected by NestedMonitorLockoutDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectNestedMonitorLockout = true, failOn = FailOn.LOW)
    void test_concurrent_detectsBug() {
        // Record acquiring lockA (outer monitor)
        AsyncTestContext.nestedMonitorLockoutMonitor()
                .recordMonitorAcquired(service.lockA);

        // Record attempting a blocking operation (wait) while lockA is held
        AsyncTestContext.nestedMonitorLockoutMonitor()
                .recordBlockingOperationAttempted("wait on lockB while holding lockA");

        // Record acquiring lockB (inner monitor — the deadlock point)
        AsyncTestContext.nestedMonitorLockoutMonitor()
                .recordMonitorAcquired(service.lockB);
        AsyncTestContext.nestedMonitorLockoutMonitor()
                .recordMonitorReleased(service.lockB);

        AsyncTestContext.nestedMonitorLockoutMonitor()
                .recordMonitorReleased(service.lockA);

        // Drive the actual service
        try {
            service.produce("task-" + Thread.currentThread().getId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
