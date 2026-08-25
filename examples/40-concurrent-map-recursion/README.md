# Example 40 — Concurrent Map Compute Recursion

Demonstrates **ConcurrentMapComputeRecursionDetector**: a `ConcurrentHashMap`
`computeIfAbsent()` lambda that recursively calls `computeIfAbsent()` on the
same map, causing a deadlock-like hang.

## The Problem

`GraphService.getNeighbors()` lazily builds an adjacency list using
`ConcurrentHashMap.computeIfAbsent()`. The lambda itself calls `getNeighbors()`
for a related node, which triggers another `computeIfAbsent()` on the **same
map**, for a different key. `ConcurrentHashMap`'s javadoc forbids that outright:
the mapping function must not modify the map during computation.

What it actually does was measured on JDK 26, over 200 fresh maps: the nested
`computeIfAbsent` ran and returned normally 198 times, and threw
`IllegalStateException("Recursive update")` twice, on the runs where the two keys
happened to land in the same bin. So the usual outcome is not a thrown exception
but a silent one: the adjacency list is built out of order and the graph is left
inconsistent, with nothing in the log to say so.

> **Note.** `ConcurrentMapComputeRecursionDetector` keys its evidence on map, key
> and thread together, so it does not report a re-entry on a *different* key, which
> is the shape this example uses. Closing that gap is tracked in
> [#343](https://github.com/PIsberg/async-test-lib/issues/343).

## How to Reproduce

1. Remove `@Disabled` from `testGetNeighbors_concurrent_detectsRecursion`.
2. Run: `mvn test` or `./gradlew test`
3. The run shows the graph coming out inconsistent. It does **not** currently
   produce a **ConcurrentMapComputeRecursionDetector** report: the re-entry here
   is on a different key, and the detector keys on map, key and thread together.
   That is the gap [#343](https://github.com/PIsberg/async-test-lib/issues/343)
   tracks, and it is why this test is still disabled.

**Fix**: precompute the full graph outside any `computeIfAbsent()` lambda, or
use a plain `HashMap` with external synchronization so recursion is safe.
