# Documentation Index

52 documents, one router. Every path below is relative to this file.

**There are no per-directory indexes, deliberately.** `DocsIndexCoverageTest` requires a direct
link from *this* file to every document under `docs/`, not a transitive one, so a second index in
`architecture/` or `analysis/` would duplicate 27 rows that nothing keeps in step. If you are
browsing the folders on GitHub rather than reading here, the groupings below are the order to
read them in.

Working on the code rather than reading about it? The repository-root
[CLAUDE.md](../CLAUDE.md) is the short orientation map: module layout, build commands, and where
guardrails live.

## Start here

Three paths, depending on why you came.

| You want to | Read, in this order |
|---|---|
| **Use it** | [../README.md](../README.md) for what it is, [QUICK_REFERENCE.md](QUICK_REFERENCE.md) for the one-page cheatsheet, then [BEST_PRACTICES.md](BEST_PRACTICES.md) for how to write an `@AsyncTest` that actually finds bugs |
| **Understand it** | [ARCHITECTURE.md](ARCHITECTURE.md) as the hub, then [architecture/execution-flow.md](architecture/execution-flow.md) for the chain from annotation to report |
| **Change it** | [architecture/adding-a-detector.md](architecture/adding-a-detector.md) for the synchronized change, then [QUALITY_GATES.md](QUALITY_GATES.md) for what must stay green |

## Using the library

### The first hour

