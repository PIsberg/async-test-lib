# Detector Architecture

> Part of the [architecture documentation](../ARCHITECTURE.md).

Detectors live in `async-test-lib/src/main/java/se/deversity/asynctest/diagnostics/`. **127 are
wired into the `@AsyncTest` pipeline**, one `DetectorType` enum constant each, spanning 18 phases.
`AllDetectorsSpiCoverageTest` pins the count — the built-in factory count must equal
`DetectorType.values().length` — so that number cannot drift silently.

## The common pattern

Every detector follows the same shape:

- public `recordXxx(...)` methods called during the test run, using `ConcurrentHashMap` /
  `ConcurrentHashMap.newKeySet()` / `LongAdder` for thread safety;
- `analyze()` post-test, returning a typed `*Report` inner class with `hasIssues(): boolean`;
- disabled detectors are `null` in `DetectorRegistry` — zero overhead when off.

The conventions that hold for *all* of them (mandatory matching test, thread-safe by construction,
allocation-free on the record path) are stated once in
`async-test-lib/.claude/rules/async-test-detectors.md` rather than repeated per class.

## Phases

- **Phase 1** — `DeadlockDetector`, `MemoryModelValidator` (visibility), `LivelockDetector` — always-available core
- **Phase 2** — 40+ specialized detectors instantiated by `DetectorRegistry` from `AsyncTestConfig` flags
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
- **Phase 13** (1.6.0+) — `DaemonThreadHygieneDetector` (non-daemon leaks blocking JVM exit), `NotifyWithoutMonitorDetector` (illegal `notify*()` without the monitor), `SharedSecureRandomDetector` (provider-dependent thread safety), `WeakHashMapSharedDetector` (`WeakHashMap`/`IdentityHashMap` GC + probing hazards), `JdbcConnectionSharedDetector` (JDBC `Connection`/`Statement`/`ResultSet` are not thread-safe per spec)
- **Phase 14** — Additional types & escape hazards: `SharedStatefulCryptoDetector` (`Cipher`/`Mac`/`Signature` shared mid-operation), `NonAtomicConcurrentMapUpdateDetector` (check-then-act on a `ConcurrentMap`), `SharedDeflaterDetector`, `ThisEscapeDetector` (constructor `this`-escape), `ThreadLocalRandomMisuseDetector` (cached `ThreadLocalRandom` used off-thread)
- **Phase 15** — Asynchronous flow & lock-usage hazards: `CompletableFutureObtrudeDetector`, `SpuriousWakeupDetector`, `LockUpgradeDeadlockDetector`, `TryLockMisuseDetector`, `CompletableFutureBlockingCallbackDetector`
- **Phase 16** — JDK 25/26 preview-era: `StableValueMisuseDetector` (read-before-set / double-set / reentrant `orElseSet`), `StructuredTaskScopeMisuseDetector` (fork-after-join / result-before-join / owner-confinement / missing join, plus JDK 26 join-timeout events since 1.8.0), `GathererConcurrencyMisuseDetector` (stateful parallel gatherer without a combiner)
- **Phase 17** — Shared stateful JDK objects & I/O races: `SharedByteBufferDetector`, `SharedCharsetCoderDetector`, `SharedChecksumDetector`, `FileChannelPositionRaceDetector`, `SharedIteratorDetector`, `HighContentionAtomicDetector`, `SharedJsonMapperReconfigDetector`
- **Phase 18** (1.8.0+) — JDK 25/26 GA-era: `LazyConstantMisuseDetector` (JDK 26 Lazy Constants — reentrant / null-producing / repeat-running / non-deterministic suppliers), `FinalFieldMutationDetector` (JEP 500 reflective final-field mutation — a JMM violation, denied in a future JDK), `SharedKdfDetector` (JEP 510 `javax.crypto.KDF` shared across threads)

## Wiring a new pipeline detector

A synchronized change across `DetectorType` (an `@AILocked` enum — edit only with explicit owner
sign-off), the `@AsyncTest` flag, `AsyncTestConfig` (field / builder default / setter / `from()` /
both `build()` blocks), the legacy `DetectorRegistry` (field / constructor / `analyzeAll`), the
`AsyncTestContext` accessor, and the SPI (`LegacyDetectorFactories` class + `META-INF/services`
line). `AllDetectorsSpiCoverageTest` fails loudly if the SPI side is incomplete. The Phase 16
detectors were wired this way.

## Source-line attribution

Detectors that have adopted `SiteCapture` (canary: `SharedMessageDigestDetector`) include an
`Access sites:` block in their reports pointing at the user-code line that produced the issue.
Adding it is mechanical: declare `Set<SiteCapture.Site> accessSites`, call
`SiteCapture.capture().ifPresent(accessSites::add)` in `recordAccess`, render in `analyze()`. The
mechanism itself is described in [reporting-pipeline.md](reporting-pipeline.md).

![Detector Architecture Diagram](../diagrams/DetectorArchitecture.png)

**Source:** [`detector-architecture.puml`](../diagrams/detector-architecture.puml) — the PlantUML
diagrams still reflect the pre-1.6.0 wiring and are accurate for the legacy registry only.

---

## Standalone validators (not pipeline-wired)

Five validators are instantiated directly in test code rather than through `DetectorRegistry`:
`NotifyAllValidator`, `LazyInitValidator`, `FutureBlockingDetector`, `ExecutorDeadlockDetector`,
`LatchMisuseDetector`. They follow the same `recordXxx(...)` → `analyze(): Report` pattern and are
thread-safe, but have no `DetectorType` constant, so `excludes` / `preset` do not address them.

Instantiate the validator (one instance can be shared across an `@AsyncTest`'s worker threads), call
its `recordXxx(...)` methods from the test body, then call `analyze()` and assert on the returned
report's `hasIssues()`. To promote one to a full pipeline detector, add the enum constant and config
flag, then either an `AsyncTestContext` accessor (legacy path) or a `DetectorFactory` +
`META-INF/services` line (SPI path) — see [detector-spi.md](detector-spi.md).
