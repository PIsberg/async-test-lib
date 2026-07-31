# Adding a Detector

> Part of the [architecture documentation](../ARCHITECTURE.md).

The most common change in this repo, and the easiest to get silently wrong: a new detector only
works when *every* wiring point is updated together. A field without construction, or construction
without analysis, compiles fine and simply never detects anything. Nothing fails loudly — the
detector just doesn't run.

## The synchronized-change contract

One new `DetectorType` constant requires simultaneous changes in five files, all under
`async-test-lib/src/main/java/se/deversity/asynctest/`. Land them as one change, never piecemeal.

1. **`DetectorType.java`** — the new enum constant. This file is `@AILocked`; edit only with
   explicit owner sign-off.
2. **`AsyncTest.java`** — the matching `detectXxx()` annotation attribute. Its name and default
   become stable public API.
3. **`AsyncTestConfig.java`** — public final flag field, `Builder` field plus same-named setter, the
   `from(AsyncTest)` call chain, **and both branches of `build()`** (the `detectAll` if/else pair and
   the non-`detectAll` excludes line). See
   [configuration-resolution.md](configuration-resolution.md).
4. **`DetectorRegistry.java`** — three steps that must land together: (a) the final field,
   (b) conditional construction in the constructor keyed on the config flag, (c) an `analyzeAll()`
   call in the correct phase block.
5. **`AsyncTestContext.java`** — the field copied from the registry plus the static accessor used by
   instrumented code, keeping ThreadLocal install/uninstall symmetric. See
   [execution-flow.md](execution-flow.md).

## The detector class itself

New detectors live in `diagnostics/` and follow the house thread-safety idiom: per-key state in a
`ConcurrentHashMap` with a **get-then-`computeIfAbsent`** hot path, thread-id/name sets as
`ConcurrentHashMap.newKeySet()`, counters as `LongAdder`. Violation lists are `CopyOnWrite` or
synchronized lists; first-registration-wins uses `putIfAbsent`.

Declare parameters and fields as `ConcurrentMap`, not `ConcurrentHashMap` — PMD's `LooseCoupling`
rule fails the build otherwise.

`analyze()` must be idempotent: same observed state → same violations, no side effects.
`DetectorRegistry.analyzeAll()` relies on it.

**Hot-path constraints.** `recordAccess`-style methods run inside every invocation round under full
contention: never introduce O(n²) work, allocation, autoboxing, or lock acquisition there.
`SiteCapture` must not allocate when a site is already captured for a key.

**Locale.** Message formatting that renders floats must pass `Locale.ROOT`. The maintainer's machine
locale is `sv-SE`, where `%f` produces comma decimals and breaks assertions.

## Severity

Findings carry `diagnostics/IssueSeverity` markers embedded in report text, and
`IssueSeverity.fromReport()` infers severity — **defaulting untagged reports to HIGH**. A report that
should gate at a specific level must tag itself; a deadlock report says CRITICAL in its own text.
Severity feeds the `failOn` gate.

## Tests are part of the change

Every detector has a mandated JUnit 5 test at
`async-test-lib/src/test/java/se/deversity/asynctest/diagnostics/<Name>DetectorTest.java`, with an
80% coverage goal. An implementation change without the matching test update is incomplete.
Integration-style coverage typically uses the `EngineTestKit` dummy pattern — see
[../QUALITY_GATES.md](../QUALITY_GATES.md).

## Also register the factory

`spi/adapters/LegacyDetectorFactories.java` exposes each detector through the `DetectorFactory`
`ServiceLoader` path via `LegacyDetectorAdapter`. `AllDetectorsSpiCoverageTest` fails loudly if the
SPI side is incomplete.

The adapter's structure is deliberately legacy-shaped — do not modernize it; touch its business
logic only when explicitly asked.
