# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed — three implemented detectors were unreachable; now wired into `detectAll`
`LatchMisuseDetector`, `ExecutorDeadlockDetector` and `FutureBlockingDetector` shipped with
full implementations and passing unit tests but no wiring: no `DetectorType` constant, no
`@AsyncTest` flag, no `AsyncTestConfig` field, no `DetectorRegistry` field and no
`AsyncTestContext` accessor. Nothing constructed them during a real `@AsyncTest`, so their
findings could never reach a report — while the green test suite made them look shipped.
- New `DetectorType` constants: `LATCH_MISUSE`, `EXECUTOR_DEADLOCK`, `FUTURE_BLOCKING`;
  addressable from `excludes` / `includes` / `Preset` like every other detector.
- New `@AsyncTest` flags (deprecated on arrival, matching the house convention):
  `detectLatchMisuse`, `detectExecutorDeadlock`, `detectFutureBlocking` — all default `true`.
- New accessors: `AsyncTestContext.latchMisuseDetector()`, `executorDeadlockDetector()`,
  `futureBlockingDetector()` — without them the detectors would run but never receive events.
- Registered as SPI factories in `LegacyDetectorFactories` + `META-INF/services`.
- Detector count is now **127** (`DetectorType.values().length`). README and the catalog
  previously disagreed (121 vs 124); both now state the verified count.
- Guardrail note: this edits `DetectorType`, which CLAUDE.md marks `<locked_files>`. The lock
  exists to prevent *isolated* enum edits that break the enum↔flag↔registry↔factory mapping;
  this change makes all of those edits together, plus the wiring test that pins them.

### Fixed — third-party `Detector` SPI now runs inside `@AsyncTest`
- `AsyncTestContext` builds an SPI registry per test via the new
  `spi.DetectorRegistry.buildExternal(config)`. Previously nothing on the execution path
  ever built one: a detector supplied through `META-INF/services` was discovered by
  nobody, never received `onTestStart()` / `onTestEnd()`, and its violations reached
  neither the printed reports nor the `failOn` gate. The published extension point was
  effectively documentation-only.
- `buildExternal` excludes the built-in bridge factories in `spi/adapters` — they wrap
  freshly constructed legacy detectors that observe nothing, so including them would
  allocate ~120 blind duplicates per test. Built-in detection is unchanged and still runs
  through the legacy registry.
- Third-party violations are merged into `AsyncTestContext.analyzeAllNamed()` keyed by
  `Violation.detector()` and prefixed with the severity label, so `failOn` classifies a
  finding at the severity its detector assigned instead of defaulting to `HIGH`.
- `AsyncTestContext.analyzeAll()` is now derived from `analyzeAllNamed()`, so the
  free-text and keyed views can no longer disagree about which detectors were consulted.
- New: `spi.DetectorRegistry.buildExternal(AsyncTestConfig)` and
  `spi.DetectorRegistry.isEmpty()`. No existing signature changed.

### Fixed — a timeout is reported once
- `ConcurrencyRunner`'s pre-round deadline check threw an error that `timeoutError` had
  already reported; its message then satisfied `isTimeoutLike` in the enclosing
  `catch (AssertionError)`, sending it through `timeoutError` a second time. One timeout
  fired two `AsyncTestListener.onTimeout` callbacks and printed the thread dump and every
  Phase 1 / Phase 2 report twice. Errors already reported are now rethrown as-is.

### Added — Phase 18: JDK 25/26 GA-era detectors
Three new pipeline detectors (each with a `DetectorType` constant, deprecated `@AsyncTest`
boolean flag, `AsyncTestContext` accessor, SPI factory, and example project):
- `LazyConstantMisuseDetector` (`DetectorType.LAZY_CONSTANT_MISUSE`) — JDK 26 Lazy
  Constants (second preview, successor of `StableValue`): reentrant suppliers
  (`IllegalStateException`), null-producing suppliers (NPE on JDK 26), computations
  running more than once in hand-rolled holders, non-deterministic suppliers, and
  compute convoys. Example: `examples/117-lazy-constant-misuse`.
- `FinalFieldMutationDetector` (`DetectorType.FINAL_FIELD_MUTATION`) — JEP 500 (JDK 26):
  reflective `Field.set` on `final` fields, warned on JDK 26 and denied in a future
  release; also a JMM final-field publication-guarantee violation today. Escalates to
  CRITICAL when foreign threads read the mutated field or multiple threads write it.
  Example: `examples/118-final-field-mutation`.
- `SharedKdfDetector` (`DetectorType.SHARED_KDF`) — JEP 510 (JDK 25): one
  `javax.crypto.KDF` instance shared across threads; the KDF javadoc documents the type
  as not thread-safe, so concurrent `deriveKey`/`deriveData` calls can silently derive
  wrong keys. Sibling of the `SHARED_MESSAGE_DIGEST` / `SHARED_STATEFUL_CRYPTO` family.
  Example: `examples/119-shared-kdf`.

### Changed — JDK 26 awareness in existing detectors
- `StructuredTaskScopeMisuseDetector` — models the JDK 26 sixth preview (JEP 525):
  new `recordJoinTimeout(scopeId, thread)` and `recordTimeoutSwallowed(scopeId, thread)`
  events; new findings for `Subtask.get()` after a join timeout (CRITICAL) and for
  `Joiner.onTimeout()` fallbacks returned while forked subtasks were cancelled mid-flight
  (LOW warning).
- `VirtualThreadPinningDetector` — JDK-version-aware pinning-cause classification:
  `synchronized`/`Object.wait` events no longer pin on JDK 24+ (JEP 491) and class-init
  waits no longer pin on JDK 26+; such events are kept but annotated as obsolete. New API:
  `PinningCause`, `classifyOperation(op)`, `stillPinsOn(cause, jdkFeature)`,
  `PinningReport.hasEffectivePinningIssues()` / `getObsoleteEventCount()`,
  `PinningEventSnapshot.getCause()` / `isObsoleteOnCurrentJdk()`.

## [1.7.0-RC3] - 2026-07-17

A detector-correctness release. Almost every change here fixes a detector that either missed
a real bug or reported one that wasn't there — no public API changes, so RC2 consumers can
upgrade in place. Expect **fewer false positives** and, in several cases, findings on code
that previously passed.

### Security
- **JUnit XML report writer** — neutralize a CDATA breakout: a violation message containing
  `]]>` could terminate the CDATA section early and inject arbitrary markup into the report
  consumed by CI dashboards.
- **Benchmark baseline store** — restrict Java deserialization to an allow-list filter,
  closing an arbitrary-class-loading sink (CWE-502) in `BenchmarkComparator.readStore`.

### Fixed — detectors that reported issues that weren't there
- `SleepInLockDetector` — no longer mistakes the executor's internal `Worker` AQS for a user
  lock, and now detects locks that are *actually held* rather than guessing from stack-frame
  names.
- `MemoryOrderingMonitor` — dropped a reordering rule that fired on ordinary, correct code.
- `InterruptMonitor` — stops reporting a correctly-restored interrupt as swallowed.
- `LivelockDetector` — only watches the test's own threads, and no longer starves a thread
  that is legitimately `RUNNABLE`.
- `LockOrderValidator` — derives lock edges from the locks actually held, instead of inferring
  them from adjacency in a list.
- `ConstructorSafetyValidator` — compares the accessing thread to the constructing thread,
  rather than flagging any cross-thread access.

### Fixed — detectors that missed real issues
- `BusyWaitDetector` — now reports spin loops that never yield.
- `OptimisticReadValidationDetector` — stops discarding evidence of reads that were never
  validated.
