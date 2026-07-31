# Detector SPI

> Part of the [architecture documentation](../ARCHITECTURE.md).

## Detector SPI (1.6.0)

The legacy detector architecture required synchronized edits across five places
(annotation field, config field+builder+defaults, both `build()` branches,
registry instantiation arm) — a documented fan-out risk in `CLAUDE.md`. The SPI
in `se.deversity.asynctest.spi` collapses that to **one class + one
META-INF/services line**.

```
META-INF/services/se.deversity.asynctest.spi.DetectorFactory
        │
        ▼  ServiceLoader.load(...)
DetectorFactory(s)                              ← user-implemented or built-in
        │
        ▼  build(AsyncTestConfig)
DetectorRegistry                                 ← SPI registry (new package)
        │
        ▼  analyzeAll()
List<Violation>                                  ← structured stream
```

**SPI contracts:**

- `Detector` — `type() → DetectorType`, `analyze() → List<Violation>`, optional
  `onTestStart()` / `onTestEnd()` lifecycle hooks. Per-test instance lifecycle.
- `DetectorFactory` — `type()`, `isEnabledFor(AsyncTestConfig)`,
  `create(AsyncTestConfig)`. `isEnabledFor` reads whichever boolean field on
  `AsyncTestConfig` corresponds to the detector — no automatic mapping (each
  factory is explicit, keeping the addressable surface intact).
- `DetectorRegistry` (in the `spi` package, distinct from the legacy one) —
  `build(config)` discovers via ServiceLoader, filters by `isEnabledFor`,
  instantiates. Two lookup styles: typed `get(Class<T>)` and enum-keyed
  `get(DetectorType)`. `analyzeAll()` aggregates structured violations.

**Full SPI coverage (1.6.0+).** Every `DetectorType` value — all 100 of them —
is registered as a `DetectorFactory` and discoverable via `ServiceLoader`.
Coverage is automated:

- **`LegacyDetectorFactories`** is a single file containing 99 inner-class
  `DetectorFactory` implementations (one per `DetectorType`, excluding the
  one with a dedicated typed adapter). Each declares its `type()`, reads its
  matching `AsyncTestConfig` boolean in `isEnabledFor()`, and produces a
  `LegacyDetectorAdapter` wrapping a fresh detector instance.
- **`LegacyDetectorAdapter<D>`** is the generic SPI `Detector` that reflectively
  invokes `delegate.analyze()` and the resulting report's `hasIssues()` /
  `toString()`, wrapping the toString into a single `Violation` when has-issues
  fires. Detectors whose report doesn't follow the canonical
  `analyze() → Report{hasIssues(), toString()}` shape return an empty list.
- **`SharedMessageDigestDetectorFactory`** is the typed-adapter template: when
  a legacy detector is migrated to expose `structuredViolations` natively,
  its factory uses a typed adapter to project them directly (no reflection).
- **`AllDetectorsSpiCoverageTest`** guards against drift: a new
  `DetectorType` value without a matching factory fails the build with a
  precise list of missing types.

**Coexistence with the legacy registry.** Both registries instantiate
independently and run side-by-side. The legacy
`se.deversity.asynctest.DetectorRegistry` continues to drive per-test
execution for the built-in detectors and owns the `AsyncTestContext` wiring.

**Third-party detectors are live (1.7.0).** `AsyncTestContext` builds a second,
SPI-driven registry per test via `DetectorRegistry.buildExternal(config)`, which
discovers every `DetectorFactory` on the classpath *except* the built-in bridges
in `spi/adapters` (those wrap fresh legacy instances that observe nothing, so
including them would allocate ~120 duplicate detectors per test). A user-supplied
detector therefore:

- is instantiated once per `@AsyncTest` method, if `isEnabledFor(config)`,
- receives `onTestStart()` before the first invocation round and `onTestEnd()`
  after the run's analysis,
- has its `Violation`s merged into `analyzeAllNamed()` — keyed by
  `Violation.detector()`, prefixed with the severity label so the `failOn` gate
  classifies them at the severity the detector assigned.

Before 1.7.0 nothing on the execution path ever built an SPI registry: the
published extension point compiled, was discovered by `ServiceLoader` in tests,
and then never ran inside a real test. The registry remains the surface for
programmatic discovery and for incremental migration of each built-in detector
to expose structured violations natively.

## The built-in-package exclusion

`AsyncTestContext` builds `spi.DetectorRegistry.buildExternal(config)` per test, taking every
discovered factory **except** the built-in bridges in `spi/adapters`. Those bridges wrap fresh legacy
instances that observe nothing, so including them would allocate roughly 120 blind duplicate
detectors on every test. Keep the exclusion when touching this path.

## Contract notes

`spi/Detector.java` (`type()`, `analyze()`, `onTestStart()`, `onTestEnd()`) and
`spi/DetectorFactory.java` (`type()`, `isEnabledFor()`, `create()`) are stable public contracts —
extend by adding strategies, never by widening branch conditionals.

`analyze()` must be idempotent: same observed state → same violations, no side effects.
`DetectorRegistry.analyzeAll()` relies on it.

Two classes share the name `DetectorRegistry`: `spi/DetectorRegistry.java` (an effectively-immutable
`EnumMap` populated only in its private constructor, safe to publish) and the package-root
`se.deversity.asynctest.DetectorRegistry` wiring class. The package-root one holds a final field per
detector, constructs each conditionally on its config flag, and calls each `analyzeAll()` in phase
order — the three-step contract in [adding-a-detector.md](adding-a-detector.md).

Built-in detectors are bridged through `spi/adapters/LegacyDetectorAdapter` (reflection-based, once
per round per detector). Its structure is deliberately legacy-shaped — do not refactor it.

---

