---
paths: ["**/benchmark/**", "**/telemetry/**", "**/agent/**", "**/analysis/**"]
---

<!-- VIBETAGS-START -->
# Rules for async-test-instrumentation

## se.deversity.asynctest.benchmark.BenchmarkRecorder

## Performance Constraints
- **Rule**: Optimal complexity required. O(n^2) is forbidden on hot paths.
- **Constraint**: recordInvocationStart() and recordInvocationEnd() are called on the hot path inside every invocation round. Keep them allocation-free and avoid acquiring locks in the common case.

## Observability Instrumentation
- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard.
- **Details**: Metrics: benchmark.invocation.times. Logs: [BENCHMARK] Baseline created, [BENCHMARK] Baseline updated, [BENCHMARK] STABLE, [BENCHMARK] REGRESSION, [BENCHMARK] IMPROVEMENT. Note: Hot path telemetry used by JUnit benchmark metrics and baseline regression checks.

## Feature Flag Gate
- **Flag**: 'async-test.benchmarking.enabled' (default: false)
- **Rule**: This code is gated behind a feature flag. Preserve the flag check. Never assume the flag is always active.

### Rules for method recordInvocationStart
- **Policy**: NO_AUTOBOXING
- **Rule**: Strictly limit or prevent object allocations.

### Rules for method recordInvocationEnd
- **Policy**: NO_AUTOBOXING
- **Rule**: Strictly limit or prevent object allocations.

## se.deversity.asynctest.benchmark.BenchmarkComparator

### Rules for method readStore
- **Rule**: Prohibit dynamic class loading, custom classloaders, runtime reflection hacks, or execution of dynamic external code.
<!-- VIBETAGS-END -->
