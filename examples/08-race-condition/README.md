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
# RaceConditionDetector reports unsynchronized concurrent writes, and failOn = LOW
# turns that finding into a failed run
```

Expected output:

```
HIGH: Potential race conditions detected — unsynchronized writes to shared fields
allow threads to overwrite each other's changes, producing lost updates, stale reads,
and silently wrong results

Concurrent write hotspots:
  - ConcurrentHashMap@1bf5609d.WIDGET-42: 8 writes observed across 8 threads

Unsynchronized access sequences:
  - ConcurrentHashMap@1bf5609d.WIDGET-42: thread 81 write followed by thread 74 read
```

## How the Detector Works

`RaceConditionDetector` is activated by `detectRaceConditions = true`, and it is
**recording-fed**: it sees nothing the code under test does not hand it, through
`recordFieldRead` and `recordFieldWrite`. `InventoryService.observeStockAccess`
installs those two methods on either side of the race window, so the read before the
check and the write after it are reported from the threads that made them. The hooks
are no-ops by default, so the production path never touches the test library.

Recording around `reserveItem` from the test body instead would report a balanced
read and write per body execution with the check nowhere in between — which is not
the shape the detector is looking for, and is why this demonstration reported nothing
before issue #346.

With the accesses recorded, the detector analyses the timeline for:

1. **Concurrent write hotspots**: the same field written by two or more threads
2. **Unsafe access sequences**: an access on thread A followed by an access on thread B
   with at least one write and no lock in common

Accesses are paired only within one invocation round: the runner orders rounds, so a
cross-round pair has a happens-before edge and cannot race.

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
- **`pom.xml`** — Maven dependencies (JUnit 5 + async-test-lib 1.7.0-RC2)

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