| Document | Purpose |
|----------|---------|
| [../README.md](../README.md) | Project overview and quick start |
| [QUICK_REFERENCE.md](QUICK_REFERENCE.md) | One-page cheatsheet |
| [USAGE.md](USAGE.md) | Full `@AsyncTest` parameter reference, detectors, examples |
| [API reference](https://pisberg.github.io/async-test-lib/api/latest/) | Generated javadoc, one directory per release. Built by `javadoc.yml`, not committed |

### Writing tests that find things

| Document | Purpose |
|----------|---------|
| [BEST_PRACTICES.md](BEST_PRACTICES.md) | How to write an `@AsyncTest` that actually finds bugs |
| [CONFIGURATION.md](CONFIGURATION.md) | Configuration options in depth |
| [DETECTOR_CATALOG.md](DETECTOR_CATALOG.md) | All 146 detectors with buggy-vs-fixed examples, their trust tiers and what feeds each |
| [ASYNC_ASSERT.md](ASYNC_ASSERT.md) | `AsyncAssert` — polling for side effects that land asynchronously — and `AsyncFindings`, for asserting on what the detectors reported |
| [MIGRATION.md](MIGRATION.md) | Moving an existing test suite onto `@AsyncTest` |

### Reading what it found

| Document | Purpose |
|----------|---------|
| [OBSERVABILITY.md](OBSERVABILITY.md) | `AsyncTestListener` — hooking test events into logging, metrics, CI |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | Common issues and fixes |

### Running it somewhere else

| Document | Purpose |
|----------|---------|
| [CI_INTEGRATION.md](CI_INTEGRATION.md) | GitHub Actions / Jenkins / GitLab CI setup |
| [JVM_LANGUAGES.md](JVM_LANGUAGES.md) | `@AsyncTest` from Kotlin, Groovy, Scala and Clojure: what each needs, what the agent sees, and which native test frameworks it does not run inside |
| [../intellij-plugin/README.md](../intellij-plugin/README.md) | IntelliJ IDEA companion plugin |

### Optional extras

| Document | Purpose |
|----------|---------|
| [AGENT.md](AGENT.md) | Optional Byte Buddy agent — auto-record field access without manual hooks, and the notices the runner prints when a detector cannot see |
| [BENCHMARKING.md](BENCHMARKING.md) | Optional throughput-regression tracking |
| [../load-tests/README.md](../load-tests/README.md) | The JMH/stress suite — what each benchmark can and cannot measure |
| [LICENSING.md](LICENSING.md) | Issuing a commercial licence to a customer, the flags they run with, expiry and renewal |

## Understanding the internals

Read [ARCHITECTURE.md](ARCHITECTURE.md) first; it is the hub these hang off. The order below is
the order the pieces appear in a run, not alphabetical.

| Document | Purpose |
|----------|---------|
| [ARCHITECTURE.md](ARCHITECTURE.md) | **Architecture hub** — links to one file per topic below |
| [architecture/principles.md](architecture/principles.md) | Separation of concerns, thread safety, opt-in complexity, zero overhead |
| [architecture/execution-flow.md](architecture/execution-flow.md) | The `@AsyncTest` → extension → interceptor → runner → registry chain, the key supporting types, and the invariants the runner must not break |
| [architecture/configuration-resolution.md](architecture/configuration-resolution.md) | How `includes` / `excludes` / `detectAll` / `Preset` resolve, and the `failOn` gate |
| [architecture/contention-engine.md](architecture/contention-engine.md) | `SpinContentionBarrier`, telemetry ring buffer, agent, pinning scanner |
| [architecture/detector-architecture.md](architecture/detector-architecture.md) | The 18 phases, the common detector pattern, wiring a new one |
| [architecture/detector-spi.md](architecture/detector-spi.md) | `Detector` / `DetectorFactory` SPI, `LegacyDetectorFactories` |
| [architecture/reporting-pipeline.md](architecture/reporting-pipeline.md) | `Violation` → `Formatter` → report listeners |
| [architecture/observability.md](architecture/observability.md) | Listener system, seen from the inside |
| [architecture/logging.md](architecture/logging.md) | The two output channels, the `domain.event key=value` format, and which log events are pinned by tests |
| [architecture/runtime-guarantees.md](architecture/runtime-guarantees.md) | License guard; the worker `latch.countDown()` guarantee |
| [architecture/design-patterns.md](architecture/design-patterns.md) | ThreadLocal context, detector recording, barrier synchronization |
| [architecture/file-structure.md](architecture/file-structure.md) | Where each package and class lives |

## Changing it

| Document | Purpose |
|----------|---------|
| [architecture/adding-a-detector.md](architecture/adding-a-detector.md) | The synchronized five-file change, the thread-safety idiom, hot-path constraints |
| [BUILDING.md](BUILDING.md) | Building from source with Maven and Gradle |
| [QUALITY_GATES.md](QUALITY_GATES.md) | What must stay green — static analysis, coverage, mutation testing, japicmp, and the build quirks behind them |
| [architecture/guardrails.md](architecture/guardrails.md) | How the `@AI*` guardrails are generated, the three vibetags config files, and why Gradle needs `-Avibetags.root` |
| [DEPENDENCIES.md](DEPENDENCIES.md) | Every third-party library, why it is used, and how far it travels toward a consumer's classpath |
| [WORKFLOW.md](WORKFLOW.md) | Development workflow |
| [architecture/diagrams.md](architecture/diagrams.md) | C4, sequence, class, activity and deployment diagrams + PlantUML sources |
| [diagrams/README.md](diagrams/README.md) | The PlantUML sources and their rendered PNGs, one row per diagram |
| [diagrams/GENERATE_DIAGRAMS.md](diagrams/GENERATE_DIAGRAMS.md) | Rendering the PlantUML sources locally (CLI, server or IDE plugin) |
| [CHANGELOG.md](CHANGELOG.md) | Version history |

## Releasing & distributing

| Document | Purpose |
|----------|---------|
| [RELEASE.md](RELEASE.md) | **Canonical release process** — automated (`v*` tag → `mvn deploy -P release`) and manual steps |
| [DISTRIBUTION.md](DISTRIBUTION.md) | Distribution/technical reference — artifacts, channels, dependencies, install methods, versioning |
| [SUPPORT_POLICY.md](SUPPORT_POLICY.md) | Versioning, support windows and end-of-life — what an adopting team can rely on, and for how long |

## Analysis

Point-in-time findings, evaluations and plans. These record what was true when they were written —
read them for the reasoning, not as current reference. Where a number in one has since drifted, the
document says so rather than being edited in place.

### Plans and open questions

| Document | Purpose |
|----------|---------|
| [analysis/roadmap-v2.md](analysis/roadmap-v2.md) | The 2.0 plan: three trains, only the last of which breaks compatibility. Carries a re-measured status section — Trains 1 and 2 have not started, and every metric the plan exists to reduce has grown |
| [analysis/production-readiness.md](analysis/production-readiness.md) | Remaining work to reach GA / external usability |
| [analysis/modularization.md](analysis/modularization.md) | Investigation: should the library be split into Maven submodules, and what blocks it |
| [analysis/test-profiles-and-detector-gaps.md](analysis/test-profiles-and-detector-gaps.md) | Investigation: splitting the suite into a fast local tier and a CI-only e2e tier, and which bug classes the detectors miss |
| [analysis/jvm-languages-plan.md](analysis/jvm-languages-plan.md) | Plan: `@AsyncTest` from Kotlin, Groovy, Scala and Clojure (all four verified 2026-08-16), the per-language traps, and the fixtures, CI job and two library items that make it first-class |

### Measured evidence

| Document | Purpose |
|----------|---------|
| [analysis/detector-accuracy-eval.md](analysis/detector-accuracy-eval.md) | Measured detector behavior on buggy code vs its synchronized twin, enforced by `DetectorAccuracyEvalTest` |
| [analysis/corpus-eval.md](analysis/corpus-eval.md) | Measured detector behavior on 42 third-party classes with a documented thread-safety contract, with the exposure denominator every rate is over, produced by the standalone `corpus-eval` module |
| [analysis/regression-sweep.md](analysis/regression-sweep.md) | What the downstream sweep is, the failure each of its rules prevents, and the result of the latest run: three unrelated consuming projects run against the artifact on Maven Central |
| [analysis/vibe-architecture-scorecard.md](analysis/vibe-architecture-scorecard.md) | Self-audit against the *Vibe Architecture* health scorecard (2026-08-15): 42 to 56 of 66, what each gate enforces, and the deferred decisions |

### History and troubleshooting

| Document | Purpose |
|----------|---------|
| [analysis/refactoring-history.md](analysis/refactoring-history.md) | The v1.2.0 refactor — what was broken up and why |
| [analysis/comparison.md](analysis/comparison.md) | How async-test differs from JUnit, stress tests, ThreadSanitizer |
| [analysis/codecov-troubleshooting.md](analysis/codecov-troubleshooting.md) | Coverage-upload troubleshooting |

> **Routing is enforced, and it is not transitive.** `DocsIndexCoverageTest` fails the build when a
> document under `docs/` is not linked from this index, or when any relative link in the doc set
> points at a file that does not exist. The link must come from this file directly, which is why a
> new document needs a row here and not only a mention in a neighbouring doc, and why there are no
> per-directory indexes to drift out of step with this one.
>
> The only document exempt is `docs/README.md`, a redirect stub that points back here.

> **Removed docs.** `docs/CLAUDE.md` held the module layout, build commands and inlined architecture
> notes; the orientation half moved to the repository-root [CLAUDE.md](../CLAUDE.md) and the rest
> into the `architecture/` topic files that already owned those subjects. `docs/README.md` was a
> 2461-line duplicate of the project README and is now a stub. `DISTRIBUTION_SETUP.md`,
> `DISTRIBUTION_COMPLETE.md`, `SUMMARY.md` and `PRE_RELEASE_CHECKLIST.md` went in an earlier
> consolidation — use `RELEASE.md` and `DISTRIBUTION.md` instead.
