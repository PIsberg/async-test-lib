# Example 62 — Optimistic Read Validation

Demonstrates `OptimisticReadValidationDetector` catching a `StampedLock` optimistic
read that never validates the stamp before using the read data.

## The Problem

`StampedLock.tryOptimisticRead()` returns a stamp **without acquiring any lock**.
A concurrent writer can modify the data between the optimistic read and data access.
The mandatory pattern is:

```java
long stamp = lock.tryOptimisticRead();
int value = this.stock;              // read data
if (!lock.validate(stamp)) {        // REQUIRED: check for concurrent write
    // fall back to a real read lock
}
return value;
```

Skipping `validate()` means the returned data may have been partially overwritten
by a concurrent writer, producing a torn read with inconsistent field values.

`InventoryService.getStock()` calls `tryOptimisticRead()` and reads `stock` but
never calls `lock.validate(stamp)` — the read is silently unsound.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsBug` in
`InventoryServiceTest`. The `OptimisticReadValidationDetector` will report the
missing validation call.

```
@AsyncTest(threads = 8, invocations = 50, detectAll = false, detectOptimisticReadValidation = true)
void test_concurrent_detectsBug() { ... }
```

Run with Maven:
```
mvn test
```

Or with Gradle:
```
./gradlew test
```
