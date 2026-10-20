---
paths: ["**/benchmark/**", "**/telemetry/**", "**/agent/**", "**/analysis/**"]
---

<!-- VIBETAGS-START -->
# Rules for async-test-instrumentation

## Core Functionality

### se.deversity.asynctest.telemetry.ControlledSchedule
- **Sensitivity**: High
- **Note**: A seed replays a round only while every decision of who runs next depends on state changed at scheduling points: the statuses, priorities and step count that grant() and step() read, and the lock holds and pending acquisitions grant() consults (#1022), which change only at acquiring(), itself a point, and in acquired() and released(), which a worker calls in its own program order; an escaped worker's acquire or release lands outside a turn, but its round has already counted the escape. grant() must keep handing the round out when every ready worker waits on a held lock: the hold may be stale (a monitor released inside Object.wait is not seen), and withholding the round then stalls correct wait/notify code until the round times out (ControlledScheduleLockTest); each such grant is counted as a fallback. To keep holds from going stale, a woven Object.wait hands its holds back before waiting and takes them again after (releasingForWait, reacquiredAfterWait), and a worker re-confirms its own recorded holds with Thread.holdsLock or isHeldByCurrentThread at each of its points and at leave(), on its own thread, so the answer follows its program order and grant() still reads no thread state (#1048). Only watch() may read the clock or Thread.getState(), and it reads the state before asking whether the running worker is queued on the schedule's own lock, which is the scheduler's wait and never an escape (#1065); each decision it makes is counted as an escape, which is what tells the runner the seed no longer replays exactly, with one exception: an adopted child whose thread has terminated is marked done, not escaped (#1024), because while it ran to its end every peer was waiting, so the state the next grant reads is the same whenever watch() notices. Adopted children are ready from the moment they are adopted, so a grant never depends on how long the OS takes to start a thread. A worker waiting at a rendezvous is OUTSIDE, not escaped (#1021), and no grant is made while one is outside on its way back or when the last worker steps out, so the order a rendezvous releases workers in never decides who runs next. A grant() that read time or thread state, or an escape left uncounted, still passes every run and makes the printed replay line a false promise.

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

## Mirrored — Keep In Sync

### se.deversity.asynctest.telemetry.TelemetryBridge
- **Rule**: Free to change, but every mirror must change in the same commit.
- **Mirrors**: se.deversity.asynctest.diagnostics.DetectorFeeds, docs/DETECTOR_CATALOG.md
- **Reason**: DetectorFeeds.fedBy(AGENT) must equal the detectors the woven streams actually reach, and this class is one of those streams. The gate reflects over this class's declared constructors and methods and asserts the detector types it is compile-wired to are exactly {AtomicityValidator}. Routing a second detector here without adding its AGENT row leaves the catalog telling users the agent buys them nothing for that detector.
- **Enforced by**: se.deversity.asynctest.architecture.DetectorFeedCoverageTest
<!-- VIBETAGS-END -->
