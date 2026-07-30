# Documentation Index

Start here to find the right doc. All paths are relative to the repository root.

## Using the library

| Document | Purpose |
|----------|---------|
| [../README.md](../README.md) | Project overview and quick start |
| [USAGE.md](USAGE.md) | Full `@AsyncTest` parameter reference, detectors, examples |
| [QUICK_REFERENCE.md](QUICK_REFERENCE.md) | One-page cheatsheet |
| [CONFIGURATION.md](CONFIGURATION.md) | Configuration options in depth |
| [DETECTOR_CATALOG.md](DETECTOR_CATALOG.md) | All 114 detectors with buggy-vs-fixed examples |
| [ASYNC_ASSERT.md](ASYNC_ASSERT.md) | `AsyncAssert` — polling for side effects that land asynchronously |
| [BEST_PRACTICES.md](BEST_PRACTICES.md) | How to write an `@AsyncTest` that actually finds bugs |
| [OBSERVABILITY.md](OBSERVABILITY.md) | `AsyncTestListener` — hooking test events into logging, metrics, CI |
| [AGENT.md](AGENT.md) | Optional Byte Buddy agent — auto-record field access without manual hooks |
| [CI_INTEGRATION.md](CI_INTEGRATION.md) | GitHub Actions / Jenkins / GitLab CI setup |
| [BENCHMARKING.md](BENCHMARKING.md) | Optional throughput-regression tracking |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | Common issues and fixes |
| [MIGRATION.md](MIGRATION.md) | Moving an existing test suite onto `@AsyncTest` |
| [COMPARISON.md](COMPARISON.md) | How async-test differs from JUnit, stress tests, ThreadSanitizer |
| [../intellij-plugin/README.md](../intellij-plugin/README.md) | IntelliJ IDEA companion plugin |

## Understanding & extending

| Document | Purpose |
|----------|---------|
| [ARCHITECTURE.md](ARCHITECTURE.md) | **Architecture hub** — links to one file per topic below |
| [architecture/design-patterns.md](architecture/design-patterns.md) | ThreadLocal context, detector recording, barrier synchronization |
| [architecture/principles.md](architecture/principles.md) | Separation of concerns, thread safety, opt-in complexity, zero overhead |
| [architecture/runtime-guarantees.md](architecture/runtime-guarantees.md) | License guard; the worker `latch.countDown()` guarantee |
| [architecture/detector-architecture.md](architecture/detector-architecture.md) | Detector categories, phases, JDK 25/26 preview-era detectors |
| [architecture/detector-spi.md](architecture/detector-spi.md) | `Detector` / `DetectorFactory` SPI, `LegacyDetectorFactories` |
| [architecture/reporting-pipeline.md](architecture/reporting-pipeline.md) | `Violation` → `Formatter` → report listeners |
| [architecture/observability.md](architecture/observability.md) | Listener system, seen from the inside |
| [architecture/contention-engine.md](architecture/contention-engine.md) | `SpinContentionBarrier`, telemetry ring buffer, agent, pinning scanner |
| [architecture/diagrams.md](architecture/diagrams.md) | C4, sequence, class, activity and deployment diagrams + PlantUML sources |
| [architecture/file-structure.md](architecture/file-structure.md) | Where each package and class lives |
| [architecture/refactoring-history.md](architecture/refactoring-history.md) | The v1.2.0 refactor — what was broken up and why |
| [CLAUDE.md](CLAUDE.md) | Build/test commands and internal wiring notes for contributors |
| [BUILDING.md](BUILDING.md) | Building from source with Maven and Gradle |
| [WORKFLOW.md](WORKFLOW.md) | Development workflow |
| [CHANGELOG.md](CHANGELOG.md) | Version history |

## Releasing & distributing

| Document | Purpose |
|----------|---------|
| [RELEASE.md](RELEASE.md) | **Canonical release process** — automated (`v*` tag → `mvn deploy -P release`) and manual steps |
| [DISTRIBUTION.md](DISTRIBUTION.md) | Distribution/technical reference — artifacts, channels, dependencies, install methods, versioning |
| [CODECOV_TROUBLESHOOTING.md](CODECOV_TROUBLESHOOTING.md) | Coverage-upload troubleshooting |

## Project status

| Document | Purpose |
|----------|---------|
| [PRODUCTION_READINESS_EVAL.md](PRODUCTION_READINESS_EVAL.md) | Remaining work to reach GA / external usability |
| [ROADMAP_V2.md](ROADMAP_V2.md) | Planned v2 work |

> **Note:** `docs/README.md` was a 2461-line duplicate of the project README carrying stale
> per-release "Phase N" detector rundowns; it is now a stub. `DISTRIBUTION_SETUP.md`,
> `DISTRIBUTION_COMPLETE.md`, `SUMMARY.md` and `PRE_RELEASE_CHECKLIST.md` were removed in an earlier
> consolidation — use `RELEASE.md` and `DISTRIBUTION.md` instead.
