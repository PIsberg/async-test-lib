# Example 71 — Shared Formatter

Demonstrates **SharedFormatterDetector**: a `LogFormatterService` holds a
single `java.util.Formatter` instance shared across all threads.
`Formatter.format()` appends to an internal `StringBuilder` that is not
thread-safe. Concurrent calls produce interleaved, garbled log entries.

## The Problem

`LogFormatterService` constructs a `Formatter` backed by a single `StringBuilder`
at instantiation time. Every call to `formatEntry()` appends to the same buffer.
When two threads call `format()` concurrently they both append to the shared
buffer at the same time, interleaving their output. The `toString()` call then
reads a mixture of both entries.

## How to Reproduce

1. Remove `@Disabled` from `testFormatEntry_concurrent_detectsSharedFormatter`.
2. Run: `mvn test` or `./gradlew test`
3. The test fails with a **SharedFormatterDetector** report showing concurrent
   access from multiple threads on the same `Formatter` instance.

**Fix**: create a new `Formatter` (and backing `StringBuilder`) per call, or
use `String.format()` which is inherently stateless.
