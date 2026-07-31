# Investigation: breaking async-test-lib into submodules

**Question:** should this library be split into Maven submodules, and is the current design in
good enough shape to allow it?

**Verdict:** yes, and the structure is healthier than its size suggests — the package graph is
almost a DAG already, with exactly three two-node cycles, and two packages are true leaves that
could be extracted this week. But the *valuable* split (getting the 138-file detector set out of
the core artifact) is gated on the same thing [ROADMAP_V2.md](ROADMAP_V2.md) Train 3 is gated on:
deciding which registry wins. Modularization is best understood as the payoff of that decision,
not as a separate project.

---

## What the dependency graph actually says

Measured from imports across all 183 main source files, 11 packages (`se.deversity.asynctest` is
abbreviated `~`). The repo already has `ArchitectureTest`, an ArchUnit suite with a slice rule
forbidding cycles between runner / extension / benchmark / spi / detection-model — so the layering
below is not aspiration, it is enforced.

```
~.extension      -> ~ (AsyncTest, AsyncTestConfig), ~.runner (ConcurrencyRunner)
~.runner         -> ~ (5), ~.diagnostics (5), ~.benchmark (BenchmarkRecorder), ~.report (Baseline)
~.spi            -> ~ (AsyncTestConfig, DetectorType), ~.report (Violation)
~.spi.adapters   -> ~.spi (2), ~.diagnostics (128), ~ (2), ~.report (Violation)
~.benchmark      -> ~ (AsyncTestConfig)
~.telemetry      -> ~ (AsyncTestContext), ~.diagnostics (AtomicityValidator, VisibilityMonitor)
~.agent          -> ~.telemetry (TelemetryRegistry)
~.analysis       -> (nothing)
~                -> ~.diagnostics (128), ~.report (Violation)
~.diagnostics    -> ~ (AsyncTestConfig, AsyncTestContext, AsyncTestListenerRegistry), ~.report (Violation)
~.report         -> ~ (AsyncTestListener), ~.diagnostics (IssueSeverity, SiteCapture)
```

### The three cycles, and how small they are

| Cycle | Forward edge | Back edge | Cost to break |
|-------|--------------|-----------|---------------|
| `~` ↔ `~.diagnostics` | 128 detector classes, imported by `DetectorRegistry` / `AsyncTestContext` | **one file** — `Phase1DetectorSet` imports `AsyncTestConfig`, `AsyncTestContext`, `AsyncTestListenerRegistry` | move `Phase1DetectorSet` to the core side; it is a composite that wires detectors, not a detector |
| `~` ↔ `~.report` | `Violation` | `AsyncTestListener`, used by `JUnitXmlReportListener`, `JsonReportListener`, `StrictModeListener` | move `Violation` + `Formatter` + `AsyncTestListener` into a leaf API module |
| `~.diagnostics` ↔ `~.report` | `Violation` | `IssueSeverity`, `SiteCapture` | same move — these three types are vocabulary, not behavior |

All three collapse by relocating a handful of value types into an API module and moving one
composite class. That is a genuinely small amount of surgery for a 40k-line library.

### Two packages are already leaves

Nothing in `src/main` imports `~.agent` or `~.analysis`. The only mentions elsewhere are javadoc
prose in `TelemetryBridge` and `TelemetryRegistry`.

| Package | Files | LOC | Third-party weight it carries |
|---------|-------|-----|-------------------------------|
| `~.agent` | 2 | 691 | `byte-buddy`, `byte-buddy-agent` |
| `~.analysis` | 1 | 244 | `asm` |

---

## What could be split today, compatibly

Every dependency in `pom.xml` is compile scope and none is marked `<optional>`. A consumer who
only writes `@AsyncTest` on a test method today pulls **byte-buddy, byte-buddy-agent, asm,
slf4j-api, common-license-lib, junit-jupiter-api, junit-jupiter-engine and apiguardian** onto the
test classpath. The agent and the static pinning scanner are opt-in features; their dependencies
are not opt-in.

