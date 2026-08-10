# Example 130 — Shared MemorySegment Race

**Detector**: `SharedMemorySegmentRaceDetector` (`DetectorType.SHARED_MEMORY_SEGMENT_RACE`, also usable standalone)

## The Problem

`Arena.ofShared()` lifts the thread confinement that `ofConfined()` imposes. It is very easy
to read that as "now it is thread-safe".

It is not. **Shared means every thread is *allowed* to access the segment.** It says nothing
about what happens when two of them touch the same bytes. Off-heap memory gets no more help
from the Java memory model than a plain field does: overlapping writes tear, and a reader can
observe half of one value and half of another.

```java
Arena arena = Arena.ofShared();
MemorySegment ring = arena.allocate(SLOTS * SLOT_BYTES);

// every feed thread:
ring.set(JAVA_LONG, 0, price);      // same offset, no partitioning, no lock
```

A market-data ring buffer is the classic host for this. The writes are small, the profiler
says the segment is hot, and someone removes the lock that was "obviously" unnecessary on a
shared arena.

## The Fix

There are exactly two correct shapes, and this example ships both.

**Partition it.** Give each writer its own slice with `asSlice(offset, length)`. Disjoint
ranges cannot race, so no lock is needed at all. This is the fast answer and the one to reach
for first.

```java
ring.set(JAVA_LONG, slotOffset(writerIndex), price);   // each writer owns one slot
```

**Guard it.** If the ranges genuinely must overlap, put every access behind the same monitor.
Two threads holding the same lock are mutually excluded.

What is never correct is the third shape: overlapping ranges, no guard, and a comment
observing that the arena is shared.

## How to Detect

```java
var d = new SharedMemorySegmentRaceDetector();
d.recordAccess(segment, "marketDataRing", 0, 8, /* write */ true,  feedA);
d.recordAccess(segment, "marketDataRing", 4, 8, /* write */ false, feedB);
assertTrue(d.analyze().hasIssues());   // bytes [4,8) overlap
```

The detector records the `[offset, offset+length)` range each thread touched and intersects
them. What it deliberately stays quiet about matters as much as what it reports:

| Situation | Verdict |
|---|---|
| Disjoint ranges | silent — `asSlice` partitioning is the correct twin |
| Read / read overlap | silent — always safe |
| Same thread overlapping itself | silent — a thread cannot race with itself |
| Overlap with a write, no guard recorded | **MEDIUM** — a prompt, not a verdict |
| Overlap with a write, threads named *different* guards | **HIGH** |
| Overlap with a write, both named the *same* guard | silent — mutually excluded |

The HIGH case is the interesting one. Two threads that both lock, but disagree about which
lock protects the range, exclude nobody — and that is a stronger signal than a range with no
lock mentioned at all, where the caller may simply not have told the detector.

Pass the guard as the last argument: `recordAccess(seg, label, off, len, write, thread, "ringLock")`.

Inside `@AsyncTest`, select it with `includes = { DetectorType.SHARED_MEMORY_SEGMENT_RACE }`.
Sibling: `CONFINED_ARENA_THREAD_ESCAPE` ([example 129](../129-confined-arena-thread-escape/)),
which covers the confinement failure this one's `ofShared()` is often used to escape.

### A note on the types in this example

`java.lang.foreign` is still a **preview API on Java 21**, the baseline every example compiles
against, so `MarketDataRingBuffer` models the segment with a plain object. The detector's
interval arithmetic is identical either way.

See [`MarketDataRingBufferTest`](src/test/java/se/deversity/asynctest/example/MarketDataRingBufferTest.java)
for the partitioned / unguarded / same-guard / conflicting-guard walkthrough.

## Running

```bash
mvn -f ../../pom.xml install -DskipTests -Dlicense.mock.mode=true
mvn -f pom.xml test
```
