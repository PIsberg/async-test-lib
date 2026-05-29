# Example 106 — Shared Deflater / Inflater

Demonstrates **SharedDeflaterDetector** catching a stateful `Deflater` shared across threads.

Requires async-test-lib 1.7.0+.

## The Problem

`ResponseCompressor` holds a single `java.util.zip.Deflater` instance in a field and
reuses it for every `compress()` call across all threads. A `Deflater` wraps a stateful
native zlib stream and is explicitly not thread-safe: concurrent
`reset()`/`setInput()`/`deflate()` calls interleave on the same native state, corrupting
the compressed output or producing garbage bytes.

## How to Reproduce

1. Remove `@Disabled` from `test_concurrent_detectsSharedDeflater` in
   `ResponseCompressorTest`.
2. Run the test:
   ```
   mvn test
   gradle test
   ```
3. **SharedDeflaterDetector** will report multiple threads accessing the same
   `Deflater` instance.

## The Fix

Use one `Deflater` per thread (e.g. a `ThreadLocal<Deflater>`) and always call `end()`
in a `finally` block to release the native resource, or create a fresh `Deflater` per
call and `end()` it when done.
