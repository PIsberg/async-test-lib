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

1. **`AsyncTest` annotation** — declares `threads`, `invocations`, `timeoutMs`, and ~55 detector flags. `detectAll = true` (the default) enables every detector at once; individual flags set to `false` opt out.
2. **`AsyncTestExtension`** — `TestTemplateInvocationContextProvider` that registers `AsyncTestInvocationInterceptor` for each `@AsyncTest` method.
3. **`AsyncTestInvocationInterceptor`** — converts the annotation into an `AsyncTestConfig` (immutable snapshot) and calls `ConcurrencyRunner.execute(...)`.
4. **`ConcurrencyRunner`** — the core orchestrator. Uses a `CyclicBarrier` to force all threads to collide on the test body simultaneously, repeating for `invocations` rounds. Integrates the license gate, sets up Phase 1 and Phase 2 detectors, collects failures from all threads, and calls `DetectorRegistry.analyzeAll()` after the run.
5. **`DetectorRegistry`** — instantiates only the enabled detector objects (null otherwise) and runs `analyzeAll()` post-test to collect issue reports.
6. **`AsyncTestContext`** — ThreadLocal holder giving test code access to live detector instances via static accessors (e.g., `AsyncTestContext.falseSharingDetector()`).

### Detector Organization

Detectors live in `src/main/java/se/deversity/asynctest/diagnostics/` and are grouped by phase:

- **Phase 1** — `DeadlockDetector`, `MemoryModelValidator` (visibility), `LivelockDetector` — always-available core
- **Phase 2** — 40+ specialized detectors instantiated by `DetectorRegistry` based on `AsyncTestConfig` flags
- **Phase 3** — Behavioral: `RaceConditionDetector`, `BusyWaitDetector`, `AtomicityValidator`, `ThreadLocalMonitor`, `InterruptMonitor`
- **Phase 4** — Infrastructure: `ThreadLeakDetector`, `SleepInLockDetector`, `UnboundedQueueDetector`, `ThreadStarvationDetector`
- **Phase 5** — Common-type thread safety: `CalendarDetector`, `SimpleDateFormatDetector`, `StringBuilderDetector`, `SharedCollectionDetector`, `TimerDetector`
- **Phase 6** — Virtual thread (Java 21+): `VirtualThreadPinningDetector`, `VirtualThreadCarrierExhaustionDetector`, `VirtualThreadCpuBoundTaskDetector`, `ScopedValueMisuseDetector`, `VirtualThreadContextLeakDetector`
- **Phase 7** — High-level patterns: `CompletableFutureChainDetector`, `CompletableFutureCompletionLeakDetector`, `HttpClientConcurrencyDetector`, `StreamClosingDetector`, `CacheConcurrencyDetector`
- **Phase 8** — Lifecycle & structural correctness: `ExecutorShutdownDetector` (executor never shut down or missing `awaitTermination`), `MutableMapKeyDetector` (map/set keys mutated after insertion), `NestedMonitorLockoutDetector` (blocking op while holding a different object's monitor), `LockDowngradeDetector` (illegal read→write upgrade on `ReentrantReadWriteLock`), `InheritableThreadLocalMisuseDetector` (`InheritableThreadLocal` in thread pools — value is inherited at thread-creation time, not task-submission time)
- **Phase 9** — Repository & environment state: `UncommittedChangesDetector` (untracked or uncommitted Git files detected via `git status --porcelain`)

Each detector follows the same pattern: public recording methods called during the test run (using `ConcurrentHashMap` / `CopyOnWriteArrayList` for thread safety), then `analyze()` post-test returning a typed `*Report` inner class with `hasIssues(): boolean`. Disabled detectors are `null` in `DetectorRegistry` — zero overhead.

### Key Supporting Types

- **`AsyncTestConfig`** — immutable record of all annotation parameters passed through the execution chain
- **`DetectorType`** enum — used in `excludes = {DetectorType.BUSY_WAITING}` to opt out of specific detectors when `detectAll = true`
- **`@BeforeEachInvocation` / `@AfterEachInvocation`** — hooks that fire per invocation round (not once per `@AsyncTest`)
- **`AsyncTestListener` / `AsyncTestListenerRegistry`** — observability API; listeners must be thread-safe
- **`BenchmarkRecorder`** — optional throughput regression tracking; baselines stored in `target/benchmark-data/`
- **`Phase1DetectorSet`** — bundles the three Phase 1 detectors for cleaner hand-off to `ConcurrencyRunner`

### Consumer Fixture

`consumer-fixture/` is a separate subproject that depends on the built artifact and exercises only the public API. Run it to verify that published-artifact compatibility is intact after changes.

### License Gate

`ConcurrencyRunner` integrates `se.deversity.common:common-license-lib`. In CI (`GITHUB_ACTIONS` or `CI` env var set) without a real API key, it automatically activates mock mode — no network calls are made. Locally, mock mode is enabled via `-Dlicense.mock.mode=true`.

### Publishing

Published to Maven Central via `com.vanniktech.maven.publish`. Group/version come from `gradle.properties`. Signing is skipped unless `signingInMemoryKey` gradle property is present (set only in the release workflow).
