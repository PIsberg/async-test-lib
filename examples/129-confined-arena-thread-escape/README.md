# Example 129 — Confined Arena Thread Escape

**Detector**: `ConfinedArenaThreadEscapeDetector` (`DetectorType.CONFINED_ARENA_THREAD_ESCAPE`, also usable standalone)

## The Problem

`Arena.ofConfined()` makes exactly one promise, and the javadoc is blunt about it:

> Returns a new arena that has an unbounded lifetime, and is confined to the current thread.

Confined means every access to a segment the arena allocated must happen on the thread that
opened it. Another thread gets a `WrongThreadException` at the first access — a hard failure,
not a corrupted read.

That is the *merciful* outcome. The one that costs a weekend is the closing brace:

```java
try (Arena arena = Arena.ofConfined()) {
    MemorySegment buffer = arena.allocate(1024);
    writeHeader(buffer);
    return pool.submit(() -> checksum(buffer));   // the owner does not wait
}                                                  // memory freed here
```

A confined arena frees its memory when the try-with-resources ends, and it does not wait for
work handed to a pool. If the submitted task has not run yet, the segment it captured now
points at memory the allocator has reclaimed. Best case that is an `IllegalStateException`;
worst case the JVM reads whatever moved in.

## The buggy pattern

Two distinct failures live in the same four lines:

| Failure | When it fires | Symptom |
|---|---|---|
| Confinement violation | worker touches the segment at all | `WrongThreadException` |
| Use-after-close | worker touches it after the block exits | `IllegalStateException`, or worse |

The second one is timing-dependent, which is why it passes in a test and fails under load.

## The Fix

Pick the arena that matches the lifetime.

- **Keep the work on the owning thread.** If the buffer is scratch space for one parse, the
  thread that allocated it should be the thread that uses it. Nothing to synchronise.
- **Use `Arena.ofShared()`** when the buffer genuinely has to outlive the block. It permits
  access from any thread and stays alive until explicitly closed. Note that shared removes the
  *confinement*, not the *races* — see [example 130](../130-shared-memory-segment-race/) for
  what you take on instead.

If the buffer must outlive the enclosing block, it never belonged in a confined arena.

## How to Detect

```java
var d = new ConfinedArenaThreadEscapeDetector();
d.recordArena(arena, "packet-scratch", owner);
d.recordAllocation(segment, arena, "packet-scratch", 1024);
d.recordAccess(segment, "packet-scratch", intruder, true);   // → flagged
assertTrue(d.analyze().hasIssues());
```

`recordClose(arena, owner)` marks the try-with-resources exit; an access recorded after that
is reported as a use-after-close rather than a confinement violation, because the fix differs.

Where the JDK can answer, the detector asks it: `MemorySegment.isAccessibleBy` and
`MemorySegment.scope().isAlive()` are consulted reflectively, and the recorded owner is only
the fallback for when they cannot.

Inside `@AsyncTest`, select it with `includes = { DetectorType.CONFINED_ARENA_THREAD_ESCAPE }`.
Sibling: `SHARED_MEMORY_SEGMENT_RACE`.

### A note on the types in this example

`java.lang.foreign` is still a **preview API on Java 21**, the baseline every example here
compiles against, so `PacketParser` models the arena and segment with plain objects. The
detector keys on object identity and the accessing thread, so the pattern it observes is
identical to the real thing — this is also how the library's own unit tests drive it.

See [`PacketParserTest`](src/test/java/se/deversity/asynctest/example/PacketParserTest.java)
for the owner-thread / cross-thread / use-after-close walkthrough.

## Running

```bash
mvn -f ../../pom.xml install -DskipTests -Dlicense.mock.mode=true
mvn -f pom.xml test
```