Extracting `async-test-agent` (agent + telemetry-facing glue) and `async-test-analysis` removes
three transitive dependencies from the default classpath, is a pure addition of coordinates, and
breaks no source or binary compatibility for anyone using the core artifact.

> **One caveat that is not cosmetic:** the main JAR currently *is* the java agent — `pom.xml` puts
> `Premain-Class` / `Agent-Class` / `Can-Retransform-Classes` in its manifest. Splitting moves that
> manifest to the agent JAR, so every `-javaagent:async-test-lib.jar` invocation in user builds and
> in [AGENT.md](AGENT.md) changes path. That is a documented behavioral change even though it
> compiles clean, and it argues for shipping it with a minor version and a migration note rather
> than silently.

## The target module DAG

```
async-test-api          annotations, config value types, DetectorType, Preset,
   (no deps)            Violation, Formatter, IssueSeverity, SiteCapture,
                        AsyncTestListener + registry, AsyncAssert, spi.Detector,
                        spi.DetectorFactory
        ^
        |
async-test-detectors    the ~129 diagnostics detectors + their support types
   (-> api)             (Phase1DetectorSet moves out to core)
        ^
        |
async-test-core         AsyncTestContext, DetectorRegistry, runner, extension,
   (-> api, detectors)  spi.adapters, benchmark
        ^
        |
   +----+--------------------+
   |                         |
async-test-agent      async-test-analysis
(-> core; byte-buddy) (asm only)

async-test-report       formatters + report listeners (-> api)
async-test-lib          aggregator POM depending on all of the above, keeping the
                        existing coordinate working for current consumers
```

The aggregator artifact matters: it is what lets the split happen without every consumer editing
their `<artifactId>`.

## What blocks the valuable part

The 65% of the codebase that is detectors (138 files, 26,561 LOC of 40k) cannot leave the core
module while `DetectorRegistry` and `AsyncTestContext` import all 128 detector classes by name.
The `spi.adapters` package imports the same 128 — that is the ServiceLoader path which
[ROADMAP_V2.md](ROADMAP_V2.md) records as **built but never invoked at runtime**.

So the blocker is not architectural debt in the usual sense. It is that the library has *two*
registries and has not yet chosen. Train 3 already contains the decision:

> "Delete whichever registry lost: either the legacy hand-wired path (SPI becomes the runtime) or
> the dead SPI duplication."

If the SPI wins, `core` stops importing detectors entirely — they arrive through
`META-INF/services` — and `async-test-detectors` becomes a genuinely separable, swappable module.
If the hand-wired path wins, the 128-import fan-out is permanent and the detector split is off the
table. **Modularization is therefore an argument in favour of the SPI, and one that Train 3's
decision should weigh.**

Train 2 helps too: the `Violation` pipeline it makes reachable end-to-end is exactly the vocabulary
the API module needs to exist, and it removes the prose-parsing that currently couples the runner
to detector output formats.

## Costs, honestly

- **Build time and CI.** The pitest setup in `pom.xml` runs ~1.3h across a shared JVM and gates at
  75%. Six modules means six surefire/jacoco/pitest configurations, and that gate has to be
  re-thought per module — a module of pure value types will not hit the same coverage shape as the
  runner.
- **japicmp.** The gate is pinned to 1.6.0 on one artifact. Splitting means either a gate per
  module or accepting a gap in the release where compatibility is unchecked.
- **Release process.** [RELEASE.md](RELEASE.md) and [DISTRIBUTION.md](DISTRIBUTION.md) both assume
  a single artifact, as do the publish workflow and the `v*` tag automation.
- **The examples and fixtures.** `consumer-fixture`, `load-tests` and the 100+ `examples/*` builds
  each declare the single coordinate today.

## Recommendation

