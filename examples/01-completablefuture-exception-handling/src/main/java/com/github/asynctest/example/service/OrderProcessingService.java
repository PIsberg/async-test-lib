package com.github.asynctest.example.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Map;

/**
 * Real-world order processing service that asynchronously fetches order details
 * from multiple downstream services (inventory, payment, shipping).
 *
 * This is production-grade code that could exist in any e-commerce system.
 *
 * ========================================================================
 * DETECTED BY: CompletableFutureExceptionDetector
 * ========================================================================
 *
 * COMMON ASYNC PROBLEM: Unhandled exceptions in CompletableFuture chains
 *
 * The bug: When any async step fails, the exception is silently swallowed
 * because we use .thenApply() without .exceptionally() or .handle().
 * The CompletableFuture completes exceptionally, but the caller never knows
 * because .join() at the end rethrows it as CompletionException.
 *
 * Under sequential test execution (@Test), this might work "fine" because:
 * - The test runs single-threaded
 * - Failures happen deterministically and are caught
 * - The .join() throws immediately
 *
 * Under concurrent stress testing (@AsyncTest), the problem becomes severe:
 * - Multiple threads trigger exceptions simultaneously
 * - Unhandled exceptions propagate as CompletionException
 * - The shared state (processedOrders) may end up inconsistent
 * - CompletableFutureExceptionDetector will flag unhandled exceptions
 *
 * DETECTORS TRIGGERED:
 * 1. CompletableFutureExceptionDetector - Flags unhandled exceptions in async chains
 * 2. RaceConditionDetector - Unsynchronized access to shared state under concurrent load
 * 3. VisibilityMonitor - May flag inconsistent state visibility across threads
 *
 * ROOT CAUSE:
 * The composeAsyncChain() method chains async operations without error handling.
 * When validateOrder() or processPayment() throws an exception, it propagates
 * through the entire chain without being caught, leaving the future in an
 * exceptional state.
 *
 * SOLUTION (see comments in code):
 * Add .handle() or .exceptionally() to each async stage to catch and handle
 * exceptions gracefully, returning a fallback value or error state instead
 * of letting the exception propagate unhandled.
 */
public class OrderProcessingService {

    private final InventoryService inventoryService;
    private final PaymentService paymentService;
    private final ShippingService shippingService;
    private final ExecutorService executor;
    
    // Track successfully processed orders
    private final Map<String, OrderResult> processedOrders = new ConcurrentHashMap<>();
    
    // Track failed orders - THIS IS THE PROBLEM AREA
    // When exceptions are unhandled, failed orders are never recorded
    private final Map<String, String> failedOrders = new ConcurrentHashMap<>();

    public OrderProcessingService() {
        this.inventoryService = new InventoryService();
        this.paymentService = new PaymentService();
        this.shippingService = new ShippingService();
        this.executor = Executors.newCachedThreadPool();
    }

    /**
     * Process an order by asynchronously:
     * 1. Validating inventory availability
     * 2. Processing payment
     * 3. Initiating shipping
     * 
     * BUG: This method has NO exception handling in the async chain.
     * If any step fails, the entire chain fails silently.
     */
    public CompletableFuture<Void> processOrder(String orderId) {
        return CompletableFuture.supplyAsync(() -> inventoryService.checkStock(orderId), executor)
            // BUG: No exception handling here!
            // If checkStock throws, this exception propagates unhandled
            .thenApply(inStock -> {
                if (!inStock) {
                    throw new InventoryException("Item out of stock: " + orderId);
                }
                return orderId;
            })
            .thenCompose(this::composeAsyncChain)
            .thenAccept(result -> processedOrders.put(orderId, result));
            // PROBLEM: No .exceptionally() to handle failures!
            // When exception occurs, failedOrders is never updated
    }

    /**
     * Composes the payment and shipping async operations.
     * 
     * BUG: Again, no exception handling in this chain.
     */
    private CompletableFuture<OrderResult> composeAsyncChain(String orderId) {
        return paymentService.processPayment(orderId)
            .thenApply(paymentConfirmed -> {
                // BUG: If processPayment completes exceptionally, this never runs
                // and the exception propagates unhandled
                return shippingService.initiateShipping(orderId);
            })
            .thenApply(shippingId -> {
                return new OrderResult(orderId, shippingId, "COMPLETED");
            });
            // SOLUTION: Add exception handling here:
            // .exceptionally(ex -> {
            //     failedOrders.put(orderId, ex.getCause().getMessage());
            //     return new OrderResult(orderId, null, "FAILED: " + ex.getCause().getMessage());
            // });
    }

    /**
     * Process multiple orders concurrently.
     * 
     * BUG: When individual orders fail, their futures complete exceptionally,
     * but joinAllOrders() doesn't handle this properly.
     */
    public Map<String, OrderResult> processMultipleOrders(java.util.List<String> orderIds) {
        var futures = orderIds.stream()
            .map(this::processOrder)
            .toArray(CompletableFuture[]::new);

        // Wait for all to complete
        CompletableFuture.allOf(futures).join();

        // PROBLEM: If any future completed exceptionally, it's not in processedOrders
        // This return map will be missing failed orders, causing assertion failures
        return Map.copyOf(processedOrders);
    }

    public Map<String, String> getFailedOrders() {
        return Map.copyOf(failedOrders);
    }

    public void shutdown() {
        executor.shutdown();
    }

    // Record types for domain objects
    public record OrderResult(String orderId, String shippingId, String status) {}
    
    public static class InventoryException extends RuntimeException {
        public InventoryException(String message) {
            super(message);
        }
    }

    // Downstream service simulations
    static class InventoryService {
        private int callCount = 0;
        
        public boolean checkStock(String orderId) {
            callCount++;
            // Simulate intermittent failures - every 3rd call fails
            // This makes the bug non-deterministic and hard to catch in normal tests
            if (callCount % 3 == 0) {
                throw new InventoryException("Inventory service timeout: " + orderId);
            }
            return true; // Item in stock
        }
    }

    static class PaymentService {
        public CompletableFuture<Boolean> processPayment(String orderId) {
            return CompletableFuture.supplyAsync(() -> {
                // Simulate payment processing
                return true;
            });
        }
    }

    static class ShippingService {
        public String initiateShipping(String orderId) {
            return "SHIP-" + orderId.substring(5);
        }
    }
}
