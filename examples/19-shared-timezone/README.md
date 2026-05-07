# Shared TimeZone Example

This example demonstrates the **SharedTimeZoneDetector** (Phase 12, `async-test-lib` 0.10.0).

## The Problem

`SchedulingService.getOffsetHours()` calls `setRawOffset()` on a shared `TimeZone` instance.
`TimeZone` is a mutable class — `setRawOffset()` modifies the object in place and is not
thread-safe. When multiple threads call `getOffsetHours()` concurrently:

1. Thread A writes offset +5h
2. Thread B writes offset −3h
3. Thread A reads back −3h (B's value)

The resulting offset arithmetic is silently wrong, with no exception thrown.

## Why Sequential Tests Miss This Bug

```java
@Test
void part1_getOffset_singleThread() {
    SchedulingService svc = new SchedulingService();
    assertEquals(5, svc.getOffsetHours(5 * 3_600_000)); // ✅ Passes
}
```

Sequential execution means the write and read are adjacent — no other write can interleave.

## How `@AsyncTest` Exposes the Bug

```java
@AsyncTest(threads = 4, invocations = 3, detectSharedTimeZone = true, timeoutMs = 5000)
void part2_detectSharedTimeZone() {
    var d = AsyncTestContext.sharedTimeZoneDetector();
    d.recordMutation(sharedTz, "setRawOffset", Thread.currentThread());
    sharedTz.setRawOffset(5 * 3_600_000); // mutating shared instance — flagged!
}
```

The detector reports:

```
SHARED TIMEZONE MUTATION DETECTED:
  - TimeZone instance mutated from 4 threads via 'setRawOffset'
    Concurrent mutations corrupt date/time arithmetic silently.
    Fix: create a new TimeZone per operation, or switch to the immutable java.time.ZoneId API.
```

## Running the Example

```bash
cd examples/19-shared-timezone
mvn clean test
# ✅ Tests pass — @Test gives false confidence

# Upgrade to 0.10.0 and enable @AsyncTest (see comments in the test file)
```

## The Fix

Option 1 — new instance per call:
```java
int getOffsetHoursFixed(int offsetMillis) {
    TimeZone tz = TimeZone.getTimeZone("UTC"); // ✅ Fresh instance
    tz.setRawOffset(offsetMillis);
    return tz.getRawOffset() / 3_600_000;
}
```

Option 2 — use the immutable `java.time` API:
```java
ZoneOffset offset = ZoneOffset.ofTotalSeconds(offsetMillis / 1000); // ✅ Immutable
```

## Severity

| Failure mode | Symptom |
|-------------|---------|
| Wrong offset reads | Scheduled jobs fire at wrong times — silent correctness bug |
| Non-reproducible test failures | Results depend on thread interleaving order — flaky CI |
