<div align="center">

# async-test-lib

**JUnit 5 concurrency stress testing — one annotation, 69+ detectors**

[![License: PolyForm Noncommercial](https://img.shields.io/badge/License-PolyForm_Noncommercial-blue.svg)](LICENSE)
[![Build](https://github.com/PIsberg/async-test-lib/actions/workflows/tests.yml/badge.svg)](https://github.com/PIsberg/async-test-lib/actions/workflows/tests.yml)
[![Java](https://img.shields.io/badge/Java-21%2B-orange)](https://openjdk.org/projects/jdk/21/)
[![OpenSSF Scorecard](https://api.securityscorecards.dev/projects/github.com/PIsberg/async-test-lib/badge)](https://securityscorecards.dev/viewer/?uri=github.com/PIsberg/async-test-lib)

![async-test demo](docs/diagrams/demo.gif)

</div>

---

## Why async-test?

- **One annotation** — `@AsyncTest` hammers your code with N threads × M invocations using a `CyclicBarrier` to force maximum contention. No executor boilerplate, no manual `CountDownLatch`, no `Thread.join` loops.
- **69+ detectors** — deadlocks, race conditions, virtual-thread pinning, lifecycle bugs, misused JDK types, and more — all off by default, all enabled with `detectAll = true`.
- **JUnit 5 native** — no agent, no bytecode weaving, no special JVM flags. Works anywhere JUnit 5 runs.
- **CI-ready out of the box** — ship JUnit XML reports, machine-readable JSON, or `AssertionError` fail-gates directly to GitHub Actions, Jenkins, and GitLab CI.

---

<details>
<summary><b>⚡ Quick Start — add async-test in 60 seconds</b></summary>

<br>

**Maven** — add to `pom.xml`:

```xml
<dependency>
    <groupId>se.deversity.async-test-lib</groupId>
    <artifactId>async-test-lib</artifactId>
    <version>1.4.0</version>
    <scope>test</scope>
</dependency>
```

**Gradle** — add to `build.gradle.kts`:

```kotlin
testImplementation("se.deversity.async-test-lib:async-test-lib:1.4.0")
```

**Write your first stress test:**

```java
class CounterTest {

    private int counter = 0;

    @AsyncTest(threads = 10, invocations = 100, detectAll = true)
    void counter_mustBeThreadSafe() {
        counter++;  // Race condition — async-test will catch it
    }
}
```

That's it. Run with `mvn test` or `./gradlew test`.

</details>

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

Enable all 69 detectors with a single flag, or cherry-pick:

```java
// Everything on
@AsyncTest(detectAll = true)

// Everything on except false sharing (too slow for this suite)
@AsyncTest(detectAll = true, excludes = { DetectorType.FALSE_SHARING })

// Explicit opt-in
@AsyncTest(detectDeadlocks = true, detectRaceConditions = true)
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

Full parameter reference: [docs/USAGE.md](docs/USAGE.md)

---

## Configuration

```java
@AsyncTest(
    threads    = 10,        // concurrent threads per invocation round
    invocations = 100,      // how many rounds to run
    timeoutMs  = 5000,      // per-test timeout
    useVirtualThreads = true,              // Java 21+ virtual threads
    virtualThreadStressMode = "HIGH",      // OFF / LOW / MEDIUM / HIGH / EXTREME
    detectAll  = true,                     // enable every detector
    excludes   = { DetectorType.FALSE_SHARING }  // opt-out of specific ones
)
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `threads` | 10 | Threads spawned per round |
| `invocations` | 100 | Number of barrier rounds |
| `timeoutMs` | 5000 | Whole-test timeout (ms) |
| `useVirtualThreads` | true | Use `Thread.ofVirtual()` (Java 21+) |
| `detectAll` | false | Enable all detectors in one shot |
| `excludes` | `{}` | Detectors to skip when `detectAll = true` |

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

More examples with runnable code: [examples/](examples/)

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
