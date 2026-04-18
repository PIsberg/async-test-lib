# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

#### Phase 2: Additional Concurrency Detectors
- **Lock Contention** (`detectLockContention`) — detects monitors where more than 20% of
  acquire attempts are blocked (or ≥5 contention events), flagging hot-lock hotspots that
  degrade throughput and scalability under concurrent load
- **Synchronized on Non-Final Field** (`detectSynchronizedNonFinal`) — detects the
  anti-pattern of locking on a field that is not declared `final`; if the reference is
  reassigned between invocations, two threads may synchronize on *different* objects,
  silently breaking mutual exclusion
- **Missed Signal** (`detectMissedSignals`) — detects `notify()` / `notifyAll()` calls
  made when no thread is currently waiting on the condition; the signal is silently
  discarded, causing threads that later call `wait()` to block indefinitely
- **Lazy Initialization Race** (`detectLazyInitRace`) — detects fields that are initialized
  by multiple concurrent threads because the null-guard is unsynchronized or the field is
  not `volatile`; also flags non-volatile fields where several threads simultaneously
  observe `null`, a visibility risk even when only one initialization occurs

#### Documentation & examples
- New example project `05-lock-contention` demonstrating coarse-grained lock contention
  on `RequestCounterService` and the LockContentionDetector hotspot report

## [0.7.0] - 2026-04-17

### Added

#### Phase 6: Virtual Thread Concurrency (Java 21+)
- **Structured Concurrency Misuse** (`detectStructuredConcurrencyIssues`) — detects unclosed
  `StructuredTaskScope`, subtask results accessed before `join()`, scopes closed without
  `join()`, and empty scopes with no subtasks forked
- **Virtual Thread Context Leaks** (`detectVirtualThreadContextLeaks`) — detects `ThreadLocal`
  values set in virtual threads but never removed, `InheritableThreadLocal` misuse inside
  virtual threads, and excessive per-thread `ThreadLocal` counts (prefer `ScopedValue`)
- **ScopedValue Misuse** (`detectScopedValueMisuse`) — detects `ScopedValue.get()` calls
  outside an active binding, unintentional re-binding in nested scopes, and excessive
  simultaneous binding counts
- **Virtual Thread CPU-Bound Tasks** (`detectVirtualThreadCpuBoundTasks`) — detects
  CPU-intensive tasks running on virtual threads without yielding beyond a configurable
  threshold (default 50 ms); monopolising a carrier thread negates virtual-thread scalability
- **Virtual Thread Carrier Exhaustion** (`detectVirtualThreadCarrierExhaustion`) — detects
  scenarios where the count of concurrently blocked virtual threads approaches or exceeds
  the available carrier platform threads, causing scheduler starvation

#### Phase 7: High-Level Concurrency Patterns
- **HTTP Client Concurrency Issues** (`detectHttpClientIssues`) — detects unclosed HTTP
  responses, connection pool exhaustion, and requests initiated but never completed
- **Stream Closing** (`detectStreamClosing`) — detects `InputStream`/`OutputStream`/
  `Reader`/`Writer` instances opened but never closed in concurrent code
- **Cache Concurrency** (`detectCacheConcurrency`) — detects `HashMap`/`LinkedHashMap`
  used as a cache without synchronisation and concurrent read/write races
- **CompletableFuture Chain Issues** (`detectCompletableFutureChainIssues`) — detects
  missing exception handlers, unjoined futures, and improper chain construction

#### Documentation & examples
- New example project `04-virtual-thread-context-leak` demonstrating virtual thread
  context leak detection with a `RequestScopedService`
- Extended consumer-fixture with Phase 6 and Phase 7 usage examples
- README Phase 6 and Phase 7 deep-dive sections with usage patterns and fix guidance

### Maintenance
- Bump `step-security/harden-runner` 2.17.0 → 2.18.0
- Bump `github/codeql-action` 4.35.1 → 4.35.2
- Bump `gradle/actions` 4 → 6
- Bump `org.sonatype.central:central-publishing-maven-plugin` to 0.10.0