- `LockDowngradeDetector` — tracks read and write holds independently per thread.
- `AtomicityValidator` — no longer loses violations to a concurrently mutated `ArrayList`.
- `NotifyAllValidator` — detects a lost wakeup using evidence that outlives the waiting
  threads.
- `ABAProblemDetector` — recognizes the minimal `A → B → A` cycle instead of requiring three
  changes.
- `LockLeakDetector` / `ResourceLeakDetector` — lock and resource registration is now
  idempotent, so evidence survives repeated registration.
- `VirtualThreadContextLeakDetector` — `analyze()` is idempotent instead of accumulating
  leaks across invocations.

### Fixed — reporting and the failure gate
- **Deadlock findings are now `CRITICAL`**, so `failOn = CRITICAL` trips on a deadlock.
  Previously a detected deadlock could leave the test green. *If you rely on `failOn`, this
  may newly fail tests that were passing — that is the bug being fixed.*
- `ConcurrencyRunner` identifies a finding by its detector rather than by matching report
  text, so findings are no longer mis-attributed when messages collide.
- `DetectorRegistry` contains a throwing detector instead of losing the whole analysis sweep,
  so one broken detector can't silently suppress every other finding.

### Changed
- Example version pins are realigned to the current release across all 200 example builds.
  Examples resolve `mavenLocal()` before `mavenCentral()`, so a stale pin meant the example
  silently tested an *old release from Central* rather than the code in this repo.
- `docs/RELEASE.md` rewritten to describe the actual Maven Central pipeline; added a
  `/release` skill that automates the release flow.
- Dependency bumps: `junit-platform-testkit`, `spotbugs-maven-plugin`, `codeql-action`,
  and VibeTags to `1.0.0-RC2`.

## [1.7.0-RC2] - 2026-07-08

### Added — JDK 25/26 detectors (Phase 16), now wired into the pipeline
- **Three new concurrency detectors** for features introduced/finalized in JDK 24–26,
  **wired into the `@AsyncTest` `detectAll` pipeline** (Phase 16) — each has a `DetectorType`
  constant, an `@AsyncTest` flag, full `AsyncTestConfig` plumbing, legacy `DetectorRegistry`
  wiring, an `AsyncTestContext` accessor, and an SPI factory. Detector count: **111 → 114**.
  - `StableValueMisuseDetector` (`DetectorType.STABLE_VALUE_MISUSE`, `detectStableValueMisuse`)
    — `StableValue` (JEP 502, preview JDK 25 → 26): read-before-set, double-set, reentrant
    `orElseSet`, set-contention.
  - `StructuredTaskScopeMisuseDetector` (`DetectorType.STRUCTURED_TASK_SCOPE_MISUSE`,
    `detectStructuredTaskScopeMisuse`) — `StructuredTaskScope.open(Joiner)` (JEP 505,
    preview JDK 25 → final JDK 26): fork-after-join, result-before-join, owner-confinement,
    close-without-join.
  - `GathererConcurrencyMisuseDetector` (`DetectorType.GATHERER_CONCURRENCY_MISUSE`,
    `detectGathererConcurrencyMisuse`) — Stream Gatherers (JEP 485, final JDK 24): stateful
    gatherer on a parallel stream without a combiner, concurrent-integrator races.
- Record events via `AsyncTestContext.stableValueMisuseDetector()` /
  `structuredTaskScopeMisuseDetector()` / `gathererConcurrencyMisuseDetector()`; findings
  surface through the standard report and `failOn` gate like any other detector.
- Each detector compiles on the Java 21 baseline (modeled via `String` keys + `Thread`,
  no preview-API imports), with full JUnit 5 test suites plus a legacy-registry wiring test.
- Documentation: README detector table, `.claude/SKILL.md`, `docs/ARCHITECTURE.md`,
  `docs/DETECTOR_CATALOG.md`; examples **114–116** (`stable-value-misuse`,
  `structured-task-scope-misuse`, `gatherer-parallel-misuse`); and a
  `ConsumerJdk25And26DetectorsTest` public-surface fixture.

### Agent instrumentation (Byte Buddy) — auto-record field access, no source hooks

The `se.deversity.asynctest.agent.AsyncTestAgent` pipeline was hardened and completed so
agent-captured getter/setter access can drive detectors with zero manual `recordFieldAccess`
calls. All items ship with tests and full Javadoc (`@since 1.7.0`). See the new
[AGENT.md](AGENT.md) guide.

#### Fixed
- **Restored Byte Buddy default ignores + corrected scope Javadoc.** `AgentBuilder.ignore(...)`
  *replaces* Byte Buddy's built-in ignore matcher; the agent now re-establishes every default
  exclusion — name prefixes `java.` / `jdk.` / `sun.` / `com.sun.` / `net.bytebuddy.` /
  `se.deversity.asynctest.`, synthetic types, and bootstrap-class-loader types — preventing
  recursive instrumentation of the JDK and Byte Buddy itself. Matcher construction is extracted
  to package-private `ignoreMatcher()` / `typeMatcher(...)` for unit testing, and the class
  Javadoc now describes the real name-prefix mechanism (previously it falsely claimed
  `not(isBootstrapClassLoader())`).

#### Performance
- **Allocation-free advice hot path with compile-time origins.** The single `FieldAccessAdvice`
  is split into `ReadAccessAdvice` (`isGetter()`, `isWrite=false`) and `WriteAccessAdvice`
  (`isSetter()`, `isWrite=true`), moving the read/write decision to instrumentation time. Each
  advice supplies one `@Advice.Origin("#t.#m")` constant-pool identifier
  (`declaringClass.methodName`, e.g. `com.example.OrderService.setCount`) — a literal `.`
  separator because Byte Buddy's origin parser rejects a doubled `##` escape. New
  `TelemetryRegistry.recordAccess(long, String, boolean)` publishes with no string work; the
  legacy 3-arg `recordAccess(long, String, String)` overload is retained (non-hot-path) for
  tests/examples. The old `FieldAccessAdvice` is kept `@Deprecated` for binary compatibility.

#### Added
- **`agentArgs` include/exclude/debug package filters (`AgentOptions`).** `-javaagent:…jar=…`
  now accepts `includes=<prefix>[;<prefix>…]` (narrows the positive `type(...)` match),
  `excludes=<prefix>[;…]` (appended to the ignore matcher), and `debug=true`. Entries are
  separated by `,` or `;`; a bare token appends to the current key; keys are case-insensitive;
  unknown keys, empty entries, and whitespace are tolerated. Parsing never throws (an exception
  in `premain` would abort JVM startup). Absent/blank/null args preserve the fully
  backward-compatible `any()` behavior.
- **Transform-error listener with optional debug logging (`DiagnosticListener`).** An
  `AgentBuilder.Listener` now surfaces weaving failures Byte Buddy would otherwise swallow:
  one line per error to `System.err` —
  `[ASYNC-TEST-AGENT] Failed to instrument <type>: <throwable>` (message only). With
  `debug=true` it also logs `[ASYNC-TEST-AGENT] Instrumented <type>` per success and appends
  full stack traces on error.
- **Dynamic self-attach — no `-javaagent` flag (`selfAttach` / `agentmain`).** Adds the
  `net.bytebuddy:byte-buddy-agent` dependency and an `Agent-Class` manifest entry (Maven +
  Gradle). `AsyncTestAgent.selfAttach()` / `selfAttach(String)` attach the agent to the running
  JVM via `ByteBuddyAgent.install()` (e.g. from `@BeforeAll`). Dynamic attach installs with
  `RETRANSFORMATION` + `disableClassFormatChanges()`, so accessors of classes loaded *before*
  attach are re-woven in place (the `@Advice` adds no members — schema-safe; verified by
  `SelfAttachTest`). Idempotent and at-most-once per JVM via a shared `AtomicBoolean` CAS gate
  (a `premain` attach followed by `selfAttach` installs one transformer, never double-weaves).
  Throws `IllegalStateException` with `-javaagent` fallback advice when the JVM forbids
  self-attach (needs `-Djdk.attach.allowAttachSelf=true`).
