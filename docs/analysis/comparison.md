# Comparison with Other Tools

> Extracted from the former `docs/README.md`. See [INDEX.md](../INDEX.md) for the full
> documentation map. Unlike the rest of `docs/analysis/`, this page is a current claim, and
> `DetectorCatalogCoverageTest` checks its detector counts like any guide's.

An earlier version of this table scored ThreadSanitizer "no" on race detection, deadlock
detection and lock-order validation. That was inverted: happens-before race detection is
exactly what ThreadSanitizer does, and it reports lock-order inversions too. Its limitation
for this library's audience is different in kind: it instruments C, C++, Go and Swift, not
JVM bytecode, so it is not available to a Java test suite at all. jcstress, the OpenJDK
concurrency stress tool, is the closer Java-side comparison and is included below.

| Capability | async-test | plain JUnit 5 | hand-rolled stress test | jcstress | Lincheck | ThreadSanitizer |
|---|---|---|---|---|---|---|
| Runs inside a JUnit 5 suite | yes | yes | varies | no (own harness) | partially (runs as a JUnit test, but the body is Lincheck's harness) | no (native code only) |
| Forces simultaneous thread collisions | yes (barrier release) | no | usually | yes | yes | no (observes, does not force) |
| Happens-before data-race detection | partial: with the agent attached, conflicting field accesses that neither a common lock nor the happens-before model orders are reported, on the executions the run observed (since 1.12.3) | no | no | no | no (detects incorrect outcomes, not races) | yes |
| Zero-config deadlock detection | yes (live circular wait via ThreadMXBean) | no | no | no | in the tested operations via model checking, not live ThreadMXBean detection | lock-order inversion reports |
| Linearizability checking | experimental: `OperationHistory` checks the results workers saw against a sequential model, with drawn scenarios but no systematic scenario search (since 1.13.1) | no | no | no | yes (the reference tool on the JVM) | no |
| Memory-model conformance probes | limited | no | no | yes (the reference tool) | limited | yes |
| Reproduction handle for a failing run | replay seed: the library RNG by default; with `controlledSchedule = true` also the interleaving, at woven field accesses, substituted collection and atomic calls and explicit `schedulePoint()`s, exact when no worker blocked outside a point | no | no | seeds per config | yes in model-checking mode (deterministic interleaving replay) | no |
| Applies to JVM code | yes | yes | yes | yes | yes | no |

What that means in practice:

- async-test's genuine differentiators are the JUnit-5-native contention harness, live
  deadlock detection with no setup, and the report/gate/baseline pipeline. Most of its
  detectors observe only what the test body records explicitly. The rest read the agent's woven
  streams (field instructions, collection and lock calls), which the runner attaches by itself
  when `async-test-agent` is on the test classpath, or watch the JVM itself; the
  [catalog](../DETECTOR_CATALOG.md#what-feeds-each-detector) lists which detector is which. They
  classify the access patterns of the executions they saw and do not prove that no other
  execution races. See [detector-accuracy-eval.md](detector-accuracy-eval.md) for measured
  behavior on buggy code and on correctly synchronized code.
- jcstress is the stronger tool for memory-model and atomicity conformance questions; it
  is also the reason async-test does not claim "JMM validation" as a differentiator.
- Lincheck (JetBrains, MPL-2.0) is the stronger tool for verifying linearizability of a
  concurrent data structure. Its user declares the structure's operations with `@Operation`
  annotations; Lincheck generates concurrent scenarios over that operation set, runs them in
  stress or model-checking mode, controls the thread schedule in model-checking mode, and
  reports a minimized failing scenario as a step-by-step interleaving trace. async-test's
  `OperationHistory` asks the same question inside a plain `@AsyncTest` and reports the shortest
  impossible prefix of what it observed, but draws one scenario per round instead of searching
  them and does not shrink a scenario by re-running it
  ([#1025](https://github.com/PIsberg/async-test-lib/issues/1025)). Lincheck has no detector,
  report, severity-gate or baseline pipeline. The two compose rather than compete: Lincheck
  verifies the data structure you own, async-test stresses the code that uses it.
- ThreadSanitizer is the stronger tool for actual race proof, where the code under test
  is native. For Java there is no production equivalent; async-test's happens-before model
  covers the synchronization the agent weaves, not every edge the JVM has, so a race through
  unwoven code (a JDK internal, an excluded package) goes unseen.
