# Example 40 — Concurrent Map Compute Recursion

Demonstrates **ConcurrentMapComputeRecursionDetector**: a `ConcurrentHashMap`
`computeIfAbsent()` lambda that recursively calls `computeIfAbsent()` on the
same map, causing a deadlock-like hang.

## The Problem

`GraphService.getNeighbors()` lazily builds an adjacency list using
`ConcurrentHashMap.computeIfAbsent()`. The lambda itself calls `getNeighbors()`
for a related node, which triggers another `computeIfAbsent()` on the **same
map**. In Java 8 this caused an infinite loop (known JDK bug). In Java 9+
`ConcurrentHashMap` detects the recursion and throws `IllegalStateException`,
but the graph is left in an inconsistent state.

## How to Reproduce

1. Remove `@Disabled` from `testGetNeighbors_concurrent_detectsRecursion`.
2. Run: `mvn test` or `./gradlew test`
3. The test fails with a **ConcurrentMapComputeRecursionDetector** report
   identifying the recursive compute on the same map key.

**Fix**: precompute the full graph outside any `computeIfAbsent()` lambda, or
use a plain `HashMap` with external synchronization so recursion is safe.
