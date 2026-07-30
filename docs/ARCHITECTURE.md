# 🏗️ Async Test Library — Architecture

## Overview

This document is the entry point to the architecture of the async-test library. The library enables
deterministic concurrency testing by forcing thread collisions and detecting **100 categories** of
concurrency bugs across **13 detector phases**.

Each topic below lives in its own file under [`architecture/`](architecture/). Read the one you need
rather than the whole set.

> **Note (1.6.0):** Several public-API additions and an SPI introduced in 1.6.0 are described in
> [Reporting Pipeline](architecture/reporting-pipeline.md), [Detector SPI](architecture/detector-spi.md),
> and [Runtime Guarantees](architecture/runtime-guarantees.md). The Detector SPI now covers **every**
> `DetectorType` value via `LegacyDetectorFactories` (was canary-only at SPI introduction). Phase 13
> (5 new detectors) was added end-to-end and is integrated through the same 13-point fan-out as the
> legacy 95. The PlantUML diagrams still reflect the pre-1.6.0 detector wiring; they remain accurate
> for the legacy registry and will be regenerated in the next docs sweep.

## Topics

### How a test runs

| Document | What it covers |
|----------|----------------|
| [design-patterns.md](architecture/design-patterns.md) | ThreadLocal context, detector recording, barrier synchronization |
| [principles.md](architecture/principles.md) | Separation of concerns, thread safety, opt-in complexity, zero-overhead default |
| [runtime-guarantees.md](architecture/runtime-guarantees.md) | License guard; the worker `latch.countDown()` guarantee |

### Detectors

| Document | What it covers |
|----------|----------------|
| [detector-architecture.md](architecture/detector-architecture.md) | Detector categories, phases, and the JDK 25/26 preview-era standalone detectors |
| [detector-spi.md](architecture/detector-spi.md) | The pluggable `Detector` / `DetectorFactory` SPI and `LegacyDetectorFactories` |

### Output

| Document | What it covers |
|----------|----------------|
| [reporting-pipeline.md](architecture/reporting-pipeline.md) | `Violation` → `Formatter` → report listeners |
| [observability.md](architecture/observability.md) | `AsyncTestListener` — registration, opt-out, thread safety |

### Performance engine

| Document | What it covers |
|----------|----------------|
| [contention-engine.md](architecture/contention-engine.md) | `SpinContentionBarrier`, `TelemetryEventBuffer`, `AsyncTestAgent`, `StaticPinningScanner` |

### Reference

| Document | What it covers |
|----------|----------------|
| [diagrams.md](architecture/diagrams.md) | C4 context/container/component, sequence, class, activity and deployment diagrams, plus their PlantUML sources |
| [file-structure.md](architecture/file-structure.md) | Where each package and class lives |
| [refactoring-history.md](architecture/refactoring-history.md) | The v1.2.0 refactor — what was broken up and why |

## Related Documentation

- [AGENT.md](AGENT.md) - Byte Buddy agent instrumentation: attach, consume, filter, troubleshoot
- [BENCHMARKING.md](BENCHMARKING.md) - Detailed benchmarking guide
- [USAGE.md](USAGE.md) - User guide with examples
- [README.md](../README.md) - Project overview
- [INDEX.md](INDEX.md) - Full documentation index
