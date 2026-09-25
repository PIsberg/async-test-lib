# Other ways to run

Part of the [Usage guide](../USAGE.md).

## Agent Instrumentation (optional)

Detectors normally observe field access via explicit recording calls (e.g.
`AsyncTestContext.*Detector().recordAccess(...)`). The optional
`se.deversity.asynctest.agent.AsyncTestAgent` — a [Byte Buddy](https://bytebuddy.net) Java
agent — records getter/setter access **automatically, with no source changes**, and can feed
those events straight into a live `AtomicityValidator` via `TelemetryBridge`.

Attach it one of three ways:

```
# 1. Launch flag (static), optionally scoped:
-javaagent:async-test-agent-<version>.jar=includes=com.myapp;excludes=com.myapp.dto,debug=true
```

```java
// 2. Runtime self-attach from test setup (needs -Djdk.attach.allowAttachSelf=true):
@BeforeAll
static void attachAgent() {
    se.deversity.asynctest.agent.AsyncTestAgent.selfAttach("includes=com.myapp");
}
```

It is strictly opt-in — if you do not attach the agent, nothing changes. See
**[AGENT.md](../AGENT.md)** for the full guide: the WHY (observer-effect / Heisenbug), all three
attachment paths with Maven and Gradle snippets, consuming events via `TelemetryBridge`, scope
and filtering, `debug=true` diagnostics, limitations, and a troubleshooting table.

## Running without the annotation: `AsyncTestRunner` (1.9.4)

`@AsyncTest` is a Jupiter `@TestTemplate`, so it only runs inside a Jupiter test class. Spock,
ScalaTest, MUnit, kotest and `clojure.test` are engines or frameworks of their own and a Jupiter
template does not run inside them. `AsyncTestRunner` is the same engine as a method call: build
the configuration, hand over the body, read the findings.

```java
AsyncTestConfig cfg = AsyncTestConfig.builder()
        .threads(8).invocations(200).detectAll(true).failOn(FailOn.NONE).build();
AsyncFindings findings = AsyncTestRunner.run(cfg, () -> counter.increment());
findings.assertReported("RaceConditionDetector");
```

Three things to know, each different from the annotation:

- **Detectors are opt-in on the builder.** The annotation defaults to `detectAll = true`;
  `AsyncTestConfig.builder()` defaults every detector to off. A config without `detectAll(true)`,
  a `preset(...)` or individual `detectXxx(true)` calls runs the body under contention and
  detects nothing.
- **What it throws is what the annotated path throws.** A failing body surfaces as the engine's
  `AssertionError` with the body's exception as its cause (N workers on one defect are collapsed
  into one error); a hung body as the timeout `AssertionError`; findings at or above `failOn` as
  the gate's `AssertionError` after a clean run. On a clean run the returned `AsyncFindings` holds
  every finding; when the run throws, register your own `AsyncFindings.collect()` around the call
  if you need them.
- **Identity.** The engine names a run after the method it executes; a body has none, so every
  programmatic run is `se.deversity.asynctest.AsyncTestRunner$BodyHolder#run` in the `runner.*`
  log events, in the `failOn` message and as the finding-baseline id. Baselining a finding for one
  programmatic run suppresses it for all of them. `run(name, cfg, body)` puts your name in the
  `runner.programmatic` log event only.

Inside the body, `AsyncTestContext.get()` and the `recordXxx` hooks work as they do in an
annotated method, and the licence gate applies as it does there.

## Declaring ordering when you record by hand (1.12.3, experimental)

`RaceConditionDetector` judges a pair of accesses from two threads by the locks held at each.
Correct code whose ordering comes from somewhere else, an object handed through a queue, a
`Thread.start` and `join`, read as racing. It also consults one shared happens-before model,
`se.deversity.asynctest.diagnostics.HappensBefore`, and a round whose every conflicting pair that
model orders is not reported. A test that records accesses by hand declares the edges itself, at
the point the program makes them:

```java
// producer thread
box.value = 42;
detector.recordFieldWrite(box, "value");
HappensBefore.release(box);          // before the element is published
queue.put(box);

// consumer thread
Box taken = queue.take();
HappensBefore.acquire(taken);        // after the element was received
detector.recordFieldRead(taken, "value");
```

| Call | Where to make it | What it orders |
|------|------------------|----------------|
| `HappensBefore.release(obj)` | before the hand-off: a put, a `countDown`, a volatile write | this thread's accesses recorded so far |
| `HappensBefore.acquire(obj)` | after the matching receipt: a take, a returned `await`, a volatile read that saw the value | everything released through `obj` before it |
| `HappensBefore.fork(child)` | before `child.start()` | the parent's accesses so far, before all of the child's |
| `HappensBefore.join(child)` | after `child.join()` returned | all of the child's accesses, before the parent's later ones |

`obj` is compared by identity. An edge only ever removes a finding: declaring one the program does
not have hides a real race, so declare an acquire only once the value it depends on was actually
observed. `join` of a thread that is still alive is ignored.

`RaceConditionDetector` needs no declaration for a field the tracked object's class declares
`volatile`: it treats a recorded write of it as a release and a recorded read as an acquire, and
never pairs two reads or a read and a write of it, though two writers still race. Record the
volatile write before making it and the read after it, so a reader that sees the value always
finds the release.

## Manual Legacy Diagnostics

For older Java async patterns that need explicit instrumentation, instantiate the diagnostics directly:

```java
NotifyAllValidator notifyValidator = new NotifyAllValidator();
LazyInitValidator lazyInitValidator = new LazyInitValidator();
FutureBlockingDetector futureBlockingDetector = new FutureBlockingDetector();
ExecutorDeadlockDetector executorDeadlockDetector = new ExecutorDeadlockDetector();
LatchMisuseDetector latchMisuseDetector = new LatchMisuseDetector();
```

Use these for:
- `wait()`/`notify()` vs `notifyAll()` bugs
- unsafe lazy initialization and broken double-checked locking
- blocking on sibling futures inside bounded executors
- single-thread or bounded executor self-deadlocks
- missing `CountDownLatch.countDown()` paths
