# Load-Test Workflow

This document describes how to run the async-test-lib load tests, capture baseline results
for a release, and generate the comparison graphs.

## Overview

The `load-tests/` subproject contains three types of measurements:

| Type | File | What it measures |
|---|---|---|
| Throughput sweep | `ThroughputStressTest.java` | Wall-clock rounds/second across thread counts and invocation counts |
| Memory sweep | `MemoryStressTest.java` | Peak heap overhead of enabling all detectors vs none |
| JMH microbenchmarks | `AsyncTestBenchmark.java` | Average ms/op for each framework + detector combination |

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

## Running benchmarks for the current version (0.8.0)

### 1. Build and publish to local Maven

```bash
./gradlew publishToMavenLocal
```

### 2. Run the throughput + memory stress tests

```bash
./gradlew -p load-tests test -PasyncTestVersion=0.8.0
```

This writes two CSV files to `load-tests/results/0.8.0/`:
- `throughput.csv` — wall-clock timings across thread × invocation configurations
- `memory.csv` — peak heap usage with and without detectors
- `env.txt` — machine metadata (JDK, OS, CPU count, commit)

### 3. Run the JMH microbenchmarks (~2 minutes)

```bash
./gradlew -p load-tests jmh -PasyncTestVersion=0.8.0
```

Then copy the output to the results folder:

```bash
cp load-tests/build/jmh-results.json load-tests/results/0.8.0/jmh.json
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
# CI equivalent
./gradlew -p load-tests test -PloadTestFast=true -PasyncTestVersion=0.8.0
```

---

## CI workflow

The `load-tests.yml` GitHub Actions workflow runs automatically on every push to `main`
and on pull requests. It:

1. Builds and publishes the current library to the local Maven repository.
2. Runs the stress tests in fast mode (`loadTestFast=true`).
3. Uploads the CSV results and JUnit reports as artifacts (retained 30 days).

JMH microbenchmarks and full-sweep comparisons are intentionally excluded from per-PR CI
because they take several minutes and produce machine-dependent results that are not
meaningful for per-commit regression gating. Run them locally before tagging a release.

---

## Directory layout

```
load-tests/
├── build.gradle.kts            Standalone Gradle project (no wrapper needed — uses root)
├── settings.gradle.kts
├── src/
│   ├── test/java/.../loadtest/
│   │   ├── ThroughputStressTest.java   Wall-clock sweep via EngineTestKit
│   │   └── MemoryStressTest.java       Heap-usage sweep via EngineTestKit
│   └── jmh/java/.../loadtest/
│       └── AsyncTestBenchmark.java     JMH microbenchmarks
├── tools/
│   └── plot-results.py         Python/Matplotlib graph generator
└── results/
    ├── 0.7.0/                  Baseline results committed to repo
    │   ├── env.txt
    │   ├── throughput.csv
    │   ├── memory.csv
    │   └── jmh.json
    ├── 0.8.0/
    │   └── ...
    └── _plots/                 Generated PNGs (committed to repo)
        ├── throughput-vs-threads.png
        ├── throughput-by-release.png
        ├── detector-overhead-by-release.png
        ├── detector-overhead-detail.png
        └── memory-overhead-vs-invocations.png
```

---

## Adding a new release baseline

1. Run throughput + memory tests: `./gradlew -p load-tests test -PasyncTestVersion=<NEW>`
2. Run JMH: `./gradlew -p load-tests jmh -PasyncTestVersion=<NEW>` then copy JSON.
3. Regenerate plots: `python load-tests/tools/plot-results.py`
4. Commit `load-tests/results/<NEW>/` and updated `_plots/`.
