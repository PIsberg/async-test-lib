# Architecture Principles

> Part of the [architecture documentation](../ARCHITECTURE.md).

## Architecture Principles

### 1. Separation of Concerns

- **Extension Layer**: JUnit 5 integration only
- **Runner Layer**: Test execution orchestration
- **Detector Layer**: Concurrency issue detection
- **Benchmark Layer**: Performance tracking

### 2. Thread Safety

- All detectors are thread-safe
- Shared state protected by concurrent collections
- ThreadLocal for per-thread context isolation

### 3. Opt-in Complexity

- Phase 1: Always on (core detectors: deadlock, visibility, livelock)
- Phase 2: Opt-in via flags (40+ advanced detectors managed by DetectorRegistry)
- Phase 3: Behavioral auto-detectors (race conditions, ThreadLocal leaks, busy-wait, atomicity, interrupt)
- Standalone validators: manual instantiation for targeted legacy-pattern checks
- Benchmarking: Opt-in via flag or system property

### 4. Zero Overhead Default

- Detectors only created when enabled
- No performance impact when not using @AsyncTest
- Benchmarking completely optional

---