1. ~~**Now, independent of 2.0:** extract `async-test-agent` and `async-test-analysis`.~~
   **Done.** See [Status](#status) below.
2. **Alongside Train 2:** create `async-test-api` and move `Violation`, `Formatter`,
   `IssueSeverity`, `SiteCapture`, `AsyncTestListener` and the config value types into it. This
   kills all three cycles and is invisible to consumers if the aggregator POM is in place.
3. **As part of Train 3's registry decision:** if the SPI wins, split `async-test-detectors`. If it
   does not, stop at step 2 — a four-module split is still worth having, and forcing the detector
   split against a hand-wired registry would just relocate the coupling.
4. **Extend `ArchitectureTest` first, in every case.** The ArchUnit slice rule already forbids the
   cycles that matter; adding a rule per intended module boundary means the boundary is enforced
   *before* the directory move, so the move itself becomes mechanical.

## Status

**Step 1 and step 4 are done.** The build is a three-module Maven reactor:

```
async-test-parent          pom, aggregation and shared build config only
├── async-test-lib         jar — unchanged coordinate, unchanged consumers
├── async-test-agent       jar — Byte Buddy; carries the Premain-Class manifest
└── async-test-analysis    jar — ASM; depends on nothing else here
```

- `async-test-lib` keeps `se.deversity.async-test-lib:async-test-lib`. Existing consumers, the
  `consumer-fixture` and the 100+ `examples/*` builds need no edit. **byte-buddy, byte-buddy-agent
  and asm are off that artifact's classpath.**
- The agent is now `se.deversity.async-test-lib:async-test-agent`, and attaching uses
  `-javaagent:async-test-agent-<version>.jar`. This is the one behavioral change; see
  [AGENT.md](AGENT.md).
- Module-specific gates went with their modules: japicmp and the 70%/65% jacoco gate and the 74%
  pitest gate are declared in `async-test-lib`, since all three were measured against that
  artifact. Compiler, surefire, checkstyle, PMD, SpotBugs, source, javadoc and the SBOM stay in the
  parent and apply to every module. The three QA config files stay at the reactor root, resolved
  through `${maven.multiModuleProjectDirectory}`.
- Gradle was split to match (`settings.gradle.kts` + a `subprojects {}` block), and its version
  constants — which had drifted from the POM on junit, byte-buddy and vibetags — were re-synced.
- **Step 4 first, as recommended:** the boundary rules went into `ArchitectureTest` *before* the
  directory move, so the move was mechanical. One of them,
  `analysis_depends_on_nothing_in_the_library`, was then deleted rather than kept: after the split
  the analysis classes are not on the library module's classpath, so the rule's selection is empty
  — and the reactor now enforces structurally what the rule only described.

Steps 2 and 3 are unchanged and still gated as described above.

## Side benefit worth naming

vibetags 1.0.0-RC7 added `ModuleRootResolver` and `ModuleOutputWriter`: in a reactor build each
module gets its own `CLAUDE.md` and `.claude/rules/` containing only that module's guardrails.
This now works here, and it is why the reactor was worth doing beyond the classpath:

| File | Lines | Contents |
|------|-------|----------|
| `CLAUDE.md` (reactor root) | 157 | merged view across all modules |
| `async-test-lib/CLAUDE.md` | 136 | that module's guardrails + its own `.claude/rules/` (5 role files) |
| `async-test-agent/CLAUDE.md` | 14 | the agent's constraints, nothing else |
| `async-test-analysis/CLAUDE.md` | 11 | the scanner's constraints, nothing else |

An agent working in `async-test-agent/` loads 14 lines of guardrails instead of the library's 136.
As modules 2 and 3 of the plan land, the split keeps paying: each module's index lists only its own
elements, so the always-loaded cost stops growing with the library.

One operational note: the pre-split build left a stale `.vibetags-mod-_root_` sidecar behind, and
the reactor aggregation merged it *alongside* the new per-module sidecar — every element appeared
twice and the root file went from 157 to 286 lines. Deleting the stale sidecar and `.vibetags-cache`
fixed it. Both are gitignored build artifacts; if the root file ever looks doubled, that is why.
