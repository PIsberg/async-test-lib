# Configuration Resolution

> Part of the [architecture documentation](../ARCHITECTURE.md).

How a test declares what to stress and what to detect: `@AsyncTest` attributes snapshotted into an
immutable config, resolved against 139 detector flags. The model is a strict 1:1 mapping maintained
across several places at once — partial edits break detector wiring silently, so read
[adding-a-detector.md](adding-a-detector.md) before touching any of it.

## AsyncTestConfig

`se.deversity.asynctest.AsyncTestConfig` is the immutable snapshot of one `@AsyncTest`'s parameters:
public final fields, built once, safe to share across worker threads. It must stay immutable — no
setters, no mutable state after construction.

Non-detector knobs: `threads`, `invocations`, `timeoutMs`, `useVirtualThreads`,
`virtualThreadStressMode`, `replaySeed`, `failOn`, `enableBenchmarking`, plus the license fields.

## Detector selection resolution

`AsyncTestConfig.Builder.build()` resolves which detector flags end up enabled. Precedence:
**includes beats everything; then the `detectAll` branch; then the non-`detectAll` branch, where
excludes always win over explicit enables.**

1. **includes** — a non-empty `includes` forces the `detectAll` path and excludes every type not
   listed. Explicit excludes still apply on top.
2. **one expression per type** — `build()` collapses the rest into a single line per detector:

   ```java
   detectDeadlocks = (detectAll || detectDeadlocks) && !excludes.contains(DetectorType.DEADLOCKS);
   ```

   `detectAll || flag` covers "on because everything is on" and "on because it was asked for";
   `&& !excludes.contains(...)` gives excludes the last word in both cases.

**That line must exist for every `DetectorType`.** A type missing from it is a real bug: it can be
neither enabled by `detectAll` nor disabled by `excludes`. Ten types were once missing from what was
then a separate excludes branch, and mutation testing caught it; folding the two branches into one
expression removed the possibility of a type being present in one and absent from the other. The
exhaustive per-type mapping test in
`async-test-lib/src/test/java/se/deversity/asynctest/AsyncTestConfigBuildResolutionTest.java`
derives the type→flag mapping empirically and pins it.

## DetectorType and Preset

`DetectorType` is the enum of all detector identities (127 constants) used in `includes` / `excludes`.
It is `@AILocked`: adding a constant requires the synchronized five-file change in
[adding-a-detector.md](adding-a-detector.md).

`Preset` offers curated subsets — `ALL`, `ESSENTIALS`, `STRICT`, `CI_FAST`, `NONE` — enum constants
whose enabled-sets are captured at class init and structurally immutable.

## Severity gate

`FailOn` (`NONE` → `LOW` → `MEDIUM` → `HIGH` → `CRITICAL`) sets the minimum severity at which
detector findings fail the test.

The gate runs **only on the success path**: a test that already failed on its own is never given a
second, synthetic failure from detector findings.

## Feature flags

Flag-gated behaviour must always keep its flag check; never assume a flag is on.

- `enableBenchmarking` / system property `async-test.benchmarking.enabled` (default false) — gates
  the benchmark recorder.
- `licenseMockMode` / system property `license.mock.mode` (default false in production; the POM
  defaults it to true for local test runs) — gates the license guard.

Both are described in [../QUALITY_GATES.md](../QUALITY_GATES.md).
