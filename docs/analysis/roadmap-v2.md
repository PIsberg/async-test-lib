# Roadmap: unlocking the 2.0 architecture

The 2026-07 improvement sweep fixed every non-breaking finding. The remaining findings
are blocked by two deliberate safety rails: the frozen public API (contract signatures,
annotation attributes, public config fields) and the japicmp binary-compatibility gate
pinned to 1.6.0. This document is the plan for getting past both without ever shipping
a broken intermediate release.

## Status, re-measured 2026-08-27

Trains 1 and 2 have not started. The plan below is unchanged in shape and every number in it is
stale, so the counts are restated here rather than edited in place: the drift is the finding.

| Metric this plan exists to reduce | 2026-08-03 | 2026-08-27 | Direction |
|---|---:|---:|---|
| public boolean fields on `AsyncTestConfig` | 132 | **151** | worse |
| deprecated attributes on `@AsyncTest` | 127 | **146** | worse |
| detectors keyed by `identityHashCode` | 84 | **103** | worse |
| `DetectorType` constants | 127 | **146** | grew |

Nothing regressed. Every one of those grew because detectors were added, and each detector costs a
constant, an attribute, a config field, a builder setter, a `from()` read, a `build()` resolution
line and usually an identity-keyed map. That is the tax this plan was written to remove, and it
compounds: **the longer Train 1 waits, the larger Train 3 gets.** At the current rate the 2.0
deletion is roughly 15% bigger every three weeks.

The mechanical shape today, for anyone picking this up:

| Site | Lines |
|---|---:|
| `AsyncTestConfig` | 2,210 |
| `DetectorRegistry` | 1,181 |
| `ann.<attr>()` reads in `from()` | 168 |
| resolution lines in `build()` | 147 |
| detector constructions in the registry | 146 |

### What changed in the plan's favour

`DetectorWiringIsCompleteTest` (2026-08-27) pins the correspondence Train 1 rests on: one
`DetectorType` per `@AsyncTest` detector attribute, no strays either way, every attribute read in
`from()` and resolved in `build()`. Before it, the `EnumSet` refactor would have been a rewrite of
three hand-maintained lists that nothing proved equivalent; now a mismatch fails the build. That
was the missing precondition, not a nice-to-have.

### What Train 1 actually costs, stated honestly

The original text says the config core is "computed once in `build()` ... keep every existing
public boolean field, now assigned as a one-line derivation". That is right, and it is worth being
clear that it does **not** reduce line count on its own:

- reading the annotation still needs one line per attribute, because annotation members cannot be
  enumerated by convention (`DEADLOCKS` is `detectDeadlocks`, but `SEMAPHORE` is `monitorSemaphore`
  and `SIMPLE_DATE_FORMAT` is `detectSimpleDateFormatIssues`);
- assigning the public fields still needs one line each, because they are `public final`.

What it does move is the *logic*: resolution stops being 147 independent expressions that can each
be wrong, and becomes one loop over the enum. The 147 becomes 146 mechanical assignments that
cannot be individually wrong and that Train 3 deletes outright. That is the win; a smaller file is
not.

### Recommended next step

Train 1's registry table, not the config core. It is one file, it is the half that removes a real
edit site rather than relocating one, and `DetectorRegistry`'s 146 constructions are already
uniform enough to table-drive. The config core should follow it, not lead, because the registry
table is the cheaper way to learn whether the table-driven shape survives this codebase's
constraints.

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

By this point the breaking release is pure deletion and defaults. A smaller 2.0.0 is available
at any time and does not wait on the `AsyncTestConfig` decision: the 146 `@AsyncTest` attributes
and the 42 `AsyncTestContext` accessors can be removed now, because both are deprecated and both
name their replacement. Each item below says whether it is ready.

* Remove the 146 deprecated boolean attributes from `@AsyncTest`. Ready: all 146 carry a
  `@deprecated` tag naming `preset` / `includes` / `excludes` and a `DetectorType`, and
  `DeprecationsNameTheirReplacementTest` keeps that true. Seven of them had no tag at all until
  2026-08-27.
* Remove the deprecated public boolean fields/builder setters from `AsyncTestConfig`
  (the `EnumSet` is already the source of truth). **Not ready, and the premise is wrong:** as of
  2026-08-27 `AsyncTestConfig` carries ~146 `public final boolean detect*` fields and 140
  `detect*(boolean)` builder setters, and *none of them is deprecated*. The Consumers rule below
  requires a deprecation to ship first, so that clock has never started. Deciding it needs the
  builder's replacement settled too: it has `includes`, `excludes` and `detectAll` but no
  `preset(...)`, because preset resolution lives in `from(AsyncTest, int)` and never reaches the
  builder. Tracked in issue #383.
* Remove the 42 deprecated `*Monitor()` accessors from `AsyncTestContext` (renamed
  `*Detector()` aliases shipped in 1.7). Ready: all 42 name their replacement. Four are not a
  suffix swap and one defeats a global `Monitor` to `Detector` replace; `docs/MIGRATION.md` lists
  them.
* Delete whichever registry lost: either the legacy hand-wired path (SPI becomes the
  runtime) or the dead SPI duplication — decided during Train 2 based on how the
  id-keyed SPI shakes out.
* Flip the default from detect-everything to a lean preset (e.g. `Preset.ESSENTIALS`);
  `detectAll` stays available as an explicit opt-in.

## Overcoming the mechanical gates

* **japicmp**: **done, 2026-08-27.** The plugin now runs in semantic-versioning mode
  (`<breakBuildBasedOnSemanticVersioning>true</breakBuildBasedOnSemanticVersioning>`) on `main`,
  not only on a 2.0 branch, because the previous setting
  (`breakBuildOnBinaryIncompatibleModifications`) failed on any break whatever the version said,
  which made 2.0.0 unbuildable rather than merely gated. Measured at the time of the change, with
  the version set to 2.0.0 and `AsyncTestContext.semaphoreMonitor()` deleted: it failed with
  `METHOD_REMOVED` before, passes after. The same removal still fails at 1.10.0 and at 1.9.9, and
  adding public API in a patch release still passes, so nothing was weakened.
  `JapicmpBaselineFreshnessTest` pins the setting and was verified to behave correctly at 2.0.0
  (accepts a 1.9.8 baseline, rejects a stale 1.9.7). After the 2.0.0 release, re-pin
  `<oldVersion>` to 2.0.0 — that step is itself gated by the same test.
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
