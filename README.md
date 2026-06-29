<div align="center">

# async-test-lib

**JUnit 5 concurrency stress testing — one annotation, 114 detectors**

[![Maven Central](https://img.shields.io/maven-central/v/se.deversity.async-test-lib/async-test-lib)](https://central.sonatype.com/artifact/se.deversity.async-test-lib/async-test-lib)
[![License: PolyForm Noncommercial](https://img.shields.io/badge/License-PolyForm_Noncommercial-blue.svg)](LICENSE)
[![Build](https://github.com/PIsberg/async-test-lib/actions/workflows/tests.yml/badge.svg)](https://github.com/PIsberg/async-test-lib/actions/workflows/tests.yml)
[![codecov](https://codecov.io/gh/PIsberg/async-test-lib/graph/badge.svg)](https://codecov.io/gh/PIsberg/async-test-lib)
[![Java](https://img.shields.io/badge/Java-21%2B-orange)](https://openjdk.org/projects/jdk/21/)
[![Lines of Code](https://www.aschey.tech/tokei/github/PIsberg/async-test-lib?languages=Java&category=code)](https://github.com/PIsberg/async-test-lib)
[![OpenSSF Scorecard](https://api.securityscorecards.dev/projects/github.com/PIsberg/async-test-lib/badge)](https://securityscorecards.dev/viewer/?uri=github.com/PIsberg/async-test-lib)
[![Checkstyle](https://img.shields.io/badge/Checkstyle-passing-brightgreen)](checkstyle.xml)
[![PMD](https://img.shields.io/badge/PMD-passing-brightgreen)](pmd-ruleset.xml)
[![SpotBugs](https://img.shields.io/badge/SpotBugs-passing-brightgreen)](spotbugs-exclude.xml)
[![Error Prone](https://img.shields.io/badge/Error_Prone-passing-brightgreen)](https://errorprone.info)

![async-test demo](docs/diagrams/demo.gif)

</div>

---

## Why async-test?

- **One annotation** — `@AsyncTest` hammers your code with N threads × M invocations using a `CyclicBarrier` to force maximum contention. No executor boilerplate, no manual `CountDownLatch`, no `Thread.join` loops.
- **114 detectors** — deadlocks, race conditions, virtual-thread pinning, lifecycle bugs, misused JDK types, JDBC sharing, MessageDigest/SecureRandom/Cipher integrity, and more — all on by default (`detectAll = true`), or pick a `Preset` for a curated subset.
- **JUnit 5 native** — zero required configuration. Works anywhere JUnit 5 runs with no special JVM flags. An optional Java agent (`-javaagent:async-test-lib.jar`) enables deeper field-access instrumentation via Byte Buddy; default usage needs no agent.
- **CI-ready out of the box** — ship JUnit XML reports, machine-readable JSON, or `AssertionError` fail-gates directly to GitHub Actions, Jenkins, and GitLab CI.

---

## ⚡ Quick Start

### Maven Quick Start

1. **Add the dependency** to `pom.xml`:
   ```xml
   <dependency>
       <groupId>se.deversity.async-test-lib</groupId>
       <artifactId>async-test-lib</artifactId>
       <version>1.7.0-RC1</version>
       <scope>test</scope>
   </dependency>
   ```

2. **Write your first stress test**:
   ```java
   import se.deversity.asynctest.AsyncTest;

   class CounterTest {
       private int counter = 0;

       @AsyncTest(threads = 10, invocations = 100, detectAll = true)
       void counter_mustBeThreadSafe() {
           counter++;  // Race condition — async-test will catch it
       }
   }
   ```

3. **Run your tests**:
   ```bash
   mvn test
   ```

### Gradle Quick Start

1. **Add the dependency** to `build.gradle.kts`:
   ```kotlin
   testImplementation("se.deversity.async-test-lib:async-test-lib:1.7.0-RC1")
   ```

2. **Write your first stress test**:
   ```java
   import se.deversity.asynctest.AsyncTest;

   class CounterTest {
       private int counter = 0;

       @AsyncTest(threads = 10, invocations = 100, detectAll = true)
       void counter_mustBeThreadSafe() {
           counter++;  // Race condition — async-test will catch it
       }
   }
   ```

3. **Run your tests**:
   ```bash
   ./gradlew test
   ```

---

## Table of Contents

- [What is async-test?](#what-is-async-test)
- [Detectors](#detectors)
- [Configuration](#configuration)
- [Examples](#examples)
- [CI/CD Integration](#cicd-integration)
- [IntelliJ Plugin](#intellij-plugin)
- [Documentation](#documentation)
- [License](#license)

---

## What is async-test?

`async-test` is a JUnit 5 `@TestTemplate` extension that stress-tests concurrent code by running the annotated method body simultaneously across N threads, repeated M times. A `CyclicBarrier` forces all threads to start each round at the same instant, maximising contention and surfacing bugs that only appear under real concurrency — not in sequential unit tests.

After the run, the **detector registry** analyses what was observed and reports any issues via the standard JUnit failure mechanism, so they surface in your IDE, CI dashboard, and test reports without any extra tooling.

```
@AsyncTest
  └─► ConcurrencyRunner
        ├─ CyclicBarrier (all N threads collide on each invocation)
        ├─ Phase 1–N detectors observe the run
        └─ DetectorRegistry.analyzeAll() → JUnit failure / listener events
```

---

## Detectors

114 detectors enabled by default with a single flag, or cherry-pick:

```java
// Everything on (default for bare @AsyncTest)
@AsyncTest

// Curated preset for everyday CI
@AsyncTest(preset = Preset.ESSENTIALS)

// Everything on except false sharing (too slow for this suite)
@AsyncTest(excludes = { DetectorType.FALSE_SHARING })

// Explicit opt-in
@AsyncTest(detectAll = false, detectDeadlocks = true, detectRaceConditions = true)
```

### Detector categories

| Category | What it catches |
|----------|----------------|
| **Core** | Deadlocks, livelocks, memory-model visibility (`volatile` gaps) |
| **Race conditions** | Unsynchronized field access, non-atomic compound ops (`get`+`set` on `Atomic*`) |
| **Common JDK types** | `ArrayList`, `HashMap`, `StringBuilder`, `Calendar`, `SimpleDateFormat`, `DecimalFormat`, `Matcher`, `MessageDigest`, `TimeZone`, `Timer` shared across threads |
| **Virtual threads** | Thread pinning (`synchronized` inside virtual thread), CPU-bound tasks, carrier exhaustion, `ScopedValue` misuse, context leak |
| **Locks & monitors** | Boxed-primitive lock (`synchronized(Integer)`), public lock exposure, lock leak, nested monitor lockout, `StampedLock` optimistic-read without `validate()`, lock downgrade |
| **Lifecycle** | Executor never shut down, thread leak, `Future` result ignored, `CountDownLatch` misuse, `CyclicBarrier` trip count wrong |
| **Concurrency primitives** | `CompletableFuture` chain issues, blocking on common pool, `ForkJoinTask` blocking, `Exchanger`, `Phaser`, `Semaphore` misuse |
| **Hygiene** | Interrupt swallowing, MDC context leak, `System.setProperty` from multiple threads, `System.gc()` in tests, deprecated thread API (`Thread.stop()` etc.) |
| **Environment** | Uncommitted Git changes (reproducibility gate) |
| **Phase 13** | Daemon-thread hygiene, illegal `notify*()`, shared `SecureRandom`, shared `WeakHashMap`/`IdentityHashMap`, shared JDBC `Connection`/`Statement`/`ResultSet` |
| **Phase 14** (new) | Shared stateful crypto (`Cipher`/`Mac`/`Signature`), non-atomic `ConcurrentMap` check-then-act, shared `Deflater`/`Inflater`, constructor `this`-escape, cached `ThreadLocalRandom` used off-thread |
| **Phase 16 — JDK 25/26 preview** (new) | `StableValue` misuse (read-before-set / double-set / reentrant `orElseSet`), `StructuredTaskScope` lifecycle (fork-after-join, result-before-join, owner-confinement, missing join), parallel-`Gatherer` without a combiner |

Full parameter reference: [docs/USAGE.md](docs/USAGE.md)

> **JDK 25/26 detectors are wired into the pipeline** (Phase 16). `StableValueMisuseDetector`,
> `StructuredTaskScopeMisuseDetector`, and `GathererConcurrencyMisuseDetector` are part of
> `detectAll` and the `Preset.ALL` / `STRICT` bundles, each with a `DetectorType` constant
> (`STABLE_VALUE_MISUSE`, `STRUCTURED_TASK_SCOPE_MISUSE`, `GATHERER_CONCURRENCY_MISUSE`) and
> an `@AsyncTest` flag (`detectStableValueMisuse`, `detectStructuredTaskScopeMisuse`,
> `detectGathererConcurrencyMisuse`). Record events against them via the
> `AsyncTestContext.stableValueMisuseDetector()` / `structuredTaskScopeMisuseDetector()` /
> `gathererConcurrencyMisuseDetector()` accessors; findings surface through the standard
> report and `failOn` gate. See [docs/DETECTOR_CATALOG.md](docs/DETECTOR_CATALOG.md).

---

## Configuration

```java
@AsyncTest(
    threads      = 10,                       // concurrent threads per invocation round
    threadCounts = {2, 4, 8, 16, 32},        // OR: sweep multiple counts (one JUnit invocation per entry)
    invocations  = 100,                      // how many rounds to run
    timeoutMs    = 5000,                     // per-test timeout
    useVirtualThreads = true,                // Java 21+ virtual threads
    virtualThreadStressMode = "HIGH",        // OFF / LOW / MEDIUM / HIGH / EXTREME
    preset       = Preset.ESSENTIALS,        // curated detector bundle (overrides detectAll)
    detectAll    = true,                     // legacy umbrella when preset = ALL
    includes     = { DetectorType.DEADLOCKS },      // OR: exactly these detectors, nothing else
    excludes     = { DetectorType.FALSE_SHARING },  // prune even from a preset/includes
    failOn       = FailOn.HIGH,              // findings at/above this severity fail the test
    replaySeed   = 0L                        // 0 = fresh per round; set on failure to reproduce
)
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `threads` | 10 | Threads spawned per round |
| `threadCounts` | `{}` | Schedule matrix — one invocation per entry; ignored when empty. Sweeps thread counts cheaply since race sensitivity is count-dependent |
| `invocations` | 100 | Number of barrier rounds |
| `timeoutMs` | 5000 | Whole-test timeout (ms) |
| `useVirtualThreads` | true | Use `Thread.ofVirtual()` (Java 21+) |
| `preset` | `Preset.ALL` | Curated bundle: `ALL` / `STRICT` / `ESSENTIALS` / `CI_FAST` / `NONE` |
| `detectAll` | true | Enable all detectors in one shot (honored when `preset = ALL`) |
| `includes` | `{}` | Enable exactly these detectors — overrides `preset`/`detectAll`/per-detector flags when non-empty |
| `excludes` | `{}` | Detectors to skip — layers on top of any preset or `includes` and wins on conflict |
| `failOn` | `FailOn.NONE` | Severity gate: findings at/above this level (`LOW`/`MEDIUM`/`HIGH`/`CRITICAL`) fail the test; `NONE` = report-only |
| `replaySeed` | 0 | Per-round RNG seed. `0` = fresh per round (printed on failure); set explicitly to reproduce a failing schedule |

`@AsyncTest` can also be placed on a **class** (shared config for all `@TestTemplate`
methods; method-level `@AsyncTest` wins) or on an **annotation** to compose reusable
presets like `@EssentialsAsyncTest`.

### Fail gates & baseline

Gate CI on serious findings while adopting incrementally:

```java
@AsyncTest(failOn = FailOn.HIGH)   // HIGH and CRITICAL findings fail the test
void checkout_concurrently() { ... }
```

For a legacy codebase, record the current findings once and ratchet them down:

```bash
mvn test -Dasync-test.baseline=async-test-baseline.txt -Dasync-test.baseline.update=true  # record
mvn test -Dasync-test.baseline=async-test-baseline.txt                                    # enforce
```

Each baseline line is `com.example.MyTest#method | DetectorName` — diff-friendly and
hand-editable; delete lines as you fix the findings.

---

## Examples

### Catching a race condition

```java
class CounterTest {
    private int counter = 0;

    @AsyncTest(threads = 10, invocations = 100, detectAll = true)
    void increment_mustBeAtomic() {
        counter++;  // BUG: compound read-modify-write, not atomic
    }
}
// Fix: use AtomicInteger.incrementAndGet()
```

### Catching a deadlock

```java
class LockTest {
    private final Object lockA = new Object();
    private final Object lockB = new Object();

    @AsyncTest(threads = 4, invocations = 50, detectDeadlocks = true)
    void acquireLocks() {
        if (Thread.currentThread().getId() % 2 == 0) {
            synchronized (lockA) { synchronized (lockB) { /* work */ } }
        } else {
            synchronized (lockB) { synchronized (lockA) { /* work */ } }
            //  ^^^ opposite order — deadlock waiting to happen
        }
    }
}
```

### Virtual thread stress test

```java
class VirtualThreadTest {
    private final List<String> items = Collections.synchronizedList(new ArrayList<>());

    @AsyncTest(
        threads = 100_000,
        invocations = 5,
        useVirtualThreads = true,
        virtualThreadStressMode = "EXTREME",
        detectAll = true
    )
    void highConcurrency() {
        items.add("item-" + Thread.currentThread().threadId());
    }
}
```

### Sweep thread counts to find the contention sweet spot

```java
class HashMapCacheTest {
    private final Map<String, String> cache = new HashMap<>(); // BUG: not thread-safe

    @AsyncTest(threadCounts = {2, 4, 8, 16, 32, 64})  // 6 separate JUnit invocations
    void put_thenGet() {
        cache.put(UUID.randomUUID().toString(), "v");
    }
}
// JUnit emits one test per count; race condition surfaces reliably at 16+
```

### Async test body — await a `CompletionStage` inside @AsyncTest

```java
class AsyncPipelineTest {
    @AsyncTest(threads = 8)
    void hammer_pipeline() {
        CompletableFuture<String> stage = service.processAsync(payload);
        String result = AsyncAssert.awaitAsync(stage, Duration.ofSeconds(5));
        assertEquals("ok", result);
    }
}
// awaitAsync unwraps ExecutionException — failures surface as the real exception type
```

### Reproduce a flaky failure

```java
class FlakyTest {
    @AsyncTest                                          // 1st run: failure prints replaySeed=4242L
    @AsyncTest(replaySeed = 4242L)                      // re-run with the printed seed
    void randomised_workload() {
        var rng = new Random(AsyncTestContext.replaySeed());
        Thread.sleep(rng.nextInt(10));                  // randomised jitter is now deterministic
        service.handle(payload(rng));
    }
}
```

### Scoped listener (no JVM-wide leak)

```java
@Test
void capture_findings_for_one_test() {
    try (var ignored = AsyncTestListenerRegistry.registerScoped(myListener)) {
        // myListener fires only inside this block
        runMyAsyncTest();
    }
    // automatic unregister on close
}
```

More examples with runnable code: [examples/](examples/)

Release notes: [docs/CHANGELOG.md](docs/CHANGELOG.md)

---

## CI/CD Integration

Register a listener in `@BeforeAll` to get structured output alongside the standard JUnit failure:

```java
@BeforeAll
static void setup() {
    // JUnit XML → GitHub Actions / Jenkins / GitLab CI test dashboards
    AsyncTestListenerRegistry.register(new JUnitXmlReportListener());

    // Structured JSON → dashboards, quality gates, custom tooling
    AsyncTestListenerRegistry.register(new JsonReportListener());

    // Throw AssertionError immediately on any finding
    AsyncTestListenerRegistry.register(new StrictModeListener());
}
```

### GitHub Actions example

```yaml
- name: Run tests
  run: mvn test

- name: Upload async-test reports
  uses: actions/upload-artifact@v4
  if: always()
  with:
    name: async-test-reports
    path: target/async-test-reports/
```

Findings appear as named test-case failures in the Actions UI — not just as `stderr` noise.

Full CI/CD setup guide: [docs/CI_INTEGRATION.md](docs/CI_INTEGRATION.md)

---

## IntelliJ Plugin

A companion IntelliJ IDEA plugin reads the JSON report and surfaces findings in a dedicated tool window — with severity colouring, full report text, and a Refresh action to pick up new results without leaving the IDE.

```
View → Tool Windows → async-test Findings
```

**Setup:**

1. Build the plugin: `cd intellij-plugin && ./gradlew buildPlugin`
2. Install in IntelliJ: **Settings → Plugins → Install Plugin from Disk**
3. Point it at your report: **Settings → Tools → async-test**
4. Run your tests, then click **Refresh** in the tool window

See [intellij-plugin/README.md](intellij-plugin/README.md) for full instructions.

---

## Documentation

| Resource | Description |
|----------|-------------|
| [docs/USAGE.md](docs/USAGE.md) | Full `@AsyncTest` parameter reference, all detectors, examples |
| [docs/CI_INTEGRATION.md](docs/CI_INTEGRATION.md) | GitHub Actions, Jenkins, GitLab CI setup |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Execution flow, detector phases, extension points |
| [docs/CHANGELOG.md](docs/CHANGELOG.md) | Version history |
| [examples/](examples/) | 30+ runnable example projects |
| [intellij-plugin/README.md](intellij-plugin/README.md) | IntelliJ plugin setup |

---

## License

[PolyForm Noncommercial License 1.0.0](LICENSE) — free for non-commercial use.

### Running without a license key

| Environment | Behavior |
|---|---|
| CI (any `GITHUB_ACTIONS` or `CI` env var set, no key) | Auto-mocked — tests run freely |
| Local, no key, `-Dlicense.mock.mode=true` | Mock mode active — tests run freely |
| Local, no key, no mock flag | License gate runs; outcome depends on the configured backend |
| Real key via `-Dlicense.key=<key>` | Full validation against the licensing backend |

To run locally without a key during development:
```
mvn test -Dlicense.mock.mode=true
```
Or add to your IDE's JVM args: `-Dlicense.mock.mode=true`

Set your email identity when using a real key: `-Dlicense.user.email=you@example.com`