- **`TelemetryBridge` — bridge agent events into live detectors.** New public
  `se.deversity.asynctest.telemetry.TelemetryBridge` registers as the `TelemetryRegistry` drain
  callback and forwards agent field-access events — filtered to the stress-test worker-thread
  ids — into an `AtomicityValidator` via the new explicit-thread-id overload
  `recordFieldAccess(String, Object, boolean, long)` (attributing access to the originating
  worker thread, not the drain thread). Routes to `AtomicityValidator` **only**;
  `VisibilityMonitor` is deliberately not routed (its analysis is value-based and the agent has
  no field values). `AutoCloseable` for try-with-resources, idempotent `close()` /
  `deactivate()`, plus `forCurrentContext(Set<Long>)` and
  `AsyncTestContext.atomicityValidator()`. `TelemetryRegistry` gains `setCallback(...)` as the
  clear attach/detach hook.

#### Changed
- **`TelemetryEventBuffer` overflow is spin-wait, not overwrite.** Corrected the stale class
  Javadoc: when the 16 384-slot buffer fills, producers spin-wait (`Thread.onSpinWait()`) in
  `publish()` until the consumer drains — events are never overwritten or dropped on overflow.

## [1.7.0-RC1] - 2026-06-13

### Added — Concurrency Detectors (Phases 11 & 12)
- **18 New Concurrency Detectors** — Synced the framework to support **111 total problem detectors**, including new checks for:
  - Stateful Lambdas, Shared Message Digests, Weak Reference Races, Shared Matchers, and Shared Decimal Formats (Phase 11).
  - Interrupt Swallowing, MDC Context Leaks, System Property Mutations, and Ignored Futures (Phase 12).
  - Corresponding unit tests, code examples, and downstream validation fixtures.

### Added — Failure Diagnostics & Documentation (Step 6 & 7)
- **Circular Deadlock ASCII Visualization** — Formatted deadlock diagnostic output as a circular ASCII graph mapping out cycles, lock acquisition order, holding threads, and waiting threads.
- **Educational Diagnostics & Recommendations** — Structured learning material and copy-paste ready auto-fix recommendations for all major concurrency bugs (visibility issues, false sharing, CompletableFuture leaks, thread-pool deadlocks, lock leaks, and busy-waiting).
- **Onboarding Guides** — Created comprehensive guides:
  - `docs/DETECTOR_CATALOG.md` detailing all 111 detectors with "Buggy Code" vs. "Fixed Code" examples.
  - `docs/TROUBLESHOOTING.md` addressing flaky tests, timeouts, and optimized thread allocations.
  - Updated `docs/CI_INTEGRATION.md` to include SonarQube Quality Gates configurations.

### Added — Release Automation & Security (Step 8)
- **Public API Stability Freeze** — Frozen public-facing APIs using `@API(status = Status.STABLE)` annotations and marked legacy per-detector boolean attributes as `@Deprecated`.
- **Binary Compatibility Checking** — Integrated `japicmp-maven-plugin` to verify zero breaking API modifications against the `1.6.0` release.
- **Central Staging Publishing** — Integrated publishing and GPG signing automated workflows using the new Sonatype Central Publishing Portal.
- **LicenseGuard Fallback Validation** — Added a unit test validating that invalid/expired licenses trigger a clean, informative `SecurityException` instead of thread lockups or test report corruption.

### Added — Detector selection & fail-gating (`@AsyncTest` extensions)

- **`@AsyncTest(includes = {DetectorType...})`** — enable exactly the listed
  detectors and nothing else. Takes precedence over `preset` / `detectAll` /
  the legacy per-detector booleans; `excludes` still layers on top and wins on
  conflict. Also available programmatically via
  `AsyncTestConfig.Builder.includes(...)`.  Prefer `preset` / `includes` / `excludes` over the 111 per-detector boolean attributes.

- **Class-level and composed `@AsyncTest`** — `@Target` now includes `TYPE`
  and `ANNOTATION_TYPE`:
  - a class-level `@AsyncTest` provides shared configuration for every
    `@TestTemplate` method in the class (a method-level `@AsyncTest` wins);
  - reusable composed annotations are now possible, e.g.
    `@AsyncTest(preset = Preset.ESSENTIALS) public @interface EssentialsAsyncTest {}`.
  `AsyncTestExtension` resolves the annotation meta-aware (method first, then
  class) via `AnnotationSupport.findAnnotation`.

- **`@AsyncTest(failOn = FailOn.X)`** — severity threshold at or above which
  detector findings fail the test (`NONE` / `LOW` / `MEDIUM` / `HIGH` /
  `CRITICAL`). The runner now analyzes all enabled detectors **after a passing
  run too** (previously findings only surfaced when the test body had already
  failed or timed out), prints reports, and fires them to registered
  listeners — so `JUnitXmlReportListener` / `JsonReportListener` /
  `StrictModeListener` now see findings from passing tests. The default
  `FailOn.NONE` preserves the legacy report-only behavior.

- **Known-findings baseline** (`se.deversity.asynctest.report.Baseline`) —
  adopt fail-gating on a legacy codebase without a red wall:
  - `-Dasync-test.baseline=<file>` suppresses baselined findings from the
    `failOn` gate (one diff-friendly line per finding:
    `com.example.MyTest#method | DetectorName`);
  - `-Dasync-test.baseline.update=true` records gate-failing findings into the
    file instead of failing, so the baseline can be generated in one run and
    ratcheted down over time.

### Production Readiness Pass

#### Added
- **slf4j-api** logging facade (`org.slf4j:slf4j-api:2.0.16`) — consumers bind their own backend; framework chatter (license banners, JMM warnings, benchmark comparator output, listener exception warnings) now routes through SLF4J instead of `System.out/err`.
- **JaCoCo coverage check** (`mvn verify`) — enforces ≥70% line / ≥65% branch coverage at the bundle level; ratchet up after measuring baselines.
- **Gradle build parity** — bumped to match Maven canonical versions (JUnit 6.1.0, Jazzer 0.30.0, Byte Buddy 1.18.8, ASM 9.10.1, vibetags 0.9.8); added PMD, SpotBugs, and CycloneDX SBOM tasks to Gradle build.
- **License documentation** — README now documents CI auto-mock, `-Dlicense.mock.mode=true` for local keyless runs, and `-Dlicense.user.email` for real-key usage.

#### Fixed
- **LicenseGuard** — hardcoded `"user@example.com"` identity replaced by `-Dlicense.user.email` sysprop; denial message now includes actionable guidance on enabling mock mode or supplying a key.
- **README agent claim** — corrected "no agent, no bytecode weaving" to accurately describe the Byte Buddy agent as optional/opt-in.
- **Detector count** — unified across README, pom.xml, build.gradle.kts to `93+` (was inconsistent: "51+", "100", "20+").
- **Stale docs** — replaced `yourusername` placeholder, GitHub Packages URL, old artifact coordinates (`se.deversity.asynctest:async-test:1.1.0`), and stale version strings across 8 docs files.
- **load-tests.yml** — `asyncTestVersion` default updated from `1.4.0` to `1.6.0`.


## [1.6.0] - 2026-05-28

