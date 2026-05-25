# Example 73 — SimpleDateFormat Shared State

Demonstrates **SimpleDateFormatDetector**: an `AuditLogger` uses a single
static `SimpleDateFormat` instance. `SimpleDateFormat` is not thread-safe —
concurrent calls to `format()` or `parse()` corrupt its internal `Calendar`
state, producing wrong dates or throwing `NumberFormatException`.

## The Problem

`AuditLogger` declares `private static final SimpleDateFormat SDF`. The
`Calendar` field inside `SimpleDateFormat` is updated during every `format()`
and `parse()` call without synchronisation. When two threads call
`formatTimestamp()` simultaneously, one thread's Calendar mutation overwrites
the other's, producing an incorrect formatted string or a parse failure.

## How to Reproduce

1. Remove `@Disabled` from `testFormatTimestamp_concurrent_detectsSharedSdf`.
2. Run: `mvn test` or `./gradlew test`
3. The test fails with a **SimpleDateFormatDetector** report listing concurrent
   format/parse accesses on the shared `SimpleDateFormat` instance.

**Fix**: use `DateTimeFormatter` from `java.time` (inherently thread-safe), or
create a new `SimpleDateFormat` per call, or use a `ThreadLocal<SimpleDateFormat>`.
