# Example 63 — Parallel Stream

Demonstrates `ParallelStreamDetector` catching a `parallelStream()` that feeds
results into a shared non-thread-safe `ArrayList`, causing `ConcurrentModificationException`
or silently lost updates.

## The Problem

`parallelStream()` distributes work across multiple threads from the common
ForkJoinPool. If those threads all write to the same non-thread-safe collection,
the result is undefined behavior:

```java
List<String> results = new ArrayList<>();   // NOT thread-safe

orders.parallelStream()
      .forEach(o -> results.add(process(o))); // concurrent add() → data races
```

`ArrayList.add()` is not atomic. Concurrent writes can corrupt the internal array,
lose elements, or throw `ConcurrentModificationException`. Use `Collectors.toList()`
or a `ConcurrentLinkedQueue` instead.

`OrderAggregator.processOrders()` has exactly this bug: it accumulates into a
shared `ArrayList` field from a `parallelStream().forEach()`.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsBug` in
`OrderAggregatorTest`. The `ParallelStreamDetector` will report the non-thread-safe
side effect inside the parallel stream.

```
@AsyncTest(threads = 8, invocations = 50, detectAll = false, detectParallelStreamIssues = true)
void test_concurrent_detectsBug() { ... }
```

Run with Maven:
```
mvn test
```

Or with Gradle:
```
./gradlew test
```
