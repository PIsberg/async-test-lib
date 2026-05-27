# Example 93 — Volatile Array

**Detector**: `VolatileArrayDetector`  
**Flag**: `detectVolatileArrayIssues = true`

## The Problem

`SharedBuffer` declares its backing store as `volatile int[] buffer`. Developers
often assume that `volatile` makes all array accesses thread-safe. It does not.
The `volatile` keyword applies only to the **array reference** (the pointer to
the object on the heap). Writes to individual elements — `buffer[i] = value` —
are plain memory stores with no visibility guarantee across threads.

Under concurrency:
- Thread A writes `buffer[3] = 42`.
- Thread B reads `buffer[3]` and may observe `0` (the default) because the JMM
  does not require Thread A's element write to be flushed before Thread B reads.
- Lost updates, stale reads, and non-deterministic results follow.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsVolatileArrayIssue`
and run the test. `VolatileArrayDetector` tracks multi-thread element accesses
and reports arrays where writes from multiple threads are observed.

## The Fix

Use `AtomicIntegerArray` (or `AtomicLongArray` / `AtomicReferenceArray<T>`) for
element-level volatile semantics, or protect all accesses with explicit
synchronisation.