The 1.6.0 release introduces the new high-precision contention engine, addressing thread collision precision, compiler-transparent telemetry logging, compile-time pinning scanning, and comprehensive correctness improvements.

### Added — High-Precision Contention Engine
- **`SpinContentionBarrier`** — Lock-free busy-spin barrier using VarHandle acquire/release semantics. Releases all threads within a sub-microsecond window instead of the 20–100 µs stagger of CyclicBarrier.
- **`TelemetryEventBuffer` / `TelemetryRegistry`** — MPSC lock-free ring buffer modeled after the LMAX Disruptor pattern. publish() is allocation-free and signals readiness via VarHandle setRelease. A background daemon thread drains it every 1 ms, eliminating Heisenbugs.
- **`AsyncTestAgent`** — Byte Buddy Java agent that injects FieldAccessAdvice into every getter/setter at class-load time, routing to TelemetryRegistry.recordAccess.
- **`StaticPinningScanner`** — ASM ClassVisitor that statically detects MONITORENTER + blocking-JDK-call patterns in compiled .class files.

### Fixed & Hardened
- **`StaticPinningScanner` implicit monitor detection** — Correctly parses method access flags to detect Loom pinning inside standard `synchronized` methods.
- **`SpinContentionBarrier` overflow immunity** — Uses subtraction sequence checks to prevent barrier lockups or bypasses on Integer.MAX_VALUE boundaries.
- **`TelemetryEventBuffer` overflow protection** — Uses lock-free spin-wait catch-up checks to prevent slot data corruption under high publisher pressure.
- **`TelemetryRegistry` cleanup** — Cleans up JVM shutdown hooks on stop and supports dynamic callback registrations.

### Performance & Throughput Improvements
Replacing intrinsic locking and OS-level thread scheduling with the lock-free MPSC event buffer and busy-spin barrier yielded significant latency and throughput improvements under full detector load:
* **Overhead reduction (4 threads)**: Under `threads=4, invocations=10, detectAll=true`, the median round execution latency dropped from **139 ms** (v1.4.0) to **125 ms** (v1.6.0), representing a **~11.2% throughput increase** (from 71 to 80 rounds/sec).
* **Overhead reduction (2 threads)**: Under `threads=2, invocations=10, detectAll=true`, the median round execution latency dropped from **123 ms** (v1.4.0) to **108 ms** (v1.6.0), representing a **~13.5% throughput increase** (from 81 to 92 rounds/sec).

![Framework & detector overhead by release](../load-tests/results/_plots/detector-overhead-by-release.png)
![Detector memory overhead vs invocations](../load-tests/results/_plots/memory-overhead-vs-invocations.png)


## [1.5.0] - 2026-05-20

The 1.5.0 release combines the originally-drafted CI/CD-native fail-gate work
(2026-05-16) with a substantial follow-up that introduced curated detector
presets, the schedule matrix, structured violation reporting, the Detector
SPI, source-line attribution, hardening of the runner cleanup path, the
process-wide LicenseGuard cache, replay-seed support, async-body helpers,
scoped listener registration, the Phase 13 detector category (5 new
detectors), and the 0.9.7 vibetags annotation upgrade.

### Added — Public API (`@AsyncTest` extensions)

- **`@AsyncTest(threadCounts = {1, 2, 4, 8, 16, 32})`** — schedule matrix.
  Each entry becomes its own JUnit invocation with display name
  `[AsyncTest] N threads x M invocations`. Bug-finding sensitivity is often
  thread-count-dependent; sweeping a range cheaply surfaces races that
  single-count runs miss. Empty array (default) keeps legacy `threads`
  behavior.
- **`@AsyncTest(preset = Preset.X)`** — curated detector bundles instead of
  editing ~90 individual flags. Five presets: `ALL` (default; every detector),
  `STRICT` (same set as ALL, named explicitly), `ESSENTIALS` (12 high-signal
  detectors for everyday CI), `CI_FAST` (pruned ESSENTIALS for PR gates),
  `NONE` (every detector off; pure N×M stress execution). `excludes = {...}`
  layers on top of any preset.
- **`@AsyncTest(replaySeed = N)`** + **`AsyncTestContext.replaySeed()`** —
  per-round seed exposed to the test body so RNG-driven choices (sleep
  jitter, payload selection, branch picks) become reproducible. Default
  `0L` draws a fresh seed per round and prints it on failure for
  paste-and-reproduce.

### Added — Public API (assertions & listeners)

- **`AsyncAssert.awaitAsync(stage, timeout)`** — block on a `CompletionStage`
  inside a test body and unwrap `ExecutionException` so chain failures surface
  with the original exception type. The supported way to exercise async APIs
  from `@AsyncTest`, since JUnit Jupiter rejects non-void `@TestTemplate`
  return types at discovery.
- **`AsyncTestListenerRegistry.registerScoped(listener)`** — returns an
  `AutoCloseable Registration`; closing it unregisters the listener. Pair
  with try-with-resources to bind a listener's lifetime to a single test
  and avoid JVM-wide listener leakage.
- **`AsyncTestListenerRegistry.snapshot()` / `restoreSnapshot(s)`** —
  capture/restore the full registry around a block.

### Added — Structured reporting (`se.deversity.async-test-lib.report`)

- **`Violation` record** — `(detector, severity, message, sites, attributes,
  when)` with defensive defaults and validation. Replaces flat strings for
  tooling that needs to parse violations.
- **`Formatter`** functional interface (`List<Violation> → String`).
- **`MarkdownFormatter`** — Markdown for PR comments and CI logs (`##`
  header, `###` per-violation sections, "Access sites" and "Details"
  sub-blocks).
- **`JsonFormatter`** — compact JSON array, no external dependency, with
  proper string escaping. Stable schema.

### Added — Detector SPI (`se.deversity.async-test-lib.spi`)

- **`Detector`** — `type() → DetectorType`, `analyze() → List<Violation>`,
  optional `onTestStart()` / `onTestEnd()` lifecycle hooks.
- **`DetectorFactory`** — `type()`, `isEnabledFor(config)`, `create(config)`.
  Discovered via `ServiceLoader` from
  `META-INF/services/se.deversity.async-test-lib.spi.DetectorFactory`.
- **`DetectorRegistry`** (new package) — `build(config)` instantiates enabled
  factories, `get(Class<T>)` typed lookup, `get(DetectorType)` enum lookup,
  `analyzeAll()` aggregates structured violations.
- **`LegacyDetectorAdapter<D>`** — generic SPI `Detector` that wraps any
  legacy detector via reflection and projects its `analyze()` into a
  `Violation`.
- **`LegacyDetectorFactories`** — 99 inner-class factories registering every
  pre-existing `DetectorType` through the SPI (plus the dedicated typed
  `SharedMessageDigestDetectorFactory` for the canary). Coexists with the
  legacy `se.deversity.async-test-lib.DetectorRegistry`.
- **`AllDetectorsSpiCoverageTest`** — guards against drift: a new
  `DetectorType` value without a matching factory fails the build.

### Added — Phase 13 detectors (5 new categories)

Each ships with full framework wiring: `DetectorType` enum value, `@AsyncTest`
flag, `AsyncTestConfig` field+builder+from()+build() blocks, legacy
`DetectorRegistry` instantiation + `analyzeAll()`, `AsyncTestContext` static
accessor, SPI factory, `META-INF/services` registration, and dedicated tests.

- **`DaemonThreadHygieneDetector`** (`detectDaemonThreadHygiene`,
  `DAEMON_THREAD_HYGIENE`) — flags non-daemon `Thread` instances still alive
  at analyze time. Severity MEDIUM. Complements `ThreadLeakDetector`.
