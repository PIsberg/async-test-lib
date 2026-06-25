# Example 115 — StructuredTaskScope Misuse (JDK 25/26, JEP 505)

**Detector**: `StructuredTaskScopeMisuseDetector` (standalone — not pipeline-wired)
**JDK feature**: `java.util.concurrent.StructuredTaskScope` — JEP 505, fifth preview in
JDK 25, on track to finalize in JDK 26

## The Problem

The JDK 25 API reshapes structured concurrency around a factory and a pluggable `Joiner`,
and enforces a strict lifecycle:

```
open → fork* → join → get* → close   (try-with-resources)
```

Breaking it does not merely produce a wrong result — the runtime throws, leaks subtasks, or
returns a value read from an incomplete subtask:

| Misuse | Result |
|--------|--------|
| `fork()` after `join()` | `IllegalStateException` — scope no longer accepts work |
| `Subtask.get()` before `join()` | `IllegalStateException` — partial / unpublished result |
| `fork()` / `join()` off-owner thread | `WrongThreadException` — scope is confined to its owner |
| `close()` without `join()` | running subtasks are cancelled; their work is abandoned |

## The buggy pattern (real JDK 25 API)

```java
try (var scope = StructuredTaskScope.open(Joiner.<String>allSuccessfulOrThrow())) {
    Subtask<String> a = scope.fork(() -> fetchA());
    String early = a.get();        // ✗ read before join() → IllegalStateException
    scope.join();
    scope.fork(() -> fetchB());    // ✗ fork after join() → IllegalStateException
}
```

> `StructuredTaskScope.open(Joiner)` is a preview API not on the Java 21 baseline this
> example targets, so [`ParallelFetchService`](src/main/java/se/deversity/asynctest/example/service/ParallelFetchService.java)
> performs the fan-out with a virtual-thread-per-task `ExecutorService`. The lifecycle rules
> are identical; the test models them with the detector.

## The Fix

```java
try (var scope = StructuredTaskScope.open(Joiner.<String>allSuccessfulOrThrow())) {
    Subtask<String> a = scope.fork(() -> fetchA());
    Subtask<String> b = scope.fork(() -> fetchB());
    scope.join();                              // wait first
    return combine(a.get(), b.get());          // then read
}
```

## How to Detect

`StructuredTaskScopeMisuseDetector` is standalone — record lifecycle events, then assert:

```java
var d = new StructuredTaskScopeMisuseDetector();
Thread owner = Thread.currentThread();
d.recordScopeOpened("s", owner);
d.recordFork("s", "a", owner);
d.recordJoin("s", owner);
d.recordFork("s", "late", owner);     // fork after join → flagged
assertTrue(d.analyze().hasIssues());
```

See [`ParallelFetchServiceTest`](src/test/java/se/deversity/asynctest/example/ParallelFetchServiceTest.java)
for all four lifecycle violations plus the clean path.

## Running

```bash
mvn -f ../../pom.xml install -DskipTests -Dlicense.mock.mode=true
mvn -f pom.xml test
```
