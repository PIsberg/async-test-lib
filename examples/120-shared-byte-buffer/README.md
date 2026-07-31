# Example 120 — Shared ByteBuffer

**Detector**: `SharedByteBufferDetector` (`DetectorType.SHARED_BYTE_BUFFER`, also usable standalone)

## The Problem

A `ByteBuffer` looks like a byte array with extras. It is really a **cursor**: `position`,
`limit` and `mark` are mutable fields, and every relative `put`/`get`, `flip()`, `clear()`,
`rewind()` and `mark()`/`reset()` moves them. The javadoc is explicit:

> Buffers are not safe for use by multiple concurrent threads. If a buffer is to be used by
> more than one thread then access to the buffer should be controlled by appropriate
> synchronization.

Buffers get shared because allocation looks expensive, so they end up in a field — one
scratch buffer per service, hit by every request thread.

## The buggy pattern

```java
private final ByteBuffer sharedScratch = ByteBuffer.allocate(1024);   // ✗ one cursor...

byte[] frame(String payload) {
    byte[] body = payload.getBytes(UTF_8);
    sharedScratch.clear();               // ✗ ...reset by every thread...
    sharedScratch.putInt(body.length);
    sharedScratch.put(body);
    sharedScratch.flip();
    byte[] out = new byte[sharedScratch.remaining()];
    sharedScratch.get(out);              // ✗ ...and read by every thread
    return out;
}
```

Interleave two calls and the length header belongs to one message while the body belongs to
another — or a `flip()` landing mid-`put` throws `BufferOverflowException` /
`BufferUnderflowException`. There is no partial correctness here: the frame is wrong and
nothing says so.

## The Fix

```java
byte[] frame(String payload) {
    byte[] body = payload.getBytes(UTF_8);
    ByteBuffer scratch = ByteBuffer.allocate(Integer.BYTES + body.length);  // ✓ per call
    ...
}
```

A `ThreadLocal<ByteBuffer>` is the answer when allocation really is on the hot path — but
measure first; heap allocation is rarely what costs you next to the I/O that follows. If the
buffer must stay shared, synchronize the **whole** clear-through-get sequence, not the
individual calls: each one is atomic, the sequence is not.

**Absolute accessors are fine.** `get(int)` and `put(int, ..)` take an explicit index and do
not touch `position`/`limit`/`mark`. Many threads may use them on one buffer concurrently.
The detector records them as context and never reports them alone.

## How to Detect

```java
var d = new SharedByteBufferDetector();
d.recordPositionalAccess(buffer, "flip");     // called on each accessing thread
// ... same buffer recorded from a second thread → flagged (HIGH)
assertTrue(d.analyze().hasIssues());
```

Inside `@AsyncTest` the detector is pipeline-wired: grab it with
`AsyncTestContext.sharedByteBufferDetector()`, select it alone with
`includes = { DetectorType.SHARED_BYTE_BUFFER }`, or exclude it with `excludes`. Sibling of
`SHARED_CHARSET_CODER`, `SHARED_CHECKSUM` and `FILE_CHANNEL_POSITION_RACE`.

See [`MessageFramingServiceTest`](src/test/java/se/deversity/asynctest/example/MessageFramingServiceTest.java)
for the clean / shared / absolute-is-safe / corrupted-frame walkthrough.

## Running

```bash
mvn -f ../../pom.xml install -DskipTests -Dlicense.mock.mode=true
mvn -f pom.xml test
```
