---
paths: ["**/benchmark/**", "**/telemetry/**", "**/agent/**", "**/analysis/**"]
---

<!-- VIBETAGS-START -->
# Rules for async-test-instrumentation

## Performance Constraints

### se.deversity.asynctest.benchmark.BenchmarkRecorder
- **Rule**: Optimal complexity required. O(n^2) is forbidden on hot paths.
- **Constraint**: recordInvocationStart() and recordInvocationEnd() are called on the hot path inside every invocation round. Keep them allocation-free and avoid acquiring locks in the common case.

## Observability Instrumentation

### se.deversity.asynctest.benchmark.BenchmarkRecorder
- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard.
- **Details**: Metrics: benchmark.invocation.times. Logs: [BENCHMARK] Baseline created, [BENCHMARK] Baseline updated, [BENCHMARK] STABLE, [BENCHMARK] REGRESSION, [BENCHMARK] IMPROVEMENT. Note: Hot path telemetry used by JUnit benchmark metrics and baseline regression checks.

## Feature Flag Gate

### se.deversity.asynctest.benchmark.BenchmarkRecorder
- **Flag**: 'async-test.benchmarking.enabled' (default: false)
- **Rule**: This code is gated behind a feature flag. Preserve the flag check. Never assume the flag is always active.

## Memory Budget Constraints
- **Policy**: NO_AUTOBOXING
- **Rule**: Strictly limit or prevent object allocations.
- **Applies to**: `se.deversity.asynctest.benchmark.BenchmarkRecorder.recordInvocationEnd(long)`, `se.deversity.asynctest.benchmark.BenchmarkRecorder.recordInvocationStart()`

## Strict Classpath Integrity

### se.deversity.asynctest.benchmark.BenchmarkComparator.readStore(java.io.File)
- **Rule**: Prohibit dynamic class loading, custom classloaders, runtime reflection hacks, or execution of dynamic external code.
- **Reason**: Java native deserialization sink. The BASELINE_FILTER allow-list (ending in !*) must resolve every class in the stream and reject all others, preventing arbitrary class loading (CWE-502 RCE). Never widen the filter or remove setObjectInputFilter.

## Mirrored — Keep In Sync

### se.deversity.asynctest.telemetry.TelemetryBridge
- **Rule**: Free to change, but every mirror must change in the same commit.
- **Mirrors**: se.deversity.asynctest.diagnostics.DetectorFeeds, docs/DETECTOR_CATALOG.md
- **Reason**: DetectorFeeds.fedBy(AGENT) must equal the detectors the woven streams actually reach, and this class is one of those streams. The gate reflects over this class's declared constructors and methods and asserts the detector types it is compile-wired to are exactly {AtomicityValidator}. Routing a second detector here without adding its AGENT row leaves the catalog telling users the agent buys them nothing for that detector.
- **Enforced by**: se.deversity.asynctest.architecture.DetectorFeedCoverageTest
<!-- VIBETAGS-END -->
