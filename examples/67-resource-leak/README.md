# Example 67 — Resource Leak

Demonstrates **ResourceLeakDetector**: a `FileProcessorService` opens
`InputStream` objects on every call but never closes them. Under concurrent
load each thread accumulates unclosed streams; on real file descriptors this
quickly exhausts the OS limit.

## The Problem

`FileProcessorService.processFile()` creates a `ByteArrayInputStream` (or, in
production, a `FileInputStream`) and stores it in a list without ever calling
`close()`. In sequential tests GC may reclaim these objects before the limit is
hit, giving false confidence. Under concurrent load with 8 threads and 50
invocations, 400 streams are opened and none are closed.

ResourceLeakDetector records every `registerResource` + `recordResourceOpened`
call and checks whether a matching `recordResourceClosed` occurs. Streams that
are never closed are reported as leaks.

## How to Reproduce

1. Remove `@Disabled` from `testProcessFile_concurrent_detectsStreamLeak`.
2. Run: `mvn test` or `./gradlew test`
3. The test fails with a **ResourceLeakDetector** report listing unclosed
   `InputStream` instances.

**Fix**: wrap the stream in a try-with-resources block so it is closed
automatically when processing is complete.
