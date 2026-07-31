# Example 121 — Shared CharsetEncoder / CharsetDecoder

**Detector**: `SharedCharsetCoderDetector` (`DetectorType.SHARED_CHARSET_CODER`, also usable standalone)

## The Problem

`Charset` is immutable and thread-safe, so it is easy to assume its coders are too. They are
not. `CharsetEncoder` and `CharsetDecoder` are **state machines**: each tracks whether it is
mid-surrogate-pair, whether end-of-input has been signalled, and whether `flush()` has run.
`reset()` exists precisely because that state survives between calls.

The javadoc documents a per-coder protocol:

> Reset the encoder via the `reset` method [...] invoke the `encode` method zero or more
> times [...] invoke the `encode` method one final time with `endOfInput` set to `true`;
> and then invoke the `flush` method.

That sequence belongs to the coder, not to the caller. Two threads running it concurrently
on one instance interleave the stages: output is garbled, or `IllegalStateException` fires
for a transition neither thread requested.

## The buggy pattern

```java
private final CharsetEncoder sharedEncoder = UTF_8.newEncoder()   // ✗ cached "for performance"
        .onMalformedInput(REPLACE)
        .onUnmappableCharacter(REPLACE);

byte[] encode(String text) throws CharacterCodingException {
    sharedEncoder.reset();                                        // ✗ another thread's reset
    return toArray(sharedEncoder.encode(CharBuffer.wrap(text)));  //   lands mid-encode
}
```

## The Fix

```java
byte[] encode(String text) throws CharacterCodingException {
    CharsetEncoder encoder = UTF_8.newEncoder()   // ✓ per call
            .onMalformedInput(REPLACE)
            .onUnmappableCharacter(REPLACE);
    return toArray(encoder.encode(CharBuffer.wrap(text)));
}
```

`newEncoder()` is a small allocation — cache it in a `ThreadLocal` only if a profiler says
so. And if you do not need the coder's malformed-input / unmappable-character actions, skip
the coder entirely: `String.getBytes(charset)` and `new String(bytes, charset)` allocate
their own internally and are thread-safe by construction.

## How to Detect

```java
var d = new SharedCharsetCoderDetector();
d.recordAccess(encoder, "encode", Thread.currentThread());
// ... same coder recorded from a second thread → flagged (HIGH)
assertTrue(d.analyze().hasIssues());
```

Encoders and decoders are tracked as separate instances, so a report names which direction
broke. Inside `@AsyncTest`, grab the detector with
`AsyncTestContext.sharedCharsetCoderDetector()`, select it alone with
`includes = { DetectorType.SHARED_CHARSET_CODER }`, or drop it with `excludes`. Sibling of
`SHARED_BYTE_BUFFER`, `SHARED_CHECKSUM` and `SHARED_ITERATOR`.

See [`TextEncodingServiceTest`](src/test/java/se/deversity/asynctest/example/TextEncodingServiceTest.java)
for the clean / shared-encoder / shared-decoder walkthrough — including the part that makes
this bug survive review: the shared version round-trips perfectly on one thread.

## Running

```bash
mvn -f ../../pom.xml install -DskipTests -Dlicense.mock.mode=true
mvn -f pom.xml test
```
