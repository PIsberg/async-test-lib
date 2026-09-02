<!-- VIBETAGS-START -->
# Rules for async-test-instrumentation

<!-- VIBETAGS-MODULE: async-test-agent -->
## Core Functionality

### se.deversity.asynctest.agent.AsyncTestAgent
- **Sensitivity**: Critical
- **Note**: The INSTALLED gate must stay at-most-once per JVM: every entry point (premain, agentmain, selfAttach) races on the same compareAndSet, and a second transformer would double-weave accesses and double-count every one. premain installs without retransformation because classes are woven as they load; agentmain must keep RETRANSFORMATION + disableClassFormatChanges(), which is only safe while neither weaver adds members — the Advice is a method-entry prologue, and FieldAccessWeaver inserts a stack-neutral, branch-free call before each field instruction, so frames stay valid and only maxStack grows (COMPUTE_MAXS, never COMPUTE_FRAMES, which would load classes from inside the agent). Nothing may throw out of premain — an exception there aborts JVM startup, which is why install() catches Throwable and releases the gate rather than propagating. The Premain-Class / Agent-Class manifest entries live in this module's jar, which is why attaching uses -javaagent:async-test-agent.jar.

## Contract-Frozen Signature
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.

### se.deversity.asynctest.agent.AgentOptions
- **Reason**: The class is package-private but the agentArgs grammar it parses is public surface: users type it on the -javaagent: command line. Key names (includes/excludes/debug), the comma-or-semicolon separator, the bare-token continuation that lets one key carry several values, and case-insensitive key matching are all part of that contract — changing any of them breaks existing launch scripts silently. Parsing must stay total: it is called from premain, where a thrown exception aborts JVM startup, so unknown keys are ignored and malformed input degrades to the default instrument-everything behaviour rather than failing.

### se.deversity.asynctest.agent.AtomicFieldRegistry
- **Reason**: Resolved reflectively so async-test-agent keeps its zero-dependency boundary on async-test-lib, which ArchitectureTest enforces in both directions. The method name and signature must match TelemetryRegistry.atomicallyManaged(String). Every failure path here must stay silent: this only ever suppresses findings, so losing it degrades precision rather than correctness, while throwing out of a class transformation would fail the user's test run.

### se.deversity.asynctest.agent.CollectionAccessWeaver
- **Reason**: The hook class name and the method names here are the other half of AgentCollectionHooks and AgentLockHooks: they are matched by erased signature at weave time, so renaming a hook or changing a parameter type breaks weaving with a NoSuchMethodError inside user code rather than at compile time. Each substitution must consume exactly the stack its original invocation consumed - stack-shape-neutral and member-free is what keeps retransformation safe under disableClassFormatChanges(). The visitor must never touch invokedynamic: parsing its constants is what made every Java record fail to instrument when this went through MemberSubstitution. Collection weaving is opt-in (collections=true) because it instruments every listed call in every matched class. The one-instruction lookahead behind whenResultDiscarded is a flag meaning the instruction just emitted was a substituted call whose result may be discarded: visitInsn(POP) is its only consumer and every other visit method must clear it, because a stale flag would turn an unrelated POP into a call whose parameter does not match the value on the stack, which is a VerifyError in the user's class at load time. SubstitutingVisitorClearsLookaheadEverywhereTest enumerates MethodVisitor to keep that override list complete.
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
- **Reason**: Java native deserialization sink. The BASELINE_FILTER allow-list (ending in !*) must resolve every class in the stream and reject all others, preventing arbitrary class loading (CWE-502 RCE). Never widen the filter or remove setObjectInputFilter.
<!-- VIBETAGS-MODULE-END: async-test-lib -->
<!-- VIBETAGS-END -->