## [0.6.2] - 2026-04-13

### Fixed
- Jazzer fuzzing CI: added `repo.maven.apache.org:443` to the harden-runner egress allow-list so Maven can resolve plugins (e.g. `maven-source-plugin`) during `test-compile`.

## [0.5.1] - 2026-04-12

### Fixed
- Re-release of 0.5.0 after initial deployment to Maven Central failed due to a duplicate component conflict.

## [0.5.0] - 2026-04-12

First public release on Maven Central.

### Added

#### Core framework
- `@AsyncTest` annotation — drop-in replacement for `@Test` that runs the test body
  concurrently across a configurable number of threads and invocation rounds
- `@BeforeEachInvocation` / `@AfterEachInvocation` lifecycle hooks that fire once per
  invocation round (complementing JUnit's `@BeforeEach` / `@AfterEach`)
- `AsyncTestContext` — thread-local access to per-test runtime state and detector instances
- Barrier synchronisation via `CyclicBarrier` to maximise thread collision probability
- Virtual thread support (`useVirtualThreads = true`) with stress modes `LOW`, `MEDIUM`,
  `HIGH`, and `EXTREME` (up to 100 000 concurrent virtual threads)
- Configurable timeout per test (`timeoutMs`)
- Benchmarking mode with regression threshold and fail-on-regression flag

#### Phase 1 detectors (always-on)
- **Deadlock detection** — identifies circular lock chains and reports which threads are
  waiting for which locks
- **Memory visibility** — tracks field values across invocations to detect missing
  `volatile` / synchronisation
- **Race condition forcing** — barrier-synchronised thread collisions expose data races
  that standard sequential tests miss
- **Livelock detection** — recognises threads spinning without making progress
- **Starvation detection** — flags threads that are consistently scheduled last

#### Phase 2 detectors (opt-in)
- False sharing (`detectFalseSharing`)
- ABA problem in lock-free code (`detectABAProblem`)
- Lock order validation (`validateLockOrder`)
- Constructor safety / early publication (`validateConstructorSafety`)
- Memory ordering violations (`detectMemoryOrderingViolations`)
- Synchroniser monitoring — `CountDownLatch`, `CyclicBarrier`, `Semaphore`
  (`monitorSynchronizers`)
- Thread pool saturation and queue exhaustion (`monitorThreadPool`)
- Read/write lock fairness (`monitorReadWriteLockFairness`)
- Async pipeline monitoring (`monitorAsyncPipeline`)
- Spurious wakeup / lost notification detection (`detectWakeupIssues`)

#### Phase 3 detectors (opt-in)
- `CompletableFuture` completion leak detection (`detectCompletableFutureCompletionLeaks`)
- Thread pool deadlock detection (`detectThreadPoolDeadlocks`)
- Thread leak detection (`detectThreadLeaks`)
- Sleep-in-lock detection (`detectSleepInLock`)
- Unbounded queue detection (`detectUnboundedQueue`)
- Thread starvation (`detectThreadStarvation`)
- Phaser misuse (`monitorPhaser`)
- Wait-without-timeout detection (`monitorWaitTimeout`)

#### Convenience
- `detectAll = true` — enables all Phase 1, 2 and 3 detectors in one flag
- `excludes` — selectively disable individual detector types when using `detectAll`

#### Build & distribution
- Maven and Gradle (Kotlin DSL) build support
- Published to Maven Central
- Sources JAR, Javadoc JAR, and CycloneDX SBOM generated on every release
- Artifacts signed with GPG and cosign (keyless OIDC) on every release
- OpenSSF Scorecard integration
- Codecov coverage reporting

### Examples
- `01-completablefuture-exception-handling` — demonstrates unhandled exceptions in async
  chains that standard tests miss
- `02-visibility-volatile-flag` — demonstrates memory visibility bugs caused by a missing
  `volatile` keyword

[0.5.0]: https://github.com/PIsberg/async-test-lib/releases/tag/v0.5.0
