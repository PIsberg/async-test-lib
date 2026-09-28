# Load-Test Workflow

This document describes how to run the async-test-lib load tests, capture baseline results
for a release, and generate the comparison graphs.

## Overview

The `load-tests/` subproject contains three types of measurements:

| Type | File | What it measures |
|---|---|---|
| Throughput sweep | `ThroughputStressTest.java` | Wall-clock rounds/second across thread counts and invocation counts |
| Memory sweep | `MemoryStressTest.java` | Peak heap overhead of enabling all detectors vs none |
| JMH microbenchmarks | `AsyncTestBenchmark.java`, `DetectorLifecycleBenchmark.java` | End-to-end `@AsyncTest` cost, and the two per-method costs that scale with the detector set (registry construction, the analysis sweep) — see `load-tests/README.md` for what each can and cannot show |

Results are stored in `load-tests/results/<version>/` and committed to the repository.
A Python script reads all version directories and generates PNG comparison graphs in
`load-tests/results/_plots/`.

---

## Prerequisites

- Java 21 (Temurin recommended)
- Gradle wrapper (from the project root)
- Python 3.9+ with `matplotlib` and `numpy` for graph generation

```bash
# Install Python deps once
python -m pip install matplotlib numpy
```

---

## Running benchmarks for the current build (HEAD)

`load-tests/build.gradle.kts` reads the version under test from `-PasyncTestVersion`, and
if that property is absent it resolves the reactor's own `pom.xml` version instead — i.e.
whatever you last ran `publishToMavenLocal` with. You don't need to pass a version to
benchmark HEAD; pass one only to target a published release (see below).

### 1. Build and publish to local Maven

```bash
./gradlew publishToMavenLocal
```

### 2. Run the throughput + memory stress tests

```bash
./gradlew -p load-tests test
```

This writes two CSV files to `load-tests/results/<current-pom-version>/`:
- `throughput.csv` — wall-clock timings across thread × invocation configurations
- `memory.csv` — peak heap usage with and without detectors
- `env.txt` — machine metadata (JDK, OS, CPU count, commit)

### 3. Run the JMH microbenchmarks (~20 minutes)

```bash
./gradlew -p load-tests jmh
```

Scope it to one class while iterating on a single benchmark (a full run is too slow a loop
to check whether a change moved the number):

```bash
./gradlew -p load-tests jmh -PjmhIncludes=DetectorLifecycleBenchmark
```

Then copy the output to the results folder:

```bash
cp load-tests/build/jmh-results.json load-tests/results/<current-pom-version>/jmh.json
```

---

## Running benchmarks for a previous version (e.g. 0.7.0)

Previous versions are available on Maven Central — no local build required.

```bash
./gradlew -p load-tests test -PasyncTestVersion=0.7.0
./gradlew -p load-tests jmh  -PasyncTestVersion=0.7.0
cp load-tests/build/jmh-results.json load-tests/results/0.7.0/jmh.json
```

If the previous version is not yet on Maven Central (e.g. a release candidate), check it
out in a git worktree, build it, and publish to local Maven first:

```bash
git worktree add ../async-test-lib-prev v0.7.0
cd ../async-test-lib-prev
./gradlew publishToMavenLocal
cd ../async-test-lib
./gradlew -p load-tests test -PasyncTestVersion=0.7.0
```

---

## Generating comparison graphs

After capturing results for at least one version:

```bash
python load-tests/tools/plot-results.py
```

Graphs are written to `load-tests/results/_plots/`:

| File | Description |
|---|---|
| `throughput-vs-threads.png` | Line chart: rounds/second vs thread count (one line per version) |
| `throughput-by-release.png` | Grouped bar chart: throughput by configuration, coloured by release |
| `detector-overhead-by-release.png` | Grouped bar chart: JMH avg ms/op for all benchmarks, coloured by release |
| `detector-overhead-detail.png` | Bar chart: no-detector vs all-detector for the latest version with JMH data |
| `memory-overhead-vs-invocations.png` | Line chart: detector memory overhead (MB) vs invocation count |

Commit the CSV files and generated PNGs together as the release baseline.

---

## Fast mode (CI)

The `loadTestFast=true` Gradle property restricts the sweep to a small subset
(threads ≤ 4, invocations ≤ 10) to keep CI runtime under one minute.
JMH is skipped in CI — run it locally before each release.

```bash
# CI equivalent (no -PasyncTestVersion: CI resolves the reactor's own pom.xml version,
# i.e. it measures HEAD, not a fixed release)
./gradlew -p load-tests test -PloadTestFast=true
```

---

## CI workflow

`.github/workflows/load-tests.yml` ("Load Tests") is *not* a required status check gating
merges. It has four triggers:

