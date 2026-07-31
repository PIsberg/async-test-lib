# Quality Gates

Everything that must stay green, plus the build quirks that trip up newcomers — human or agent.

On Windows/PowerShell, quote every `-D` argument (`"-Dlicense.mock.mode=true"`) or Maven parses it
as a lifecycle phase.

## Test suite conventions

`mvn test` runs the full suite (~1600 tests, ~5 min). `-Dtest=<Class>` does filter normally — add
`-DfailIfNoSpecifiedTests=false` when scoping to a module that has no match.

Surefire forks a fresh JVM per class (`reuseForks=false`), which masks cross-test JVM contamination;
pitest's shared JVM surfaces it, so tests must not assume a pristine JVM (see
[JVM-global vs instance state](#jvm-global-vs-instance-state)). Integration tests drive the engine
with JUnit `EngineTestKit` against nested dummy classes annotated with `@AsyncTest` — the pattern
throughout `src/test/java`.

**There is deliberately no flaky-test rerun.** The parent POM omits `<rerunFailingTestsCount>` on
purpose: in this library a test that fails intermittently is reporting a real detector finding, not
infrastructure noise. Auto-rerunning would mask the exact signal the project exists to catch.

## Static analysis and API gates

`mvn verify` runs Checkstyle, PMD, SpotBugs, Error Prone, JaCoCo thresholds and japicmp — all of
which fail the build.

- **Checkstyle** fails on warnings.
- **PMD** flags `LooseCoupling` (declare `ConcurrentMap`, not `ConcurrentHashMap`) and
  `UnusedPrivateField`.
- **SpotBugs** runs at Max effort / Low threshold and flags repeated `path.getParent()` null paths.
- **Error Prone** covers main sources only.
- **JaCoCo** requires line ≥ 70% and branch ≥ 65%.
- **japicmp** breaks the build on binary-incompatible API changes against the last release.
- **ArchUnit** tests enforce package structure and the module boundaries from within the suite.

## Mutation testing

PITest gates the mutation score at **≥ 74%** (measured 75.4%; the margin absorbs run-to-run
`TIMED_OUT` jitter). Run on demand — it is never bound to `verify`.

```bash
mvn org.pitest:pitest-maven:mutationCoverage                 # full run, ~1h20m
mvn org.pitest:pitest-maven:mutationCoverage -DtargetClasses=se.deversity.asynctest.diagnostics.Shared*
```

Quirks: `parseSurefireArgLine=false` is required because JaCoCo's late-bound `@{argLine}` crashes
pitest, so the needed JVM flags are duplicated in the plugin's `jvmArgs`. Reports are
non-timestamped, so each run overwrites `target/pit-reports/`.

Surviving mutants are dominated by diagnostic output and timing-heuristic detectors — killing them
would require flaky timing-forced tests, so they are deliberately tolerated. Mutation analysis has
caught real wiring bugs: see the excludes-branch gap in
[configuration-resolution.md](architecture/configuration-resolution.md) and the deadlock baselining
below.

### JVM-global vs instance state

Detectors that query JVM-wide facilities (`ThreadMXBean`, thread dumps) must baseline pre-existing
state at construction so they only report what the monitored test caused. `DeadlockDetector.analyze()`
excludes thread ids already deadlocked when the detector was created — otherwise leaked deadlocked
threads from earlier tests in a shared JVM cause false positives. Found by mutation testing. Its
static `hasDeadlock()` stays JVM-wide by design.

## License guard

`runner/LicenseGuard.check(config)` runs once per config fingerprint per JVM and throws
`SecurityException` on denial. Security-critical: never weaken the check.
`ConcurrentHashMap.computeIfAbsent` gives at-most-once gate execution per fingerprint; denied
fingerprints consistently throw.

Mock mode — `license.mock.mode=true` (the POM default for local tests) or auto-mock in CI (`CI` /
`GITHUB_ACTIONS` env) — grants without a key. Mechanics in
[architecture/runtime-guarantees.md](architecture/runtime-guarantees.md).

Related hardening: `benchmark/BenchmarkComparator.readStore` deserializes with a strict
`ObjectInputFilter` allow-list ending in `!*` — never widen it (CWE-502).

## Benchmarking

Opt-in invocation timing with baseline regression classification (STABLE / REGRESSION /
IMPROVEMENT), gated by the `enableBenchmarking` flag.

`benchmark/BenchmarkRecorder` times every round — its hot path is allocation-free, no autoboxing,
lock-free in the common case — and persists baselines compared by `BenchmarkComparator`. The
`[BENCHMARK]` log lines and the `benchmark.invocation.times` metric are consumed by dashboards;
never rename or remove them without flagging the dashboard change. Full guide:
[BENCHMARKING.md](BENCHMARKING.md).

## Fuzzing

`async-test-lib/src/test/java/se/deversity/asynctest/fuzz/` holds standalone Jazzer harnesses,
excluded from pitest. They exercise parsing and config surfaces rather than the concurrency engine.
