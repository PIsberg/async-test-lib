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

`ConcurrentMapComputeRecursionDetector` reports this. It used to key its evidence
on map, key and thread together, so a re-entry on a *different* key was invisible
and this example demonstrated a defect its own detector could not see; that was
[#343](https://github.com/PIsberg/async-test-lib/issues/343). The rule now asks
whether the thread was already inside a compute on **this map**, whichever key it
was computing, because that is what the contract says. Nesting into a *different*
map is still not reported: a mapping function that fills some other cache is
ordinary code.

## How to Reproduce

1. Remove `@Disabled` from `testGetNeighbors_concurrent_detectsRecursion`.
2. Run: `mvn test` or `./gradlew test`
3. The test fails with a **ConcurrentMapComputeRecursionDetector** report naming
   both keys:

   ```
   CONCURRENT MAP COMPUTE RECURSION DETECTED:
     - Thread 'async-test-worker-1': compute*()/merge() on adjacency-map for key 'B'
       entered while key(s) [A] on the same map were still being computed ...
   ```

Two details that are easy to get wrong when instrumenting your own code, and that
this example exists to show:

- **The recording has to happen inside the mapping function.** That is the only
  place the nesting is visible. Recording around `getNeighbors("A")` instead sees
  one balanced start/end per call and reports nothing, however long the run is.
  `GraphService` exposes two no-op `Consumer<String>` hooks for that; the test
  wires them to the detector, and the production path never touches the library.
- **`failOn` defaults to `NONE`,** which reports a finding without failing the
  run, and this detector is `PROMPT` tier. The test therefore sets
  `failOn = FailOn.HIGH, minTrust = TrustTier.PROMPT`. Without both, the report is
  printed and the run still goes green.

**Fix**: precompute the full graph outside any `computeIfAbsent()` lambda, or
use a plain `HashMap` with external synchronization so recursion is safe.
