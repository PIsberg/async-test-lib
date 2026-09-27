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

## Validating is not enough

A `validate()` that returns false is the normal path under contention, not a bug: it tells the
caller to re-read under the read lock. The bug is using the value anyway. The detector sees that
only where the use is recorded, with `recordValuesUsed(lock, stamp, thread)`, passing the stamp
the used value was read under:

- after a failed `validate()`, using the value read under the optimistic stamp is reported;
- re-reading under `readLock()` and passing that read-lock stamp is the fix, and is silent.

`testOptimisticReadDetector_valueUsedAfterFailedValidate_reports` and
`testOptimisticReadDetector_rereadUnderReadLockAfterFailedValidate_isSilent` show both, with a
restock forced between the read and `validate()` so the validation fails every run.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsBug` in
`InventoryServiceTest`. The `OptimisticReadValidationDetector` will report the
missing validation call. Remove it from `test_concurrent_detectsValueUsedAfterFailedValidate`
to see a value used after a failed `validate()` reported.

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
