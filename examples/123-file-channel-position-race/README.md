# Example 123 — FileChannel Position Race

**Detector**: `FileChannelPositionRaceDetector` (`DetectorType.FILE_CHANNEL_POSITION_RACE`, also usable standalone)

## The Problem

`FileChannel` is documented as safe for use by multiple concurrent threads — and that
sentence has a second half people stop reading before:

> Where the position is affected, [operations] are not safe for use by multiple concurrent
> threads.

Both halves are true, and they are about different things. The **channel object** survives
concurrent use. The **file contents** do not. A channel carries one implicit position, and
`read(ByteBuffer)` / `write(ByteBuffer)` both consult it and advance it. Every thread using
those overloads is sharing one cursor.

So two threads appending audit records interleave at offsets neither of them chose: one
record lands on top of another, or is split across a third's bytes. The write succeeds. The
channel is fine. The file is wrong.

## The buggy pattern

```java
void append(String record) throws IOException {
    byte[] bytes = (record + "\n").getBytes(UTF_8);
    channel.write(ByteBuffer.wrap(bytes));      // ✗ implicit position — one shared cursor
}
```

## The Fix

```java
private final AtomicLong nextOffset = new AtomicLong();

void append(String record) throws IOException {
    byte[] bytes = (record + "\n").getBytes(UTF_8);
    long offset = nextOffset.getAndAdd(bytes.length);   // ✓ reserve the range atomically
    ByteBuffer buffer = ByteBuffer.wrap(bytes);
    while (buffer.hasRemaining()) {
        offset += channel.write(buffer, offset);        // ✓ positional — no shared cursor
    }
}
```

`write(ByteBuffer, long)` and `read(ByteBuffer, long)` take an explicit offset and never
touch the channel's position, so any number of threads may use them concurrently. Note the
loop: a positional write is not guaranteed to write the whole buffer in one call.

Alternatives worth knowing: `StandardOpenOption.APPEND` makes each write atomic with respect
to the file's end on most platforms, and `AsynchronousFileChannel` is positional-only by
design.

## How to Detect

```java
var d = new FileChannelPositionRaceDetector();
d.recordImplicitPositionAccess(channel, "write(buf)");   // on each accessing thread
// ... same channel recorded from a second thread → flagged (HIGH)
assertTrue(d.analyze().hasIssues());
```

`recordPositionalAccess` registers the channel but never reports it, so a service that only
uses the positional overloads stays clean no matter how many threads touch it — the detector
distinguishes the two overload families rather than flagging sharing as such.

Inside `@AsyncTest`, grab it with `AsyncTestContext.fileChannelPositionRaceDetector()`,
select it alone with `includes = { DetectorType.FILE_CHANNEL_POSITION_RACE }`, or drop it
with `excludes`.

See [`AuditLogWriterTest`](src/test/java/se/deversity/asynctest/example/AuditLogWriterTest.java)
for the positional-is-clean / implicit-is-flagged / records-overwrite-each-other walkthrough.

## Running

```bash
mvn -f ../../pom.xml install -DskipTests -Dlicense.mock.mode=true
mvn -f pom.xml test
```
