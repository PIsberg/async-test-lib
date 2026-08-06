<!-- VIBETAGS-START -->
# Rules for async-test-instrumentation

<!-- VIBETAGS-MODULE: async-test-agent -->
## Core Functionality

### se.deversity.asynctest.agent.AsyncTestAgent
- **Sensitivity**: Critical
- **Note**: The INSTALLED gate must stay at-most-once per JVM: every entry point (premain, agentmain, selfAttach) races on the same compareAndSet, and a second transformer would double-weave field accessors and double-count every access. premain installs without retransformation because classes are woven as they load; agentmain must keep RETRANSFORMATION + disableClassFormatChanges(), which is only safe while the Advice stays a method-entry prologue that adds no fields, methods or interfaces. Nothing may throw out of premain — an exception there aborts JVM startup. The Premain-Class / Agent-Class manifest entries live in this module's jar, which is why attaching uses -javaagent:async-test-agent.jar.

## Contract-Frozen Signature

### se.deversity.asynctest.agent.AgentOptions
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: The class is package-private but the agentArgs grammar it parses is public surface: users type it on the -javaagent: command line. Key names (includes/excludes/debug), the comma-or-semicolon separator, the bare-token continuation that lets one key carry several values, and case-insensitive key matching are all part of that contract — changing any of them breaks existing launch scripts silently. Parsing must stay total: it is called from premain, where a thrown exception aborts JVM startup, so unknown keys are ignored and malformed input degrades to the default instrument-everything behaviour rather than failing.
<!-- VIBETAGS-MODULE-END: async-test-agent -->
<!-- VIBETAGS-MODULE: async-test-analysis -->
## Core Functionality

### se.deversity.asynctest.analysis.StaticPinningScanner
- **Sensitivity**: High
- **Note**: The whole module is this one class plus ASM, and ArchitectureTest pins both directions: nothing here may reference the library, and asm may not leak out of here. Keep the analysis one-directional — if the scanner starts needing the runner or a detector, that is a design question, not a dependency to add. The asymmetry in the findings is deliberate and must be preserved: monitor depth is tracked within a single method body only, so cross-method synchronization yields false negatives, and MONITOREXIT on exception-handler edges may undercount depth. False negatives are acceptable here; a false positive is not, because the scanner runs without executing tests and has no way to confirm a site.
<!-- VIBETAGS-MODULE-END: async-test-analysis -->
<!-- VIBETAGS-MODULE: async-test-lib -->
## Performance Constraints

### se.deversity.asynctest.benchmark.BenchmarkRecorder
- **Rule**: Optimal complexity required. O(n^2) is forbidden on hot paths.
- **Constraint**: recordInvocationStart() and recordInvocationEnd() are called on the hot path inside every invocation round. Keep them allocation-free and avoid acquiring locks in the common case.

## Observability Instrumentation

### se.deversity.asynctest.benchmark.BenchmarkRecorder
- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard.
- **Details**: Metrics: benchmark.invocation.times. Logs: [BENCHMARK] Baseline created, [BENCHMARK] Baseline updated, [BENCHMARK] STABLE, [BENCHMARK] REGRESSION, [BENCHMARK] IMPROVEMENT. Note: Hot path telemetry used by JUnit benchmark metrics and baseline regression checks.

## Feature Flag Gate

### se.deversity.asynctest.benchmark.BenchmarkRecorder
- **Flag**: 'async-test.benchmarking.enabled' (default: false)
- **Rule**: This code is gated behind a feature flag. Preserve the flag check. Never assume the flag is always active.

## Memory Budget Constraints
- **Policy**: NO_AUTOBOXING
- **Rule**: Strictly limit or prevent object allocations.
- **Applies to**: `se.deversity.asynctest.benchmark.BenchmarkRecorder.recordInvocationEnd(long)`, `se.deversity.asynctest.benchmark.BenchmarkRecorder.recordInvocationStart()`

## Strict Classpath Integrity

### se.deversity.asynctest.benchmark.BenchmarkComparator.readStore(java.io.File)
- **Rule**: Prohibit dynamic class loading, custom classloaders, runtime reflection hacks, or execution of dynamic external code.
<!-- VIBETAGS-MODULE-END: async-test-lib -->
<!-- VIBETAGS-END -->