- **`push`** to `main` or `develop`, and **`pull_request`** into `main` or `develop` — both
  skip when only `docs/**`, `**/*.md`, `.github/ISSUE_TEMPLATE/**` or `LICENSE` changed
  (`paths-ignore`). A superseded `pull_request` run is cancelled by a newer push on the same
  ref; a run on `main` or the nightly schedule is never cancelled, since it is the permanent
  record for that commit.
- **`schedule`** — every night at 04:00 UTC. This is the "nightly ring" half of the
  performance contract: the same fast sweep, then compared against the newest committed
  baseline.
- **`workflow_dispatch`** — manual run with an optional `asyncTestVersion` input, to
  benchmark a specific published release on demand instead of HEAD.

On every trigger, the single `load-tests` job (JDK 21, Temurin, `ubuntu-latest`, egress
locked down by `step-security/harden-runner`) runs:

1. `./gradlew publishToMavenLocal -x test` — builds and publishes the current library,
   skipping its own test suite (that gate runs elsewhere).
2. `./gradlew -p load-tests test -PloadTestFast=true`, plus `-PasyncTestVersion=<input>`
   only when `workflow_dispatch` supplied one. On `push`/`pull_request`/`schedule` no
   version is passed, so the run measures HEAD via the reactor `pom.xml` resolution
   described above — not a pinned old release.
3. **Compare against the newest committed baseline** (`if: always()`): finds the results
   directory the step above just wrote and runs `load-tests/tools/compare-baseline.sh`
   against it, appending the report to the job's step summary. This is warn-only — it
   always exits 0 and prints a `::warning::` line per row that exceeds the band (1.5x
   median throughput, 2.0x all-detector memory) — because baselines are recorded on
   whichever machine cut the release and are not comparable machine to machine as a hard
   gate. `RunnerAllocationBudgetTest` (in `async-test-lib`'s e2e tier, so every CI leg) is
   the actual inner-loop gate that fails a build over allocation, which is stable across
   machines; wall-clock never is. Read this step's output mainly on the scheduled runs.
4. Uploads two artifact sets, both `if: always()`: the CSVs and `env.txt` under
   `load-test-results-<run-id>` (retained 30 days), and the JUnit XML reports under
   `load-test-junit-reports-<run-id>` (retained **7 days**, not 30).

JMH microbenchmarks and full-sweep comparisons are intentionally excluded from every
automatic trigger, including the nightly one, because they take several minutes and
produce machine-dependent results that are not meaningful for regression gating. Run them
locally before tagging a release.

---

## Directory layout

```
load-tests/
├── README.md                   What each benchmark measures, and what it cannot show
├── build.gradle.kts            Standalone Gradle project (no wrapper needed — uses root)
├── settings.gradle.kts
├── src/
│   ├── test/java/.../loadtest/
│   │   ├── ThroughputStressTest.java   Wall-clock sweep via EngineTestKit
│   │   └── MemoryStressTest.java       Heap-usage sweep via EngineTestKit
│   └── jmh/java/.../loadtest/
│       ├── AsyncTestBenchmark.java          End-to-end @AsyncTest, JMH
│       └── DetectorLifecycleBenchmark.java  Per-method registry-construction + analysis-sweep cost, JMH
├── tools/
│   ├── plot-results.py         Python/Matplotlib graph generator
│   └── compare-baseline.sh     Warn-only fresh-vs-committed-baseline comparison (used by CI's nightly run)
└── results/
    ├── 0.7.0/                  Baseline results committed to repo
    │   ├── env.txt
    │   ├── throughput.csv
    │   ├── memory.csv
    │   └── jmh.json
    ├── 0.8.0/, 1.3.0/, 1.4.0/, 1.6.0/, 1.7.0/
    │   └── ...                 One directory per release baseline, same shape as 0.7.0/
    └── _plots/                 Generated PNGs (committed to repo)
        ├── throughput-vs-threads.png
        ├── throughput-by-release.png
        ├── detector-overhead-by-release.png
        ├── detector-overhead-detail.png
        └── memory-overhead-vs-invocations.png
```

---

## Adding a new release baseline

Right after tagging, `<NEW>` is not yet resolvable from Maven Central — either
`publishToMavenLocal` at the tag first, or just omit `-PasyncTestVersion` so it resolves
from the reactor `pom.xml`, which already reads `<NEW>` at that point.

1. Run throughput + memory tests: `./gradlew -p load-tests test -PasyncTestVersion=<NEW>`
2. Run JMH: `./gradlew -p load-tests jmh -PasyncTestVersion=<NEW>` then copy JSON.
3. Regenerate plots: `python load-tests/tools/plot-results.py`
4. Commit `load-tests/results/<NEW>/` and updated `_plots/`.
