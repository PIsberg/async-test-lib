# CompletableFuture Exception Handling Example

This example demonstrates a **real-world production bug** found in many e-commerce systems: **unhandled exceptions in CompletableFuture chains**.

## The Problem

The `OrderProcessingService` processes orders through multiple async stages:
1. Inventory check
2. Payment processing  
3. Shipping initiation

**The Bug**: None of the async chains have `.exceptionally()` or `.handle()` to catch errors. When any stage fails, the exception propagates unhandled, and the order is **silently lost** - never recorded in either `processedOrders` or `failedOrders`.

## Why This Happens

```java
// BUGGY CODE (OrderProcessingService.java):
public CompletableFuture<Void> processOrder(String orderId) {
    return CompletableFuture.supplyAsync(() -> inventoryService.checkStock(orderId), executor)
        .thenApply(inStock -> {
            if (!inStock) throw new InventoryException("Out of stock");
            return orderId;
        })
        .thenCompose(this::composeAsyncChain)
        .thenAccept(result -> processedOrders.put(orderId, result));
        // ❌ No .exceptionally() - unhandled exceptions are lost!
}
```

## How to Reproduce

### 1. Run with @Test (PASSES - false confidence)

```java
@Test
void testProcessMultipleOrders_Sequential() {
    // Passes because exceptions are predictable in single-threaded mode
}
```

```bash
cd example
mvn clean test
# ✅ Tests pass: 2 passed, 0 failed
```

The test passes because it expects the exception and catches it. Sequential execution gives predictable failures.

### 2. Run with @AsyncTest (FAILS - exposes the real bug)

Change the test annotation:
```java
@AsyncTest(threads = 10, invocations = 50, detectAll = true)
void testProcessMultipleOrders_Concurrent() {
    // Now fails - shows data loss under concurrent load
}
```

```bash
cd example
mvn clean test
# ❌ Tests fail: "Processed: 0, Failed: 0 ==> expected: <5> but was: <0>"
```

**8 concurrent threads failed** - ALL orders were lost! The system ends up with:
- `processedOrders`: 0 entries (futures completed exceptionally)
- `failedOrders`: 0 entries (exceptions were never caught)
- **Data loss**: 5 orders disappeared completely

## The Root Cause

Under concurrent stress testing with `@AsyncTest`:
1. **10 threads** execute `processOrder()` simultaneously
2. `InventoryService.callCount` has a **race condition** (not thread-safe)
3. Every 3rd call fails, but with concurrent access, **more calls fail than expected**
4. Unhandled exceptions cascade through the CompletableFuture chain
5. **No error handling means failures are silent** - no logging, no retries, no fallback
6. The `@AsyncTest` detectors flag:
   - ✅ **CompletableFutureExceptionDetector**: Unhandled async exceptions
   - ✅ **LivelockDetector**: Threads with no progress
   - ✅ **RaceConditionDetector**: Unsynchronized access to `callCount`

## The Solution

Add `.exceptionally()` to handle errors gracefully:

```java
// FIXED CODE (see commented section in OrderProcessingServiceTest.java):
public CompletableFuture<Void> processOrder(String orderId) {
    return CompletableFuture.supplyAsync(() -> inventoryService.checkStock(orderId), executor)
        .thenApply(inStock -> {
            if (!inStock) throw new InventoryException("Out of stock");
            return orderId;
        })
        .thenCompose(this::composeAsyncChain)
        .thenAccept(result -> processedOrders.put(orderId, result))
        // ✅ Add exception handling
        .exceptionally(ex -> {
            String errorMsg = ex.getCause() != null ? 
                ex.getCause().getMessage() : ex.getMessage();
            failedOrders.put(orderId, errorMsg);  // Now failures are tracked
            return null;  // Don't propagate exception
        });
}

// Also fix the composeAsyncChain method:
private CompletableFuture<OrderResult> composeAsyncChain(String orderId) {
    return paymentService.processPayment(orderId)
        .thenApply(paymentConfirmed -> shippingService.initiateShipping(orderId))
        .thenApply(shippingId -> new OrderResult(orderId, shippingId, "COMPLETED"))
        // ✅ Handle exceptions at each stage
        .exceptionally(ex -> {
            String errorMsg = ex.getCause() != null ? 
                ex.getCause().getMessage() : ex.getMessage();
            failedOrders.put(orderId, errorMsg);
            return new OrderResult(orderId, null, "FAILED: " + errorMsg);
        });
}
```

Also make `InventoryService.checkStock()` thread-safe:
```java
public synchronized boolean checkStock(String orderId) {  // Added synchronized
    callCount++;
    if (callCount % 3 == 0) {
        throw new InventoryException("Inventory service timeout: " + orderId);
    }
    return true;
}
```

## Files in This Example

- **`OrderProcessingService.java`** - Buggy production code with unhandled async exceptions
- **`OrderProcessingServiceTest.java`** - Tests that demonstrate the problem
  - `testProcessMultipleOrders_Sequential()` - Passes with @Test
  - `testProcessMultipleOrders_Concurrent()` - Fails with @AsyncTest
  - Commented solution showing the fixed implementation
- **`pom.xml`** - Maven dependencies (JUnit 5 + async-test-lib)

## Key Takeaways

1. **@Test gives false confidence**: Sequential tests don't expose concurrent bugs
2. **@AsyncTest finds real problems**: Stress testing with 10 threads × 50 invocations exposes the data loss
3. **Always handle CompletableFuture exceptions**: Use `.exceptionally()` or `.handle()` to prevent silent failures
4. **Monitor both success and failure paths**: Track processed AND failed items to ensure consistency
5. **Make shared state thread-safe**: Use `synchronized`, `volatile`, or atomic operations for concurrent access

## Try It Yourself

1. Run `mvn clean test` - tests pass with @Test
2. Change `@Test` to `@AsyncTest(threads = 10, invocations = 50, detectAll = true)` in the concurrent test
3. Run `mvn clean test` again - watch it fail with detailed detector reports
4. Uncomment the fixed service in `OrderProcessingServiceTest.java` and test it
5. See the solution pass with @AsyncTest

## What the Library Detectors Find

When running with `@AsyncTest(detectAll = true)`, these detectors are triggered:

- **CompletableFutureExceptionDetector**: Flags unhandled exceptions in async chains
- **LivelockDetector**: Detects threads waiting without progress
- **RaceConditionDetector**: Finds unsynchronized access to `callCount`
- **DeadlockDetector**: Monitors for circular lock dependencies
- **VisibilityMonitor**: Checks for missing volatile keywords

The detailed reports show exactly where the concurrency bugs are, making it easy to fix the root cause rather than guessing.
