# Example 30 — False Sharing

## The Problem

`PerformanceCounters` holds three `volatile long` fields written by every
request-handling thread: `requestCount`, `errorCount`, and `latencySum`.
Each `long` is 8 bytes; an object header is 16 bytes. The three fields are
laid out at offsets ~16, ~24, and ~32 — all within the same 64-byte CPU
cache line.

When Thread A writes `requestCount` and Thread B writes `errorCount`:

```
Cache line: [header 16B][requestCount 8B][errorCount 8B][latencySum 8B]...
                              ^Thread A         ^Thread B
```

Both writes dirty the **same cache line**. The CPU cache coherence protocol
must invalidate and re-fetch that line on every other core on every write,
even though the threads are touching completely different fields. This is
**false sharing** — they share a cache line but not actual data.

## Why This Happens

CPUs transfer memory in fixed-size **cache lines** (typically 64 bytes).
A write to any byte within a cache line marks the entire line dirty and
forces all other cores that hold a copy to invalidate theirs. Throughput
can degrade 5–10x under high concurrency because the cache line bounces
between cores instead of being held locally.

## How to Reproduce

1. Open `PerformanceCountersTest`.
2. Remove `@Disabled` from `testRecordRequest_concurrent_detectsFalseSharing`.
3. Run the test.

`FalseSharingDetector` will report something like:

```
POTENTIAL FALSE SHARING DETECTED:

Fields in same cache line accessed by different threads:
  - requestCount (accesses: 54) <-> errorCount (accesses: 51) [distance: 8 bytes]
  - requestCount (accesses: 54) <-> latencySum (accesses: 49) [distance: 16 bytes]

High-contention fields accessed by multiple threads:
  - se.deversity.asynctest.example.service.PerformanceCounters.requestCount
```

## The Solution

**Option A — `LongAdder` (recommended for counters):**

```java
private final LongAdder requestCount = new LongAdder();
private final LongAdder errorCount   = new LongAdder();
private final LongAdder latencySum   = new LongAdder();
```

`LongAdder` maintains an internal array of cells, each on its own cache line.
Each thread typically updates its own cell, eliminating coherence traffic.

**Option B — `@Contended` (for general fields):**

```java
@jdk.internal.vm.annotation.Contended
public volatile long requestCount;
@jdk.internal.vm.annotation.Contended
public volatile long errorCount;
@jdk.internal.vm.annotation.Contended
public volatile long latencySum;
```

Run with `-XX:+EnableContendedPadding` (Java 8–10) or rely on the default
from Java 11 onward. This tells the JVM to pad each annotated field to its
own cache line.

**Option C — manual padding:**

```java
public volatile long requestCount;
private long p1, p2, p3, p4, p5, p6, p7; // 56 bytes of padding
public volatile long errorCount;
private long q1, q2, q3, q4, q5, q6, q7;
public volatile long latencySum;
```

## Key Takeaways

- False sharing is a **performance bug**, not a correctness bug — the code
  produces correct results but throughput degrades silently.
- It is nearly impossible to spot in a code review or profile without
  hardware performance counters or a detector like `FalseSharingDetector`.
- The fix is always to ensure that fields written by different threads land
  on different cache lines — via `LongAdder`, `@Contended`, or manual padding.
- `FalseSharingDetector` identifies pairs of fields accessed by distinct
  thread sets whose estimated memory offsets fall within 64 bytes of each other.
