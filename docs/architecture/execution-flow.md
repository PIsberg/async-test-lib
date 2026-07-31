# Execution Flow

> Part of the [architecture documentation](../ARCHITECTURE.md).

How one `@AsyncTest` method actually runs, from JUnit's discovery of the template through to the
detector reports. This is the wiring; [detector-architecture.md](detector-architecture.md) covers
what the detectors themselves do, and [runtime-guarantees.md](runtime-guarantees.md) covers the
invariants the runner must not break.

## The chain

`@AsyncTest` is a JUnit 5 `@TestTemplate`. Six pieces carry an invocation:

1. **`AsyncTest` annotation** — declares `threads`, `invocations`, `timeoutMs`, the per-detector
   flags, and (since 1.6.0) `threadCounts`, `preset` and `replaySeed`. `detectAll = true` (the
   default) enables every detector; individual flags set to `false` opt out. `preset = Preset.X`
   replaces the detector set with a curated bundle (`ESSENTIALS` / `CI_FAST` / `STRICT` / `NONE` /
   `ALL`).

2. **`AsyncTestExtension`** — a `TestTemplateInvocationContextProvider` producing one invocation per
   `@AsyncTest` method, or one per `threadCounts[]` entry when that matrix is non-empty. Each
   invocation gets its own `AsyncTestInvocationInterceptor` carrying that entry's thread count.

3. **`AsyncTestInvocationInterceptor`** — converts the annotation into an immutable
   `AsyncTestConfig` snapshot via `AsyncTestConfig.from(ann, threadCount)` and calls
   `ConcurrencyRunner.execute(...)`. It calls `invocation.skip()` deliberately: the runner owns the
   full N×M execution, and restoring `proceed()` would run the body once outside the barrier,
   bypassing every detector.

4. **`ConcurrencyRunner`** — the orchestrator. A `CyclicBarrier` forces all threads to collide on
   the test body simultaneously, repeated for `invocations` rounds. It calls
   `LicenseGuard.check(config)` (cached per JVM since 1.6.0), sets up Phase 1 and Phase 2 detectors,
   draws or pins a `replaySeed` per round, collects failures from every thread, and calls
   `DetectorRegistry.analyzeAll()` afterwards. Per-worker `latch.countDown()` is guaranteed under
   every cleanup-failure path — see [runtime-guarantees.md](runtime-guarantees.md).

5. **`DetectorRegistry`** — instantiates only the enabled detectors (`null` otherwise) and runs
   `analyzeAll()` post-test to collect reports. There are two registries: the legacy
   `se.deversity.asynctest.DetectorRegistry` and the SPI-driven
   `se.deversity.asynctest.spi.DetectorRegistry`. Both coexist and run independently — see
   [detector-spi.md](detector-spi.md).

6. **`AsyncTestContext`** — a ThreadLocal holder giving test code access to the live detector
   instances through static accessors (`AsyncTestContext.falseSharingDetector()` and friends), plus
   `replaySeed()` for RNG-driven test bodies. Install and uninstall must always be symmetric; a leak
   propagates stale detector state into the next invocation and produces false positives or missed
   detections.

## Key supporting types

- **`AsyncTestConfig`** — immutable record of every annotation parameter, passed down the chain.
- **`DetectorType`** (`@AILocked`) — the enum addressing each pipeline detector, used in
  `excludes = {DetectorType.BUSY_WAITING}`. Adding a constant is a synchronized multi-place change;
  the module's `.claude/rules/` spell it out.
- **`Preset`** (1.6.0+) — `ALL` / `STRICT` / `ESSENTIALS` / `CI_FAST` / `NONE`, resolved in
  `AsyncTestConfig.from` by deriving an effective `excludes` set.
- **`@BeforeEachInvocation` / `@AfterEachInvocation`** — hooks firing per invocation round, not once
  per `@AsyncTest` method.
- **`AsyncTestListener` / `AsyncTestListenerRegistry`** — the observability API; listeners must be
  thread-safe. `registerScoped(...)` (1.6.0+) gives try-with-resources scoping instead of JVM-wide
  registration. See [observability.md](observability.md).
- **`AsyncAssert`** — `awaitUntil`, `capture`, and `awaitAsync(stage, timeout)` (1.6.0+) for
  awaiting a `CompletionStage` inside a test body.
- **`BenchmarkRecorder`** — optional throughput-regression tracking; baselines land in
  `target/benchmark-data/`.
- **`Phase1DetectorSet`** — bundles the three Phase 1 detectors for a cleaner hand-off to
  `ConcurrencyRunner`.

## Invariants the runner must not break

`ConcurrencyRunner` is the most carefully calibrated code in the repo. Subtle changes to its timeout
logic or context lifecycle introduce flaky tests or silently disable detectors. Treat every edit as
high-risk and verify with the full suite.

**The interceptor skip.** `AsyncTestInvocationInterceptor` calls `invocation.skip()` intentionally.
Never "fix" it: the runner owns the full N×M execution, and `invocation.proceed()` would run the test
body once more outside the barrier, bypassing every detector.

**Rounds and the contention barrier.** Each of the `invocations` rounds submits the test body to N
workers that all block on a barrier so they hit the body at the same instant — that simultaneity is
the point, maximising interleaving coverage. Workers come from a platform pool or a
virtual-thread-per-task executor per config; `runner/SpinContentionBarrier` provides the spin
variant. Virtual-thread stress modes (`virtualThreadStressMode`) override the thread count with
preset stress levels.

**The timeout budget is converted exactly once.** `config.timeoutMs` becomes an effective budget
scaled by a CI multiplier via `resolveTimeoutMultiplier()`, and every downstream wait — per-round
remaining time, barrier awaits, completion waits — derives from that single value, never from
`config.timeoutMs` directly. That is what keeps the scaling from being applied twice. On exhaustion
the runner throws a timeout error enriched with `DeadlockDetector.printThreadDump()`.

**Context lifecycle.** `AsyncTestContext.install(...)` may only be called by `ConcurrencyRunner` —
nothing else installs contexts. `uninstall()` is idempotent (extra uninstalls are safe no-ops) and
the runner relies on that in its outermost `finally`. A leaked ThreadLocal propagates stale detector
state across invocations, causing false positives or missed detections. `@BeforeEachInvocation` /
`@AfterEachInvocation` hooks are discovered once per test and invoked around every round.

**Replay seeds.** Failures print a `replaySeed` that reproduces the failing round's randomness when
pasted into `@AsyncTest(replaySeed = N)`. The default `0` draws a fresh seed per round; a non-zero
seed fixes every round to that value.

**Failure paths.** On failure the runner prints the replay seed, fires listeners, prints reports and
rethrows; timeout-like assertion errors are rerouted through the timeout error with diagnostics. The
`failOn` gate runs only on the success path — see
[configuration-resolution.md](configuration-resolution.md).
