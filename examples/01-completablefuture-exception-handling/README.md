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

Remove `@Disabled` from `testProcessMultipleOrders_Concurrent_WITH_ASYNC_TEST` and run:

```bash
cd example
mvn clean test
```

```
COMPLETABLEFUTURE EXCEPTION HANDLING ISSUES DETECTED:
  Unhandled Exceptions:
    - processOrder:ORD-001: completed exceptionally without exception handler
    - processOrder:ORD-004: completed exceptionally without exception handler
    ...
```

`failOn = FailOn.LOW` is what turns that report into a failed run.

The orders behind those lines are simply gone. `processedOrders` never received them, because
the chain never reached `thenAccept`; `failedOrders` never received them either, because nothing
in the chain catches anything. `testProcessMultipleOrders_failedOrdersVanish` pins that with no
detector involved.

**This example was the odd one out in issue #346.** Its `@Disabled` sat on a plain `@Test`, with
the `@AsyncTest` annotation commented out on the line above, so it was never part of the enabled
run at all, and its reason said "fails with @AsyncTest" - a claim about an annotation that was
not there. There is one now.

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
  - `testProcessMultipleOrders_failedOrdersVanish()` - the accounting hole, no detector needed
  - `testProcessMultipleOrders_Concurrent_WITH_ASYNC_TEST()` - the `@AsyncTest` demonstration
  - `testProcessOrderHandled_isTheFix()` - and the fix, which is a real method now rather than a
    commented-out class
- **`pom.xml`** - Maven dependencies (JUnit 5 + async-test-lib)

## Key Takeaways

1. **@Test gives false confidence**: Sequential tests don't expose concurrent bugs
2. **@AsyncTest finds real problems**: Stress testing with 10 threads × 50 invocations exposes the data loss
3. **Always handle CompletableFuture exceptions**: Use `.exceptionally()` or `.handle()` to prevent silent failures
4. **Monitor both success and failure paths**: Track processed AND failed items to ensure consistency
5. **Make shared state thread-safe**: Use `synchronized`, `volatile`, or atomic operations for concurrent access

## Try It Yourself

1. Run `mvn clean test` - the enabled tests pass
2. Remove `@Disabled` from `testProcessMultipleOrders_Concurrent_WITH_ASYNC_TEST`
3. Run `mvn clean test` again - watch it fail with the detector's report
4. Swap `processMultipleOrders` for `processMultipleOrdersHandled` in that body
5. Run it once more - the chains now carry a handler, and the report is empty

## What the Library Detectors Find

The demonstration switches on **one** detector, `detectCompletableFutureExceptions`, and that is
the only one that can report. It is fed through `OrderProcessingService.observeFutures`, which
tells it when each chain is built, when it finishes, and whether it finished normally; the hooks
default to no-ops, so the production path never touches the test library.

The service has other problems - `InventoryService.callCount` is an unsynchronized `int`
incremented from every thread, which is a lost-update race - and switching on
`detectRaceConditions` and recording those accesses would report them. This example does not,
because a demonstration that names one detector and switches on all 146 cannot say which one
found what.