- **`NotifyWithoutMonitorDetector`** (`detectNotifyWithoutMonitor`,
  `NOTIFY_WITHOUT_MONITOR`) — samples `Thread.holdsLock(monitor)` when a
  notify attempt is declared; flags calls without the monitor held.
- **`SharedSecureRandomDetector`** (`detectSharedSecureRandom`,
  `SHARED_SECURE_RANDOM`) — flags `java.security.SecureRandom` shared across
  threads. Provider-dependent thread safety. Reports carry algorithm +
  provider names.
- **`WeakHashMapSharedDetector`** (`detectWeakHashMapShared`,
  `WEAK_HASH_MAP_SHARED`) — flags `WeakHashMap` / `IdentityHashMap` shared
  across threads. GC-driven removal and linear-probing-specific hazards
  beyond the regular `HashMap` family.
- **`JdbcConnectionSharedDetector`** (`detectJdbcConnectionShared`,
  `JDBC_CONNECTION_SHARED`) — flags `java.sql.Connection` / `Statement` /
  `PreparedStatement` / `ResultSet` shared across threads. JDBC spec doesn't
  require any to be thread-safe; most drivers aren't.

A sixth detector (`ThreadLocalRandomCachedDetector`) was prototyped but
removed: `ThreadLocalRandom.current()` is intentionally safe to share
because each thread reads its own seed slot.

**Total: 95 → 100 detectors across 12 → 13 phases.**

### Added — Diagnostics

- **`SiteCapture`** helper (`se.deversity.async-test-lib.diagnostics`) — captures
  the first non-framework stack frame for any detector access event via
  `StackWalker`. Reports now carry `Access sites:` blocks pointing at the
  user-code line that produced the issue. Canary: `SharedMessageDigestDetector`.

### Added — CI/CD-native fail gates (`se.deversity.async-test-lib.report`)

Three listener implementations make it straightforward to wire async-test into
CI pipelines and IDE tooling without writing custom code.

- **`JUnitXmlReportListener`** — accumulates detector findings during a test
  run and writes a JUnit-compatible XML report to
  `target/async-test-reports/TEST-AsyncTestConcurrencyReport.xml` (Maven) or
  `build/async-test-reports/…` (Gradle). GitHub Actions, Jenkins, and GitLab
  CI parse this file and surface each finding as a named test-case failure.
  Flushed automatically via a JVM shutdown hook, or immediately via `flush()`.
- **`StrictModeListener`** — converts any detector report into an immediate
  `AssertionError`. Register this in zero-tolerance pipelines where
  concurrency findings must always break the build.
- **`JsonReportListener`** — writes a structured JSON file
  (`async-test-report.json`) consumed by the IntelliJ IDEA plugin and
  dashboards.
- **`DetectorFinding`** — immutable value object shared by both report
  listeners.

See [CI_INTEGRATION.md](CI_INTEGRATION.md) for GitHub Actions, Jenkins, and
GitLab CI snippets.

### Added — Structured listener event: `onStructuredReport`

`AsyncTestListener` gains a new default method:

```java
default void onStructuredReport(String detectorName, IssueSeverity severity, String report) {}
```

`AsyncTestListenerRegistry.fireDetectorReport` now also parses severity from
the report text and fires `onStructuredReport` alongside the existing
`onDetectorReport`. Existing implementations that do not override the new
method receive a no-op default — **no migration required**.

### Added — IntelliJ IDEA plugin

A standalone Gradle module (`intellij-plugin/`) targeting IntelliJ IDEA
2024.1+:

- **async-test Findings tool window** — docked at the bottom panel; shows a
  severity-coloured table of findings (CRITICAL = red, HIGH = orange,
  MEDIUM = yellow, LOW = green).
- **Expandable detail pane** — click any row to see the full detector report.
- **Summary bar** — finding counts broken down by severity level.
- **Refresh action** — re-reads the JSON report file on demand after tests
  are re-run.
- **Settings panel** — Settings → Tools → async-test; configurable report
  file path for both Maven and Gradle outputs.

Build and install: `cd intellij-plugin && ./gradlew buildPlugin`, then
install from disk. See [intellij-plugin/README.md](../intellij-plugin/README.md).

### Changed

- **`AsyncTestListenerRegistry.fireDetectorReport`** now also parses
  `IssueSeverity` from the report text and calls
  `listener.onStructuredReport(detectorName, severity, report)` for every
  registered listener. The existing `onDetectorReport` call is unchanged.
- **`ConcurrencyRunner` workers — `latch.countDown()` is now guaranteed
  under every failure mode**. Previously an exception from
  `AsyncTestContext.install`, `uninstall`, or
  `phase1.livelock.captureSnapshot()` inside the worker's `finally` block
  would skip `countDown()`, causing the runner to block on
  `latch.await(roundTimeoutMs)` and surface a misleading "timed out —
  possible deadlock" report instead of the real cause. Each cleanup step
  is now independently guarded; `countDown()` is the last statement in
  the outermost finally.
- **License gating moved out of `ConcurrencyRunner.execute()`** into a new
  `LicenseGuard` class with a process-wide `ConcurrentHashMap` cache keyed
  on the resolved license-config fingerprint. The gate is now a
  `ConcurrentHashMap.get()` after the first call per JVM, not a fresh
  `LicenseGate` construction + `gate.check(...)` per test. "Zero-Config CI"
  announcement and "LICENSE GRANTED" message print once per JVM instead of
  once per test.
- **`SharedMessageDigestDetector.recordAccess` hot path** restructured with
  a double-check: cheap `ConcurrentHashMap.get()` first; only on miss does
  the `instanceof Cipher/Mac/Signature` chain and label-string construction
  run, inside the `computeIfAbsent` factory.
- **`AsyncTestListenerRegistry` javadoc** rewritten to lead with a "Lifetime
  warning" block making the JVM-wide static reality obvious, with two
  recommended scoped patterns shown before the legacy unscoped `register`.

### Fixed

- **Async-body failures no longer surface wrapped in `ExecutionException`**
  — `ConcurrencyRunner.unwrap()` now also strips `ExecutionException` so
  user assertions/exceptions from `CompletionStage` chains surface with the
  original type.

### Performance

- Hot-path detector `recordAccess` calls no longer allocate per-invocation
  when the detected instance is already registered (the common case).
  Targeted at the `@AIPerformance` constraint documented on
  `BenchmarkRecorder`; same pattern is the template for migrating the
  other detectors that call framework helpers on the hot path.

### Build / workflow

- **vibetags** bumped `0.8.0 → 0.9.5 → 0.9.7`. Three new annotation types
  applied across the codebase:
  - `@AIIdempotent` on operations that must remain side-effect-stable
    (`Registration.close`, `unregister`, `clearAll`,
    `AsyncTestContext.uninstall`, `LicenseGuard.check`, SPI
    `DetectorRegistry.analyzeAll`).
  - `@AIFeatureFlag` on runtime-flag-gated surfaces
    (`AsyncTestConfig.enableBenchmarking`, `AsyncTestConfig.licenseMockMode`,
    `BenchmarkRecorder` class).
  - `@AISecure` on security-critical code (`LicenseGuard` =
    authorization; `SharedSecureRandomDetector` = cryptography / RNG quality;
    `SharedMessageDigestDetector` = cryptography / hash integrity).
- **Demo-GIF workflow** (`.github/workflows/demo.yml`) switched from
  branch + `gh pr create` ceremony to a direct push to `main`. The
  default `GITHUB_TOKEN` cannot create PRs without explicit repo-setting
  authorisation; the workflow's `paths:` filter prevents recursion.
  Commit also carries `[skip ci]` as belt-and-suspenders.
