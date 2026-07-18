# Architecture

A JUnit 5 library that stress-tests code under maximum thread contention and diagnoses concurrency bugs with ~123 pluggable detectors, driven by a single `@AsyncTest` annotation.

The library runs a test body N threads × M invocations behind a barrier, watches it with the configured detectors, and fails the test when findings cross the configured severity gate. It replaces hand-rolled executor/latch boilerplate in consumer tests.

## Pipeline

One test method flows through five stages: annotation → JUnit hook → execution → detection → reporting. Each stage has its own concept page.

1. **Annotation → config**: `@AsyncTest` attributes are snapshotted into an immutable `AsyncTestConfig` — see [[configuration#AsyncTestConfig]].
2. **JUnit hook**: `se.deversity.asynctest.extension.AsyncTestExtension` (a `TestTemplateInvocationContextProvider`) and `AsyncTestInvocationInterceptor` hand control to the runner. The interceptor calls `invocation.skip()` on purpose — see [[execution-model#Interceptor skip invariant]].
3. **Execution**: `se.deversity.asynctest.runner.ConcurrencyRunner` owns the N×M barrier-synchronized rounds — see [[execution-model]].
4. **Detection**: detectors record events during rounds and are analyzed via `DetectorRegistry.analyzeAll()` — see [[detectors]].
5. **Reporting & gating**: findings become reports/violations, listeners fire, and `failOn` decides pass/fail — see [[reporting]].

## Package map

Where things live under `src/main/java/se/deversity/asynctest/`. The root package holds the public API surface: annotation, config, context, listener registry, presets.

- `extension/` — JUnit 5 SPI glue ([[execution-model#JUnit extension entry]])
- `runner/` — `ConcurrencyRunner`, `LicenseGuard`, `SpinContentionBarrier` ([[execution-model]], [[quality-gates#License guard]])
- `diagnostics/` — all detector implementations + `SiteCapture`, severity, learning content ([[detectors]])
- `spi/` — public `Detector`/`DetectorFactory` interfaces, `DetectorRegistry`, `adapters/` legacy bridge ([[detectors#Detector SPI]])
- `report/` — `Violation`, `Formatter` strategy + JSON/Markdown impls, report listeners, `Baseline` ([[reporting]])
- `benchmark/` — opt-in invocation timing + baseline regression checks ([[quality-gates#Benchmarking]])
- `agent/` — optional `-javaagent` Byte Buddy field-access instrumentation (`AsyncTestAgent`)
- `analysis/` — ASM static pre-scan for virtual-thread pinning sites (`StaticPinningScanner`)
- `telemetry/` — event buffer/bridge for external observability
- root — `AsyncTest`, `AsyncTestConfig`, `AsyncTestContext`, `DetectorType`, `Preset`, `FailOn`, `AsyncTestListener(Registry)`, `AsyncAssert`

## Stable API surface

Public contracts must keep their exact signatures until a major version bump; japicmp breaks the build on binary-incompatible changes ([[quality-gates#Static analysis and API gates]]).

The stable set: the `@AsyncTest` annotation (attribute names, types, defaults), `AsyncAssert`, `AsyncTestListener` + `AsyncTestListenerRegistry`, `spi.Detector`, `spi.DetectorFactory`, `report.Formatter`, and the JUnit-mandated methods of `extension.AsyncTestExtension`. Internal logic behind these interfaces may be refactored freely.
