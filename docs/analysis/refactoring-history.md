# Refactoring History

> Part of the [architecture documentation](../ARCHITECTURE.md).

## Refactoring Summary (v1.2.0)

The following structural improvements were made to address code quality concerns:

### 1. Break Up Large Classes

**AsyncTestContext** (539 lines → ~150 lines)
- Extracted `DetectorRegistry` to handle detector instantiation and analysis
- AsyncTestContext now focuses on ThreadLocal lifecycle and public API accessors

**ConcurrencyRunner** (350 lines → ~200 lines)
- Extracted `Phase1DetectorSet` to group Phase 1 detectors
- Eliminates long parameter lists in helper methods

### 2. Reduce Tight Coupling

- `Phase1DetectorSet.from(AsyncTestConfig)` encapsulates detector creation
- `Phase1DetectorSet.printReports()` owns Phase 1 report printing logic
- ConcurrencyRunner now depends on the facade, not individual detectors

### 3. Memory Management

- ThreadLocal cleanup ensured in `runSingleInvocationRound` finally block
- `uninstall()` called before `latch.countDown()` to prevent context leaks
- `ThreadLocal.remove()` properly clears thread state

### 4. Observability (NEW)

- `AsyncTestListener` interface for lifecycle event callbacks
- `AsyncTestListenerRegistry` for thread-safe listener management
- `NoopAsyncTestListener` for opt-out of default output
- Events: invocation start/complete, test failure, detector reports, timeout

### New Classes

| Class | Package | Purpose |
|-------|---------|---------|
| `DetectorRegistry` | `se.deversity.async-test-lib` | Phase 1–3 detector lifecycle |
| `Phase1DetectorSet` | `se.deversity.asynctest.diagnostics` | Phase 1 detector grouping |
| `AsyncTestListener` | `se.deversity.async-test-lib` | Observability interface |
| `AsyncTestListenerRegistry` | `se.deversity.async-test-lib` | Listener registration |
| `NoopAsyncTestListener` | `se.deversity.async-test-lib` | No-op listener for opt-out |

---