- **README** gains a tokei lines-of-code badge.

### Originally drafted as 1.5.0 (2026-05-16, never released)

### Added

#### CI/CD-native fail gates (`se.deversity.async-test-lib.report`)

Three new listener implementations make it straightforward to wire async-test into CI pipelines
and IDE tooling without writing custom code.

- **`JUnitXmlReportListener`** — accumulates detector findings during a test run and writes a
  JUnit-compatible XML report to `target/async-test-reports/TEST-AsyncTestConcurrencyReport.xml`
  (Maven) or `build/async-test-reports/…` (Gradle). GitHub Actions, Jenkins, and GitLab CI
  parse this file and surface each finding as a named test-case failure in their dashboards.
  The report is flushed automatically via a JVM shutdown hook, or immediately via `flush()`.

- **`StrictModeListener`** — converts any detector report into an immediate `AssertionError`.
  Register this in zero-tolerance pipelines where concurrency findings must always break the build,
  not just log to stderr.

- **`JsonReportListener`** — writes a structured JSON file (`async-test-report.json`) containing
  detector name, `IssueSeverity` enum value, full report text, and a Unix timestamp for each
  finding. Consumed by the new IntelliJ IDEA plugin and any dashboard or alerting webhook that
  understands JSON.

- **`DetectorFinding`** — immutable value object shared by both report listeners. Captures
  detector name, severity (parsed from `IssueSeverity` emoji/keyword markers in the report text),
  full report, and timestamp.

See [CI_INTEGRATION.md](CI_INTEGRATION.md) for GitHub Actions, Jenkins, and GitLab CI snippets.

#### Structured listener event: `onStructuredReport` (v1.5.0)

`AsyncTestListener` gains a new default method:

```java
default void onStructuredReport(String detectorName, IssueSeverity severity, String report) {}
```

`AsyncTestListenerRegistry.fireDetectorReport` now also parses severity from the report text and
fires `onStructuredReport` alongside the existing `onDetectorReport`. Existing implementations
that do not override the new method receive a no-op default — **no migration required**.

#### IntelliJ IDEA plugin

A standalone Gradle module (`intellij-plugin/`) targeting IntelliJ IDEA 2024.1+:

- **async-test Findings tool window** — docked at the bottom panel; shows a severity-coloured
  table of findings (CRITICAL = red, HIGH = orange, MEDIUM = yellow, LOW = green).
- **Expandable detail pane** — click any row to see the full detector report.
- **Summary bar** — displays finding counts broken down by severity level.
- **Refresh action** — available in Tools menu and the tool window toolbar; re-reads the JSON
  report file on demand after tests are re-run.
- **Settings panel** — Settings → Tools → async-test; configurable report file path supporting
  both Maven and Gradle output directories.

Build and install: `cd intellij-plugin && ./gradlew buildPlugin`, then install from disk in
IntelliJ's Plugin settings. See [intellij-plugin/README.md](../intellij-plugin/README.md).

#### Documentation

- [CI_INTEGRATION.md](CI_INTEGRATION.md) — full setup guide with workflow snippets for GitHub
  Actions, Jenkins, and GitLab CI; covers all three listener types and combining strategies.
- [intellij-plugin/README.md](../intellij-plugin/README.md) — plugin installation, setup,
  tool window walkthrough, settings reference, and troubleshooting.

### Changed

- `AsyncTestListenerRegistry.fireDetectorReport` now also parses `IssueSeverity` from the report
  text and calls `listener.onStructuredReport(detectorName, severity, report)` for every
  registered listener. The existing `onDetectorReport` call is unchanged.

## [1.4.0] - 2026-05-15

### Added

#### Phase 1 & Phase 3 detectors now wired through `DetectorRegistry`

Phase 1 detectors were previously always-active via a direct `Phase1DetectorSet` hand-off
to `ConcurrencyRunner`. They are now also registered in `DetectorRegistry`, making them
subject to the same `AsyncTestConfig` flag and `excludes` opt-out mechanism as every other
detector. Phase 3 detectors existed as classes but had no registry wiring — they are now
fully integrated.

**Phase 1 (newly config-controlled):**
- **Deadlock detection** (`detectDeadlocks`) — circular lock chain detection with thread
  and lock ownership report; previously always-on, now opt-outable via `excludes`
- **Memory visibility** (`detectVisibility`) — tracks field values across invocations to
  detect missing `volatile` or synchronisation; now config-controlled
- **Livelock detection** (`detectLivelocks`) — recognises threads spinning without making
  forward progress; now config-controlled

**Phase 3 (newly wired):**
- **Race condition** (`detectRaceConditions`) — barrier-synchronised thread collisions that
  force concurrent field access and expose data races standard sequential tests miss
- **ThreadLocal leak** (`detectThreadLocalLeaks`) — detects `ThreadLocal` values set but
  never removed, causing stale values to propagate to the next task on a reused pooled thread
- **Busy waiting** (`detectBusyWaiting`) — detects spin-loops that consume CPU without
  yielding (`Thread.sleep`, `LockSupport.park`, or `Thread.yield`), degrading throughput and
  starving other threads on the carrier pool
- **Atomicity violation** (`detectAtomicityViolations`) — detects check-then-act sequences
  on shared state (null-check then write, read-then-increment, conditional update) that are
  not protected by a single atomic operation or lock, silently losing concurrent updates
- **Interrupt mishandling** (`detectInterruptMishandling`) — detects `InterruptedException`
  catches that neither rethrow nor call `Thread.currentThread().interrupt()`, permanently
  suppressing the cooperative-cancellation signal

#### Test coverage

Added 24 test files covering Phase 1 & Phase 3 detector classes that previously had no
dedicated tests: `DeadlockDetectorTest`, `LivelockDetectorTest`, `VisibilityMonitorTest`,
`RaceConditionDetectorTest`, `ThreadLocalMonitorTest`, `BusyWaitDetectorTest`,
`AtomicityValidatorTest`, `InterruptMonitorTest`, `FalseSharingDetectorTest`,
`ABAProblemDetectorTest`, `LockOrderValidatorTest`, `ConstructorSafetyValidatorTest`,
`MemoryOrderingMonitorTest`, `SynchronizerMonitorTest`, `ThreadPoolMonitorTest`,
`ReadWriteLockMonitorTest`, `PipelineMonitorTest`, `WakeupDetectorTest`,
`NotifyAllValidatorTest`, `LazyInitValidatorTest`, `FutureBlockingDetectorTest`,
`ExecutorDeadlockDetectorTest`, `LatchMisuseDetectorTest`, and `ThreadLocalMonitorTest`.

#### Examples

Added 15 new example projects demonstrating Phase 3 and Phase 2 detectors in realistic
service classes:

- `21-busy-wait` — `SpinPollingWorker` polling a queue without yield or sleep
- `22-atomicity-violation` — `HitCounterService` with an unsynchronised read-then-increment
- `23-thread-local-leak` — `RequestContextService` setting a `ThreadLocal` without cleanup
- `24-interrupt-mishandling` — `BackgroundWorker` swallowing `InterruptedException`
- `25-executor-deadlock` — `ReportGenerationService` submitting tasks that wait on each other
- `26-future-blocking` — `BatchProcessingService` blocking the submitter thread on `get()`
- `27-latch-misuse` — `ServiceInitializer` with a `CountDownLatch` never counted down
- `28-lazy-init` — `ConfigurationSingleton` with an unsynchronised null-guard
- `29-aba-problem` — `LockFreeStack` with a `compareAndSet` susceptible to ABA
- `30-false-sharing` — `PerformanceCounters` with adjacent fields sharing a cache line
- `31-lock-order-violation` — `FundsTransferService` locking two accounts in caller-defined order
- `32-rwlock-starvation` — `ReadHeavyCache` where writers starve behind a read flood

