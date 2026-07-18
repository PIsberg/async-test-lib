# Detectors

The ~123 detectors in `diagnostics/` are the product: each watches for a specific concurrency bug class, from deadlocks and races to misused JDK types and virtual-thread pitfalls.

Coverage spans deadlocks, race conditions, visibility, shared mutable JDK types (SimpleDateFormat, MessageDigest, WeakHashMap...), virtual-thread pinning/carrier exhaustion, structured-concurrency and JDK 25/26 API misuse. Detectors accumulated in numbered phases (Phase 1 … Phase 18); phase comments in `AsyncTestConfig.build()` and `DetectorRegistry` group them chronologically.

## Detector SPI

`spi/Detector.java` (`type()`, `analyze()`, `onTestStart()`, `onTestEnd()`) and `spi/DetectorFactory.java` (`type()`, `isEnabledFor()`, `create()`) are stable public contracts; extend by adding strategies, never by widening branch conditionals.

`DetectorFactory` enables ServiceLoader-based discovery of third-party detectors. `analyze()` must be idempotent: same observed state → same violations, no side effects; `DetectorRegistry.analyzeAll()` relies on this. Built-in detectors are bridged through `spi/adapters/LegacyDetectorAdapter` (reflection-based, once per round per detector, deliberately legacy-shaped — do not refactor its structure) and `spi/adapters/LegacyDetectorFactories`.

## DetectorRegistry

Two classes share the name: `spi/DetectorRegistry.java` (effectively-immutable EnumMap, populated only in its private constructor, safe to publish) and the package-root `se.deversity.asynctest.DetectorRegistry` wiring class.

The package-root registry holds one final field per detector, constructs each conditionally on its config flag, and calls each `analyzeAll()` in phase order — the three-step contract in [[adding-a-detector#The synchronized-change contract]].

## Thread-safety idiom

Detectors record events from N racing threads. House pattern: per-key state in a `ConcurrentHashMap` with a **get-then-computeIfAbsent** hot path, thread-id/name sets as `ConcurrentHashMap.newKeySet()`, counters as `LongAdder`.

Violation lists are CopyOnWrite or synchronized lists; first-registration-wins uses `putIfAbsent`. Declare parameters/fields as `ConcurrentMap`, not `ConcurrentHashMap` (PMD LooseCoupling). Any modification must preserve the documented synchronization strategy of the class it touches.

## Hot-path constraints

`recordAccess`-style methods run inside every invocation round under full contention: never introduce O(n²) work, allocation, autoboxing, or lock acquisition there.

`diagnostics/SiteCapture` must not allocate when a site is already captured for a key; `benchmark/BenchmarkRecorder.recordInvocationStart/End` are allocation-free and lock-free in the common case by design.

## JVM-global vs instance state

Detectors that query JVM-wide facilities (ThreadMXBean, thread dumps) must baseline pre-existing state at construction so they only report what the monitored test caused.

`DeadlockDetector.analyze()` excludes thread ids already deadlocked when the detector was created — leaked deadlocked threads from earlier tests in a shared JVM otherwise cause false positives (found by mutation testing, [[quality-gates#Mutation testing]]). Its static `hasDeadlock()` stays JVM-wide by design.

## Severity

Findings carry `diagnostics/IssueSeverity` markers embedded in report text; `IssueSeverity.fromReport()` infers severity, defaulting untagged reports to HIGH.

A report that should gate at a specific level must tag itself — a deadlock report says CRITICAL in its own text. Severity feeds the `failOn` gate ([[reporting#Gating]]).
