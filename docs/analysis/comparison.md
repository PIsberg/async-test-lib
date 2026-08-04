# Comparison with Other Tools

> Extracted from the former `docs/README.md`. See [INDEX.md](../INDEX.md) for the full
> documentation map.

An earlier version of this table scored ThreadSanitizer "no" on race detection, deadlock
detection and lock-order validation. That was inverted: happens-before race detection is
exactly what ThreadSanitizer does, and it reports lock-order inversions too. Its limitation
for this library's audience is different in kind: it instruments C, C++, Go and Swift, not
JVM bytecode, so it is not available to a Java test suite at all. jcstress, the OpenJDK
concurrency stress tool, is the closer Java-side comparison and is included below.

| Capability | async-test | plain JUnit 5 | hand-rolled stress test | jcstress | ThreadSanitizer |
|---|---|---|---|---|---|
| Runs inside a JUnit 5 suite | yes | yes | varies | no (own harness) | no (native code only) |
| Forces simultaneous thread collisions | yes (barrier release) | no | usually | yes | no (observes, does not force) |
| Happens-before data-race detection | no | no | no | no | yes |
| Zero-config deadlock detection | yes (live circular wait via ThreadMXBean) | no | no | no | lock-order inversion reports |
| Memory-model conformance probes | limited | no | no | yes (the reference tool) | yes |
| Reproduction handle for a failing run | replay seed (library RNG only, not the schedule) | no | no | seeds per config | no |
| Applies to JVM code | yes | yes | yes | yes | no |

What that means in practice:

- async-test's genuine differentiators are the JUnit-5-native contention harness, live
  deadlock detection with no setup, and the report/gate/baseline pipeline. Most of its 127
  detectors observe only what the test body records explicitly or what the optional agent
  weaves (JavaBean accessors); they classify access patterns and do not prove or disprove
  a race. See [detector-accuracy-eval.md](detector-accuracy-eval.md) for measured behavior
  on buggy code and on correctly synchronized code.
- jcstress is the stronger tool for memory-model and atomicity conformance questions; it
  is also the reason async-test does not claim "JMM validation" as a differentiator.
- ThreadSanitizer is the stronger tool for actual race proof, where the code under test
  is native. For Java there is no production equivalent, which is the gap stress
  harnesses (this library included) partially cover by making latent races fail a test
  body's own assertions more often.
