# Documentation Index

Start here to find the right doc. All paths are relative to this file.

Working on the code rather than reading about it? The repository-root
[CLAUDE.md](../CLAUDE.md) is the short orientation map — module layout, build commands, and where
guardrails live.

## Using the library

| Document | Purpose |
|----------|---------|
| [../README.md](../README.md) | Project overview and quick start |
| [API reference](https://pisberg.github.io/async-test-lib/api/latest/) | Generated javadoc, one directory per release. Built by `javadoc.yml`, not committed |
| [USAGE.md](USAGE.md) | Full `@AsyncTest` parameter reference, detectors, examples |
| [QUICK_REFERENCE.md](QUICK_REFERENCE.md) | One-page cheatsheet |
| [CONFIGURATION.md](CONFIGURATION.md) | Configuration options in depth |
| [LICENSING.md](LICENSING.md) | Issuing a commercial licence to a customer, the flags they run with, expiry and renewal |
| [DETECTOR_CATALOG.md](DETECTOR_CATALOG.md) | All 135 detectors with buggy-vs-fixed examples |
| [ASYNC_ASSERT.md](ASYNC_ASSERT.md) | `AsyncAssert` — polling for side effects that land asynchronously — and `AsyncFindings`, for asserting on what the detectors reported |
| [BEST_PRACTICES.md](BEST_PRACTICES.md) | How to write an `@AsyncTest` that actually finds bugs |
| [OBSERVABILITY.md](OBSERVABILITY.md) | `AsyncTestListener` — hooking test events into logging, metrics, CI |
| [AGENT.md](AGENT.md) | Optional Byte Buddy agent — auto-record field access without manual hooks |
| [CI_INTEGRATION.md](CI_INTEGRATION.md) | GitHub Actions / Jenkins / GitLab CI setup |
| [BENCHMARKING.md](BENCHMARKING.md) | Optional throughput-regression tracking |
| [../load-tests/README.md](../load-tests/README.md) | The JMH/stress suite — what each benchmark can and cannot measure |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | Common issues and fixes |
| [MIGRATION.md](MIGRATION.md) | Moving an existing test suite onto `@AsyncTest` |
| [../intellij-plugin/README.md](../intellij-plugin/README.md) | IntelliJ IDEA companion plugin |

## Understanding & extending

| Document | Purpose |
|----------|---------|
| [ARCHITECTURE.md](ARCHITECTURE.md) | **Architecture hub** — links to one file per topic below |
| [architecture/execution-flow.md](architecture/execution-flow.md) | The `@AsyncTest` → extension → interceptor → runner → registry chain, the key supporting types, and the invariants the runner must not break |
| [architecture/adding-a-detector.md](architecture/adding-a-detector.md) | The synchronized five-file change, the thread-safety idiom, hot-path constraints |
| [architecture/configuration-resolution.md](architecture/configuration-resolution.md) | How `includes` / `excludes` / `detectAll` / `Preset` resolve, and the `failOn` gate |
| [architecture/design-patterns.md](architecture/design-patterns.md) | ThreadLocal context, detector recording, barrier synchronization |
| [architecture/principles.md](architecture/principles.md) | Separation of concerns, thread safety, opt-in complexity, zero overhead |
| [architecture/runtime-guarantees.md](architecture/runtime-guarantees.md) | License guard; the worker `latch.countDown()` guarantee |
| [architecture/detector-architecture.md](architecture/detector-architecture.md) | The 18 phases, the common detector pattern, wiring a new one |
| [architecture/detector-spi.md](architecture/detector-spi.md) | `Detector` / `DetectorFactory` SPI, `LegacyDetectorFactories` |
| [architecture/reporting-pipeline.md](architecture/reporting-pipeline.md) | `Violation` → `Formatter` → report listeners |
| [architecture/observability.md](architecture/observability.md) | Listener system, seen from the inside |
| [architecture/contention-engine.md](architecture/contention-engine.md) | `SpinContentionBarrier`, telemetry ring buffer, agent, pinning scanner |
| [architecture/diagrams.md](architecture/diagrams.md) | C4, sequence, class, activity and deployment diagrams + PlantUML sources |
| [architecture/file-structure.md](architecture/file-structure.md) | Where each package and class lives |
| [BUILDING.md](BUILDING.md) | Building from source with Maven and Gradle |
| [QUALITY_GATES.md](QUALITY_GATES.md) | What must stay green — static analysis, coverage, mutation testing, japicmp, and the build quirks behind them |
| [DEPENDENCIES.md](DEPENDENCIES.md) | Every third-party library, why it is used, and how far it travels toward a consumer's classpath |
| [WORKFLOW.md](WORKFLOW.md) | Development workflow |
| [CHANGELOG.md](CHANGELOG.md) | Version history |

## Releasing & distributing

| Document | Purpose |
|----------|---------|
| [RELEASE.md](RELEASE.md) | **Canonical release process** — automated (`v*` tag → `mvn deploy -P release`) and manual steps |
| [DISTRIBUTION.md](DISTRIBUTION.md) | Distribution/technical reference — artifacts, channels, dependencies, install methods, versioning |

## Analysis

Point-in-time findings, evaluations and plans. These record what was true when they were written —
read them for the reasoning, not as current reference.

| Document | Purpose |
|----------|---------|
| [analysis/modularization.md](analysis/modularization.md) | Investigation: should the library be split into Maven submodules, and what blocks it |
| [analysis/production-readiness.md](analysis/production-readiness.md) | Remaining work to reach GA / external usability |
| [analysis/roadmap-v2.md](analysis/roadmap-v2.md) | Planned v2 work |
| [analysis/refactoring-history.md](analysis/refactoring-history.md) | The v1.2.0 refactor — what was broken up and why |
| [analysis/comparison.md](analysis/comparison.md) | How async-test differs from JUnit, stress tests, ThreadSanitizer |
| [analysis/detector-accuracy-eval.md](analysis/detector-accuracy-eval.md) | Measured detector behavior on buggy code vs its synchronized twin, enforced by `DetectorAccuracyEvalTest` |
| [analysis/codecov-troubleshooting.md](analysis/codecov-troubleshooting.md) | Coverage-upload troubleshooting |
| [analysis/test-profiles-and-detector-gaps.md](analysis/test-profiles-and-detector-gaps.md) | Investigation: splitting the suite into a fast local tier and a CI-only e2e tier, and which bug classes the detectors miss |

> **Removed docs.** `docs/CLAUDE.md` held the module layout, build commands and inlined architecture
> notes; the orientation half moved to the repository-root [CLAUDE.md](../CLAUDE.md) and the rest
> into the `architecture/` topic files that already owned those subjects. `docs/README.md` was a
> 2461-line duplicate of the project README and is now a stub. `DISTRIBUTION_SETUP.md`,
> `DISTRIBUTION_COMPLETE.md`, `SUMMARY.md` and `PRE_RELEASE_CHECKLIST.md` went in an earlier
> consolidation — use `RELEASE.md` and `DISTRIBUTION.md` instead.
