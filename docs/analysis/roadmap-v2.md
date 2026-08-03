# Roadmap: unlocking the 2.0 architecture

The 2026-07 improvement sweep fixed every non-breaking finding. The remaining findings
are blocked by two deliberate safety rails: the frozen public API (contract signatures,
annotation attributes, public config fields) and the japicmp binary-compatibility gate
pinned to 1.6.0. This document is the plan for getting past both without ever shipping
a broken intermediate release.

## Blocked findings this plan unlocks

> Counts re-measured against the tree on 2026-08-03, ahead of 1.7.0 GA. Several had drifted
> far enough from the originals to change what the finding says, so they are stated exactly
> rather than approximately, with the command that produces each.

1. `AsyncTestConfig` carries **132** public boolean flags, **127** of them per-detector;
   adding a detector needs synchronized edits in several places across 4 files.
2. Two registries coexist, but the SPI is **not** dead: `AsyncTestContext` calls
   `spi.DetectorRegistry.buildExternal` on every construction, which is how third-party
   detectors reach the reports and the `failOn` gate. What is *not* live is the built-in
   half: `DetectorRegistry.build(config)`, the view that includes the 127 built-in bridge
   factories, is called only from tests. Since the 1.7.0 performance fix those built-ins
   are listed in `META-INF/async-test/builtin-detector-factories` rather than registered
   for `ServiceLoader`, so the runtime no longer loads them. The 2.0 decision is therefore
   narrower than "delete whichever registry lost": the SPI stays, and the question is only
   what to do with the built-in bridge shims.
3. The structured `Violation`/`Formatter` pipeline is unreachable end-to-end; severity and
   detector names are re-parsed out of prose reports.
4. The SPI cannot introduce new detector identities (`Detector.type()` is bound to the
   closed `DetectorType` enum).
5. **84** detector classes duplicate instance-tracking scaffolding keyed by
   `System.identityHashCode` (collision + unbounded-retention hazard).
6. A bare `@AsyncTest` enables all **127** detectors; the **127** deprecated boolean
   annotation attributes dominate the public annotation surface.

Reproducing the counts:

```bash
# 1 and 6: public boolean flags, and deprecated annotation attributes
grep -cE '^\s+public final boolean ' async-test-lib/src/main/java/se/deversity/asynctest/AsyncTestConfig.java
grep -c '@Deprecated' async-test-lib/src/main/java/se/deversity/asynctest/AsyncTest.java
# 2: who calls the all-inclusive SPI view
git grep -n 'DetectorRegistry.build(' -- async-test-lib/src
# 5: detectors keyed by identity hash
grep -rl identityHashCode --include=*.java async-test-lib/src/main/java/se/deversity/asynctest/diagnostics | wc -l
# 6: detector count
grep -oE '^ +[A-Z][A-Z0-9_]{2,}' async-test-lib/src/main/java/se/deversity/asynctest/DetectorType.java | tr -d ' ' | sort -u | wc -l
```

## Strategy: three trains, only the last one breaks

The insight is that most of the "breaking" refactor is not actually breaking — it can be
done behind the existing API. Only deletions must wait for 2.0.

### Train 1 — 1.8.x (fully compatible, start immediately)

* **Config core**: introduce an internal `EnumSet<DetectorType> enabledDetectors` on
  `AsyncTestConfig` as the single source of truth, computed once in `build()` from
  preset/includes/excludes/detectAll/legacy booleans. Keep every existing public boolean
  field, now assigned as a one-line derivation (`this.detectXxx = enabled.contains(XXX)`).
  Binary compatibility: unchanged — fields keep their signatures and values.
* **Registry table**: replace the 127 hand-written conditional constructions in
  `DetectorRegistry` with a `Map<DetectorType, Supplier<Object>>` factory table iterated
  against `config.enabledDetectors`. The per-detector fields and accessors remain, assigned
  from the table's output, so `AsyncTestContext` and all tests are untouched.
* **Result**: adding a detector drops from ~10 edit sites to ~4 (enum constant, annotation
  attribute, factory-table line, accessor) — and further to ~3 when Train 2 lands.

### Train 2 — 1.9.x (fully compatible)

* **`AbstractInstanceDetector<T>` base class**: owns the instance map, `stateFor()`
  (get-then-computeIfAbsent hot path), thread-id/name key sets, enabled flag, and `reset()`.
  Migrate the ~83 scaffolding-duplicating detectors in waves — each has a dedicated test
  class to pin behavior. Fix the `identityHashCode` hazard here once, in the base class,
  with weak identity keying.
* **Structured output beside prose**: add `default List<Violation> violations()` to the
  detector surface (additive). Detectors populate `Violation` records; the existing string
  reports become rendered views of them. The runner gates on `Violation.severity()` instead
  of parsing prose; `JsonFormatter`/`MarkdownFormatter` become reachable end-to-end.
* **Open detector identity (additive)**: add `default String id()` to `spi.Detector`
  (defaulting to `type().name()`), and an id-keyed enablement path in the SPI, so third
  parties can ship genuinely new detectors without touching the sealed enum.

### Train 3 — 2.0.0 (the breaking release, now small)

By this point the breaking release is pure deletion and defaults:

* Remove the ~110 deprecated boolean attributes from `@AsyncTest`.
* Remove the deprecated public boolean fields/builder setters from `AsyncTestConfig`
  (the `EnumSet` is already the source of truth).
* Remove the deprecated `*Monitor()` accessors from `AsyncTestContext` (renamed
  `*Detector()` aliases shipped in 1.7).
* Delete whichever registry lost: either the legacy hand-wired path (SPI becomes the
  runtime) or the dead SPI duplication — decided during Train 2 based on how the
  id-keyed SPI shakes out.
* Flip the default from detect-everything to a lean preset (e.g. `Preset.ESSENTIALS`);
  `detectAll` stays available as an explicit opt-in.

## Overcoming the mechanical gates

* **japicmp**: the gate is doing its job — keep it green through Trains 1–2. For the 2.0
  branch, switch the plugin to semantic-versioning mode
  (`<breakBuildBasedOnSemanticVersioning>true</breakBuildBasedOnSemanticVersioning>`),
  which permits binary breaks exactly when the major version increments; alternatively keep
  strict mode and enumerate each intentional break in `<excludes>` so removals stay
  auditable. After the 2.0.0 release, re-pin `<oldVersion>` to 2.0.0.
* **Branching**: release 1.7.0 from `main`, then cut a `1.x` maintenance branch. `main`
  moves to `2.0.0-SNAPSHOT` once Train 2 completes. Trains 1 and 2 merge to `main` and are
  released as ordinary 1.8/1.9 minors.
* **Project guardrails**: the `DetectorType` file lock and the multi-place-sync warnings in
  `CLAUDE.md`/`project_guardrails` exist *because* of the wiring duplication. After Train 1
  they overstate the danger; after Train 3 they should be rewritten to describe the
  factory-table pattern (owner action — the guardrails file is maintainer-owned).
* **Consumers**: every removal in Train 3 ships deprecated (with `@deprecated` pointers to
  the replacement) for at least two minor releases first, so migration is a
  find-and-replace, and the deprecations themselves document it.
