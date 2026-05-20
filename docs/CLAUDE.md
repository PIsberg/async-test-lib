# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Build and run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "se.deversity.asynctest.AsyncTestContextTest"

# Run a single test method
./gradlew test --tests "se.deversity.asynctest.AsyncTestContextTest.someMethodName"

# Run tests in a subpackage
./gradlew test --tests "se.deversity.asynctest.diagnostics.*"

# Build without running tests
./gradlew build -x test

# Generate JaCoCo coverage report
./gradlew jacocoTestReport

# Generate Javadoc
./gradlew javadoc
```

**Important:** `forkEvery = 1` in `build.gradle.kts` means each test class runs in a separate JVM. Inner/static nested classes matching `*$*` are excluded from direct discovery — they are only invoked via JUnit's `EngineTestKit` in meta-tests like `AsyncTestLibraryMetaTest`.

## Architecture

### Execution Flow

`@AsyncTest` is a JUnit 5 `@TestTemplate`. The wiring is:

1. **`AsyncTest` annotation** — declares `threads`, `invocations`, `timeoutMs`, ~90 detector flags, and (since 1.0.0) `threadCounts`, `preset`, and `replaySeed`. `detectAll = true` (the default) enables every detector at once; individual flags set to `false` opt out. `preset = Preset.X` overrides the detector set with a curated bundle (`ESSENTIALS` / `CI_FAST` / `STRICT` / `NONE` / `ALL`).
2. **`AsyncTestExtension`** — `TestTemplateInvocationContextProvider` that produces one invocation per `@AsyncTest` method, or one per `threadCounts[]` entry when the matrix is non-empty. Each invocation gets its own `AsyncTestInvocationInterceptor` with the entry's thread count.
3. **`AsyncTestInvocationInterceptor`** — converts the annotation into an `AsyncTestConfig` (immutable snapshot) via `AsyncTestConfig.from(ann, threadCount)` and calls `ConcurrencyRunner.execute(...)`.
4. **`ConcurrencyRunner`** — the core orchestrator. Uses a `CyclicBarrier` to force all threads to collide on the test body simultaneously, repeating for `invocations` rounds. Calls `LicenseGuard.check(config)` (cached per JVM since 1.0.0), sets up Phase 1 and Phase 2 detectors, draws / pins a `replaySeed` per round, collects failures from all threads, and calls `DetectorRegistry.analyzeAll()` after the run. Per-worker `latch.countDown()` is guaranteed under every cleanup-failure path.
5. **`DetectorRegistry`** — instantiates only the enabled detector objects (null otherwise) and runs `analyzeAll()` post-test to collect issue reports. **Note:** there is now a second, SPI-driven `DetectorRegistry` in `se.deversity.asynctest.spi` (see "Detector SPI" below); both coexist during the 1.0.0 cutover.
6. **`AsyncTestContext`** — ThreadLocal holder giving test code access to live detector instances via static accessors (e.g., `AsyncTestContext.falseSharingDetector()`), plus `replaySeed()` for RNG-driven test bodies.

### Detector Organization

Detectors live in `src/main/java/se/deversity/asynctest/diagnostics/` and are grouped by phase:

- **Phase 1** — `DeadlockDetector`, `MemoryModelValidator` (visibility), `LivelockDetector` — always-available core
- **Phase 2** — 40+ specialized detectors instantiated by `DetectorRegistry` based on `AsyncTestConfig` flags
- **Phase 3** — Behavioral: `RaceConditionDetector`, `BusyWaitDetector`, `AtomicityValidator`, `ThreadLocalMonitor`, `InterruptMonitor`
- **Phase 4** — Infrastructure: `ThreadLeakDetector`, `SleepInLockDetector`, `UnboundedQueueDetector`, `ThreadStarvationDetector`
- **Phase 5** — Common-type thread safety: `CalendarDetector`, `SimpleDateFormatDetector`, `StringBuilderDetector`, `SharedCollectionDetector`, `TimerDetector`
- **Phase 6** — Virtual thread (Java 21+): `VirtualThreadPinningDetector`, `VirtualThreadCarrierExhaustionDetector`, `VirtualThreadCpuBoundTaskDetector`, `ScopedValueMisuseDetector`, `VirtualThreadContextLeakDetector`
- **Phase 7** — High-level patterns: `CompletableFutureChainDetector`, `CompletableFutureCompletionLeakDetector`, `HttpClientConcurrencyDetector`, `StreamClosingDetector`, `CacheConcurrencyDetector`
- **Phase 8** — Lifecycle & structural correctness: `ExecutorShutdownDetector`, `MutableMapKeyDetector`, `NestedMonitorLockoutDetector`, `LockDowngradeDetector`, `InheritableThreadLocalMisuseDetector`
- **Phase 9** — Repository & environment state: `UncommittedChangesDetector`
- **Phase 10** — API traps & subtle bugs: `ThreadLocalContaminationDetector`, `AtomicNonAtomicUpdateDetector`, `SynchronizedCollectionIterationDetector`, `SharedFormatterDetector`, `ConcurrentMapComputeRecursionDetector`, `SynchronizedOnLiteralDetector`, `PublicLockExposureDetector`, `ForkJoinTaskBlockingDetector`, `OptimisticReadValidationDetector`, `CompletableFutureCommonPoolBlockingDetector`
- **Phase 11** — Thread-safety of additional types: `SharedMatcherDetector`, `SharedDecimalFormatDetector`, `WeakReferenceRaceDetector`, `StatefulLambdaDetector`, `SharedMessageDigestDetector`
- **Phase 12** — Operational & hygiene: `InterruptSwallowingDetector`, `MdcContextLeakDetector`, `SystemPropertyMutationDetector`, `FutureIgnoredDetector`, `ExplicitGcDetector`, `DeprecatedThreadApiDetector`, `SharedXmlParserDetector`, `BoxedPrimitiveLockDetector`, `SharedTimeZoneDetector`, `UncaughtExceptionHandlerDetector`
- **Phase 13** (1.0.0+) — Additional categories: `DaemonThreadHygieneDetector` (non-daemon thread leaks blocking JVM exit), `NotifyWithoutMonitorDetector` (illegal `notify*()` while not holding monitor), `SharedSecureRandomDetector` (`SecureRandom` provider-dependent thread safety), `WeakHashMapSharedDetector` (`WeakHashMap`/`IdentityHashMap` GC + probing hazards), `JdbcConnectionSharedDetector` (JDBC `Connection`/`Statement`/`ResultSet` not thread-safe per spec)

**Total: 100 detectors across 13 phases.** Each follows the same pattern: public recording methods called during the test run (using `ConcurrentHashMap` / `CopyOnWriteArrayList` for thread safety), then `analyze()` post-test returning a typed `*Report` inner class with `hasIssues(): boolean`. Disabled detectors are `null` in `DetectorRegistry` — zero overhead.

**Source-line attribution.** Detectors that have adopted `SiteCapture` (canary: `SharedMessageDigestDetector`) include an `Access sites:` block in their reports pointing at the user-code line that produced the issue. Adding it to a detector is a small mechanical change: declare `Set<SiteCapture.Site> accessSites`, call `SiteCapture.capture().ifPresent(accessSites::add)` in `recordAccess`, render in `analyze()`.

### Key Supporting Types

- **`AsyncTestConfig`** — immutable record of all annotation parameters passed through the execution chain
- **`DetectorType`** enum (`@AILocked`) — used in `excludes = {DetectorType.BUSY_WAITING}` to opt out of specific detectors
- **`Preset`** enum (1.0.0+) — `ALL` / `STRICT` / `ESSENTIALS` / `CI_FAST` / `NONE`; resolved in `AsyncTestConfig.from` by deriving an effective `excludes` set
- **`@BeforeEachInvocation` / `@AfterEachInvocation`** — hooks that fire per invocation round (not once per `@AsyncTest`)
- **`AsyncTestListener` / `AsyncTestListenerRegistry`** — observability API; listeners must be thread-safe. Use `registerScoped(...)` for try-with-resources scoping (1.0.0+) to avoid JVM-wide leakage
- **`AsyncAssert`** — `awaitUntil`, `capture`, plus `awaitAsync(stage, timeout)` (1.0.0+) for awaiting `CompletionStage` inside test bodies
- **`BenchmarkRecorder`** — optional throughput regression tracking; baselines stored in `target/benchmark-data/`
- **`Phase1DetectorSet`** — bundles the three Phase 1 detectors for cleaner hand-off to `ConcurrencyRunner`

### Structured reporting (1.0.0+)

`se.deversity.asynctest.report`:
- **`Violation`** record — `(detector, severity, message, sites, attributes, when)`. Detectors that have migrated populate `analyze().structuredViolations` alongside the legacy string `violations` list.
- **`Formatter`** — functional interface `List<Violation> → String`.
- **`MarkdownFormatter`** / **`JsonFormatter`** — built-in renderers. JSON is hand-rolled (no external dependency) with proper escape handling.

### Detector SPI (1.0.0+)

`se.deversity.asynctest.spi` provides an alternative to the fan-out wiring rule documented in `CLAUDE.md`:
- **`Detector`** — `type()`, `analyze() → List<Violation>`, optional `onTestStart` / `onTestEnd`.
- **`DetectorFactory`** — `type()`, `isEnabledFor(config)`, `create(config)`. Registered via `META-INF/services/se.deversity.asynctest.spi.DetectorFactory`.
- **`DetectorRegistry`** — `build(config)` discovers via `ServiceLoader`, instantiates enabled factories. `get(Class<T>)` typed lookup, `get(DetectorType)` enum lookup, `analyzeAll()` aggregates structured violations.
- **`adapters/SharedMessageDigestDetectorFactory`** — canary adapter wrapping the existing detector. The migration pattern for any of the legacy 90+: existing class unchanged, factory + adapter projects `analyze().structuredViolations`, one line added to the `META-INF/services` file.

The legacy `se.deversity.asynctest.DetectorRegistry` coexists with the SPI one; both run independently. New detectors should use the SPI; existing ones migrate incrementally.

### Consumer Fixture

`consumer-fixture/` is a separate subproject that depends on the built artifact and exercises only the public API. Run it to verify that published-artifact compatibility is intact after changes.

### License Gate

`LicenseGuard` (extracted from `ConcurrencyRunner` in 1.0.0, lives in `se.deversity.asynctest.runner`) integrates `se.deversity.common:common-license-lib`. `LicenseGuard.check(config)` is a `ConcurrentHashMap.get()` on the resolved license-config fingerprint — the real gate fires at most once per fingerprint per JVM, not per test. In CI (`GITHUB_ACTIONS` or `CI` env var set) without a real API key, it automatically activates mock mode — no network calls are made. Locally, mock mode is enabled via `-Dlicense.mock.mode=true`.

### Publishing

Published to Maven Central via `com.vanniktech.maven.publish`. Group/version come from `gradle.properties`. Signing is skipped unless `signingInMemoryKey` gradle property is present (set only in the release workflow).
