# Troubleshooting Guide

This guide addresses common issues, tuning parameters, and best practices when using `async-test-lib` in local environments and CI/CD pipelines.

---

## 1. Dealing with Flaky Tests

Because `async-test-lib` forces thread scheduling decisions to trigger race conditions, tests that pass in a normal JVM may occasionally fail under stress due to timing dependencies.

### Common Cause: Too Many Threads
* **Symptom**: Deadlocks are reported or CPU scheduler gets saturated, causing threads to starve.
* **Solution**: Keep thread count aligned with system capacity. The default is `10` threads. For CPU-bound workloads, do not exceed `Runtime.getRuntime().availableProcessors() * 2`.
* **Action**: Configure threads explicitly:
  ```java
  @AsyncTest(threads = 4) // Limit concurrency to avoid CPU thrashing
  void test_heavy_computation() { ... }
  ```

### Common Cause: False-Positive Livelock/Starvation Reports
* **Symptom**: Under heavy VM load, CPU scheduler delays thread execution, causing the Livelock detector to report "Starved Threads" or "No Progress".
* **Solution**: Exclude the livelock/starvation detector from highly sensitive or resource-intensive tests:
  ```java
  @AsyncTest(excludes = {DetectorType.LIVELOCKS})
  void test_io_bound_operation() { ... }
  ```

---

## 2. Adjusting Timeouts for CI/CD

Default timeouts (5,000ms) that work locally can easily trigger timeouts in resource-constrained CI/CD containers (e.g., GitHub Actions hosted runners, shared Jenkins agents) due to noisy neighbors or slower CPU allocation.

### Symptom: `Invocation round timed out` or `Test timed out after 5000ms`
* **Solution 1**: Scale timeouts for test runs in CI/CD using JUnit configuration properties or the `timeoutMs` attribute:
  ```java
  @AsyncTest(timeoutMs = 15000) // Increase total execution ceiling to 15 seconds
  void test_complex_flow() { ... }
  ```
* **Solution 2**: Use matrix sweeps (`threadCounts`) with shorter invocation numbers to offset total run duration:
  ```java
  @AsyncTest(threadCounts = {2, 4, 8}, invocations = 20) // Sweeps counts without high overhead
  void test_contention() { ... }
  ```

---

## 3. Optimizing Thread Counts & Stress Modes

### Thread Counts Selection
* **Contention Sweeps**: Use the `threadCounts` attribute instead of a single `threads` count. Concurrency bugs often surface at specific contention levels (e.g., a bug that occurs at 2 threads may not occur at 32 due to cache line synchronization).
  ```java
  @AsyncTest(threadCounts = {2, 4, 8, 16})
  void test_sweep() { ... }
  ```

### Virtual Threads Stressing
* **Exhaustion or Pinning**: When testing with Loom (`useVirtualThreads = true`), the scheduler uses carrier threads (usually matching CPU core count). Using a high `virtualThreadStressMode` (e.g., `HIGH` or `MAX`) spawns hundreds/thousands of virtual threads. This is great for finding pinning issues but can overwhelm weak CI runners.
* **Tuning Guide**:
  * **Local / High-Spec CI**: Use `virtualThreadStressMode = "MEDIUM"` (spawns 500 virtual threads).
  * **Low-Spec CI / Containerized Runs**: Use `virtualThreadStressMode = "LOW"` (spawns 100 virtual threads) or `OFF`.

---

## 6. `SecurityException: LICENSE DENIED` before any test runs

### Symptom

A run stops immediately, before a single test body executes:

```
java.lang.SecurityException: LICENSE DENIED: <reason>
  To run locally without a key: -Dlicense.mock.mode=true
  In CI (GITHUB_ACTIONS or CI env var set, no key): mock mode activates automatically.
```

### Cause

`LicenseGuard` runs once per configuration at the start of `ConcurrencyRunner.execute`, before the
`CyclicBarrier` is built. It is not reacting to anything your test did; it decided before the test
started.

Mock mode, which bypasses the check, turns itself on in exactly two situations:

* `-Dlicense.mock.mode=true` is set, or
* the run looks like CI (`GITHUB_ACTIONS` or `CI` is set in the environment) **and** no key is
  configured.

A developer machine with no key matches neither, so the gate consults the backend and can refuse.
This is why the same suite passes in CI and stops locally: CI is silently mocked, your laptop is not.

### Fix

For local development, set the flag once rather than per run:

* **Maven**: `mvn test -Dlicense.mock.mode=true`, or add it to `.mvn/jvm.config`.
* **Gradle**: `systemProperty("license.mock.mode", "true")` in your `test { }` block.
* **IDE**: add `-Dlicense.mock.mode=true` to the default JUnit run configuration, so every new test
  you create inherits it.

With a real key, pass `-Dlicense.key=<key>` and set `-Dlicense.user.email=you@example.com`.

### Why it is not simply off by default

The library is [PolyForm Noncommercial](../LICENSE); the gate is the mechanism behind that, not an
accident. It is deliberately loud rather than silently degrading, so that a run which was not
licensed never looks like a run that found no bugs.
