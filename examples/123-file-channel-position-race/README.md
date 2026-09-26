# Example 123 — FileChannel Position Race

**Detector**: `FileChannelPositionRaceDetector` (`DetectorType.FILE_CHANNEL_POSITION_RACE`, also usable standalone)

## The Problem

`FileChannel` is documented as safe for use by multiple concurrent threads, and its javadoc
says exactly what that covers:

> Only one operation that involves the channel's position or can change its file's size may
> be in progress at any given time; attempts to initiate a second such operation while the
> first is still in progress will block until the first operation completes.

So one `read(ByteBuffer)` or one `write(ByteBuffer)` is whole: it runs at the offset the
channel's single shared cursor held when it started, and advances it. Threads that each make
self-contained calls like that lose nothing. A probe on JDK 21 and 26 (8 threads, 5,000
operations each) wrote 40,000 records with unguarded `write(buffer)` and every one landed
whole.

What the channel cannot make atomic is two calls. A thread that calls `position(n)` and then
`read(buffer)`, relying on the seek, has nothing to stop another thread's read, write or
`position` call from landing in between. The read then starts wherever the other call left
the cursor, and returns some other record. The same probe, with unguarded `position(n)` then
`read(buffer)`, read the wrong bytes 1,693 times in 40,000 on JDK 21 and 1,450 on JDK 26.

## The buggy pattern

```java
String readRecord(int index) throws IOException {
    channel.position((long) index * RECORD_SIZE);   // ✗ seek on the one shared cursor...
    ByteBuffer buffer = ByteBuffer.allocate(RECORD_SIZE);
    channel.read(buffer);                           // ✗ ...then read relying on it
    return decode(buffer);
}
```

## The Fix

```java
String readRecordAt(int index) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(RECORD_SIZE);
    long offset = (long) index * RECORD_SIZE;
    while (buffer.hasRemaining()) {
        if (channel.read(buffer, offset + buffer.position()) < 0) break;   // ✓ positional
    }
    return decode(buffer);
}
```

`read(ByteBuffer, long)` and `write(ByteBuffer, long)` take an explicit offset and never
touch the channel's position, so any number of threads may use them concurrently. Note the
loop: a positional read is not guaranteed to fill the buffer in one call.

Alternatives worth knowing: hold one lock across the seek and the I/O on every thread that
uses the channel, open one `FileChannel` per thread, or use `AsynchronousFileChannel`, which
is positional-only by design.

## How to Detect

```java
var d = new FileChannelPositionRaceDetector();
d.recordImplicitPositionAccess(channel, "position(n)");   // the seek, on each accessing thread
d.recordImplicitPositionAccess(channel, "read(buf)");     // the read relying on it
// ... the same on a second thread, with no lock common to both → flagged (HIGH)
assertTrue(d.analyze().hasIssues());
```

An operation whose name starts with `position` opens a seek, and the same thread's next
implicit-position call on that channel is the I/O relying on it. The finding needs such a
sequence and another thread's implicit-position call that no common lock keeps out of it.
Self-contained `read(buf)` / `write(buf)` calls with no seek before them are recorded but
never reported on their own, and `recordPositionalAccess` registers the channel and never
reports it. Holding the channel's own monitor, or a lock declared with
`AsyncTestContext.holdingLock(...)`, across the seek and the I/O on every thread keeps it
silent.

Inside `@AsyncTest`, grab it with `AsyncTestContext.fileChannelPositionRaceDetector()`,
select it alone with `includes = { DetectorType.FILE_CHANNEL_POSITION_RACE }`, or drop it
with `excludes`.

See [`AuditLogWriterTest`](src/test/java/se/deversity/asynctest/example/AuditLogWriterTest.java)
for the walkthrough: positional reads are clean, self-contained appends land whole and are
clean, a seek-then-read on two threads is flagged, and a call replayed between a seek and its
read returns the wrong record.

## Running

```bash
mvn -f ../../pom.xml install -DskipTests -Dlicense.mock.mode=true
mvn -f pom.xml test
```
