# Load tests

A standalone Gradle build that measures what the library costs the suite it runs inside. It
resolves `async-test-lib` from `mavenLocal()` rather than the reactor, so it measures a published
artefact, and it can point at any released version for a comparison.

```bash
./gradlew publishToMavenLocal                     # from the repo root, first
./gradlew -p load-tests test                      # throughput + memory stress
./gradlew -p load-tests jmh                       # microbenchmarks (~20 min)

./gradlew -p load-tests test -PasyncTestVersion=1.6.0    # compare against a release
./gradlew -p load-tests test -PloadTestFast=true         # CI subset
./gradlew -p load-tests jmh -PjmhIncludes=DetectorLifecycleBenchmark   # one class while iterating
```

Results land in `results/<version>/` (`jmh.json`, `throughput.csv`, `memory.csv`, `env.txt`) and
the plots in `results/_plots/` are regenerated from them by `tools/`.

## What each benchmark measures, and what it cannot

**`AsyncTestBenchmark`** runs a whole `@AsyncTest` end to end through `EngineTestKit`. That is the
number a user feels, and it is the right shape for tracking release over release.

It is the wrong instrument for detector cost. Turning all 127 detectors on moves its total by about
1.5% (188.9 ms vs 186.1 ms at 2 threads, measured on 1.7.0), which is inside the run-to-run spread:
a detector that got ten times slower would not show up. If you are changing a detector, do not
conclude anything from these numbers.

**`DetectorLifecycleBenchmark`** isolates the two costs that scale with the detector set, both paid
once per `@AsyncTest` method:

| Benchmark | What it covers |
|---|---|
| `contextConstruction_allDetectors` | building the full 127-detector registry |
| `contextConstruction_noDetectors` | the same construction with every detector off — the floor |
| `analyzeSweep_allDetectors` | the end-of-run sweep asking every detector "did you see anything?" |
| `engineHarnessOnly` | a plain `@Test` through the same `EngineTestKit` path, so the harness cost `AsyncTestBenchmark` includes is a measured number rather than an assumption |

The construction pair is the useful reading, not either number alone. Against 1.7.0-RC5,
all-detectors measured 594 µs ± 82 and the no-detector floor 567 µs ± 114. Those overlap, so the
honest conclusion is that building 127 detectors is *not measurable* at this resolution, not that
it costs 27 µs — quoting the difference of two overlapping intervals is the same mistake this file
warns about two paragraphs up.

Most of that floor is fixed setup, and it has been attributed. A tighter loop outside JMH
(200 iterations, warmed) splits construction as: 961 µs with every detector off, 1766 µs with all
127 on, and **331 µs for `ServiceLoader` discovery of the `DetectorFactory` services alone** — all
127 provider classes re-resolved on every `AsyncTestContext` construction, i.e. once per
`@AsyncTest` method, to produce a list that cannot change while the JVM runs.

That is a third of the no-detector floor and it is deliberately **not** fixed here. Caching it
means sharing `DetectorFactory` instances across tests instead of building fresh ones per test,
which changes observable semantics of a published extension point — and
[roadmap-v2.md](../docs/analysis/roadmap-v2.md) Train 3 already has the dual-registry question
open over that same code. 331 µs against the 170 ms below is not worth pre-empting that decision.

`analyzeSweep_allDetectors` is why this class exists. It first measured **170 ms** per sweep, and
attributing that detector by detector put 99.2% of a 290 ms profile in `UncommittedChangesDetector`
alone, which forked `git status` on every call. With the subprocess cached per JVM the same
benchmark on the same machine measures **32.5 µs ± 6.6** — the one number in this file where before
and after are separated by four orders of magnitude rather than by measurement noise.
`UncommittedChangesDetectorTest` pins the at-most-once contract.

The construction numbers moved between those two runs as well (399 µs and 269 µs the second time),
but their error bars are ±523 and ±25, the machine was under different load, and nothing in the
change touches construction. Do not read that as an improvement.

Meanwhile the stored 1.7.0 `AsyncTestBenchmark` result moves 2.8 ms between no detectors and all
127 — on the same OS and JDK, and far less than the sweep above would predict. Whatever explains
the difference, the end-to-end benchmark did not surface a cost this large, and that is the case
for keeping both benchmarks rather than either alone.

## Reading a comparison

Compare like for like: same machine, same JDK, back to back. `results/<version>/env.txt` records
what a stored run was measured on, and a comparison against a run from different hardware is not a
comparison. JMH's `scoreError` is part of the result — `engineHarnessOnly` above measured
463 µs ± 673 µs, which says "small" and nothing more precise than that.
