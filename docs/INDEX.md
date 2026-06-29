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
| [CI_INTEGRATION.md](CI_INTEGRATION.md) | GitHub Actions / Jenkins / GitLab CI setup |
| [BENCHMARKING.md](BENCHMARKING.md) | Optional throughput-regression tracking |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | Common issues and fixes |
| [../intellij-plugin/README.md](../intellij-plugin/README.md) | IntelliJ IDEA companion plugin |

## Understanding & extending

| Document | Purpose |
|----------|---------|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Execution flow, detector phases, extension points |
| [CLAUDE.md](CLAUDE.md) | Build/test commands and internal wiring notes for contributors |
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

> **Note:** the earlier `DISTRIBUTION_SETUP.md`, `DISTRIBUTION_COMPLETE.md`, `SUMMARY.md`,
> and `PRE_RELEASE_CHECKLIST.md` were setup/completion summaries that duplicated
> `RELEASE.md` and `DISTRIBUTION.md` (and carried stale first-release/`1.6.0` content). They
> were removed during consolidation — use `RELEASE.md` and `DISTRIBUTION.md` instead.
