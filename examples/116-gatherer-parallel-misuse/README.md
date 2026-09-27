# Example 116 — Gatherer Parallel Misuse (JDK 24+, JEP 485)

**Detector**: `GathererConcurrencyMisuseDetector` (standalone — not pipeline-wired)
**JDK feature**: `Stream.gather(Gatherer)` — JEP 485, finalized in JDK 24, the standard
custom intermediate-operation extension point in JDK 25/26

## The Problem

A `Gatherer` has four parts: an `initializer` (per-segment private state), an `integrator`,
an optional `combiner`, and an optional `finisher`. On a **parallel** stream, a gatherer with a
**combiner** is run like this:

1. the runtime splits the input,
2. runs the integrator on independent state per segment,
3. merges those states with the combiner.

The dangerous combination is a gatherer whose segments do not keep to their own state: an
initializer that returns a shared object, or an integrator that mutates captured state. Then
the segments race across the split, and results are silently dropped, duplicated, or
non-deterministic.

A gatherer with **no combiner** is not that bug. The JDK evaluates it sequentially even on a
parallel stream: one state, integrated segment by segment in encounter order and handed
between threads, so nothing is lost. What it costs is the parallelism, and the detector
reports that at `LOW`.

## The buggy pattern (real JDK 24+ API)

```java
Set<T> seen = new HashSet<>();                         // one set, captured
Gatherer<T,?,T> runningDistinct = Gatherer.of(
    () -> seen,                                        // ✗ every segment gets the same set
    (state, elem, downstream) -> state.add(elem) ? downstream.push(elem) : true,
    (left, right) -> left);

list.parallelStream().gather(runningDistinct).toList();   // segments race on seen
```

> `Stream.gather` / `java.util.stream.Gatherer` are not on the Java 21 baseline this
> example targets, so [`RunningDistinctService`](src/main/java/se/deversity/asynctest/example/service/RunningDistinctService.java)
> shows the same hazard with a stateful `filter(seen::add)` over a shared `HashSet` on a
> parallel stream. The detector is event-based, so it applies unchanged to a real `Gatherer`.

## The Fix

```java
// A fresh state per segment, merged by a combiner:
Gatherer<T,?,R> g = Gatherer.of(HashSet::new, integrator, combiner, finisher);

// Or, when there is no safe merge, evaluate sequentially (the JDK does, on any stream):
Gatherer<T,?,R> g = Gatherer.ofSequential(HashSet::new, integrator, finisher);
```

Keep all mutation inside the per-thread state from the initializer; never touch
captured/shared state from the integrator.

## How to Detect

Declare the gatherer's shape, then record each integrator invocation:

```java
var d = new GathererConcurrencyMisuseDetector();
d.registerGatherer("running-distinct", /*hasCombiner*/ false, /*parallel*/ true);
// integrator: d.recordIntegrate("running-distinct", Thread.currentThread());
assertTrue(d.analyze().hasIssues());   // LOW once seen on >1 thread without a combiner
```

See [`RunningDistinctServiceTest`](src/test/java/se/deversity/asynctest/example/RunningDistinctServiceTest.java)
for the safe-vs-buggy comparison.

## Running

```bash
mvn -f ../../pom.xml install -DskipTests -Dlicense.mock.mode=true
mvn -f pom.xml test
```
