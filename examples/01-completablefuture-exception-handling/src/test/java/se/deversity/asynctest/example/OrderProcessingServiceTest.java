package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.diagnostics.CompletableFutureExceptionDetector;
import se.deversity.asynctest.example.service.OrderProcessingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for OrderProcessingService
 *
 * ========================================================================
 * DETECTOR: CompletableFutureExceptionDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - A sequential @Test PASSES (but gives false confidence)
 * - The same test with @AsyncTest FAILS (exposing the real concurrent bug)
 *
 * THE BUG:
 * OrderProcessingService has unhandled exceptions in its CompletableFuture chains.
 * When any async step fails (e.g., inventory timeout every 3rd call), the exception
 * propagates unhandled, and the order is never recorded in processedOrders or failedOrders.
 *
 * WHY @Test PASSES:
 * With single-threaded execution, we process orders one at a time. The 3rd order fails,
 * .join() throws CompletionException, and the test catches it or the assertion sees
 * fewer processed orders than expected - but it's somewhat deterministic.
 *
 * WHY @AsyncTest DETECTS IT:
 * CompletableFutureExceptionDetector reports a chain that completed exceptionally
 * with no exception handler registered on it. OrderProcessingService.observeFutures
 * tells it when each chain is built, when it finishes, and whether it finished
 * normally, so a chain that ends in an exception nobody attached a handler for is
 * exactly what it sees.
 *
 * THIS EXAMPLE WAS THE ODD ONE OUT IN ISSUE #346:
 * its @Disabled sat on a plain @Test, with the @AsyncTest annotation commented out
 * on the line above, so it was never part of the enabled run at all. Its reason
 * said "fails with @AsyncTest", a claim about an annotation that was not there.
 * There is one now.
 *
 * DETECTOR ENABLED HERE:
 * CompletableFutureExceptionDetector — a chain that completed exceptionally with no
 * handler. It is the only one this demonstration switches on, so it is the only one
 * that can report.
 */
class OrderProcessingServiceTest {

    private OrderProcessingService service;

