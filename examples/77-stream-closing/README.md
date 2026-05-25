# Example 77 — Stream Closing

**Detector**: `StreamClosingDetector`  
**Flag**: `detectStreamClosing = true`

## The Problem

`DataPipelineService.openPipeline()` creates a `Stream` (simulating
`Files.lines()` or another I/O-backed stream) and tracks it internally, but
never calls `.close()` on it. Under concurrent load each thread opens a new
stream per invocation without releasing the previous ones, leaking file
descriptors or other I/O resources until the OS limit is reached and further
`open()` calls fail with `Too many open files`.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsUnclosedStream`
and run the test. `StreamClosingDetector` records every `recordStreamOpened()`
call and matches it against `recordStreamClosed()` calls. Streams that were
opened but never closed appear in the analysis report.

## The Fix

Always open I/O-backed streams inside a try-with-resources block:

```java
try (Stream<String> lines = Files.lines(path)) {
    return lines.filter(...).collect(toList());
}
```
