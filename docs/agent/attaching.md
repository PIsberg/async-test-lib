# Attaching the agent

Part of the [Agent Instrumentation Guide](../AGENT.md).

## 3. How to attach (at startup, as a runtime fallback, or three explicit ways)

The agent JAR (`async-test-agent`, not the library JAR — the manifest moved there in the
module split) is the agent-capable artifact: its `MANIFEST.MF` declares `Premain-Class`,
`Agent-Class`, `Can-Retransform-Classes: true`, and `Can-Redefine-Classes: true`.

### 3.0 Recommended: at startup, from the build

Start the test JVM with `-javaagent:` and every class loads already woven. This is the route
[JEP 451](https://openjdk.org/jeps/451) endorses: loading an agent into a running JVM prints a
warning on JDK 21 and later, and a future JDK will refuse it by default. The build has to name the
agent jar's path, which the README quick start does in two plugin blocks for Maven
(`dependency:properties`, then surefire's `argLine`) and a configuration for Gradle. Those snippets
are the build of [`consumer-fixture-langs/quickstart`](../../consumer-fixture-langs/quickstart),
which CI runs under Maven and Gradle, and `QuickStartAgentSnippetTest` fails if the README and the
fixture drift apart. The fixture's test fails unless the agent arrived by `-javaagent:` and found
the README's race.

The quick start passes `fields=true,collections=true,includes=com.example`: field instructions and
collection calls, woven only in classes under your own package prefix, so JUnit and the build tool
stay unwoven. If JaCoCo or another plugin also sets surefire's `argLine`, start yours with
`@{argLine} `.

**Build plugins (#1045).** Two plugins replace those snippets with one block each. Both are
published with every release from 1.14.0 on, the Maven plugin to Maven Central and the Gradle
plugin to the Gradle Plugin Portal ([#1099](https://github.com/PIsberg/async-test-lib/issues/1099)),
and the README quick start switches to them once the first such release is out
([#1100](https://github.com/PIsberg/async-test-lib/issues/1100)):

- Gradle, [`async-test-gradle-plugin`](../../async-test-gradle-plugin): `id("se.deversity.async-test")`
  plus `asyncTest { includes.add("com.example") }`. It adds an `asyncTestAgent` configuration,
  defaulting to `async-test-agent` of the plugin's own version, and puts the `-javaagent:` argument
  on every `Test` task through a `CommandLineArgumentProvider`, which declares the jar and the
  options as inputs and works with the configuration cache.
- Maven, [`async-test-maven-plugin`](../../async-test-maven-plugin): the goal `prepare-agent`, with
  `<includes>`. Like `jacoco:prepare-agent`, it appends to the `argLine` property instead of
  owning it, so the two agents compose in either order with no surefire configuration. It takes the
  agent jar from the project's own `async-test-agent` test dependency.

`fields` and `collections` default to `true` in both, and leaving `includes` empty passes none,
which weaves every class the agent may touch. [`consumer-fixture-langs/quickstart-plugin`](../../consumer-fixture-langs/quickstart-plugin)
runs the quick start through each one, the Maven build beside JaCoCo's agent, and fails unless both
agents arrived and the race was reported.

### 3.0.1 Fallback: loaded at runtime by the runner

With the `async-test-agent` artifact on the test classpath but no agent started with the JVM, the
runner loads it into the running JVM before the first round of the first `@AsyncTest`. Which
arguments it uses:

| `-Dasynctest.agent` | Agent on the classpath | What the runner does |
|---|---|---|
| unset | yes | loads it with `fields=true,collections=true,includes=<the first three segments of the test class's package>`; for `AsyncTestRunner.run`, the body's class's package |
| unset | no | nothing, and logs nothing |
| `off` or `false` | either | nothing |
| any other value, `""` included | either | loads it with that value verbatim, as after `-javaagent:...=` |

The runner does nothing when an agent is already running, attached with `-javaagent:` or by a
`selfAttach` in setup: explicit configuration always wins. A body run through `AsyncTestRunner`
is scoped by the package of the body's own class, since the invocation names the library's holder
class ([#1042](https://github.com/PIsberg/async-test-lib/issues/1042)): a Java, Kotlin or Scala
lambda and a Clojure `reify` are compiled into the caller's package or namespace. A dynamic-Groovy
closure coerced to `AsyncTestRunner.Body` is a JDK proxy in `jdk.proxyN`, so for a proxy body the
runner takes the first caller frame outside itself, the JDK and the Groovy runtime instead, which is
the test or specification ([#1068](https://github.com/PIsberg/async-test-lib/issues/1068)).
`consumer-fixture-langs` proves each language under Maven and Gradle. A test class or a body in the default package gets no default, because there is no
project package to scope the weaving to. The log line
`runner.agent.attached source=classpath mode=runtime args="..." hint="..."` names what was chosen and
points at the startup route.

It is a fallback, not a setup. Checked on 2026-10-19 with a program that calls `selfAttach` and
nothing else: on JDK 21.0.9 and on JDK 26 the load succeeds and the JVM prints four `WARNING` lines,
the last one "Dynamic loading of agents will be disallowed by default in a future release". Started
with `-XX:-EnableDynamicAgentLoading`, which is that future default, the same JDK 26 refuses it,
`selfAttach` throws (`SelfAttachRefusedTest` reproduces this), and the runner logs
`runner.agent.attach.failed reason=attach-refused` and runs uninstrumented: the README's example
would report nothing again. `-XX:+EnableDynamicAgentLoading` in the test JVM's arguments silences
the warning and keeps the fallback working on such a JDK, but a build that can add a JVM argument
can add `-javaagent:` instead.

Measured with this fallback on 2026-10-19 (Windows 11, JDK 26, five runs each way): the load and the
retransformation of the already-loaded classes took about 0.6 s, once per JVM. A body making 200
synchronized calls on a map-backed service, at `threads = 10, invocations = 100`, took 3.4 s with the
agent and 1.1 s with `-Dasynctest.agent=off`, so woven code ran roughly three times slower in that
hot loop. The startup route skips the retransformation; the woven code is the same.

If the artifact is missing while the property is set, or the JVM refuses the load, the runner logs
`runner.agent.attach.failed` once and continues without instrumentation rather than failing the
suite.

The three ways below are the explicit forms.

### 3.1 Launch flag (static attach), plain

```
-javaagent:async-test-agent-<version>.jar
```

Routes through `AsyncTestAgent.premain(String, Instrumentation)` before `main()` runs. Every
class loaded afterwards is woven at load time. `premain` never retransforms — it does not
need to, because nothing has loaded yet.

### 3.2 Launch flag with arguments

```
-javaagent:async-test-agent-<version>.jar=includes=com.myapp;excludes=com.myapp.dto,debug=true
```

Everything after the `=` is the `agentArgs` string, parsed by `AgentOptions`. The grammar:

| Key | Value | Effect |
|-----|-------|--------|
| `includes` | one or more name prefixes | Instrument **only** types whose fully-qualified name starts with one of the prefixes (narrows the positive match). |
| `excludes` | one or more name prefixes | **Never** instrument types whose name starts with one of the prefixes (appended to the built-in ignore matcher). |
| `debug` | `true` / `false` | `debug=true` turns on verbose diagnostics (see [Diagnostics](diagnostics.md#6-diagnostics)). Any other value, or absence, keeps the default errors-only logging. Case-insensitive. |
| `fields` | `true` / `false` | `fields=true` also weaves **direct field instructions**, so a field touched only inside a method body — the `count++` in an `increment()` — produces events. Off by default; see below. Case-insensitive. |
| `collections` | `true` / `false` | `collections=true` rewrites the collection calls an instrumented type makes, so the **collection instance** reaches the detectors that are keyed by instance. It is what makes a class whose state lives in a `HashMap` visible at all. Off by default; see below. Case-insensitive. |

**Separators and multi-values.** Entries are separated by `,` **or** `;`. A bare token (no
`=`) is appended to the **most recently named key**, so a single key can carry several
values:

```
includes=com.myapp;com.other        # two include roots
includes=com.myapp,debug=true        # include + a flag
```

**`fields=true`: what it buys and what it costs.** Accessor weaving binds `Advice` to method
entry, so it can only see a field reached *through* a getter or setter. A bare `count++` inside a
method compiles to `GETFIELD` / `PUTFIELD` with no method call to bind to, and that is the most
common shape of a real race — it is why the README's own counter example reported nothing before
this option existed. `fields=true` instruments the instruction stream instead, inserting a
stack-neutral, branch-free observation call before each field instruction, and for a volatile field
a second one that hands the value to the happens-before model: just before a store, and just after
a load ([#742](https://github.com/PIsberg/async-test-lib/issues/742)). A `long` or `double` takes
the two-slot copy instructions rather than `DUP` and `SWAP`; `WideOperandWeavingTest` weaves a class
with every field width, volatile and plain, static and instance, through the full
`fields=true,collections=true` chain and checks that it verifies and computes what the unwoven class
does ([#1147](https://github.com/PIsberg/async-test-lib/issues/1147)).

The same observation call is a scheduling point of a controlled schedule
(`@AsyncTest(controlledSchedule = true)`): before each woven field instruction the round may hand
over to another worker, in an order the replay seed decides, so the read and the write of a bare
`count++` are two places a lost update can be made to happen and replayed
([results-and-practices.md](../usage/results-and-practices.md#reproducing-a-failure)). Every call
the agent substitutes, a collection call under `collections=true` or an atomic one under
`fields=true`, is a point too, so a `containsKey` then `put` on a shared map is split and replayed
the same way with no field access between them
([#1023](https://github.com/PIsberg/async-test-lib/issues/1023)). Entering a monitor and taking a
`ReentrantLock` or a write lock are points that name the lock, and the schedule knows which worker
holds it, so a worker paused inside a critical section is never handed a peer that would only
block on it: a `synchronized` or locked increment replays with no escape
([#1022](https://github.com/PIsberg/async-test-lib/issues/1022)). Read locks, other `Lock`
implementations and `StampedLock` are plain points, since they may be shared. A woven
`Object.wait` or `Condition.await` hands the waiter's hold back for the wait, and a worker
re-confirms its own holds at each point, which catches a monitor left by an exception out of a
`synchronized` method ([#1048](https://github.com/PIsberg/async-test-lib/issues/1048)). A hold the
table still gets wrong (a wait in code that is not woven, a write lock's condition) cannot stall
the round: when no ready worker can proceed, the round is handed out anyway, and
`runner.schedule.round` counts it as `fallbacks=`. A thread the body starts with a
woven `Thread.start` (or a `Thread.Builder`) joins the round at its first point, under a slot the
order of the starts decides, and an untimed `join` on it waits inside the schedule, so a worker
racing its own child is split and replayed like two workers
([#1024](https://github.com/PIsberg/async-test-lib/issues/1024)). A task handed to one of the
JDK's executors through a woven `submit`, `execute`, `supplyAsync` or `runAsync` is adopted the
same way: it joins the round when it starts on the pool thread and leaves when it ends, so the pool
thread carries nothing into its next task, and a woven `get` or `join` on its future waits inside
the schedule ([#1049](https://github.com/PIsberg/async-test-lib/issues/1049)). A task the executor
rejects is dropped from the round, one it has not started within the stall bound escapes, and a task
run inline on the submitting worker, under a caller-runs policy, is part of that worker's turn. A
task given to an executor outside `java.util.concurrent` runs outside the schedule.

The call carries the receiver, so the analysis can tell six threads racing on one object from six
threads each using their own, and — for a store of a reference type — the value being stored. That
last one is what lets the atomicity model tell an idempotent value apart from a side effect: a
double-submit converges on its field exactly like a view cache does, and the difference is only
visible in what the stored object does afterwards
([#326](https://github.com/PIsberg/async-test-lib/issues/326)). An instance reference store is the
shape where the value is already on the operand stack in argument order, so `DUP2` reaches it in
two instructions with nothing to undo. A static reference store needs one instruction more,
`DUP; LDC class; SWAP`, because the value sits alone with the class constant pushed above it
([#337](https://github.com/PIsberg/async-test-lib/issues/337)); without that, class-scope
lazy-init was the one double-submit shape the value evidence could not reach. Every other shape
passes `null`, which the analysis reads as "not known" rather than as evidence. The stored value
leaves the call as an identity hash only. The receiver also travels as itself, because two live
objects can share an identity hash and the atomicity model groups accesses per object: the ring
buffer lends it to the drain for one callback and then clears the slot, and the model keeps it
only weakly, so neither is retained by the telemetry path.

It is off by default because the cost scales with the instrumented surface, not with the number of
accessors: every field read and write in every matched class emits an event. Pair it with
`includes=` so the weaving lands on the code under test rather than on the whole classpath:

```
-javaagent:async-test-agent-<version>.jar=includes=com.myapp,fields=true
```

Fields owned by the JDK, Byte Buddy or this library are never woven, whatever the includes say —
without that, a `System.out` reference in user code would emit on every call, and a field access
inside the telemetry sink would recurse. Static initialisers are skipped too, because emitting
from `<clinit>` can force the telemetry classes to initialise inside another class's
initialisation, and circular class initialisation deadlocks rather than failing.

**`collections=true`: reaching state a class does not own.**

Field weaving makes a class's own fields observable. It does nothing for a class that keeps its
state in a collection:

```java
class Registry {
    private final Map<String, Integer> entries = new HashMap<>();   // final: no PUTFIELD to weave

    void record(String key) {                                        // the racing write happens
        entries.put(key, entries.getOrDefault(key, 0) + 1);          // inside java.util.HashMap
    }
}
```

`entries` is assigned once, so there is no field instruction to observe, and the write that races
happens inside `java.util.HashMap`, which is on the ignore list and always will be. Under
`fields=true` this class produces no finding no matter how many threads collide on it. This is not
a corner case: it was measured on real libraries, where three of nine documented-not-thread-safe
classes were silent for exactly this reason ([corpus eval](../analysis/corpus-eval.md)).

`collections=true` rewrites the collection call itself, so the map instance reaches
`SharedCollectionDetector` and its instance-keyed siblings:

```
-javaagent:async-test-agent-<version>.jar=includes=com.myapp,collections=true
```

What is rewritten is an explicit table in `CollectionAccessWeaver`: `Map.put/get/remove/containsKey`,
`Collection.add/addAll/remove/removeIf/contains/clear`, `List.get/set`, `Queue.offer/poll/peek/remove`,
and the `Deque` end-specific offers and takes. Each call becomes a
call to a hook that records and then performs the original operation, so behaviour is unchanged;
`CollectionWeavingEndToEndTest` pins that a woven program still computes the same values.

A map whose calls are each atomic, a `ConcurrentHashMap`, a `Hashtable` or a
`Collections.synchronizedMap` wrapper, is not a data race on any one call, so
`SharedCollectionDetector` leaves it alone. What goes wrong on such a map is the pair:
`if (!map.containsKey(k)) map.put(k, v)` or `v = map.get(k); map.put(k, v + 1)` from two threads
loses an update. The map hooks hand those pairs to `NonAtomicConcurrentMapUpdateDetector` with no
recording call: a `containsKey` that found the key absent, or any `get`, is held as the calling
thread's pending check, and a `put` or `remove` on the same map and key in the same round completes
it. The pair is reported when two threads complete it in one round with no lock held across both
calls; under the map's own monitor it is atomic and stays silent
(`CheckThenActWeavingEndToEndTest`). A round whose callers all put one shared object is excused;
a boxed primitive such as `Integer.valueOf(1)` or an enum constant is not, since it is the same
instance only because its value is (#996).

Blocking calls are classified by thread too (#991, #993). Every woven blocking call (a sleep, a
`join` or `get` of a pending future, a latch await, a take) also reaches
`BlockingOnNonBlockingThreadDetector`, which reports it on a thread that must not block: a Reactor
`NonBlocking` scheduler thread, a Netty or Vert.x event loop, or a Kotlin `Dispatchers.Default`
worker. Those threads are started inside the framework, so the run claims one only when the
framework's own code is woven: add its scheduler packages to `includes=`
(`reactor.core.scheduler`, `io.netty.util.concurrent`, `io.vertx.core`, `kotlinx.coroutines`), and the
woven start or submission inside it carries the run to the thread
(`BlockingOnNonBlockingThreadWeavingTest`, `BlockingOnNonBlockingThreadSparesTest`).

Pooled tasks carry two more feeds (#995). The task wrapper a woven `submit` hands the pool snapshots
the MDC at the task's start and end for `MdcContextLeakDetector`, and gives the task an id, so the
woven `ThreadLocal.get`, `set` and `remove` can tell `ThreadLocalMonitor` which task set a value: a
task that reads a value an earlier task on its thread set and left behind is reported, if two tasks
on that thread both set it, which a set-once per-thread cache never does
(`ThreadLocalTaskLeakWeavingTest`, `ThreadLocalTaskLeakSparesRemovalTest`,
`ThreadLocalTaskLeakSparesCacheTest`, `MdcTaskLeakWeavingTest`, `MdcTaskLeakSparesClearedTest`).

Persistence handles are watched by name (#992). A JPA `EntityManager` or Hibernate `Session` is not on
the agent's class path and must not be, so `PersistenceHandleWeaver` substitutes nothing: before a
call whose owner, as written at the call site, is `jakarta.persistence.EntityManager`,
`javax.persistence.EntityManager`, `org.hibernate.Session` or `org.hibernate.StatelessSession`, it
copies the receiver off the stack and hands it to `JdbcConnectionSharedDetector`, which reports a
handle two threads held at once; a `close()` lets it go. Calls with more than two arguments, or a
`long` or `double` one, are left alone (`PersistenceHandleWeavingTest`,
`PersistenceHandleSparesOnePerThreadTest`).

Kotlin's `runBlocking` is watched by name as well (#1007). Kotlin compiles `runBlocking { }` to a
static call on `kotlinx.coroutines.BuildersKt`, named `runBlockingK` or `runBlockingK$default` by
kotlinx 1.11 and `runBlocking` or `runBlocking$default` by older releases, and none of those types
may be on the agent's class path. So `RunBlockingWeaver` inserts one call of a hook that takes and
leaves nothing before each such call, in every woven class outside kotlinx itself, and
`BlockingOnNonBlockingThreadDetector` reports it on a coroutine dispatcher worker of either
dispatcher, on a thread that must not block, and on any thread whose stack holds a
`kotlinx.coroutines` frame below the call, which is how a `runBlocking` nested in another, or one
on an executor dispatcher's plain thread, is seen without a thread class to classify
(`KotlinRunBlockingInsideACoroutineIsReportedTest`); that thread must still be the run's, which
the woven `execute` makes it on a bare JDK pool and through the
`Executors.unconfigurableExecutorService` wrapper `newSingleThreadContext` adds (#1107). Both the calling code's package and
`kotlinx.coroutines` belong in `includes=`: the first so the call site is woven, the second so the
dispatcher worker is attributed to the run (`RunBlockingWeavingTest`, `RunBlockingSparesTheCallerTest`,
and with real kotlinx `consumer-fixture-langs/kotlin`).

Executors are watched the same way. The fourteen `Executors` factories are woven, and an executor a
run thread creates through one of them is declared to `ExecutorShutdownDetector` as the run's own,
which is the detector's whole rule: an executor handed in from outside the run, or built in a
static initializer, is never declared, so leaving it running is not reported (#387). A
`submit` whose future the caller never kept is seen as the `POP` after the call, and
`FutureIgnoredDetector` reports that future when its task failed, because nothing can ever read
the exception it holds. A `supplyAsync` or `runAsync` whose future the caller drops is handed to
`CompletableFutureExceptionDetector` the same way. The stage functions the agent already wraps
for the happens-before model mark a callback's entry and exit, so a woven `join` or `get` of a
future that is not done, or a sleep, latch await, `Object.wait` or take from an empty queue, is
reported when it happens inside a callback, and the same calls inside a task on the common
`ForkJoinPool` are reported as blocking that pool.

Child processes are watched too. A woven `ProcessBuilder.start` or `startPipeline` records each
child with its builder, which is how a stderr merged into stdout is told from a pipe on Linux,
where the JDK still hands out a pipe object for it. A woven `Process.waitFor`, timed, with a
`Duration` (JDK 25+) or neither, records which of the
child's stdout and stderr are still pipes no thread has taken, and the woven `getInputStream`,
`getErrorStream`, `inputReader` and `errorReader` record a take on whichever thread makes it.
`ProcessStreamStarvationDetector` reports a wait whose pipe no other thread took: a child that
writes a pipe's worth blocks, and the wait never returns. A drain thread or task that takes the
pipe after the wait began excuses it (`ProcessFeedSparesCorrectUseTest`). One that runs in code
the agent does not weave, a process library's own pump threads for example, takes its pipe
unseen, and the wait is reported with a hint to add that library to `includes=` (#996). The same
detector hears the woven `PipedOutputStream.write` and `PipedInputStream.read` calls and reports
one thread on both ends of a pair, paired through the writer's private `sink`; a write through a
variable typed `OutputStream` is not woven (#994).

The same option substitutes `Object.wait`, `notify` and `notifyAll` (#694), which is what feeds
`MissedSignalDetector` without a recorded call: the hooks run with the monitor held, so a notify is
judged against the threads really inside `wait()`. The weaver also inserts one call in front of
the backward jump that closes a loop around a woven wait, and a wait whose thread reaches it
after waking is a `while (!ready)` loop's and is never reported. A wait with no such jump around it
is an `if`'s.

The whole class is read before any of it is emitted, so the wait may sit in a helper the loop calls
(#707); a helper in another class is recognised whether it is inherited from a supertype or reached
through an interface, which the call site names instead of the class that waits (#709).

Which of the two classes is woven first used to decide it, and load-time weaving delivers the
unhelpful order almost every time: a class named only inside a method body is resolved lazily, on
first execution of the instruction naming it, so when the caller is woven at load its helper has
not been loaded at all. Measured on one pair of fixtures in one JVM before the fix, caller first
gave 0 marks where helpers first gave 1. Since #715 a call the weaver cannot resolve is remembered
against the signature it named, and a later class registering that signature has the callers
retransformed, which runs the weaver over them again with the helper now in the index.

The window is narrowed, not closed. The retransformation is handed to a daemon thread rather than
run inside the transform that triggers it, because a transform runs while a class is being defined
and calling back into `retransformClasses` from there re-enters the transformer chain under the
defining thread's locks. A loop that executes before the retransformation lands still runs unmarked
code, and a finding recorded in that window is still a finding. It is one of the readings keeping
`MISSED_SIGNAL` at `PROMPT`.

A jump counts as closing the loop when it is an unconditional `goto` and something is read
between the loop's head and the wait, which is how javac and kotlinc close a `while`.
`do { wait(); } while (!ready)` closes with the predicate test itself,
and a loop closed by `continue` reads nothing before it blocks; both enter `wait` before they have
read the predicate, and both are reported.

A compiler that rotates loops emits `goto test; body; test: if (...) goto body`, so its `while`
closes with a conditional jump and puts the test after the wait. That shape counts too, when the
loop head is entered by a `goto` that lands between the wait and the back-edge, which is what a
rotated loop has and a `do`/`while` has not (#710). ECJ rotates and javac does not, so without this
a correct poll compiled by Eclipse would be reported; `MissedSignalRotatedLoopWeavingTest` compiles
the shapes with the real ECJ and reads the marks back. kotlinc emits the javac shape for both
`while` and `do`/`while`, first checked by hand at 2.4.10 and gated since #714 by a pair in
`consumer-fixture-langs/kotlin`: a Kotlin `while` poll that must stay silent and a Kotlin
`do`/`while` that must be reported, run against the agent with `-javaagent`. The gate lives there
rather than beside the ECJ one because a mark-level gate would need a Kotlin compiler on
`async-test-agent`'s test classpath, and the language toolchains are deliberately confined to that
fixture's own pom. It asserts on the finding instead of on the marks, which is what a user sees;
the firing half is what stops the silent half passing by nothing being woven at all.

**What the hold costs.** Reading the whole class before emitting any of it means holding every
method of every class `collections=true` weaves, including the great majority that never call
`wait`, as a tape of replayable actions (#711). Measured against the streaming tables, which emit
each method as it arrives: one monitor-table pass over six ASM and Byte Buddy classes, 119,871
class-file bytes, allocates 3,218,824 bytes against 1,648,720, a ratio of 1.95x, and takes 13-14 ms
against 9-11 ms on JDK 26 and 9 ms against 8 ms on JDK 21. That is roughly 13 extra bytes of
short-lived garbage per class-file byte and roughly 3 ms extra per 100 kB of bytecode, paid once
per class while the agent attaches and never inside a round.
`MonitorWeavingAllocationBudgetTest` holds the ratio at 2.2x, so a change in the shape of the hold
fails a build instead of being absorbed.

Holding the methods in ASM's own encoding instead, a scratch `ClassWriter` written as they arrive
and read back through a `ClassReader` at the end, was built and measured on the same classes at
2.14x: the second reader rebuilds the whole constant pool as strings, which costs more than the
captured lambdas it saves. It is not in the tree, and the measurement is recorded here so the same
afternoon is not spent twice.

**Ordering the lockset cannot see (1.12.3).** The same hooks feed the shared happens-before model,
`HappensBefore`, which `RaceConditionDetector` and `AtomicityValidator` consult before they report
a round: a round whose every conflicting pair the model orders is not reported. The detectors on
the shared per-instance round verdict (`SelfGuard`, the `Shared*` family among them) consult it
too: a thread whose use of the instance the model orders after the previous thread's takes it
over rather than sharing it, while two threads using it at once still report. For the field
stream the accessing thread's clock is stamped at publish time and travels through the ring with
the event, since the drain thread's own clock orders nothing. The edges are the ones the Java memory
model names for the woven calls: an element offered to and taken from a `java.util.concurrent` queue
(or any `BlockingQueue` or `ConcurrentMap`, and the synchronized wrappers), where an offer the queue
refused publishes nothing, a value put into and read back from such a map,
`CountDownLatch.countDown` and an `await` that reached zero, `Semaphore.release` and an acquire that
took a permit, and `Thread.start` and a `Thread.join` that returned with the thread finished (every
`join` overload is substituted for this). Since #741 also: a `CompletableFuture` completed with
`complete`, `completeExceptionally` or an `obtrude` call and a `join` or `get` that observed it, where a
`complete` the future refused publishes nothing; a task handed to `ExecutorService.submit` or
`CompletableFuture.supplyAsync`/`runAsync`, which starts ordered after the call and is ordered before
a `get` or `join` of its future on the thread that submitted it; and each side of an
`Exchanger.exchange`, ordered before its partner's return. The submitted task runs wrapped, which only
the JDK's own executors get, since only there nothing a caller can reach returns the task; a get on
another thread, or of one of more than 16 futures a thread submitted without getting, orders nothing.
A function registered on a `CompletableFuture` with `thenApply`, `thenAccept`, `thenRun`,
`thenCombine`, `thenAcceptBoth`, `runAfterBoth`, `thenCompose`, `whenComplete`, `handle`,
`exceptionally` or `exceptionallyCompose`, or their `Async` forms, runs wrapped as well: the thread
that runs it is ordered after the registering thread and after the completion of each stage it
waits for, and a `join` or `get` of the stage it returned, on the registering thread, is ordered
after it. A `thenCompose` or `exceptionallyCompose` stage completes when the stage its function
returned does, so its join is also ordered after what a woven `complete` of that stage released.
The `Either` forms carry only the registering thread's clock, since which stage they followed is
not known. A call typed against `CompletionStage` is woven too, and wraps the function
only when the stage is a `CompletableFuture`: any other implementation gets the caller's own
function, since it might hand it back. A `CompletableFuture` never does, so the wrapper cannot be
seen.
`Executor.execute` runs its task wrapped, ordered after the call, on the JDK executors that never
hand a task back: `ForkJoinPool`, `ScheduledThreadPoolExecutor` and the virtual-thread-per-task
executor, matched by exact class (#741), and an `Executors.unconfigurableExecutorService` or
`unconfigurableScheduledExecutorService` wrapper a woven call made, judged by the executor it
forwards to (#1107). On a `ThreadPoolExecutor`, which returns the task itself
from `getQueue`, `shutdownNow` and `remove`, on a subclass of any of them, and on an executor
outside the JDK, it is not an edge. With `fields=true`, a volatile write releases that field
of its object, and a read of the same field acquires at the read what the write whose value it
returned published: reading one volatile field receives nothing a write of another published, and
a read that returned an older value receives nothing the later write published (#742). The acquire
goes into the reading thread's clock, so everything the thread does after the read is ordered,
including its accesses to the object the read returned, which is how a node published through a
volatile `next` reaches its reader (#804). A field is the one its class declares, whatever static type
the access went through, so a subclass field that shadows a superclass field has a clock of its own,
and a volatile write in a constructor releases once the super constructor has run (#813). An `AtomicReference` is modelled the same way (#741): a
`set`, `lazySet`, `setRelease`, successful `compareAndSet` or `getAndSet` releases the slot, and a
`get` or `getAcquire` acquires what the store whose value it returned published, so the same object
read out of another slot receives nothing. So is a reference slot reached through an `AtomicReferenceFieldUpdater`,
an `AtomicReferenceArray` element or a `VarHandle` (since #741): their volatile, release and successful
compare-and-set stores release the slot, and an updater `get`, an array `get` or `getAcquire`, or a
handle's `getVolatile` or `getAcquire` acquires. An `AtomicStampedReference` is a slot too (#817): its
`set` and successful swaps release, its `get` and `getReference` acquire. The slot is the field the updater or handle reaches,
the same clock a direct access to that field uses, or the element's index. A plain or opaque access
orders nothing, and an updater's field is named only while `java.util.concurrent.atomic` is open to the
library, which `fields=true` arranges. The value is compared as a primitive's bits or a
reference's identity, held weakly by the release so the model keeps nothing alive, and among the
field's last four writes only, since a write is released just before it is stored and a read can
still return an earlier value while later writers are in that window (#813). An `ArrayDeque` or a `HashMap` promises nothing and gives no edge. A lock
hand-off is deliberately not an edge: the lockset judges locking, and ordering it by the one
schedule a run took would hide what another schedule exposes.

Three limits worth knowing before switching it on:

- **Guarding works, and has to.** Monitor weaving is installed alongside, so a collection touched
  only inside a `synchronized` block reports nothing even though the test never declared the lock.
  Lock weaving rides along too: a `Lock.lock()`/`unlock()` call site in woven code feeds the same
  lockset, `ReadWriteLock.readLock()`/`writeLock()` call sites resolve each view to its owner,
  in shared mode for the read side, and `StampedLock`'s own call shapes are modelled the same way
  (write stamps exclusive, read stamps shared), so a collection guarded by a `ReentrantLock`, a
  `ReentrantReadWriteLock` or a `StampedLock` reports nothing either. An optimistic read holds
  nothing, so `tryOptimisticRead()` and `validate(long)` mark where a speculation starts and
  whether it held: the field reads in between count as reads under the lock in shared mode when
  `validate` returned `true`, are dropped when it returned `false`, since the caller discards what
  it read, unless the caller threw the answer away (`lock.validate(stamp);` as a statement, a call
  followed by a `POP`), when they count as plain reads (#823), and count as plain reads when
  nothing validated them before the thread's next
  speculation on the same lock, its next write or the end of the round (#740). A speculation on
  a second lock nests inside the first instead of closing it, and reads it validates are judged
  by the enclosing one's `validate` as well (#823). A lock acquired only inside unwoven
  code still needs `AsyncTestContext.holdingLock(...)`. The same monitor entry also tells
  `SynchronizedNonFinalDetector` which instance the monitor of a `synchronized (owner.field)` block
  was read from (#793): javac compiles it to a load of the owner into a local, a read of the field
  and the entry, and the weaver reloads the owner from that local, so a lock that one instance
  reassigns is reported with no recording call, and a non-final lock per instance stays silent.
- **Spinlocks and hand-offs are exclusion too (with `fields=true`).** A won
  `VarHandle.compareAndSet(this, 0, 1)` on an `int` field is a spinlock: the weaver replaces the
  call with a hook that performs it and declares a lock on that receiver's flag, released by a
  `compareAndSet(this, 1, 0)`, an `int` `set` through the handle, or a plain write to the field by
  the holder. A volatile field replaced only while it is held is safe publication, which is how
  Caffeine's `StripedBuffer` table reads now (#554). The same holds for an
  `AtomicIntegerFieldUpdater` bound by a `newUpdater` call the weaver saw (`compareAndSet`,
  released by the swap back, `set`, `lazySet` or the holder's write), and for an `AtomicBoolean` or
  `AtomicInteger` that is the lock itself (`compareAndSet(false, true)`, `!getAndSet(true)`,
  `compareAndSet(0, 1)`, released by the swap back, `set` or `lazySet`). The value-returning
  releases are substituted too (#658): `getAndSet`, `getAndAdd`, `compareAndExchange` and the weak
  swaps on the handle, `getAndSet`, `getAndAdd`, `addAndGet`, `getAndDecrement`, `decrementAndGet`
  and `weakCompareAndSet` on the updater and the `AtomicInteger`, and `compareAndExchange` and the
  weak swaps on both atomics. A handle bound before the agent attached is resolved from its own
  descriptor (#558), and an updater bound before it from its own target class and field offset,
  which the agent opens `java.util.concurrent.atomic` to read (#659), to the module of the library
  copy each woven loader resolves and to nothing else (#668). In every mode the agent also opens
  `java.util.concurrent.locks` to that module, so `ReentrantLockDetector` and `LockLeakDetector` read
  a held lock's owner thread at analysis instead of the name the lock prints (#855).
  A spinlock is never trusted past what its flag says: it counts as held only while the flag
  still reads locked and this thread is its last observed winner, re-checked whenever the lockset
  is read. A release through a call the weaver does not substitute (`Unsafe`, JNI, reflection, a
  `VarHandle` call site with an `Object` or `long` result, unwoven code) therefore drops the lock before the
  next access is recorded instead of leaving it declared, which would make every later write on
  that thread look guarded. One narrow window survives for those forms: a release landing between
  another thread's check of the flag and its swap leaves the old holder passing that re-check until
  the new holder records itself (#658; the forms are listed in `SpinLocks`). Separately, the
  object a reference `getAndSet` returns, or a `Queue.poll` or JCTools `MessagePassingQueue`
  `poll`/`relaxedPoll` hands back, is reported as taken. A queue that orders nothing itself, an
  unsynchronized `java.util` collection such as an `ArrayDeque`, does not count when the offer and the
  poll held no lock in common, a side with no lock sharing none: an unguarded deque, one guarded on
  one side only, and one guarded by two different locks hand nothing over (#751). A `synchronized`
  method's monitor counts, though no instruction takes it: the weaver hands it to the offer and take
  hooks (#796), and in any other instance method but a constructor it hands over `this`, which
  counts when held, so a `synchronized` method that polls through a private helper is seen too.
  The weaver also declares a `synchronized` method's monitor to the lockset at the method's entry
  and releases it before each return (#822), so a monitor held only by a `synchronized` method
  further up the stack counts where the queue call sits in another object's method, a static
  helper or a lambda body. An exception leaving the method passes no return; the entry is
  re-confirmed with `Thread.holdsLock` whenever the lockset is read and dropped once the monitor is
  let go. A
  take starts a new ownership generation, exclusive to the taker until another thread touches it, and
  locks only have to agree within a generation. That is netty's chunk moving between magazines
  (#555). Another thread's access inside the generation the receiver is still in withdraws the
  taker's exclusivity for that whole generation, including accesses the taker made before it, so
  an alias kept from before the take cannot hide behind the order its access was published in
  (#559). In a generation a later take closed, an access withdraws it only when its thread neither
  took that generation nor owned the one before it: the previous owner's late access is a hand-off,
  and when no access showed who owned generation 0, the thread that offered the object to the
  queue it was polled from is that owner, from the `collections=true` hooks for `Queue.offer`/`add`,
  `Collection.addAll` on a queue, `Deque.offerFirst`/`offerLast`/`addFirst`/`addLast`/`push`,
  `BlockingDeque.putFirst`/`putLast` and its timed `offerFirst`/`offerLast`
  ([#692](https://github.com/PIsberg/async-test-lib/issues/692))
  and `BlockingQueue.offer`/`put`, and from `fields=true` for a reference slot (`set`, `lazySet`,
  `setRelease` or `compareAndSet` on an `AtomicReference`, an `AtomicReferenceFieldUpdater`, an
  `AtomicReferenceArray` or a `VarHandle` (instance field, static field or array element)) and a
  JCTools `offer`/`relaxedOffer`.
  `BlockingQueue.take`, `Queue.remove()`, `remove(Object)` on a queue,
  `Deque.pollFirst`/`pollLast`/`removeFirst`/`removeLast`/`pop`, and `BlockingDeque.takeFirst`/
  `takeLast` with its timed `pollFirst`/`pollLast` are takes like `poll`, and `drainTo`
  and `removeIf` on a queue drop every offer recorded into it, so an element taken or drained and
  put back through an unwoven call cannot keep naming its first offerer
  ([#664](https://github.com/PIsberg/async-test-lib/issues/664),
  [#692](https://github.com/PIsberg/async-test-lib/issues/692)); a removal in unwoven code, or
  through an iterator, still can. Only when no such offer was recorded does every thread get that
  benefit: an element that entered through code outside `includes`
  ([#630](https://github.com/PIsberg/async-test-lib/issues/630)). Spinlock shapes not modelled,
  so writes under them still report: `Unsafe.compareAndSwapInt`, and an
  `AtomicIntegerFieldUpdater` created before the agent attached whose target cannot be read (a
  library copy in a loader outside the woven loader chains, a named module, or a JDK whose updater
  implementation changed shape).
- **Thread-safe types are skipped.** A receiver from `java.util.concurrent`, a
  `Collections.synchronizedX` wrapper, a `Hashtable` or a `Vector` synchronizes where nothing can
  be woven, so recording it would report every shared use. Those calls are delegated and never
  recorded. So is any receiver that inherits no instance state from a bootstrap-loaded class: its
  fields live in weavable code, where the field weaver already watches them, and a stateless
  implementation such as Guava's discarding queue has nothing a write could corrupt.
- **Threads are counted per round.** Rounds are ordered by the runner, so two accesses from
  different rounds cannot race; a collection written by one (virtual) thread per round is
  sequential, and a finding names the widest single round.
- **`super` calls keep their dispatch.** Only virtual and interface invocations are rewritten. A
  decorator's `super.get(...)` must stay an `INVOKESPECIAL`, or the substitution would re-dispatch
  virtually into the override and recurse.

**Named modules.** Woven code calls into the library, so a woven class in a named module has to
read the library's module. The JVM gives every transformed class a read edge to the unnamed module
of the agent's loader, which covers a library on the class path. For a library on a module path
(the automatic module `se.deversity.asynctest`) or in another loader, the agent adds the edge to
the copy the woven class's loader resolves, in every mode
([#862](https://github.com/PIsberg/async-test-lib/issues/862)). Before that fix only `fields=true`
added it, and the first woven call under `collections=true` or the default accessor weaving threw
`IllegalAccessError`.

**Robustness.** Parsing never throws (an exception in `premain` would abort JVM startup).
Whitespace is trimmed, empty entries are skipped, keys are matched **case-insensitively**,
and **unknown keys are ignored**. A `null` or blank argument leaves the default behavior
(instrument every non-ignored class) fully intact.

### 3.3 Runtime self-attach (no launch flag)

```java
import se.deversity.asynctest.agent.AsyncTestAgent;

@BeforeAll
static void attachAgent() {
    AsyncTestAgent.selfAttach("includes=com.myapp");
}
```

`selfAttach()` obtains an `Instrumentation` handle via Byte Buddy's
`byte-buddy-agent` (`ByteBuddyAgent.install()`) and installs the same transformer used by
`premain` — no `-javaagent:` launch-flag edit required. `selfAttach()` is equivalent to
`selfAttach(null)` (instrument everything not ignored).

**When to prefer it.** Use self-attach when you cannot control JVM launch flags (for example,
an IDE run configuration or a shared surefire setup), and want to scope instrumentation to a
single test class's `@BeforeAll`.

**Retransformation caveat (already-loaded classes).** Unlike `premain`, self-attach happens
*after* application classes may already be loaded. It therefore installs with
`RedefinitionStrategy.RETRANSFORMATION` + `disableClassFormatChanges()`. Because the injected
`@Advice` only inlines a method-entry prologue — it adds no fields, methods, or interfaces —
the class schema is unchanged and retransformation is safe. **Verified empirically**
(`SelfAttachTest`): accessors of classes loaded *before* the attach are re-woven in place,
exactly like classes loaded afterwards, and so are the classes the attach loads while it runs.

**A class the JVM refuses does not cost the others their weaving.** `retransformClasses` is
all-or-nothing per call, and some classes cannot be re-verified at all: Netty's optional logger
adapters fail with `InternalError: class redefinition failed: invalid class` when log4j is absent
from the classpath, because re-verifying them needs a type that is not there. Byte Buddy's default
passes every loaded class in one such call and its default listener swallows the failure, so a
single refused class used to leave the agent installed but weaving nothing that had already
loaded, silently. The agent therefore retransforms in fixed batches, halves a failing batch until
the refused class stands alone, and prints one line naming it:

```
[ASYNC-TEST-AGENT] Could not re-weave already-loaded class io.netty.util.internal.logging.Log4JLogger: java.lang.InternalError: class redefinition failed: invalid class
```

If detectors go quiet after an attach, that line is the first thing to look for. Pinned by
`RetransformBatchIsolationTest`; measured on a real classpath in
[the corpus eval](../analysis/corpus-eval.md#what-the-corpus-taught-the-model-in-five-rounds), where
the defect cost 874 of 1074 instrumented classes and the whole documented-unsafe detection column.

**The attach loads classes of its own, and those used to be lost.** There are three sets, not
two. Retransformation covers what is already loaded at the moment of the attach; load-time weaving
covers everything loaded afterwards; and in between sits a set nobody thinks about — the classes
the retransformation pass itself loads while it runs. Byte Buddy describes an already-loaded class
through the reflection API, and reflection eagerly resolves every field and method signature type,
so describing a class *loads the types it names*. Those loads happen on the attaching thread while
Byte Buddy holds its circularity lock, where the transformer declines without calling any listener,
and the default `DiscoveryStrategy.SinglePass` took its snapshot of the loaded classes before they
existed. Woven by nothing, with no error and no log line.

It was expensive and it looked like file order. The corpus eval measured it: running its two test
classes in the other order moved detection of documented-unsafe subjects from 20 of 20 to 6 of 20,
because the classes that went missing were the test class's own field types — `MutableInt` and
`MutableLong` were never consulted while 56 other `commons-lang3` types wove normally
([#316](https://github.com/PIsberg/async-test-lib/issues/316),
[#321](https://github.com/PIsberg/async-test-lib/issues/321)).

The agent now discovers with `RedefinitionStrategy.DiscoveryStrategy.Reiterating`, which re-queries
the loaded set until it stops growing and so picks up whatever the pass loaded. Pinned by
`SelfAttachTest.classLoadedByTheAttachItself_isStillWoven`, which fails on the unfixed agent.

**And if a class is ever missed again, the agent says so.** The reason the above took an afternoon
to find is that there was no line to look for. After a dynamic attach the agent now diffs the type
names the transformer was handed against `getAllLoadedClasses()`, filtered by the same ignore and
`includes` matchers the install used, and names whatever is left:

```
[ASYNC-TEST-AGENT] 1 already-loaded class(es) were never handed to the transformer, so they are
woven by nothing and invisible to every agent-fed detector. ...
[ASYNC-TEST-AGENT]   com.example.probe.Payload
```

A healthy attach prints nothing. Pinned by `AttachCoverageReportTest`; `premain` skips the check,
where the set is empty by construction.

If a suite's results have to be comparable across machines, attach with the launch flag anyway:
`premain` installs before any application class exists, so nothing depends on which test runs
first and the whole question above never arises. `corpus-eval/pom.xml` does exactly this and gates
on it.

**Idempotency and interaction with `-javaagent`.** All three entry points share a single
at-most-once install gate (an `AtomicBoolean` CAS). If the agent was already attached — via a
launch flag or a prior `selfAttach` — the call returns immediately without attaching again or
double-weaving. `selfAttach()` is therefore safe to call unconditionally even when a
`-javaagent:` flag may already be present, and safe to call concurrently from multiple
threads.

**If the JVM refuses the load.** A JVM started with `-XX:-EnableDynamicAgentLoading`, the
default JEP 451 announces for a future JDK, refuses any agent loaded at runtime. `selfAttach` then
throws an `IllegalStateException` (it does **not** swallow the failure) whose message names that
switch and the `-javaagent:` launch flag, which keeps working (`SelfAttachRefusedTest`).
`-Djdk.attach.allowAttachSelf` is not what decides it: with self-attach off, Byte Buddy attaches
from a helper process instead.

### 3.4 Build snippets for self-attach

These keep a runtime attach quiet and working: `-Djdk.attach.allowAttachSelf=true` lets Byte
Buddy attach in-process instead of from a helper process, and `-XX:+EnableDynamicAgentLoading`
hides the JDK 21+ warning and keeps the load allowed once a JDK refuses it by default. Prefer
[section 3.0](#30-recommended-at-startup-from-the-build) where you can.

**Maven (surefire):**

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <!-- @{argLine} preserves JaCoCo's late-bound agent argLine -->
    <argLine>@{argLine} -Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading</argLine>
  </configuration>
</plugin>
```

On surefire 3.6.0, `-XX:+EnableDynamicAgentLoading` is required, not cosmetic. Without it the JDK
prints its dynamic-agent warning to `System.err` from the Attach Listener thread, surefire's
output capture fails there, and the attach aborts with `Agent failed to start!`. With the flag
the JDK prints no warning, so surefire 3.5.x and Gradle need it only to keep the log quiet.

**Gradle (Kotlin DSL):**

```kotlin
tasks.test {
    // Allow ByteBuddyAgent.install() to self-attach (disabled by default on JDK 9+).
    jvmArgs("-Djdk.attach.allowAttachSelf=true")
}
```

For **static** attach via `-javaagent` instead, point surefire's `argLine` / Gradle's
`jvmArgs` at the built agent JAR: `-javaagent:/path/to/async-test-agent-<version>.jar`.
