# Configuration

How a test declares what to stress and what to detect: `@AsyncTest` attributes snapshotted into an immutable config, resolved against ~123 detector flags.

The whole model is a strict 1:1 mapping maintained across several places at once; partial edits break detector wiring silently, so read [[adding-a-detector]] before touching any of it.

## AsyncTestConfig

`se.deversity.asynctest.AsyncTestConfig` is the immutable snapshot of one `@AsyncTest`'s parameters — public final fields, built once, safe to share across worker threads. It must stay immutable: no setters, no mutable state after construction.

Non-detector knobs: `threads`, `invocations`, `timeoutMs`, `useVirtualThreads`, `virtualThreadStressMode`, `replaySeed`, `failOn`, `enableBenchmarking`, plus license fields ([[quality-gates#License guard]]).

## Detector selection resolution

`AsyncTestConfig.Builder.build()` resolves which detector flags end up enabled. Precedence: includes beats everything; then the detectAll branch; then the non-detectAll branch where **excludes always win over explicit enables**.

1. **includes**: a non-empty `includes` forces the detectAll path and excludes every type not listed. Explicit excludes still apply on top.
2. **detectAll branch**: every type enabled unless excluded — one `if/else` pair per type.
3. **non-detectAll branch**: explicit per-flag enables are respected, then excludes are applied — one `if` per type.

Both branches must enumerate every `DetectorType`. A type missing from either branch is a real bug — 10 types were missing from the excludes branch until mutation testing caught it ([[quality-gates#Mutation testing]]). The exhaustive per-type mapping test in `src/test/java/se/deversity/asynctest/AsyncTestConfigBuildResolutionTest.java` derives the type→flag mapping empirically and pins both branches.

## DetectorType and Preset

`DetectorType` is the enum of all detector identities (~123 constants) used in `includes`/`excludes`. It is effectively **locked**: adding a constant requires synchronized changes in five other places ([[adding-a-detector#The synchronized-change contract]]).

`Preset` offers curated subsets: `ALL`, `ESSENTIALS`, `STRICT`, `CI_FAST`, `NONE` — enum constants whose enabled-sets are captured at class init and structurally immutable.

## Severity gate

`FailOn` (`NONE` → `LOW` → `MEDIUM` → `HIGH` → `CRITICAL`) sets the minimum severity at which detector findings fail the test.

The gate runs only on the success path — a test that already failed on its own is not additionally failed by detector findings ([[reporting#Gating]]).

## Feature flags

Flag-gated behavior must always keep its flag check; never assume a flag is on.

- `enableBenchmarking` / system property `async-test.benchmarking.enabled` (default false) — gates [[quality-gates#Benchmarking]]
- `licenseMockMode` / system property `license.mock.mode` (default false in production; the pom defaults it to true for local test runs) — gates [[quality-gates#License guard]]
