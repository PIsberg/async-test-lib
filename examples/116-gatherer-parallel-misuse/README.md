# Example 116 — Gatherer Parallel Misuse (JDK 24+, JEP 485)

**Detector**: `GathererConcurrencyMisuseDetector` (standalone — not pipeline-wired)
**JDK feature**: `Stream.gather(Gatherer)` — JEP 485, finalized in JDK 24, the standard
custom intermediate-operation extension point in JDK 25/26

## The Problem

A `Gatherer` has four parts: an `initializer` (per-thread private state), an `integrator`,
an optional `combiner`, and an optional `finisher`. On a **parallel** stream the runtime:

1. splits the input,
2. runs the integrator on independent state per worker thread,
3. merges those states with the **combiner**.

The dangerous combination is a **stateful** gatherer with **no combiner** (or one whose
integrator mutates shared/captured state) running on a parallel stream. The per-thread
states can't be merged, so results are silently dropped, duplicated, or non-deterministic —
and shared mutable state races across the split.

## The buggy pattern (real JDK 24+ API)

```java
Gatherer<T,?,T> runningDistinct = Gatherer.ofSequential(
    HashSet::new,
    (state, elem, downstream) -> state.add(elem) ? downstream.push(elem) : true);

list.parallelStream().gather(runningDistinct).toList();   // ✗ no combiner → can't merge
```

> `Stream.gather` / `java.util.stream.Gatherer` are not on the Java 21 baseline this
> example targets, so [`RunningDistinctService`](src/main/java/se/deversity/asynctest/example/service/RunningDistinctService.java)
> shows the same hazard with a stateful `filter(seen::add)` over a shared `HashSet` on a
> parallel stream. The detector is event-based, so it applies unchanged to a real `Gatherer`.

## The Fix

```java
// Stateful + parallel-safe → supply a combiner so per-thread states merge:
Gatherer<T,?,R> g = Gatherer.of(initializer, integrator, combiner, finisher);

// Or, when there is no safe merge, force sequential evaluation:
Gatherer<T,?,R> g = Gatherer.ofSequential(initializer, integrator, finisher);
```

Keep all mutation inside the per-thread state from the initializer; never touch
captured/shared state from the integrator.

## How to Detect

Declare the gatherer's shape, then record each integrator invocation:

```java
var d = new GathererConcurrencyMisuseDetector();
d.registerGatherer("running-distinct", /*hasCombiner*/ false, /*parallel*/ true);
// integrator: d.recordIntegrate("running-distinct", Thread.currentThread());
assertTrue(d.analyze().hasIssues());   // fires once seen on >1 thread without a combiner
```

See [`RunningDistinctServiceTest`](src/test/java/se/deversity/asynctest/example/RunningDistinctServiceTest.java)
for the safe-vs-buggy comparison.

## Running

```bash
mvn -f ../../pom.xml install -DskipTests -Dlicense.mock.mode=true
mvn -f pom.xml test
```