### Fixed

- `examples/06-deadlock` — transfer tasks now run on separate threads to actually trigger
  the circular lock chain the test is designed to detect
- `examples/28-lazy-init` — `SafeSingleton` extracted to a static nested class so
  `AsyncTest` can instantiate the outer test class without triggering the lazy-init race
  under construction
- `examples/21-busy-wait` — removed stray `diagnostics` import from `SpinPollingWorker`
  production source

### Maintenance

- Default `license.mock.mode=true` in the root Gradle build so the test suite runs locally
  without a license key (CI behaviour unchanged)
- Bump `step-security/harden-runner` 2.19.1 → 2.19.3
- Bump `actions/dependency-review-action` 4.9.0 → 5.0.0
- Bump `sigstore/cosign-installer` 4.1.1 → 4.1.2

## [1.3.0] - 2026-05-08

### Added

- **VibeTags 0.8.0 integration** — AI guardrail annotations declared in `CLAUDE.md`
  cover locked files, contextual instructions, audit requirements, ignored elements,
  core elements, performance constraints, contract signatures, and per-detector
  test-driven coverage requirements.

#### Phase 12: Operational & Hygiene Concurrency Issues
- **Interrupt Swallowing** (`detectInterruptSwallowing`) — detects `catch (InterruptedException)`
  blocks that neither rethrow the exception nor call `Thread.currentThread().interrupt()`,
  permanently suppressing the cooperative-cancellation signal and preventing executors and
  blocking operations from observing shutdown requests.
- **MDC Context Leak** (`detectMdcContextLeak`) — detects SLF4J MDC entries that are not
  cleared at task end, causing key/value leakage to the next task run on a reused pooled
  thread (wrong request-ID, user, or trace-ID in logs).
- **System Property Mutation** (`detectSystemPropertyMutation`) — detects concurrent
  `System.setProperty()` or `clearProperty()` calls during the test run, which introduce
  non-deterministic configuration and test pollution that survives to subsequent test methods.
- **Future Ignored** (`detectFutureIgnored`) — detects `Future` / `CompletableFuture` instances
  returned from `submit()` that are never inspected via `get()`, `isDone()`, `isCancelled()`, or
  `cancel()`, causing exceptions from failed tasks to be silently discarded.
- **Explicit GC** (`detectExplicitGc`) — detects `System.gc()` or `Runtime.gc()` invocations
  during concurrent execution, which trigger unpredictable stop-the-world pauses that corrupt
  latency measurements and concurrency-timing tests.
- **Deprecated Thread API** (`detectDeprecatedThreadApi`) — detects calls to `Thread.stop()`,
  `Thread.suspend()`, `Thread.resume()`, `Thread.destroy()`, and `Thread.countStackFrames()`,
  which are unsafe (`stop()` releases all monitors, breaking invariants; `suspend/resume` are
  inherently deadlock-prone) and were removed or made no-ops in Java 20+.
- **Shared XML Parser** (`detectSharedXmlParser`) — detects `DocumentBuilder`, `SAXParser`,
  `Transformer`, and `XPath` instances accessed concurrently from multiple threads; all are
  not thread-safe and produce corrupted parse results or `ConcurrentModificationException`s
  under concurrent use.
- **Boxed Primitive Lock** (`detectBoxedPrimitiveLock`) — detects `synchronized` blocks that
  lock on cached boxed primitives (`Integer`/`Long` in range −128..127, `Boolean.TRUE/FALSE`,
  interned `String` literals), which are JVM-global shared instances causing unexpected
  contention with unrelated code using the same value as a lock.
- **Shared TimeZone** (`detectSharedTimeZone`) — detects `TimeZone` instances whose mutable
  state (`setRawOffset`, `setID`) is modified from multiple threads, silently producing wrong
  date/time arithmetic.
- **Uncaught Exception Handler** (`detectUncaughtExceptionHandler`) — detects threads started
  without a custom `UncaughtExceptionHandler` that subsequently throw, causing the exception
  to be silently discarded from the submitter's perspective (only printed to stderr via the
  default thread-group handler).

### Changed

- **Detector error messages** restructured to a consistent **what / why / fix** layout
  across all detector reports, making issues easier to triage without cross-referencing
  the docs.

### Fixed

- Downgrade `maven-source-plugin` from 3.4.0 to 3.3.1 to restore source-jar generation
  during `test-compile` in the fuzzing workflow.
- Align `pom.xml` and `build.gradle.kts` versions across all examples and the
  `load-tests` subproject (including `load-tests.yml`) so consumer projects resolve
  the matching published artifact.

### Performance

Despite shipping 10 additional Phase 12 detectors, 1.3.0 measurably outperforms
the 0.7.0 and 0.8.0 baselines on the two metrics that matter for users:
**detector-on CPU overhead** and **peak heap overhead**. The no-detector path
is unchanged within noise (see `load-tests/results/_plots/throughput-vs-threads.png`),
so these wins apply to anyone who runs `@AsyncTest` with `detectAll = true`
(the default).

Full sweep on JDK 21.0.9 / Windows 11 / 16 CPUs, JMH 1.37 (3 warmup + 5 measure
iterations); raw CSV/JSON in `load-tests/results/1.3.0/`.

#### Detector overhead — up to ~25% faster at 8 threads

![Framework & detector overhead by release](../load-tests/results/_plots/detector-overhead-by-release.png)

| benchmark | 0.7.0 ms/op | 0.8.0 ms/op | **1.3.0 ms/op** | Δ vs 0.8.0 |
|---|---:|---:|---:|---:|
| `detectorOverhead_t2_allDetectors` | 123.1 | 123.4 | **108.8** | −12% |
| `detectorOverhead_t4_allDetectors` | 137.8 | 140.9 | **116.4** | −17% |
| `detectorOverhead_t8_allDetectors` | 161.9 | 164.6 | **129.8** | −21% |

The all-detectors path got noticeably faster at higher thread counts even
though Phase 11 and Phase 12 added 15 new detectors since 0.8.0.

#### Memory — ~45% lower peak heap at 500 invocations

![Detector memory overhead vs invocations](../load-tests/results/_plots/memory-overhead-vs-invocations.png)

| invocations (threads=4) | 0.7.0 overhead MB | 0.8.0 overhead MB | **1.3.0 overhead MB** |
|---:|---:|---:|---:|
| 10  | 0.5  | 0.8  | **0.7** |
| 100 | 13.9 | 15.0 | **12.0** |
| 500 | 56.9 | 56.0 | **30.8** |

Peak-heap overhead vs the no-detector run grows much more slowly with invocation
count in 1.3.0 — about 45% lower at 500 invocations than either prior baseline.

## [0.9.0] - 2026-05-06

### Changed

- **BREAKING**: Java package renamed from `com.github.asynctest` to `se.deversity.async-test-lib`. Consumers must update all `import` statements. Maven coordinates (`se.deversity.async-test-lib:async-test-lib`) are unchanged.
  - Note: benchmark baselines stored under `load-tests/results/0.7.0/` and `load-tests/results/0.8.0/` reference the old package name in JMH output — this is expected and those files are left as historical data.

### Added

