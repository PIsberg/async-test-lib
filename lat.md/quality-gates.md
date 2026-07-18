# Quality gates

Everything that must stay green, plus the build quirks that trip up newcomers — human or agent.

Local runs on Windows/PowerShell: quote every `-D` arg (`"-Dlicense.mock.mode=true"`) or Maven parses it as a lifecycle phase.

## Test suite conventions

`mvn test` runs the full suite (~1600 tests, ~5 min) — surefire ignores `-Dtest` filtering here, so there is no cheap single-test loop.

Use scoped pitest runs or a throwaway main for quick probes. Surefire forks a fresh JVM per class (`reuseForks=false`), which masks cross-test JVM contamination — pitest's shared JVM surfaces it, so tests must not assume a pristine JVM ([[detectors#JVM-global vs instance state]]). There is deliberately no flaky-test rerun: in this library a flaky test is a detector finding, not noise. Integration tests drive the engine with JUnit `EngineTestKit` against nested dummy test classes annotated with `@AsyncTest` (the pattern throughout `src/test/java`). Detector tests are mandated per detector with 80% coverage goals ([[adding-a-detector#Tests are part of the change]]).

## Static analysis and API gates

`mvn verify` runs Checkstyle, PMD, SpotBugs, Error Prone, JaCoCo thresholds, and japicmp — all of which fail the build.

Specifics: Checkstyle fails on warnings; PMD flags LooseCoupling (declare `ConcurrentMap`, not `ConcurrentHashMap`) and UnusedPrivateField; SpotBugs runs Max effort / Low threshold and flags repeated `path.getParent()` null paths; Error Prone covers main sources only; JaCoCo requires line ≥ 70% and branch ≥ 65%; japicmp breaks the build on binary-incompatible API changes vs the last release — the teeth behind [[architecture#Stable API surface]]. ArchUnit tests enforce package structure from within the suite.

## Mutation testing

PITest 1.25.7 gates the mutation score at ≥74% (measured 75.4%; the margin absorbs run-to-run TIMED_OUT jitter). Run on demand — never bound to `verify`.

`mvn org.pitest:pitest-maven:mutationCoverage` takes ~1h20m for a full run; scope with `-DtargetClasses=...` for minutes-fast iterations. Quirks: `parseSurefireArgLine=false` is required because JaCoCo's late-bound `@{argLine}` crashes pitest (needed JVM flags are duplicated in the plugin's `jvmArgs`); reports are non-timestamped, so each run overwrites `target/pit-reports/`. Surviving mutants are dominated by diagnostic output and timing-heuristic detectors — killing them would need flaky timing-forced tests, so they are deliberately tolerated. Mutation analysis has caught real wiring bugs ([[configuration#Detector selection resolution]], [[detectors#JVM-global vs instance state]]).

## License guard

`runner/LicenseGuard.check(config)` runs once per config fingerprint per JVM and throws `SecurityException` on denial. Security-critical: never weaken the check.

`ConcurrentHashMap.computeIfAbsent` gives at-most-once gate execution per fingerprint; denied fingerprints consistently throw. Mock mode — `license.mock.mode=true` (pom default for local tests) or auto-mock in CI (`CI`/`GITHUB_ACTIONS` env) — grants without a key. Related hardening: `benchmark/BenchmarkComparator.readStore` deserializes with a strict `ObjectInputFilter` allow-list ending in `!*` — never widen it (CWE-502).

## Benchmarking

Opt-in invocation timing with baseline regression classification (STABLE / REGRESSION / IMPROVEMENT), gated by [[configuration#Feature flags]].

`benchmark/BenchmarkRecorder` times every round — its hot path is allocation-free, no autoboxing, lock-free in the common case — and persists baselines compared by `BenchmarkComparator`. The `[BENCHMARK]` log lines and `benchmark.invocation.times` metric are consumed by dashboards; never rename or remove them without flagging the dashboard change.

## Fuzzing

`src/test/java/se/deversity/asynctest/fuzz/` holds standalone Jazzer harnesses, excluded from pitest.

They exercise parsing/config surfaces rather than the concurrency engine.
