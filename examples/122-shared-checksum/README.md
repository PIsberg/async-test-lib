# Example 122 — Shared Checksum

**Detector**: `SharedChecksumDetector` (`DetectorType.SHARED_CHECKSUM`, also usable standalone)

## The Problem

A `java.util.zip.Checksum` — `CRC32`, `CRC32C`, `Adler32` — is an **accumulator**.
`update()` folds bytes into a running value, `getValue()` reads it, `reset()` clears it.
None of the three is synchronized, and the interface is stateful by definition: asking
whether `CRC32` is thread-safe is asking whether a counter is thread-safe. For
read-modify-write, it is not.

What makes this one worse than most shared-object bugs is that **it never throws**. Two
threads updating one accumulator produce a checksum over the concatenation of both payloads.
That is a perfectly well-formed CRC — stable, reproducible, and matching neither payload.
You find out when a downstream integrity check rejects a file that was never damaged, and
the investigation starts by looking at storage.

## The buggy pattern

```java
private final CRC32 sharedChecksum = new CRC32();     // ✗ one accumulator...

long checksum(String payload) {
    sharedChecksum.reset();                            // ✗ ...cleared by every thread...
    sharedChecksum.update(payload.getBytes(UTF_8));    // ✗ ...fed by every thread...
    return sharedChecksum.getValue();                  // ✗ ...and read by every thread
}
```

## The Fix

```java
long checksum(String payload) {
    CRC32 local = new CRC32();                         // ✓ per call
    local.update(payload.getBytes(UTF_8));
    return local.getValue();
}
```

A `CRC32` is a `long` and a lookup table. There is nothing to pool. If an accumulator genuinely
must be long-lived and shared — a running checksum over a stream several threads append to —
then the whole `reset`/`update`/`getValue` sequence needs one lock, not three atomic calls.

## How to Detect

```java
var d = new SharedChecksumDetector();
d.recordAccess(crc32, "update", Thread.currentThread());
// ... same instance recorded from a second thread → flagged (HIGH)
assertTrue(d.analyze().hasIssues());
```

Inside `@AsyncTest`, grab it with `AsyncTestContext.sharedChecksumDetector()`, select it
alone with `includes = { DetectorType.SHARED_CHECKSUM }`, or drop it with `excludes`.
Sibling of `SHARED_BYTE_BUFFER`, `SHARED_CHARSET_CODER` and `SHARED_MESSAGE_DIGEST` — the
same accumulate-then-read shape appears in `MessageDigest`, `Mac` and `Deflater`.

See [`PayloadIntegrityServiceTest`](src/test/java/se/deversity/asynctest/example/PayloadIntegrityServiceTest.java)
for the clean / shared / wrong-but-reproducible-answer walkthrough.

## Running

```bash
mvn -f ../../pom.xml install -DskipTests -Dlicense.mock.mode=true
mvn -f pom.xml test
```
