# Configuration Resolution

> Part of the [architecture documentation](../ARCHITECTURE.md).

How a test declares what to stress and what to detect: `@AsyncTest` attributes snapshotted into an
immutable config, resolved against 146 detector flags. The model is a strict 1:1 mapping maintained
across several places at once — partial edits break detector wiring silently, so read
[adding-a-detector.md](adding-a-detector.md) before touching any of it.

## AsyncTestConfig

`se.deversity.asynctest.AsyncTestConfig` is the immutable snapshot of one `@AsyncTest`'s parameters:
public final fields, built once, safe to share across worker threads. It must stay immutable — no
setters, no mutable state after construction.

Non-detector knobs: `threads`, `invocations`, `timeoutMs`, `useVirtualThreads`,
`virtualThreadStressMode`, `replaySeed`, `failOn`, `enableBenchmarking`, plus the license fields.

## Detector selection resolution

`AsyncTestConfig.from(AsyncTest)` turns the annotation into a builder call. The selection comes from
`includes` if it is non-empty, otherwise every type when `detectAll = true` or the preset is
`ALL`/`STRICT`, otherwise the preset's own set. `excludes` and `excludeIds` apply on top. The
defaults are `detectAll = false` and `preset = ESSENTIALS`, so a bare annotation runs the 12
`ESSENTIALS` detectors (2.0.0, #923; `LeanDefaultSelectionTest`). The annotation has no
per-detector attribute since 2.0.0 (#920); under 1.x, 144 of those attributes defaulted to `true`,
so `detectAll = false` left almost every detector on.

`AsyncTestConfig.Builder.build()` resolves the selection once, into one `EnumSet<DetectorType>`
that `AsyncTestConfig.enabledDetectors()` returns (#917). Precedence: **includes beats
everything; then `detectAll`; then the per-detector setters; and excludes always have the last
word.**

```java
EnumSet<DetectorType> enabled = !includes.isEmpty() ? EnumSet.copyOf(includes)
        : detectAll ? EnumSet.allOf(DetectorType.class) : EnumSet.copyOf(explicit);
enabled.removeAll(excludes);
```

The per-detector builder setters add or remove their type in `explicit` (deadlock detection starts
in it). Every public detector flag is then a membership test against the set, assigned in the
constructor:

```java
detectDeadlocks = enabled.contains(DetectorType.DEADLOCKS);
```

**Why one set.** Until #917 each flag had its own resolution line,
`(detectAll || flag) && !excludes.contains(TYPE)`: 146 expressions that could each be wrong, and
ten types were once missing from what was then a separate excludes branch until mutation testing
caught it. A flag derived from the set cannot disagree with it. `AsyncTestConfigEnabledSetTest`
checks that across 200 random selections, `AsyncTestConfigBuildResolutionTest` derives the
type→flag mapping empirically and pins it as a bijection, and `DetectorWiringIsCompleteTest` fails
on a flag with no derivation.

## DetectorType and Preset

`DetectorType` is the enum of all detector identities (one constant per detector) used in `includes` / `excludes`.
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
