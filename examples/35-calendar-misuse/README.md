# Example 35 — Calendar Misuse

Demonstrates **CalendarDetector**: a shared, mutable `java.util.Calendar`
instance accessed concurrently by multiple threads without synchronization.

## The Problem

`DateConverterService` holds a single `Calendar.getInstance()` as an instance
field. `Calendar.set()` and `Calendar.getTime()` are *not* thread-safe —
internally Calendar maintains an `areFieldsSet` dirty flag that is read and
written without any synchronization.

When two threads call `convertToDate()` simultaneously, one thread's `set()`
interleaves with another thread's `getTime()`, producing corrupted dates such
as a February date with a year value from a different call.

## How to Reproduce

1. Remove `@Disabled` from `testConvertToDate_concurrent_detectsSharingBug`.
2. Run: `mvn test` or `./gradlew test`
3. The test fails with a **CalendarDetector** report listing concurrent access
   events on the shared Calendar instance.

**Fix**: create a new `Calendar` inside each method call (or use the
thread-safe `java.time` API — `LocalDate.of(year, month, day)`).
