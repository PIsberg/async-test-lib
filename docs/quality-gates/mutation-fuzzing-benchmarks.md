# Mutation testing, fuzzing and benchmarking

Part of the [Quality Gates guide](../QUALITY_GATES.md).

## Mutation testing

PITest gates the mutation score at **>= 76%**. Measured **81%** (9019 mutations, 7272
killed) on 2026-09-06 by run 34017000749 - the first CI run of this job ever to complete.
Every attempt before it died in the coverage phase, so the 77.5% previously quoted here was a
local measurement rather than the gate's own; see #479. The margin absorbs run-to-run
`TIMED_OUT` jitter. It is never bound to `verify`; `mutation.yml` runs it weekly
(Sundays 02:00 UTC) and on demand from the Actions tab, and that job fails below the threshold.
Until 2026-08-15 nothing in CI ran it at all, while `CONTRIBUTING.md` said it ran on a schedule.

```bash
mvn org.pitest:pitest-maven:mutationCoverage                 # full run, ~2h
mvn org.pitest:pitest-maven:mutationCoverage -DtargetClasses=se.deversity.asynctest.diagnostics.Shared*
```

Quirks: `parseSurefireArgLine=false` is required because JaCoCo's late-bound `@{argLine}` crashes
pitest, so the needed JVM flags are duplicated in the plugin's `jvmArgs`. `parseSurefireConfig=false`
(2026-08-31) keeps pitest from inheriting surefire's local `excludedGroups=e2e`: before it, a
developer's full run silently scored the fast tier only (74%) while the CI job — where the e2e
profile clears the exclusion — scored the whole suite (77.5%), so the same command measured two
different things. Reports are non-timestamped, so each run overwrites `target/pit-reports/`.

Surviving mutants are dominated by diagnostic output and timing-heuristic detectors — killing them
would require flaky timing-forced tests, so they are deliberately tolerated. Mutation analysis has
caught real wiring bugs: see the excludes-branch gap in
[configuration-resolution.md](../architecture/configuration-resolution.md) and the deadlock baselining
below.

### JVM-global vs instance state

Detectors that query JVM-wide facilities (`ThreadMXBean`, thread dumps) must baseline pre-existing
state at construction so they only report what the monitored test caused. `DeadlockDetector.analyze()`
excludes thread ids already deadlocked when the detector was created — otherwise leaked deadlocked
threads from earlier tests in a shared JVM cause false positives. Found by mutation testing. Its
static `hasDeadlock()` stays JVM-wide by design.

## Fuzzing

`async-test-lib/src/fuzz/java/se/deversity/asynctest/fuzz/` holds standalone Jazzer harnesses,
excluded from pitest. They exercise parsing and config surfaces rather than the concurrency engine.
`fuzzing.yml` runs them every Monday against the Jazzer CLI.

That schedule first fuzzed anything on 2026-08-10. Every scheduled run from at least 2026-06-29
until then failed without executing the harness, behind three stacked defects, each hidden by the
one in front of it:

1. harden-runner's egress allowlist omitted `release-assets.githubusercontent.com`, the host GitHub
   serves release assets from, so the Jazzer download died on `curl: (7) Failed to connect`.
2. `tar -xzf jazzer_linux.tar.gz jazzer` extracted only the launcher. That binary is a thin driver
   that loads `jazzer_standalone.jar` from its own directory, so it aborted at startup.
3. `-artifact_prefix` accepts only a directory that already exists. libFuzzer will not create one,
   and aborts with `The required directory "fuzzing-findings/" does not exist`.

What let all three survive was `continue-on-error: true` on the fuzz step. It was meant to keep a
fuzzing *finding* from failing the build, but it also swallowed Jazzer failing to *start*. That
blanket tolerance is gone. Jazzer's exit code is still ignored, but the step now requires
libFuzzer's `INITED` line in the log, which appears only after the target class has loaded and the
initial corpus has run. A finding stays an artifact; a toolchain or classpath failure is a red job.
The assertion caught defects 2 and 3 on its first two runs.

The first genuine run executed 7,382,051 inputs in 121 s (61,008 exec/s), grew coverage from 29 to
699 features, and found no defect in `AsyncTestConfig.Builder`. It instruments the config surface
itself — `AsyncTestConfig`, `AsyncTestConfig$Builder`, `FailOn`, `DetectorType` — not merely the
harness.

They sit in `src/fuzz/java` rather than `src/test/java` for one reason: OpenSSF Scorecard's fuzzing
check discards every path containing `/src/test/` before it scans for the
`com.code_intelligence.jazzer.api.FuzzedDataProvider` import, so a harness kept under the test root
is invisible to it and the repo scores 0 on Fuzzing however much fuzzing actually happens. The
filter is in Scorecard's `checks/fileparser/listing.go`, in `isTestdataFile`.

The directory is wired in as an extra test-source root twice, once per build, and the two must stay
in agreement: `build-helper-maven-plugin`'s `add-fuzz-test-source` execution in
`async-test-lib/pom.xml`, and the `sourceSets` block in the root `build.gradle.kts`. Both compile
the harnesses into the ordinary test output, so the Jazzer classpath stays
`async-test-lib/target/test-classes`.

## Benchmarking

Opt-in invocation timing with baseline regression classification (STABLE / REGRESSION /
IMPROVEMENT), gated by the `enableBenchmarking` flag.

`benchmark/BenchmarkRecorder` times every round — its hot path is allocation-free, no autoboxing,
lock-free in the common case — and persists baselines compared by `BenchmarkComparator`. The
`[BENCHMARK]` log lines and the `benchmark.invocation.times` metric are consumed by dashboards;
never rename or remove them without flagging the dashboard change. Full guide:
[BENCHMARKING.md](../BENCHMARKING.md).