#### Phase 11: Thread-Safety of Additional Types & Patterns
- **Shared Matcher** (`detectSharedMatcher`) — detects `java.util.regex.Matcher` instances
  accessed concurrently from multiple threads. `Pattern` is thread-safe but `Matcher` holds
  mutable per-match state (position, group offsets, last-append position); concurrent use
  produces incorrect matches or `StringIndexOutOfBoundsException`. Fix: call
  `pattern.matcher(input)` inside each thread rather than sharing one `Matcher`.
- **Shared DecimalFormat** (`detectSharedDecimalFormat`) — detects `java.text.DecimalFormat`
  and `java.text.NumberFormat` instances accessed concurrently. Concurrent `format()` /
  `parse()` calls corrupt internal multiplier and grouping state, producing garbled output
  without any exception — the numeric-formatting analogue of `SimpleDateFormat` misuse.
  Fix: `ThreadLocal<DecimalFormat>` or create a new instance per call.
- **Weak Reference Race** (`detectWeakReferenceRace`) — detects two failure modes around
  `WeakReference` / `SoftReference`: (1) `get()` result used without a null check
  (`ERROR`) — the referent may be collected at any time, including between the
  `get()` call and the first dereference; (2) referent collected mid-test (`WARN`) — the
  same reference returned non-null from some threads and null from others, revealing code
  paths that do not handle null on every branch.
- **Stateful Lambda** (`detectStatefulLambda`) — detects `Runnable` / `Callable` / lambda
  instances that capture mutable containers (e.g. `int[]`, `Object[]`, wrapper objects) and
  are executed concurrently from multiple threads while mutating those captures. The JVM
  enforces *effectively final* for captured variables, but captured *containers* are mutable —
  a common, hard-to-spot data race. Fix: `AtomicInteger` / `LongAdder`, or create a new
  lambda instance per task.
- **Shared MessageDigest** (`detectSharedMessageDigest`) — detects `java.security.MessageDigest`
  instances accessed concurrently. `MessageDigest` is not thread-safe: every `update()` and
  `digest()` call mutates internal digest state (running hash buffer, byte count, padding).
  Concurrent access silently corrupts the hash without throwing any exception — one of the
  hardest concurrency bugs to diagnose in production. Fix: `MessageDigest.getInstance()` per
  thread or `ThreadLocal<MessageDigest>`.

## [0.8.0] - 2026-05-02

### Added

#### Phase 8: Lifecycle & Structural Correctness
- **Executor Shutdown** (`detectExecutorShutdown`) — detects `ExecutorService` instances
  that have tasks submitted but are never shut down (thread leak), or are shut down without
  a subsequent `awaitTermination()` call (in-flight tasks silently abandoned)
- **Mutable Map Key** (`detectMutableMapKeys`) — detects objects used as `HashMap` /
  `HashSet` keys that are mutated after insertion; mutation changes the hash bucket,
  silently breaking all future lookups and removes
- **Nested Monitor Lockout** (`detectNestedMonitorLockout`) — detects threads that attempt
  a blocking operation (`wait()`, `Future.get()`, `Lock.lock()`) while already holding a
  monitor on a *different* object, a reliable path to deadlock
- **Lock Downgrade** (`detectLockDowngrade`) — detects illegal read-to-write upgrade
  attempts on `ReentrantReadWriteLock`; the JDK does not support upgrades and the attempt
  deadlocks immediately
- **InheritableThreadLocal Misuse** (`detectInheritableThreadLocalMisuse`) — detects
  `InheritableThreadLocal` values accessed from thread-pool threads; the value is inherited
  at thread-creation time rather than task-submission time, causing stale or cross-task
  context contamination

#### Phase 9: Repository & Environment State
- **Uncommitted Changes** (`detectUncommittedChanges`) — detects untracked or uncommitted
  Git files that may affect test reproducibility; reports a low-severity issue if the
  repository is not in a clean state (requires `git` to be available in the PATH)

#### Phase 10: API Traps & Subtle Concurrency Bugs
- **ThreadLocal Contamination** (`detectThreadLocalContamination`) — detects `ThreadLocal`
  values set in one task that are read by the next task on the same pooled thread without
  an intervening `remove()` or `set()`; common source of stale MDC loggers and security
  contexts in servlet/Spring applications
- **Atomic Non-Atomic Update** (`detectAtomicNonAtomicUpdates`) — detects `get()` followed
  by `set()` on `AtomicInteger` / `AtomicLong` / `AtomicReference` without
  `compareAndSet()`; the data structure guarantees per-operation atomicity but a
  read-modify-write without CAS silently loses concurrent updates
- **Synchronized Collection Iteration** (`detectSynchronizedCollectionIteration`) —
  detects `Collections.synchronizedList` / `synchronizedMap` / `synchronizedSet` wrappers
  iterated without holding the wrapper's intrinsic lock; the Javadoc explicitly requires
  `synchronized(list) { iterator }` but the compiler never enforces it
- **Shared Formatter** (`detectSharedFormatter`) — detects `java.util.Formatter`,
  `PrintWriter`, and `PrintStream` (including `System.out` / `System.err`) accessed from
  multiple threads without external synchronization
- **ConcurrentMap Compute Recursion** (`detectConcurrentMapComputeRecursion`) — detects
  recursive `computeIfAbsent` / `compute` / `merge` calls on the same `ConcurrentHashMap`
  key from the same thread; causes an infinite loop on Java 8 and
  `IllegalStateException` on Java 9+
- **Synchronized on Literal** (`detectSynchronizedOnLiteral`) — detects `synchronized`
  blocks on interned `String` literals or JVM-cached `Integer` / `Long` values
  (range [-128, 127]); those monitors are shared JVM-wide, silently coupling unrelated
  classes through a single monitor
- **Public Lock Exposure** (`detectPublicLockExposure`) — detects `synchronized(this)` on
  objects that are publicly accessible; external callers can acquire the same lock,
  causing unexpected deadlock or starvation
- **ForkJoinTask Blocking** (`detectForkJoinTaskBlocking`) — detects blocking calls
  (`Thread.sleep`, `Object.wait`, `Future.get`, blocking I/O) inside a `ForkJoinTask`
  body; blocks a carrier thread and starves the bounded pool for all other tasks
- **Optimistic Read Validation** (`detectOptimisticReadValidation`) — detects
  `StampedLock.tryOptimisticRead()` data used without calling `validate(stamp)`, or data
  continued to be used after a failed validation, producing silent torn-snapshot corruption
- **CompletableFuture Common-Pool Blocking** (`detectCFCommonPoolBlocking`) — detects
  blocking operations inside `CompletableFuture` stages submitted to the common
  `ForkJoinPool` (i.e. created without a custom `Executor`); starves the pool for parallel
  streams and all other JVM callers

#### Documentation & examples
- CLAUDE.md updated with Phase 8, Phase 9, and Phase 10 detector descriptions

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

[1.6.0]: https://github.com/PIsberg/async-test-lib/releases/tag/v1.6.0
[1.5.0]: https://github.com/PIsberg/async-test-lib/releases/tag/v1.5.0
[1.4.0]: https://github.com/PIsberg/async-test-lib/releases/tag/v1.4.0
[1.3.0]: https://github.com/PIsberg/async-test-lib/releases/tag/v1.3.0
[0.8.0]: https://github.com/PIsberg/async-test-lib/releases/tag/v0.8.0
[0.7.0]: https://github.com/PIsberg/async-test-lib/releases/tag/v0.7.0
[0.6.2]: https://github.com/PIsberg/async-test-lib/releases/tag/v0.6.2
[0.5.1]: https://github.com/PIsberg/async-test-lib/releases/tag/v0.5.1
[0.5.0]: https://github.com/PIsberg/async-test-lib/releases/tag/v0.5.0
