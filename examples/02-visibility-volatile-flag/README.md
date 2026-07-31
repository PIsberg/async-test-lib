# Example 02 — Visibility / Missing `volatile` Flag

**Detector**: `VisibilityMonitor` (`DetectorType.VISIBILITY`)

## The Problem

A background worker loops until a `running` flag goes false. Another thread sets it. On one
thread this is obviously correct, and that is the whole trouble: without `volatile` there is
**no happens-before edge** between the write and the read, so the JMM permits the worker to
never observe it. In practice the JIT hoists the field read out of the loop — it is allowed
to assume nothing else writes it — and the loop becomes `while (true)`.

What you see in production: `shutdown()` returns, the process does not exit, connections and
file handles are never released, and a graceful restart hangs until something kills it.

## The buggy pattern

```java
private boolean running = true;      // ✗ no volatile

void processLoop() {
    while (running) {                // ✗ may be hoisted out of the loop
        processNext();
    }
}

void shutdown() {
    running = false;                 // ✗ may never become visible to the worker
}
```

## The Fix

```java
private volatile boolean running = true;   // ✓ write happens-before every later read
```

`volatile` on the flag is enough here: a write is flushed and a read is fetched, and the
compiler may not reorder across it. `AtomicBoolean` works too and is what you want when the
flag is also read-modify-written. For anything more than a flag, prefer an executor's own
shutdown protocol over a hand-rolled one.

## Why `@Test` Misses It

One thread writes and reads the field, so there is no cache-coherence question and nothing
for the JIT to optimise away. The test passes and keeps passing.

`@AsyncTest` runs the worker and the shutdown on different threads, colliding on a barrier —
and `VisibilityMonitor` reports the non-volatile field that two threads touched. Two
secondary detectors usually fire with it: `BusyWaitDetector` (the worker spinning) and
`ThreadLeakDetector` (the worker that never terminates).

See [`TaskProcessorServiceTest`](src/test/java/se/deversity/asynctest/example/TaskProcessorServiceTest.java).

## Running

```bash
mvn -f ../../pom.xml install -DskipTests -Dlicense.mock.mode=true
mvn -f pom.xml test
```
