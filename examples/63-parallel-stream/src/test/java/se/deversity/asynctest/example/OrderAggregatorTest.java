package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.OrderAggregator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for OrderAggregator demonstrating the ParallelStreamDetector.
 *
 * The concurrent test shows how using parallelStream() with a stateful side
 * effect on a non-thread-safe collection is flagged as a concurrency hazard.
 */
class OrderAggregatorTest {

    private OrderAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new OrderAggregator();
    }

    @Test
    void test_singleThread_processOrders_populatesResults() {
        aggregator.processOrders(List.of("order-1", "order-2", "order-3"));
        assertFalse(aggregator.getResults().isEmpty());
    }

    @Test
    void test_singleThread_clear_resetsResults() {
        aggregator.processOrders(List.of("order-A"));
        aggregator.clear();
        assertTrue(aggregator.getResults().isEmpty());
    }

    @Disabled("Remove @Disabled to see bug detected by ParallelStreamDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectParallelStreamIssues = true, failOn = FailOn.LOW)
    void test_concurrent_detectsBug() {
        // Inform the detector that a parallel stream is being used
        AsyncTestContext.parallelStreamMonitor()
                .recordParallelStream("OrderAggregator.processOrders");

        // Record the side effect: forEach writes into a non-thread-safe collection
        AsyncTestContext.parallelStreamMonitor()
                .recordSideEffect("OrderAggregator.processOrders", "ArrayList.add on shared results");

        // Record using a non-thread-safe collector pattern
        AsyncTestContext.parallelStreamMonitor()
                .recordNonThreadSafeCollector("OrderAggregator.processOrders", "ArrayList");

        // Drive the actual buggy method — concurrent invocations hit the same results list
        aggregator.processOrders(List.of(
                "order-" + Thread.currentThread().getId() + "-A",
                "order-" + Thread.currentThread().getId() + "-B"
        ));
    }
}
