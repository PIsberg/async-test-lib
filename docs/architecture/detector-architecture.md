# Detector Architecture

> Part of the [architecture documentation](../ARCHITECTURE.md).

## Detector Architecture

Shows the structure and common pattern of all detectors.

**Phase 1: Core Detectors (9 classes)**
- DeadlockDetector, VisibilityMonitor, LivelockDetector
- RaceConditionDetector, ThreadLocalMonitor, BusyWaitDetector
- AtomicityValidator, InterruptMonitor, MemoryModelValidator
- Run automatically on timeout
- Detect core concurrency issues

**Phase 2: Advanced Detectors (20 classes)**
- FalseSharingDetector, WakeupDetector, ConstructorSafetyValidator
- ABAProblemDetector, LockOrderValidator, SynchronizerMonitor
- ThreadPoolMonitor, MemoryOrderingMonitor, PipelineMonitor
- ReadWriteLockMonitor, SemaphoreMisuseDetector
- CompletableFutureExceptionDetector, ConcurrentModificationDetector
- LockLeakDetector, SharedRandomDetector, BlockingQueueDetector
- ConditionVariableDetector, SimpleDateFormatDetector
- ParallelStreamDetector, ResourceLeakDetector
- Opt-in via annotation flags
- Record events during test execution

**Phase 3: Behavioral Detectors (5 classes) — auto-wired via DetectorRegistry**
- RaceConditionDetector, ThreadLocalMonitor, BusyWaitDetector
- AtomicityValidator, InterruptMonitor
- Instantiated and analyzed automatically when their config flags are enabled

**Standalone Validators (5 classes) — manual instantiation**
- NotifyAllValidator, LazyInitValidator, FutureBlockingDetector
- ExecutorDeadlockDetector, LatchMisuseDetector
- Instantiate directly in test code; not wired into DetectorRegistry

**Phase 4: Infrastructure & Resource Management (4 classes)**
- ThreadLeakDetector, SleepInLockDetector, UnboundedQueueDetector, ThreadStarvationDetector
- Detect operational / infrastructure-level concurrency hazards

**Phase 5: Thread-Safety of Common Types (5 classes)**
- CalendarDetector, SharedCollectionDetector, TimerDetector
- CopyOnWriteCollectionDetector, StringBuilderDetector
- Detect misuse of JDK types that are not thread-safe

**Phase 6: Virtual Thread Concurrency — Java 21+ (3 classes)**
- StructuredConcurrencyMisuseDetector — unclosed scopes, skipped join(), unsafe result access
- VirtualThreadContextLeakDetector — ThreadLocal values set but not removed in virtual threads,
  InheritableThreadLocal misuse, excessive ThreadLocal count per virtual thread
- ScopedValueMisuseDetector — ScopedValue.get() outside a binding, unintentional re-binding

**Common Pattern:**
- Abstract base with `analyze*(): Report` and `hasIssues: boolean`
- Concrete detectors implement analysis logic
- Return specific report types (e.g., FalseSharingReport)

![Detector Architecture Diagram](../diagrams/DetectorArchitecture.png)

**Source:** [`detector-architecture.puml`](../diagrams/detector-architecture.puml)

---

## JDK 25/26 Preview-Era Detectors (Standalone, 1.7.0+)

Three detectors target concurrency features introduced or finalized in JDK 24–26.
They follow the **same `recordXxx(...)` → `analyze(): Report` pattern** as every other
diagnostics detector and are thread-safe (`ConcurrentHashMap` / `newKeySet`-backed), but
they are deliberately **not wired into the `@AsyncTest` execution pipeline**:

- `se.deversity.asynctest.diagnostics.StableValueMisuseDetector` — `StableValue`
  (JEP 502, preview JDK 25 → JDK 26): read-before-set, double-set, reentrant
  `orElseSet` supplier, set-contention.
- `se.deversity.asynctest.diagnostics.StructuredTaskScopeMisuseDetector` —
  `StructuredTaskScope` (JEP 505, fifth preview JDK 25 → final JDK 26): fork-after-join,
  `Subtask.get()` before join, owner-confinement violation, close-without-join.
- `se.deversity.asynctest.diagnostics.GathererConcurrencyMisuseDetector` — Stream
  Gatherers (JEP 485, final JDK 24): stateful gatherer on a parallel stream with no
  combiner, concurrent-integrator shared-state race.

**Why standalone, not pipeline-wired.** Every pipeline detector is addressable via a
`DetectorType` enum constant (used by `excludes` / `includes` / `Preset` / the SPI's
`type()`). `DetectorType` is an `@AILocked` file: adding a constant requires the
synchronized six-place change documented in `CLAUDE.md` (annotation field, config field +
builder default, `from(AsyncTest)`, both `build()` branches, and the registry arm). Rather
than make that locked change in isolation, these three ship as standalone, directly-usable
detectors. They are implemented entirely against `String` keys and `Thread` (no
`java.lang.StableValue` / new `StructuredTaskScope` imports), so they **compile and run on
the project's Java 21 baseline** while modeling APIs that only exist on JDK 24/25/26.

**Usage.** Instantiate the detector (one instance can be shared across an `@AsyncTest`'s
worker threads), call its `recordXxx(...)` methods from the test body, then call
`analyze()` and assert on the returned report's `hasIssues()`. To promote any of them to a
full pipeline detector once a `DetectorType` slot is available: add the enum constant +
config flag, then either add an `AsyncTestContext` accessor (legacy path) or a
`DetectorFactory` + `META-INF/services` line (SPI path) — see [Detector SPI](#detector-spi-100).

---