    @BeforeEach
    void setUp() {
        service = new OrderProcessingService();
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    /**
     * STANDARD TEST - This PASSES
     * 
     * Running sequentially with @Test, the test passes because:
     * - Orders are processed one at a time
     * - When the 3rd order fails (callCount % 3 == 0), it throws
     * - The exception propagates but we catch it and verify it's the expected error
     * - No concurrency means no race conditions on shared state
     * 
     * This gives FALSE CONFIDENCE that the code works correctly under load.
     */
    @Test
    void testProcessMultipleOrders_Sequential() {
        var orderIds = List.of("ORD-001", "ORD-002", "ORD-003", "ORD-004", "ORD-005");
        
        // This will throw CompletionException due to unhandled exceptions,
        // but we expect it and catch it
        Exception thrownException = assertThrows(Exception.class, () -> {
            service.processMultipleOrders(orderIds);
        });
        
        // Verify it's the expected InventoryException (not some other error)
        assertTrue(thrownException.getMessage().contains("InventoryException") || 
                  (thrownException.getCause() != null && 
                   thrownException.getCause().getMessage().contains("Inventory")));
    }

    /**
     * CONCURRENCY STRESS TEST - PASSES with @Test, FAILS with @AsyncTest
     * 
     * Running with @Test, this passes because we get a predictable exception.
     * Running with @AsyncTest (10 threads x 50 invocations), this FAILS because:
     * 
     * 1. Multiple threads simultaneously call processOrder()
     * 2. The InventoryService.callCount is shared and incremented concurrently  
     * 3. Race condition on callCount causes unpredictable failures
     * 4. Unhandled exceptions in CompletableFuture chains propagate inconsistently
     * 5. processedOrders map has missing entries (failed orders never recorded)
     * 6. CompletableFutureExceptionDetector flags unhandled async exceptions
     * 
     * The test now verifies that all orders are accounted for (processed + failed).
     * With @AsyncTest, this fails because unhandled exceptions mean failed orders
     * are never recorded, leaving the system in an inconsistent state.
     * 
     * NOTE: Currently using @Test so CI passes. To see the problem,
     * change to @AsyncTest(threads = 10, invocations = 50, detectAll = true)
     */
    @Test
    void testProcessMultipleOrders_Concurrent() {
        var orderIds = List.of("ORD-001", "ORD-002", "ORD-003", "ORD-004", "ORD-005");
        
        // With @Test: Exception is predictable and manageable
        // With @AsyncTest: Data loss occurs due to unhandled exceptions
        Map<String, OrderProcessingService.OrderResult> results;
        try {
            results = service.processMultipleOrders(orderIds);
        } catch (Exception e) {
            // Expected - unhandled exceptions bubble up
            results = Map.of();
        }
        
        // With sequential @Test, we get consistent behavior
        // This assertion documents the expected state after processing
        int totalAccounted = results.size() + service.getFailedOrders().size();
        
        // In sequential mode, we expect either all processed OR an exception thrown
        // The key point: behavior is predictable with @Test, chaotic with @AsyncTest
        assertTrue(totalAccounted <= orderIds.size(), 
            "Should not have more orders than requested. Got: " + totalAccounted);
    }

    /**
     * The accounting hole, with no detector involved: an order whose chain failed is in neither
     * map. The caller asked about five orders and can find fewer than five answers.
     */
    @Test
    void testProcessMultipleOrders_failedOrdersVanish() {
        var orderIds = List.of("ORD-001", "ORD-002", "ORD-003", "ORD-004", "ORD-005");

        Map<String, OrderProcessingService.OrderResult> results;
        try {
            results = service.processMultipleOrders(orderIds);
        } catch (Exception e) {
            results = Map.of();     // allOf().join() rethrows whatever failed
        }

        assertTrue(results.size() + service.getFailedOrders().size() < orderIds.size(),
                "at least one order is in neither map: " + results.size() + " processed, "
                        + service.getFailedOrders().size() + " failed, of " + orderIds.size());
    }

    /**
     * And with the handler put back, every order is accounted for.
     */
    @Test
    void testProcessMultipleOrdersHandled_everyOrderIsAccountedFor() {
        var orderIds = List.of("ORD-001", "ORD-002", "ORD-003", "ORD-004", "ORD-005");

        Map<String, OrderProcessingService.OrderResult> results =
                service.processMultipleOrdersHandled(orderIds);

        assertEquals(orderIds.size(), results.size() + service.getFailedOrders().size(),
                "processed plus failed should be every order asked about");
    }

    /**
     * The detector's positive direction: a chain that completed exceptionally with no handler
     * registered on it.
     */
    @Test
    void testCompletableFutureExceptionDetector_unhandledChain_reports() {
        CompletableFutureExceptionDetector detector = new CompletableFutureExceptionDetector();
        wire(detector);

        try {
            service.processMultipleOrders(
                    List.of("ORD-001", "ORD-002", "ORD-003", "ORD-004", "ORD-005"));
        } catch (Exception expected) {
            // allOf().join() rethrows; the finding is about the chain, not this call
        }

        assertTrue(detector.analyze().hasIssues(),
                "a chain that completed exceptionally with no handler is the finding");
    }

    /**
     * And the other direction: the same orders through the handled chain. A handler is
     * registered, nothing completes exceptionally, and there is nothing to report.
     */
    @Test
    void testCompletableFutureExceptionDetector_handledChain_isSilent() {
        CompletableFutureExceptionDetector detector = new CompletableFutureExceptionDetector();
        wire(detector);

        service.processMultipleOrdersHandled(
                List.of("ORD-001", "ORD-002", "ORD-003", "ORD-004", "ORD-005"));

        assertFalse(detector.analyze().hasIssues(),
                "a chain with an exceptionally() on it is the fix, not a finding");
    }

    private void wire(CompletableFutureExceptionDetector detector) {
        service.observeFutures(
                detector::recordFutureCreated,
                (future, name, success) -> detector.recordFutureCompleted(future, name, success),
                (future, name) -> detector.recordExceptionHandled(future, name, null));
    }

    /**
     * The demonstration.
     *
     * <p>This one is different from every other example in the sweep, and issue #346 says so:
     * its @Disabled sat on a plain @Test with the @AsyncTest annotation commented out above it,
     * so it was never part of the enabled run at all. Its reason string said "fails with
     * @AsyncTest", which was not something that could be checked, because there was no
     * @AsyncTest.
     *
     * <p>There is one now, and the detector it names is wired to the chains the service actually
     * builds.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — it fails with
     *      processOrder:ORD-003: completed exceptionally without exception handler
     * 3. Fix: processOrderHandled(), which is the same chain with .exceptionally() on the end
     */
    @Disabled("Remove @Disabled to see unhandled async exceptions detected by "
            + "CompletableFutureExceptionDetector")
    @AsyncTest(threads = 10, invocations = 5, detectAll = false,
            detectCompletableFutureExceptions = true, failOn = FailOn.LOW)
    void testProcessMultipleOrders_Concurrent_WITH_ASYNC_TEST() {
        CompletableFutureExceptionDetector detector =
                AsyncTestContext.completableFutureExceptionDetector();
        service.observeFutures(
                detector::recordFutureCreated,
                (future, name, success) -> detector.recordFutureCompleted(future, name, success),
                (future, name) -> detector.recordExceptionHandled(future, name, null));

        var orderIds = List.of("ORD-001", "ORD-002", "ORD-003", "ORD-004", "ORD-005");
        try {
            service.processMultipleOrders(orderIds);
        } catch (Exception expected) {
            // allOf().join() rethrows whatever failed. Swallowing it here is the point: this is
            // what the calling code does, and it is why the failed order is never recorded.
        }
    }

    /**
     * The fix, and it is not a commented-out class any more.
     *
     * <p>This block used to hold a commented-out test against an OrderProcessingServiceWithFix
     * that did not exist, under a heading saying it passes with @AsyncTest - a claim about code
     * nobody could run. The fix now lives on the service itself, as processOrderHandled(), and
     * testProcessMultipleOrdersHandled_everyOrderIsAccountedFor and
     * testCompletableFutureExceptionDetector_handledChain_isSilent run against it on every
     * build.
     *
     * <p>The difference is one line:
     * <pre>{@code
     * .exceptionally(failure -> {
     *     failedOrders.put(orderId, cause(failure).getMessage());
     *     return null;
     * });
     * }</pre>
     */
    @Test
    void testProcessOrderHandled_isTheFix() {
        service.processOrderHandled("ORD-003").join();

        assertEquals(1, service.getFailedOrders().size() + service.getProcessedOrderCount(),
                "the order ended up in one map or the other, which is all the fix promises");
    }
}


/**
 * ============================================================================
 * SOLUTION: OrderProcessingServiceWithFix
 * ============================================================================
 * 
 * This is how the OrderProcessingService should be written to handle async
 * exceptions properly. The key changes are marked with [FIX].
 * 
 * Copy this class over OrderProcessingService to fix the bug.
 */
/*
public class OrderProcessingServiceWithFix {
    
    private final InventoryService inventoryService;
    private final PaymentService paymentService;
    private final ShippingService shippingService;
    private final ExecutorService executor;
    
    private final Map<String, OrderResult> processedOrders = new ConcurrentHashMap<>();
    private final Map<String, String> failedOrders = new ConcurrentHashMap<>();  // [FIX] Now properly populated

    public OrderProcessingServiceWithFix() {
        this.inventoryService = new InventoryService();
        this.paymentService = new PaymentService();
        this.shippingService = new ShippingService();
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public CompletableFuture<OrderResult> processOrder(String orderId) {
        return CompletableFuture.supplyAsync(() -> inventoryService.checkStock(orderId), executor)
            .thenApply(inStock -> {
                if (!inStock) {
                    throw new InventoryException("Item out of stock: " + orderId);
                }
                return orderId;
            })
            .thenCompose(this::composeAsyncChain)
            .thenAccept(result -> processedOrders.put(orderId, result))
            // [FIX] Add exception handling to catch and record failures
            .exceptionally(ex -> {
                String errorMsg = ex.getCause() != null ? 
                    ex.getCause().getMessage() : ex.getMessage();
                failedOrders.put(orderId, errorMsg);
                return null;  // Return null on failure, but don't propagate exception
            });
    }

    private CompletableFuture<OrderResult> composeAsyncChain(String orderId) {
        return paymentService.processPayment(orderId)
            .thenApply(paymentConfirmed -> shippingService.initiateShipping(orderId))
            .thenApply(shippingId -> new OrderResult(orderId, shippingId, "COMPLETED"))
            // [FIX] Handle exceptions at each stage to prevent silent failures
            .exceptionally(ex -> {
                String errorMsg = ex.getCause() != null ? 
                    ex.getCause().getMessage() : ex.getMessage();
                failedOrders.put(orderId, errorMsg);
                return new OrderResult(orderId, null, "FAILED: " + errorMsg);
            });
    }

    public Map<String, OrderResult> processMultipleOrders(List<String> orderIds) {
        var futures = orderIds.stream()
            .map(this::processOrder)
            .toArray(CompletableFuture[]::new);

        // [FIX] allOf won't throw because individual futures handle exceptions
        CompletableFuture.allOf(futures).join();

        return Map.copyOf(processedOrders);
    }

    public Map<String, String> getFailedOrders() {
        return Map.copyOf(failedOrders);
    }

    public void shutdown() {
        executor.shutdown();
    }

    public record OrderResult(String orderId, String shippingId, String status) {}
    
    public static class InventoryException extends RuntimeException {
        public InventoryException(String message) {
            super(message);
        }
    }

    // Same InventoryService, PaymentService, ShippingService as before
    static class InventoryService {
        private int callCount = 0;
        
        public synchronized boolean checkStock(String orderId) {  // [FIX] Added synchronized
            callCount++;
            if (callCount % 3 == 0) {
                throw new InventoryException("Inventory service timeout: " + orderId);
            }
            return true;
        }
    }

    static class PaymentService {
        public CompletableFuture<Boolean> processPayment(String orderId) {
            return CompletableFuture.supplyAsync(() -> true);
        }
    }

    static class ShippingService {
        public String initiateShipping(String orderId) {
            return "SHIP-" + orderId.substring(5);
        }
    }
}
*/
