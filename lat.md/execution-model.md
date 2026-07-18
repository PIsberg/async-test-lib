# Execution model

How one `@AsyncTest` method actually runs: JUnit hands off to `ConcurrencyRunner`, which executes N threads × M invocation rounds behind a contention barrier.

The runner is the most carefully calibrated code in the repo: subtle changes to its timeout logic or context lifecycle introduce flaky tests or silently disable detectors. Treat every edit as high-risk and verify with the full suite.

## JUnit extension entry

`extension/AsyncTestExtension.java` implements `TestTemplateInvocationContextProvider` (JUnit-mandated signatures) and provides invocation contexts for `@AsyncTest` methods.

`extension/AsyncTestInvocationInterceptor.java` intercepts each invocation and delegates to `runner/ConcurrencyRunner.execute(...)`.

## Interceptor skip invariant

The interceptor calls `invocation.skip()` — **intentionally**. Never "fix" the skip.

`ConcurrencyRunner` owns the full N×M execution; calling `invocation.proceed()` would run the test body once more outside the barrier, bypassing every detector.

## Rounds and the contention barrier

Each of the `invocations` rounds submits the test body to N workers that all block on a barrier so they hit the test body at the same instant — that simultaneity is the point, maximizing interleaving coverage.

Workers come from a platform pool or a virtual-thread-per-task executor, per config. `runner/SpinContentionBarrier` provides the spin variant. Virtual-thread stress modes (`virtualThreadStressMode`) override the thread count with preset stress levels.

## Timeout budget

`config.timeoutMs` is converted exactly once into an effective budget (scaled by a CI multiplier via `resolveTimeoutMultiplier()`); every downstream wait derives from that single value.

Per-round remaining time, barrier awaits, and completion waits all derive from the effective budget — never from `config.timeoutMs` directly, so scaling happens exactly once. On exhaustion the runner throws a timeout error enriched with deadlock diagnostics (`diagnostics/DeadlockDetector.printThreadDump()`). `DeadlockDetector` baselines pre-existing deadlocked threads at construction so only deadlocks formed during the monitored test are reported ([[detectors#JVM-global vs instance state]]).

## Replay seeds

Failures print a `replaySeed` that reproduces the failing round's randomness when pasted into `@AsyncTest(replaySeed = N)`.

Default 0 draws a fresh seed per round; a non-zero seed fixes every round to that value.

## Context lifecycle

`AsyncTestContext` carries the per-run detector instances to worker threads via a ThreadLocal. Install and uninstall must always be symmetric.

A leaked ThreadLocal propagates stale detector state across test invocations, causing false positives or missed detections. `uninstall()` is idempotent (extra uninstalls are safe no-ops) and the runner relies on that in its outermost finally. `AsyncTestContext.install(...)` may only be called by `ConcurrencyRunner` — nothing else installs contexts. Per-invocation user hooks `@BeforeEachInvocation` / `@AfterEachInvocation` are discovered once per test and invoked around every round.

## Failure paths

On failure the runner prints the replay seed, fires listeners, prints reports, and rethrows; timeout-like assertion errors are rerouted through the timeout error with diagnostics.

The `failOn` gate runs only on the success path ([[reporting#Gating]]) — a failing test is never given a second, synthetic failure from detector findings.
