# Example 03 — Shared Non-Thread-Safe Collection

**Detector**: `SharedCollectionDetector` (`DetectorType.SHARED_COLLECTIONS`)

## The Problem

An event aggregator collects entries into an `ArrayList` and counts them in a `HashMap`.
Neither is synchronized, and both are written by every thread that records an event.

`ArrayList.add()` is three steps — read `size`, store into the backing array, write `size+1`
— so two threads can write the same slot, and a resize racing an add can drop entries
outright. `HashMap.put()` is worse: a concurrent resize can corrupt the bucket table, which
in older JDKs produced the famous infinite loop in `get()` and in modern ones loses entries
silently. And reading while another thread writes throws `ConcurrentModificationException`
from the iterator, which at least has the courtesy to be loud.

None of it throws at the point of the bug. You get a count that is a bit low.

## The buggy pattern

```java
private final List<String> eventLog = new ArrayList<>();          // ✗
private final Map<String, Integer> eventCounts = new HashMap<>(); // ✗

void recordEvent(String source, String event) {
    eventLog.add(source + ": " + event);            // ✗ lost entries, duplicated slots
    eventCounts.merge(source, 1, Integer::sum);     // ✗ corrupt table on resize
}
```

## The Fix

```java
private final List<String> eventLog = new CopyOnWriteArrayList<>();      // read-heavy
// or Collections.synchronizedList(new ArrayList<>()) — see the caveat below
private final ConcurrentMap<String, Integer> eventCounts = new ConcurrentHashMap<>();

void recordEvent(String source, String event) {
    eventLog.add(source + ": " + event);
    eventCounts.merge(source, 1, Integer::sum);     // ✓ merge IS atomic on ConcurrentMap
}
```

Pick by access shape: `ConcurrentHashMap` for maps, `CopyOnWriteArrayList` when reads
dominate writes, `ConcurrentLinkedQueue` when you are really queueing. For a write-heavy
collector, the fastest answer is often to collect per-thread and merge once at the end —
no sharing, no synchronization.

**One caveat on `Collections.synchronizedList`**: it synchronizes each *call*, not each
*sequence*. Iterating one still needs an explicit `synchronized (list)` block around the
loop, which is its own detector — see [example 80](../80-synchronized-collection-iteration/).

## Why `@Test` Misses It

Sequentially, `ArrayList` and `HashMap` are correct and fast. Nothing about the code looks
wrong, because nothing about the code *is* wrong until a second thread arrives.

`@AsyncTest` puts N threads on `recordEvent` behind a barrier; `SharedCollectionDetector`
reports the unsynchronized collection reached from more than one thread, and the assertion
on the final count usually fails too.

See [`EventAggregatorServiceTest`](src/test/java/se/deversity/asynctest/example/EventAggregatorServiceTest.java).

## Running

```bash
mvn -f ../../pom.xml install -DskipTests -Dlicense.mock.mode=true
mvn -f pom.xml test
```
