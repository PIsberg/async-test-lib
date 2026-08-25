# Atomicity Violation Example

This example demonstrates a **real-world production bug** found in many analytics and metrics services: **a non-atomic read-increment-write on a shared counter that silently loses increments under concurrency**.

## The Problem

The `HitCounterService` tracks per-page hit counts using a `long[]` cell per URL path. The `increment()` method performs three separate memory operations:

1. **Read** the current value from the array cell
2. **Add 1** to the value in a local variable
3. **Write** the result back to the array cell

**The Bug**: These three steps are not atomic. Two threads can interleave their reads and writes, causing one increment to be silently lost:

```
Thread A reads count = 42
Thread B reads count = 42  ← same stale value
Thread A writes count = 43
Thread B writes count = 43  ← B's increment overwrites A's!
Expected: 44, Actual: 43
```

## Why It Happens

```java
// BUGGY CODE (HitCounterService.java):
public void increment(String page) {
    long[] cell = counts.computeIfAbsent(page, k -> new long[]{0L});
    cell[0] = cell[0] + 1;   // ❌ Three separate steps: read, compute, write
}
```

`volatile` on a field guarantees visibility (every thread sees the latest write) but does **not** make a read-modify-write compound operation atomic. The same is true here — even if `cell[0]` were declared `volatile`, two threads reading the same value before either writes would still collide.

## How to Reproduce

### 1. Run with @Test (PASSES - false confidence)

```bash
cd examples/22-atomicity-violation
mvn clean test
# Tests pass: counts are exact in single-threaded mode
```

Single-threaded increments are fully sequential — no concurrent reader can observe an intermediate state.

### 2. Run with @AsyncTest (DETECTS the atomicity violation)

Remove the `@Disabled` annotation from `testIncrement_concurrent_detectsAtomicityViolation()`:

```java
@AsyncTest(threads = 10, invocations = 100, detectAll = false, detectAtomicityViolations = true)
void testIncrement_concurrent_detectsAtomicityViolation() { ... }
```

```bash
mvn clean test
# AtomicityValidator reports: "count: mixed read/write compound access across N threads"
# AtomicityValidator reports: "count: state changed between check/use windows on N threads"
```

With 10 threads all calling `increment("/home")` simultaneously:
- `AtomicityValidator` tracks every `recordFieldAccess("count", ...)` call
- When it detects mixed reads and writes from different threads on the same field name within one
  invocation round, it flags a TOCTOU window
- `failOn = FailOn.LOW` turns that finding into a failed run

## How the Detector Is Fed

`AtomicityValidator` is **recording-fed**: it sees nothing the code under test does not hand it.
`HitCounterService.observeCountAccess` installs two `LongConsumer` hooks *inside* `increment()`,
one at the read and one at the write, so the value actually stored is what reaches the validator.
The hooks default to no-ops, so the production path never touches the test library.

Two details this example was getting wrong before issue #346:

- The validator has to be **the one the run owns**, from `AsyncTestContext.atomicityValidator()`.
  A locally constructed `new AtomicityValidator()` is never read by the library, so `failOn` has
  nothing to gate on and enabling the demonstration leaves it green.
- Recording *around* `increment()` would report two extra reads through `getCount()` and never the
  value that was stored, which is a different access pattern from the one the bug is made of.

## The Root Cause

Under concurrent stress:
1. 10 threads simultaneously read the same `cell[0]`
2. Multiple threads read the same value before any writes land
3. Each computes `current + 1` independently, then writes the same result
4. `AtomicityValidator` observes that the same field was read by multiple threads, then written by
   multiple threads, inside one harness-ordered round

## The Solution

Replace the `long[]` cell with an `AtomicLong` and call `incrementAndGet()` — a single atomic compare-and-swap that cannot be interleaved:

```java
// FIXED CODE — use AtomicLong:
private final ConcurrentHashMap<String, AtomicLong> counts = new ConcurrentHashMap<>();

public void increment(String page) {
    counts.computeIfAbsent(page, k -> new AtomicLong(0L))
          .incrementAndGet();  // ✅ Single atomic CAS — no race window
}
```

For high-write-throughput scenarios, `LongAdder` is even better:

```java
// HIGHEST THROUGHPUT — LongAdder with per-stripe accumulation:
private final ConcurrentHashMap<String, LongAdder> counts = new ConcurrentHashMap<>();

public void increment(String page) {
    counts.computeIfAbsent(page, k -> new LongAdder()).increment();  // ✅
}

public long getCount(String page) {
    LongAdder adder = counts.get(page);
    return adder == null ? 0L : adder.sum();
}
```

## Files in This Example

- **`HitCounterService.java`** — Buggy counter with non-atomic read-modify-write
- **`HitCounterServiceTest.java`** — Tests that demonstrate the problem
  - `testIncrement_singleThread_exactCount()` — Passes with @Test
  - `testIncrement_concurrent_detectsAtomicityViolation()` — Detects race with @AsyncTest
  - `testIncrement_fixedWithAtomicLong_singleThread()` — Shows the correct pattern
- **`pom.xml`** — Maven dependencies (JUnit 5 + async-test-lib)

## Key Takeaways

1. **@Test gives false confidence**: Sequential increments are always exact
2. **@AsyncTest finds the race**: 10 threads × 100 invocations drives interleaved read-modify-writes
3. **volatile is not enough**: visibility ≠ atomicity — compound operations need atomic primitives or synchronization
4. **Use atomic primitives**: `AtomicLong.incrementAndGet()` is a single unbreakable CAS
5. **LongAdder for high throughput**: Reduces CAS contention by accumulating into per-thread cells
