# Example 124 — Shared Iterator

**Detector**: `SharedIteratorDetector` (`DetectorType.SHARED_ITERATOR`, also usable standalone)

## The Problem

An `Iterator` looks enough like a queue that people use it as one: build a cursor over the
work list, hand it to the pool, let each worker pull. It is not a queue. It is a **cursor**,
and `hasNext()` / `next()` are two separate reads of mutable state with a gap between them.
In that gap another worker consumes the element this one was just told exists.

Three failure modes, all of them quiet except the last:

- an element is handed to **two** workers,
- an element is handed to **none**,
- `next()` throws `NoSuchElementException` immediately after `hasNext()` returned `true`.

**A concurrent collection does not fix this.** A `CopyOnWriteArrayList` iterator is a
snapshot; a `ConcurrentLinkedQueue` iterator is weakly consistent. Both are safe to *create*
from any thread. Neither is safe to *share* — the guarantee is about the collection, not
about the cursor into it. This is the part that surprises people, and it is why the detector
flags shared iterators over concurrent collections just as loudly.

## The buggy pattern

```java
private final Iterator<String> sharedCursor = work.iterator();   // ✗ one cursor, N workers

String takeNext() {
    if (sharedCursor.hasNext()) {     // ✗ check...
        return sharedCursor.next();   // ✗ ...then act, with a gap in between
    }
    return null;
}
```

## The Fix

```java
private final Queue<String> queue = new ConcurrentLinkedQueue<>(work);

String takeNext() {
    return queue.poll();     // ✓ one atomic operation: the element, or null
}
```

`poll()` collapses check-then-act into a single operation — that is the whole difference.
When you do want iteration rather than consumption, confine the cursor: build it and drain
it on one thread. Iterators are cheap; the confinement is the point, not the allocation.

## How to Detect

```java
var d = new SharedIteratorDetector();
d.recordAccess(iterator, "next");     // called on each accessing thread
// ... same iterator recorded from a second thread → flagged (HIGH)
assertTrue(d.analyze().hasIssues());
```

The report names the kind — `Iterator`, `ListIterator` or `Spliterator` — so a
`Spliterator` leaked out of a parallel stream reads differently from a hand-rolled work
queue. Inside `@AsyncTest`, grab it with `AsyncTestContext.sharedIteratorDetector()`, select
it alone with `includes = { DetectorType.SHARED_ITERATOR }`, or drop it with `excludes`.
Related: `CONCURRENT_MODIFICATIONS` and `SYNCHRONIZED_COLLECTION_ITERATION`.

See [`WorkQueueServiceTest`](src/test/java/se/deversity/asynctest/example/WorkQueueServiceTest.java)
for the clean / shared / concurrent-collection-is-no-help / both-failure-modes / the-fix-works
walkthrough.

## Running

```bash
mvn -f ../../pom.xml install -DskipTests -Dlicense.mock.mode=true
mvn -f pom.xml test
```
