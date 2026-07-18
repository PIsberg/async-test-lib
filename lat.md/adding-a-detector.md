# Adding a detector

The most common change in this repo, and the easiest to get silently wrong: a new detector only works when *every* wiring point is updated together.

A field without construction, or construction without analysis, compiles fine and simply never detects anything. Nothing fails loudly — the detector just doesn't run.

## The synchronized-change contract

One new `DetectorType` constant requires simultaneous changes in five files (all in `src/main/java/se/deversity/asynctest/`). Land them as one change, never piecemeal.

1. `DetectorType.java` — the new enum constant.
2. `AsyncTest.java` — the matching `detectXxx()` annotation attribute (name/default become stable public API).
3. `AsyncTestConfig.java` — public final flag field, `Builder` field + same-named setter, the `from(AsyncTest)` call chain, **and both branches of `build()`** (detectAll if/else pair + non-detectAll excludes line) — see [[configuration#Detector selection resolution]].
4. `DetectorRegistry.java` — three steps that must land together: (a) final field, (b) conditional construction in the constructor keyed on the config flag, (c) an `analyzeAll()` call in the correct phase block.
5. `AsyncTestContext.java` — field copied from the registry plus the static accessor used by instrumented code, keeping ThreadLocal install/uninstall symmetric ([[execution-model#Context lifecycle]]).

## The detector class itself

New detectors live in `diagnostics/` and follow the house thread-safety idiom ([[detectors#Thread-safety idiom]]). `analyze()` must be idempotent ([[detectors#Detector SPI]]).

Message formatting that renders floats must pass `Locale.ROOT` — the dev machine locale is sv-SE, where `%f` produces comma decimals and breaks assertions.

## Tests are part of the change

Every detector has a mandated JUnit 5 test at `src/test/java/se/deversity/asynctest/diagnostics/<Name>DetectorTest.java` with an 80% coverage goal. An implementation change without the matching test update is incomplete.

Integration-style coverage typically uses the EngineTestKit dummy pattern ([[quality-gates#Test suite conventions]]).

## Also register the factory

`spi/adapters/LegacyDetectorFactories.java` exposes each detector through the `DetectorFactory` ServiceLoader path via `LegacyDetectorAdapter` ([[detectors#Detector SPI]]).

The adapter's structure is deliberately legacy-shaped — do not modernize it; only touch business logic when explicitly asked.
