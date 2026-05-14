# Race Condition Example

This example demonstrates **detection of a check-then-update (TOCTOU) race condition**
in an inventory management service using the `RaceConditionDetector`.

## The Problem

`InventoryService.reserveItem(sku, quantity)` checks whether stock is sufficient and
then decrements it in three separate, unguarded steps:

```java
// BUGGY CODE (InventoryService.java):
public boolean reserveItem(String sku, int quantity) {
    Integer current = stock.get(sku);           // STEP 1: read
    if (current == null || current < quantity) { // STEP 2: check
        return false;
    }
    // ← RACE WINDOW: another thread can decrement here
    stock.put(sku, current - quantity);          // STEP 3: write (stale!)
    totalReserved.addAndGet(quantity);
    return true;
}
```

When 8 threads all read `current = 10`, all pass the check, and all write
`stock.put(sku, 9)` — the net result is a single decrement instead of eight. The
stock count can go negative, and `totalReserved` grows beyond the initial stock level.

## Why Sequential Tests Miss This Bug

```java
@Test
void testReserveItem_sequential_neverNegative() {
    for (int i = 0; i < 20; i++) {
        service.reserveItem(SKU, 1);
    }
    assertTrue(service.getStock(SKU) >= 0);
    // ✅ Passes — one thread, steps never interleave
}
```

With a single thread, each reservation completes before the next starts. The stock
value read in STEP 1 is always current because no other thread has mutated it between
the read and the write.

## How to Reproduce

### 1. Run with @Test (PASSES)

```bash
cd examples/08-race-condition
mvn clean test
# ✅ All @Test methods pass
```

### 2. Run with @AsyncTest (RACE CONDITION DETECTED)

Remove `@Disabled` from `testReserveItem_concurrent_detectsRaceCondition()` and run:

```bash
mvn clean test
# Assertion fails: stock goes negative
# RaceConditionDetector reports unsynchronized concurrent writes
```

Expected output:

```
HIGH: Potential race conditions detected — unsynchronized writes to shared fields
allow threads to overwrite each other's changes, producing lost updates, stale reads,
and silently wrong results

Concurrent write hotspots:
  - InventoryService@1a2b3c.stock: 8 writes observed across 8 threads

Unsynchronized access sequences:
  - InventoryService@1a2b3c.stock: thread 17 write followed by thread 18 write
```

And the assertion failure:

```
AssertionError: Stock went to -6 — race condition over-committed inventory
```

## How the Detector Works

`RaceConditionDetector` is a **Phase 1 detector** activated by
`detectRaceConditions = true`. It tracks cross-thread read and write access events
recorded on shared objects and fields, then analyses the timeline for:

1. **Concurrent write hotspots**: the same field written by two or more threads
2. **Unsafe access sequences**: a write on thread A immediately followed by a write
   on thread B (no synchronization barrier between them)

After all invocation rounds complete, the detector prints a report showing which
field was accessed from how many threads and in what order, identifying the race.

## The Fix

Replace the three-step read-check-write with an atomic CAS loop:

```java
// FIXED: atomic compare-and-swap — check and update are indivisible
public boolean reserveItemFixed(String sku, int quantity) {
    while (true) {
        Integer current = stock.get(sku);
        if (current == null || current < quantity) {
            return false;
        }
        // CAS succeeds only if stock still equals 'current' — no stale writes
        if (stock.replace(sku, current, current - quantity)) {
            totalReserved.addAndGet(quantity);
            return true;
        }
        // Another thread changed stock between our read and our CAS — retry
    }
}
```

`ConcurrentHashMap.replace(key, expectedValue, newValue)` is atomic: the check and
the update happen together as a single hardware CAS instruction. If another thread
has changed the value since we read it, the `replace` returns `false` and we retry
with the new value. The race window is eliminated.

## Files in This Example

- **`InventoryService.java`** — Buggy service with TOCTOU race + fixed CAS version
- **`InventoryServiceTest.java`** — Sequential `@Test` methods that pass + `@AsyncTest`
  that triggers RaceConditionDetector
- **`pom.xml`** — Maven dependencies (JUnit 5 + async-test-lib 1.3.0)

## Key Takeaways

1. **TOCTOU races are invisible under sequential load**: the check-then-update pattern
   is correct when only one thread runs at a time. Concurrent stress is required to
   expose the race window.
2. **Negative stock is a silent production bug**: without the detector, the service
   would accept more reservations than it has inventory for — leading to unfulfillable
   orders, customer complaints, or financial loss.
3. **Atomic operations eliminate race windows**: `ConcurrentHashMap.replace()`,
   `AtomicInteger.compareAndSet()`, and `AtomicReference.compareAndSet()` all perform
   read-check-write as a single uninterruptible step.
