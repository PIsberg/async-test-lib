# C4 & UML Diagrams

> Part of the [architecture documentation](../ARCHITECTURE.md).

## System Context Diagram

Shows the high-level system architecture and external dependencies.

**Key Components:**
- **JUnit 5 Platform**: Discovers and executes @AsyncTest methods
- **Async Test Library**: Core testing framework with 142 detectors
- **User Test Code**: Tests annotated with @AsyncTest
- **Benchmark Storage**: Persistent baseline data for performance comparison

![System Context Diagram](../diagrams/SystemContext.png)

**Source:** [`system-context.puml`](../diagrams/system-context.puml)

---

## Container Diagram

Shows the main containers/components within the async-test library JAR.

**Main Containers:**
- **Extension Layer**: JUnit 5 integration (`AsyncTestExtension`, `AsyncTestInvocationInterceptor`).
  Since 1.6.0 the extension fans out one `TestTemplateInvocationContext` per
  `@AsyncTest(threadCounts={…})` entry for the schedule matrix.
- **Configuration**: `AsyncTest` annotation, `AsyncTestConfig` (immutable), `Preset` enum
- **Runner Core**: `ConcurrencyRunner`, `AsyncTestContext`, `VirtualThreadStressConfig`,
  `LicenseGuard` (extracted in 1.6.0 — see [License Guard](#license-guard-100))
- **Detector Modules** (142 detectors; the phase-by-phase catalog is [DETECTOR_CATALOG.md](../DETECTOR_CATALOG.md)):
  - Phase 1: Core (3 detectors) — grouped via `Phase1DetectorSet`
  - Phases 2–14: managed by `DetectorRegistry`
- **Reporting** (NEW in 1.6.0 — `se.deversity.asynctest.report`):
  `Violation` record, `Formatter` interface, `MarkdownFormatter`, `JsonFormatter`
- **Detector SPI** (NEW in 1.6.0 — `se.deversity.asynctest.spi`):
  `Detector`, `DetectorFactory`, SPI-driven `DetectorRegistry`, `adapters/` for canary migrations
- **Diagnostics helpers**: `SiteCapture` (new in 1.6.0) for source-line attribution
- **Benchmark Module**: 5 classes for performance tracking
- **Lifecycle Annotations**: `BeforeEachInvocation`, `AfterEachInvocation`
- **Observability**: `AsyncTestListener`, `AsyncTestListenerRegistry` (with scoped
  `Registration` / `Snapshot` since 1.6.0), `NoopAsyncTestListener`
- **Assertion helpers**: `AsyncAssert.awaitUntil`, `AsyncAssert.capture`,
  `AsyncAssert.awaitAsync` (new in 1.6.0)

![Container Diagram](../diagrams/ContainerDiagram.png)

**Source:** [`container.puml`](../diagrams/container.puml)

---

## Component Flow Diagram

Shows how the JUnit 5 extension intercepts and executes tests.

**Flow:**
1. JUnit discovers @AsyncTest method
2. AsyncTestExtension provides invocation context
3. AsyncTestInvocationInterceptor skips standard execution
4. ConcurrencyRunner creates detectors and context
5. N×M execution loop (N invocations × M threads)
6. Benchmarking records execution times
7. Detector analysis and reporting

![Component Flow Diagram](../diagrams/ComponentFlow.png)

**Source:** [`component-flow.puml`](../diagrams/component-flow.puml)

---

## Sequence Diagram - Test Execution

Detailed sequence showing the N×M execution pattern.

**Key Steps:**
1. JUnit 5 detects @AsyncTest method
2. ConcurrencyRunner.execute() is called
3. Detectors and BenchmarkRecorder are created
4. For each invocation (N times):
   - M threads are submitted to ExecutorService
   - All threads wait at CyclicBarrier
   - Barrier releases all threads simultaneously
   - Each thread executes test body concurrently
   - Events are recorded to detectors
   - Benchmark times are recorded
5. After all invocations:
   - Benchmark comparison with baseline
   - Detector analysis
   - Reports printed if issues detected

![Sequence Execution Diagram](../diagrams/SequenceExecution.png)

**Source:** [`sequence-execution.puml`](../diagrams/sequence-execution.puml)

---

## Class Diagram

Shows the main classes and their relationships.

**Core Classes:**
- **AsyncTest**: Main annotation with 35+ configuration parameters
- **AsyncTestConfig**: Immutable configuration object
- **AsyncTestExtension**: JUnit 5 TestTemplateInvocationContextProvider
- **AsyncTestInvocationInterceptor**: InvocationInterceptor that intercepts test execution
- **ConcurrencyRunner**: Static executor that orchestrates test execution
- **AsyncTestContext**: ThreadLocal context providing detector accessors
- **DetectorType**: Enumeration of all detector types
- **Benchmark Classes**: BenchmarkRecorder, BenchmarkComparator, BenchmarkResult, BenchmarkComparisonResult, BenchmarkRegressionException

**Relationships:**
- AsyncTestExtension provides AsyncTestInvocationInterceptor
- AsyncTestInvocationInterceptor calls ConcurrencyRunner.execute()
- ConcurrencyRunner creates and installs AsyncTestContext per thread
- AsyncTestContext contains all Phase 2 detector instances
- BenchmarkRecorder uses BenchmarkComparator to compare with baselines

![Class Diagram](../diagrams/ClassDiagram.png)

**Source:** [`class-diagram.puml`](../diagrams/class-diagram.puml)

---

## Sequence Diagram - Benchmarking

Shows how benchmarking integrates with test execution.

**First Run (Baseline Creation):**
1. BenchmarkRecorder created
2. For each invocation: record start/end times
3. Calculate statistics (avg, min, max, stddev)
4. No baseline exists → save current as baseline
5. Print "Baseline created" message

**Subsequent Runs (Comparison):**
1. BenchmarkRecorder created
2. For each invocation: record start/end times
3. Calculate statistics
4. Load baseline from storage
5. Calculate % change
6. If change > threshold: regression detected
   - If failOnRegression=true: throw BenchmarkRegressionException
   - Else: log warning
7. If change < -threshold: improvement detected
8. Else: stable performance

![Benchmark Sequence Diagram](../diagrams/BenchmarkSequence.png)

**Source:** [`benchmark-sequence.puml`](../diagrams/benchmark-sequence.puml)

---

## Sequence Diagram - Licensing (online)

How `LicenseGuard` decides whether an `@AsyncTest` run is licensed, and why a licensing-provider
outage does not fail a licensed build while a rejected key always does.

**Key steps:**
1. Every invocation calls `LicenseGuard.check(config)`; the outcome is memoized per
   (fingerprint x JVM), so the gate does real work at most once per configuration per JVM
2. Decision order, first match wins: mock mode, offline file, fresh disk cache, online
   validation, outage grace
3. A successful online validation is recorded on disk as a SHA-256 of the configuration plus an
   epoch (never the key), so with `forkEvery = 1` a suite makes one licensing call per
   `license.cache.ttl.hours` window instead of one per test-class JVM
4. Definitive rejections (not found, expired, suspended, wrong scope) fail the build in every
   mode
5. `NETWORK_ERROR` under the default grace mode is forgiven only when this configuration has a
   recorded prior success or the provider host is connection-level unreachable; an answering
   host that errors keeps the build failing, because it could have rejected the credentials

![License Online Sequence Diagram](../diagrams/LicenseOnlineSequence.png)

**Source:** [`license-online-sequence.puml`](../diagrams/license-online-sequence.puml)

---

## Sequence Diagram - Licensing (offline files)

The `-Dlicense.file` path for air-gapped and egress-blocked CI: issuance on the operator
machine, validation inside the customer's JVM, and no network anywhere on the customer's side.

**Key steps:**
1. The operator signs a small Properties payload (product, licensee, email, binding, expiry)
   with the Ed25519 private key that exists only in
   `~/.config/deversity/offline-license-signing/`; the file is one line,
   `ATL1.<base64url payload>.<base64url signature>`
2. The customer points `-Dlicense.file` at the file; its presence disables CI auto-mock, and
   only the explicit `-Dlicense.mock.mode=true` outranks it
3. `OfflineLicense` verifies the signature against the vendor public key embedded in the
   library, then product, expiry (UTC, inclusive) and the email binding
   (`domain` / `exact` / `none`, ASCII-only case fold)
4. Every anomaly fails closed with a named `OFFLINE_*` reason and never falls back to online
   validation
5. Renewal is a replacement file; nothing else in the customer's configuration changes

![License Offline Sequence Diagram](../diagrams/LicenseOfflineSequence.png)

**Source:** [`license-offline-sequence.puml`](../diagrams/license-offline-sequence.puml)

---

## Activity Diagram

Shows the decision flow during test execution.

**Main Flow:**
1. JUnit 5 discovers @AsyncTest method
2. Extension layer checks for annotation
3. Interceptor skips standard execution
4. Runner setup:
   - Create Phase 1, 2, 3 detectors
   - Create BenchmarkRecorder (if enabled)
   - Create AsyncTestContext
   - Determine thread count
   - Create ExecutorService
5. Execution loop (N invocations):
   - Record benchmark start time
   - Invoke @BeforeEachInvocation methods
   - Fork M threads to barrier
   - All threads released simultaneously
   - Fork M threads to execute test body
   - Record detector events
   - Record benchmark end time
   - Invoke @AfterEachInvocation methods
6. Benchmarking:
   - Calculate statistics
   - Compare with baseline
   - Report regression/improvement/stable
7. Analysis:
   - Call analyzeAll() on detectors
   - Print reports if issues detected
   - Shutdown ExecutorService

![Activity Diagram](../diagrams/ActivityDiagram.png)

**Source:** [`activity-diagram.puml`](../diagrams/activity-diagram.puml)

---

## Deployment Diagram

Shows how the library is deployed and used.

**Artifacts:**
- **async-test-lib-&lt;version&gt;.jar**: Main library
  - Extension layer classes
  - Runner classes
  - 142 detector classes
  - 5 benchmark classes
  - META-INF/services (JUnit extension registration)
- **async-test-lib-&lt;version&gt;-sources.jar**: Source code
- **async-test-lib-&lt;version&gt;-javadoc.jar**: API documentation

**Deployment:**
- Published to Maven repository (GitHub Packages)
- User projects add as test dependency
- Benchmark data stored in `target/benchmark-data/`

![Deployment Diagram](../diagrams/DeploymentDiagram.png)

**Source:** [`deployment-diagram.puml`](../diagrams/deployment-diagram.puml)

---

## Diagram Source Files

All PlantUML source files are located in [`docs/diagrams/`](../diagrams/):

| Diagram | Source File | PNG File |
|---------|-------------|----------|
| System Context | `system-context.puml` | `SystemContext.png` |
| Container | `container.puml` | `ContainerDiagram.png` |
| Component Flow | `component-flow.puml` | `ComponentFlow.png` |
| Sequence Execution | `sequence-execution.puml` | `SequenceExecution.png` |
| Class Diagram | `class-diagram.puml` | `ClassDiagram.png` |
| Benchmark Sequence | `benchmark-sequence.puml` | `BenchmarkSequence.png` |
| Licensing (online) | `license-online-sequence.puml` | `LicenseOnlineSequence.png` |
| Licensing (offline files) | `license-offline-sequence.puml` | `LicenseOfflineSequence.png` |
| Activity | `activity-diagram.puml` | `ActivityDiagram.png` |
| Deployment | `deployment-diagram.puml` | `DeploymentDiagram.png` |
| Detector Architecture | `detector-architecture.puml` | `DetectorArchitecture.png` |

To regenerate diagrams, see [`docs/diagrams/README.md`](../diagrams/README.md).

---

## Parsed diagrams (code-karta)

Everything above is hand-drawn PlantUML: it says what the design *intends*. The diagrams
under [`docs/diagrams/codekarta/`](../diagrams/codekarta/) are parsed from the source by
[code-karta](https://github.com/PIsberg/codekarta) and say what the code currently *is*.
Keeping both is the point — when they disagree, the gap is the interesting part, and it is
the kind of drift no test catches.

| Diagram | Scope | What it answers |
|---------|-------|-----------------|
| `codekarta/modules-diagram.svg` | the Maven reactor | Which of the three modules depends on which |
| `codekarta/class-diagram.svg` | `se.deversity.asynctest` | The public surface and how the annotation, extension, config and registries connect |
| `codekarta/runner-class-diagram.svg` | `runner` | What `ConcurrencyRunner` collaborates with |
| `codekarta/concurrencyrunner-sequence-diagram.svg` | `ConcurrencyRunner.java` | The call sequence and exception flow of one invocation |
| `codekarta/spi-class-diagram.svg` | `spi` | `Detector` / `DetectorFactory` / SPI `DetectorRegistry` and the legacy adapters |
| `codekarta/report-class-diagram.svg` | `report` | `Violation` → `Formatter` → the report listeners |

Regenerate with:

```bash
sh tools/generate-architecture-diagrams.sh
```

The CLI is resolved from Maven Central, so the first run needs a network and later runs do
not. Nothing is vendored into the repository.

**They cannot go stale silently.** The `diagrams` job in `.github/workflows/guardrails.yml`
regenerates them on every push and pull request and fails on structural drift, comparing
`tools/diagram-structure.sh` fingerprints (the sorted set of node titles per SVG; positions
and member labels are excluded because code-karta's directory walk follows filesystem
order, so bytes differ between the OS that committed and the OS that checks). When it
fails, the fresh SVGs are attached to the run as the `regenerated-diagrams` artifact. The
gate was introduced against a tree where 27 node titles had moved since the last manual
regeneration.

**Why the module diagram needs code-karta 0.2.0.** Before it, `--modules-only` understood
`module-info.java` and nothing else, so it answered "Graph is empty" here — this project
declares no JPMS modules, only a Maven reactor. 0.2.0 reads the reactor, which is where the
cross-module question was being asked in the first place.

**Why they share one directory.** They did not before 0.2.0. Every class diagram was called
`class-diagram.svg` whatever it was a diagram of, so each scope needed a subdirectory of its
own — a layout that described a limitation of the generator rather than anything about this
codebase. `--output-name` removed the reason for it.

**Why each diagram is scoped to one package.** A whole-tree run is not attempted, and that
is deliberate. Breadth rather than depth is what makes these unreadable: a stitched call
graph over a single large package reached 986 nodes and roughly 36000×43700 pixels, which
is a data dump wearing a diagram's clothes, and `--max-depth` does not rescue it because
the fan-out is horizontal. Scoping the input beats tuning the flags. If you add a diagram
here, scope it to a package that answers one question.

The scripts pin `--layout elk`. The default `simple` engine lays every node of one BFS
depth into a single unbounded row, and `diagnostics/` alone has ~138 largely unconnected
types, which renders tens of thousands of pixels wide.

---

