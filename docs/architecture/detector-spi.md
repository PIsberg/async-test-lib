# Detector SPI

> Part of the [architecture documentation](../ARCHITECTURE.md).

## What the SPI is for (2.0.0)

`se.deversity.asynctest.spi` is the extension point for detectors the library does not ship. A
user adds a `DetectorFactory` to `META-INF/services/se.deversity.asynctest.spi.DetectorFactory`,
and every `@AsyncTest` run builds, starts, analyses and reports it beside the built-ins.

The built-in detectors are not on this path. They have one registry, the runner's
`se.deversity.asynctest.DetectorRegistry`, which builds each from its factory-table row and holds
the very instances user code records into ([adding-a-detector.md](adding-a-detector.md)).

```
META-INF/services/…DetectorFactory              ← third-party factories only
        │                                          (ServiceLoader.load)
        ▼
DetectorFactory(s)                              ← id(), isEnabledFor(config), create(config)
        │
        ▼  buildExternal(AsyncTestConfig)
spi.DetectorRegistry                             ← one per test, keyed by id
        │
        ▼  analyzeAll()
List<Violation>                                  ← merged into the run's reports
```

**SPI contracts:**

- `Detector` — `id() → String`, `analyze() → List<Violation>`, optional
  `type() → DetectorType` for one that stands for a built-in, optional
  `onTestStart()` / `onTestEnd()` lifecycle hooks. Per-test instance lifecycle.
- `DetectorFactory` — `id()` (or `type()`), `create(AsyncTestConfig)`, and
  `isEnabledFor(AsyncTestConfig)`, which defaults to `config.isEnabled(id())`.
- `DetectorRegistry` (in the `spi` package, distinct from the runner's) —
  `buildExternal(config)` discovers via ServiceLoader, filters by `isEnabledFor` and
  `excludeIds`, instantiates, and keys each detector by its id. Three lookup
  styles: typed `get(Class<T>)`, id-keyed `get(String)` and `get(DetectorType)`,
  which is `get(type.name())`. `analyzeAll()` aggregates structured violations.

**Open detector identity (2.0.0, #919).** A detector's identity is its `id()`. A
built-in's id is its `DetectorType` name, which `id()` defaults to through
`type()`. A genuinely new third-party detector leaves `type()` alone and returns an
id of its own, preferably reverse-DNS (`"com.acme.pool-misuse"`), so it no longer
has to borrow a built-in constant, and two such detectors no longer replace each
other in a type-keyed map. A test switches one off with
`@AsyncTest(excludeIds = {"com.acme.pool-misuse"})` or
`AsyncTestConfig.Builder.excludeIds(...)`; an id the test excludes is never built,
whatever the factory's own `isEnabledFor` says. An id that is not excluded is
enabled, because the detector is on the classpath only when the user put it there.
A built-in name in `excludeIds` excludes that type, as `excludes` would.
`OpenDetectorIdentityTest` pins each of these.

**What happens to a third-party detector in a run.** `AsyncTestContext` builds
`DetectorRegistry.buildExternal(config)` once per `@AsyncTest` method. Each detector:

- is instantiated once per `@AsyncTest` method, if `isEnabledFor(config)` and its id is not
  excluded,
- receives `onTestStart()` before the first invocation round and `onTestEnd()`
  after the run's analysis,
- has its `Violation`s merged into `analyzeAllNamed()`, keyed by
  `Violation.detector()` and prefixed with the severity label, so the `failOn` gate
  classifies them at the severity the detector assigned,
- has a failure in `analyze()` contained through `DetectorFailurePolicy.detectorFailed`, so one
  broken detector cannot cost the others' findings, and strict mode fails the build over it.

`ExternalDetectorSpiWiringTest` pins this end to end. Third-party detectors are unknown to
`DetectorTrust` and resolve to `TrustTier.PROMPT`.

## The built-in bridge, removed in 2.0.0 (#922)

From 1.6.0 to 1.12.x every built-in detector was wired twice. Beside the runner's registry,
`spi.adapters.LegacyDetectorFactories` held one bridge factory per `DetectorType`, listed in
`META-INF/async-test/builtin-detector-factories`, each wrapping a *fresh* detector in a reflective
`LegacyDetectorAdapter`; `spi.DetectorRegistry.build(config)` returned them all. Those instances
were disconnected from the ones user code records into, so they observed nothing, and the view was
called only by tests. `buildExternal` already left them out at runtime, because loading them cost
about 340 ms per forked JVM and allocated about 120 blind detectors per test.

The bridge was the losing path of the two, and 2.0.0 deleted it: the package, the list resource,
`build(config)` and the typed `SharedMessageDigestDetectorFactory` template. What its gates
checked moved to the registry users actually read: `DetectorRegistryFactoryTableTest` (every type
has one row), `DetectorFiringContractTest` (every built detector is silent on empty input and is
handed to an `ifIssue` call) and `DetectorTrustCoverageTest` (every trust row names the class the
registry builds). `DetectorRegistrySpiTest` fails if a bridge factory, the list or `build(config)`
comes back.

## Contract notes

`spi/Detector.java` (`id()`, `type()`, `analyze()`, `onTestStart()`, `onTestEnd()`) and
`spi/DetectorFactory.java` (`id()`, `type()`, `isEnabledFor()`, `create()`) are stable public
contracts. Extend by adding strategies, never by widening branch conditionals.

`analyze()` must be idempotent: same observed state → same violations, no side effects.
`DetectorRegistry.analyzeAll()` relies on it.

Two classes share the name `DetectorRegistry`: `spi/DetectorRegistry.java` (an effectively
immutable id-keyed map populated only in its private constructor, safe to publish) and the
package-root `se.deversity.asynctest.DetectorRegistry` wiring class. The package-root one holds a
final field per detector, builds each from its factory-table row
`create(DetectorType.X, Xxx::new)` when the run enables that type (#916), and calls each
`analyzeAll()` in phase order: the three-step contract in
[adding-a-detector.md](adding-a-detector.md).
