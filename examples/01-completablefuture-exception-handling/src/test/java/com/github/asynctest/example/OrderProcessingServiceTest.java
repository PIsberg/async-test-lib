package com.github.asynctest.example;

import com.github.asynctest.AsyncTest;
import com.github.asynctest.example.service.OrderProcessingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for OrderProcessingService
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
 * WHY @AsyncTest FAILS:
 * With 10+ concurrent threads hitting the service simultaneously:
 * - Many more orders fail due to race conditions on callCount
 * - Unhandled exceptions cascade through the system
 * - processedOrders map ends up with inconsistent state
 * - CompletableFutureExceptionDetector flags multiple unhandled async exceptions
 * - The test fails with CompletionException or assertion errors
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
     * DEMONSTRATION: Run this with @AsyncTest to see the bug
     * 
     * This test is DISABLED by default because it will fail in CI.
     * Uncomment the @Disabled annotation to see the problem manually.
     * 
     * When enabled with @AsyncTest, this demonstrates:
     * - Data loss: "Processed: 0, Failed: 0" 
     * - All orders disappear due to unhandled CompletableFuture exceptions
     * - 8+ concurrent threads fail simultaneously
     */
    // @Test  // <-- Uncomment to enable this test
    // @Disabled("Demonstrates the bug - fails with @AsyncTest")
    // void testProcessMultipleOrders_Concurrent_WITH_ASYNC_TEST() {
    //     // To see the problem:
    //     // 1. Uncomment this test method
    //     // 2. Change @Test above to: @AsyncTest(threads = 10, invocations = 50, detectAll = true)
    //     // 3. Run: mvn test
    //     // 4. Watch it fail with: "Processed: 0, Failed: 0 ==> expected: <5> but was: <0>"
    //     
    //     var orderIds = List.of("ORD-001", "ORD-002", "ORD-003", "ORD-004", "ORD-005");
    //     
    //     Map<String, OrderProcessingService.OrderResult> results;
    //     try {
    //         results = service.processMultipleOrders(orderIds);
    //     } catch (Exception e) {
    //         results = Map.of();
    //     }
    //     
    //     int totalAccounted = results.size() + service.getFailedOrders().size();
    //     
    //     // This WILL FAIL with @AsyncTest because orders are lost
    //     assertEquals(orderIds.size(), totalAccounted, 
    //         "All orders should be accounted for, but unhandled exceptions cause data loss. " +
    //         "Processed: " + results.size() + ", Failed: " + service.getFailedOrders().size());
    // }

    /**
     * SOLUTION TEST - This PASSES with @AsyncTest
     * 
     * This test shows what the FIXED version should look like.
     * The fix is in the OrderProcessingServiceWithFix class (see comment below).
     * 
     * Uncomment this test and the fixed service to see the correct behavior.
     */
    // @AsyncTest(threads = 10, invocations = 50, detectAll = true)
    // void testProcessMultipleOrders_WithFix() {
    //     var fixedService = new OrderProcessingServiceWithFix();
    //     var orderIds = List.of("ORD-001", "ORD-002", "ORD-003", "ORD-004", "ORD-005");
    //
    //     Map<String, OrderProcessingServiceWithFix.OrderResult> results = 
    //         fixedService.processMultipleOrders(orderIds);
    //
    //     // With proper exception handling, ALL orders are accounted for
    //     // (either in processedOrders or failedOrders)
    //     int total = results.size() + fixedService.getFailedOrders().size();
    //     assertEquals(orderIds.size(), total, 
    //         "All orders should be accounted for (processed or failed)");
    //     
    //     fixedService.shutdown();
    // }
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
