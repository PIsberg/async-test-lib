# Agent Instrumentation Guide

`AsyncTestAgent` is an optional [Byte Buddy](https://bytebuddy.net) Java agent that
records field-access telemetry from your code **without any source changes**. This guide
covers what it is, why it exists, how to attach it three different ways, how to consume the
events it produces, how instrumentation scope is controlled, and its honest limitations.

> **Artifact:** `se.deversity.async-test-lib:async-test-agent` — a **separate module** since 1.7.0
> **Package:** `se.deversity.asynctest.agent`
> **Entry points:** `AsyncTestAgent.premain` / `agentmain` / `selfAttach`
> **Since:** agent 1.6.0; dynamic self-attach, package filters, diagnostics, and the
> telemetry bridge 1.7.0.

> ### Moved out of the library JAR
>
> The agent used to ship inside `async-test-lib.jar`, which carried the `Premain-Class` manifest
> and forced Byte Buddy onto every consumer's test classpath whether they used the agent or not.
> It is now its own artifact. Two things changed for you:
>
> ```xml
> <dependency>
>     <groupId>se.deversity.async-test-lib</groupId>
>     <artifactId>async-test-agent</artifactId>
>     <version><!-- same version as async-test-lib --></version>
>     <scope>test</scope>
> </dependency>
> ```
>
> and the attach flag now names the agent JAR: `-javaagent:async-test-agent-<version>.jar`.
> Nothing about the API, the entry points or the event format changed.

---

## 1. What it is

`AsyncTestAgent` weaves a tiny inlined prologue into every **getter** and **setter** of your
application classes at class-load time. Each intercepted access publishes a
`(threadId, identifier, isWrite)` event into a lock-free ring buffer
(`TelemetryEventBuffer`), which a background daemon thread drains every millisecond. The
instrumented classes require **no modification** — you never write a
`recordFieldAccess("count", count)` call by hand.

**When you want it.** Reach for the agent when you want a detector like `AtomicityValidator`
to observe accessor traffic across your stress-test worker threads but do not want to (or
cannot) pepper production code with manual detector hooks. If you are happy calling the
`AsyncTestContext.*Detector()` recording methods explicitly, you do not need the agent at
all — it is strictly opt-in.

---

## 2. Why (motivation)

Manual `recordFieldAccess()` instrumentation has three problems the agent is designed to
solve.

### (a) It pollutes production code
Every field you want observed needs a detector hook next to it. That couples your production
service classes to the test framework, adds cognitive overhead, and has to be maintained as
the code evolves.

### (b) It perturbs the very races you are hunting (the Heisenbug)
Recording an event synchronously — capturing a stack trace, acquiring a lock, allocating a
list node — slows the recording thread. That timing change alone is often enough to make a
real data race stop reproducing, or to fabricate an ordering that never happens in
production. It also disturbs JIT profiling and scheduling. This observer effect is a classic
*Heisenbug*: the act of measuring changes the outcome.

### (c) It is omission-prone
A hook you forget to add is a bug you will never see. Coverage depends on discipline.

### How the design answers each

- **Load-time weaving → no source changes (answers a).** Byte Buddy rewrites bytecode as
  classes load; your source stays clean and framework-free.
- **Inlined `@Advice` → JIT-friendly, no stack-trace pollution (answers b).** The prologue is
  inlined at the call site, not invoked reflectively, so it does not appear in stack traces
  and folds into negligible overhead after JIT compilation.
- **Compile-time origin identifiers → allocation-free producer (answers b).** Getters are
  woven with `ReadAccessAdvice` and setters with `WriteAccessAdvice`. The read/write decision
  is bound at instrumentation time (a hardcoded `isWrite` flag), and the identifier is a
  single `@Advice.Origin("#t.#m")` constant baked into the woven class's constant pool. The
  prologue therefore does **no string concatenation and allocates nothing per call**.
- **MPSC lock-free pre-allocated ring buffer → minimal observer effect (answers b).**
  `TelemetryEventBuffer` claims a slot with one `AtomicLong.getAndIncrement()`, writes the
  pre-allocated slot's fields, and publishes via a `VarHandle` release fence. No locks, no
  allocation on the producer path.
- **1 ms asynchronous drain → analysis off the hot path (answers b).** All the expensive
  work (routing events into detectors) happens on a background thread, not on the thread
  running your code.
- **Every accessor is woven → nothing to forget (answers c).** Scope is bounded by matchers,
  not by hand-placed hooks.

### Backpressure: producers spin-wait, they do not overwrite
The buffer has **16 384 pre-allocated slots** (a power of two). If producers ever outrun the
drain thread and the buffer fills, `publish()` **spin-waits** (`Thread.onSpinWait()`) until
the consumer advances — it does **not** overwrite undrained slots, so no event is silently
lost to overflow. For typical `@AsyncTest` invocation sizes the buffer is never full and the
spin path is never taken.

### Honest overhead notes
- Every getter/setter of an instrumented class gains an inlined call. After JIT compilation
  this is very cheap, but it is not literally free.
- Startup cost scales with the number of classes woven. Weaving the entire classpath (the
  default `any()` match) is the worst case. Use `includes=` to bound instrumentation to your
  own packages and keep startup fast — see [Scope & filtering](#5-scope--filtering).

---

## 3. How to attach (all three ways)

The agent JAR (`async-test-agent`, not the library JAR — the manifest moved there in the
module split) is the agent-capable artifact: its `MANIFEST.MF` declares `Premain-Class`,
`Agent-Class`, `Can-Retransform-Classes: true`, and `Can-Redefine-Classes: true`.

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
| `debug` | `true` / `false` | `debug=true` turns on verbose diagnostics (see [Diagnostics](#6-diagnostics)). Any other value, or absence, keeps the default errors-only logging. Case-insensitive. |
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
stack-neutral, branch-free observation call before each field instruction.

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
classes were silent for exactly this reason ([corpus eval](analysis/corpus-eval.md)).

`collections=true` rewrites the collection call itself, so the map instance reaches
`SharedCollectionDetector` and its instance-keyed siblings:

```
-javaagent:async-test-agent-<version>.jar=includes=com.myapp,collections=true
```

What is rewritten is an explicit table in `CollectionAccessWeaver`: `Map.put/get/remove/containsKey`,
`Collection.add/remove/contains/clear`, `List.get/set`, `Queue.offer/poll/peek`. Each call becomes a
call to a hook that records and then performs the original operation, so behaviour is unchanged;
`CollectionWeavingEndToEndTest` pins that a woven program still computes the same values.

Three limits worth knowing before switching it on:

- **Guarding works, and has to.** Monitor weaving is installed alongside, so a collection touched
  only inside a `synchronized` block reports nothing even though the test never declared the lock.
  A `ReentrantLock` is still invisible: declare it with `AsyncTestContext.holdingLock(...)`.
- **Thread-safe types are skipped.** A receiver from `java.util.concurrent` or a
  `Collections.synchronizedX` wrapper synchronizes where nothing can be woven, so recording it
  would report every shared use. Those calls are delegated and never recorded.
- **`super` calls keep their dispatch.** Only virtual and interface invocations are rewritten. A
  decorator's `super.get(...)` must stay an `INVOKESPECIAL`, or the substitution would re-dispatch
  virtually into the override and recurse.

**Reaching it without a `-javaagent` path.** `-Dasynctest.agent=fields=true` makes the runner
attach the agent itself at the start of the run, so you do not have to resolve the jar's path in
your build. It needs the `async-test-agent` artifact on the test classpath; if it is missing, or
the JVM forbids self-attachment, the runner logs `runner.agent.attach.failed` once and continues
without instrumentation rather than failing the suite.

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
exactly like classes loaded afterwards.

**Idempotency and interaction with `-javaagent`.** All three entry points share a single
at-most-once install gate (an `AtomicBoolean` CAS). If the agent was already attached — via a
launch flag or a prior `selfAttach` — the call returns immediately without attaching again or
double-weaving. `selfAttach()` is therefore safe to call unconditionally even when a
`-javaagent:` flag may already be present, and safe to call concurrently from multiple
threads.

**If the JVM forbids self-attach.** Self-attachment is disabled by default on JDK 9+ unless
the JVM was started with `-Djdk.attach.allowAttachSelf=true`. When attachment is refused,
`selfAttach` throws an `IllegalStateException` (it does **not** swallow the failure) whose
message directs you to set that flag or fall back to the `-javaagent:` launch flag.

### 3.4 Build snippets for self-attach

Self-attach needs `-Djdk.attach.allowAttachSelf=true` on the test JVM.

**Maven (surefire):**

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <!-- @{argLine} preserves JaCoCo's late-bound agent argLine -->
    <argLine>@{argLine} -Djdk.attach.allowAttachSelf=true</argLine>
  </configuration>
</plugin>
```

**Gradle (Kotlin DSL):**

```kotlin
tasks.test {
    // Allow ByteBuddyAgent.install() to self-attach (disabled by default on JDK 9+).
    jvmArgs("-Djdk.attach.allowAttachSelf=true")
}
```

For **static** attach via `-javaagent` instead, point surefire's `argLine` / Gradle's
`jvmArgs` at the built agent JAR: `-javaagent:/path/to/async-test-agent-<version>.jar`.

---

## 4. How to consume events

Weaving alone does nothing useful unless something consumes the drained events. There are
three levels of consumer, from highest to lowest.

### 4.1 `TelemetryBridge` — route agent events into a live detector

`TelemetryBridge` registers itself as the drain callback and forwards agent events — filtered
to your stress-test worker threads — into an `AtomicityValidator`, so agent-observed accesses
participate in the same cross-thread analysis as manually recorded ones.

```java
import se.deversity.asynctest.telemetry.TelemetryBridge;
import se.deversity.asynctest.diagnostics.AtomicityValidator;
import java.util.Set;

AtomicityValidator av = ...;          // a live per-test detector
Set<Long> workerIds = ...;            // ids of the stress-test worker threads
try (TelemetryBridge bridge = TelemetryBridge.activate(av, workerIds)) {
    // ... run the code under test on the worker threads;
    //     agent field-access events flow into av ...
}                                     // bridge.close() detaches here (idempotent)
```

Inside an `@AsyncTest` you can resolve the context's live validator with
`TelemetryBridge.forCurrentContext(workerIds)`, which calls
`AsyncTestContext.atomicityValidator()` for you (requires `detectAtomicityViolations = true`
on the `@AsyncTest`, and an active context — call it from the worker/test body, not from a
`@BeforeEachInvocation` hook, where the context is not yet installed).

**What routes where.** The agent has method-name granularity only — it knows *that* a
getter/setter ran and on which thread, but has no field *value*. So the bridge routes to
exactly one detector:

- **`AtomicityValidator` — routed.** Its cross-thread mixed read/write analysis
  (`analyzeAtomicity()`) depends only on the thread id and the read/write flag, both of which
  the agent supplies, and it tolerates a `null` value. Events are forwarded through
  `recordFieldAccessUnderLocks(String, Object, boolean, long, long)` so the access is attributed
  to the originating **worker** thread, not to the drain thread that replays it, and so it
  carries the locks that worker held.

  Those locks come from the weaver, not from the field: with `fields=true` it instruments
  `MONITORENTER` and `MONITOREXIT` as well, so a `synchronized (lock) { count++ }` tells the
  library which monitor was held and a field's accesses can be compared by what covered them. The
  comparison travels as a fingerprint captured on the worker at access time, because the ring
  buffer between the two threads is deliberately allocation-free and the drain thread holds none
  of what the worker held. A field always accessed under the same locks is not reported; one
  accessed under differing locks, or none, is.

  Two boundaries, both pinned by `FieldWeavingEndToEndTest`:

  - **Not under the accessor-only default.** Monitor weaving lives in `FieldAccessWeaver`, which
    the default attach never installs, so with neither `fields=true` nor `collections=true` the
    agent has no lock model. Either option installs it: `collections=true` weaves monitors without
    field instructions, because recording an access without knowing what lock covered it is how a
    correctly guarded `HashMap` gets reported as racing.
  - **Explicit `synchronized` blocks only.** A `synchronized` *method* carries the
    `ACC_SYNCHRONIZED` flag and contains no `MONITORENTER` instruction at all, so there is
    nothing to weave. A field guarded only by synchronized methods is still reported.
- **`VisibilityMonitor` — not routed.** Its analysis is value-equality based, so an access
  stream with no values carries no signal for it; worse, it rejects `null` values. Should a
  future agent version capture values, a value-aware overload can be added without breaking
  the current one.

`TelemetryBridge` is `AutoCloseable`; `close()` (equivalently `deactivate()`) restores the
registry's no-op callback and is idempotent. The registry holds a single callback, so keep at
most one bridge active at a time.

### 4.2 Raw callback — `TelemetryRegistry.start(callback)`

For a custom consumer, register any `DrainCallback` lambda directly:

```java
import se.deversity.asynctest.telemetry.TelemetryRegistry;

TelemetryRegistry.start((threadId, identifier, isWrite) -> {
    System.out.printf("%s %s by thread %d%n",
            isWrite ? "WRITE" : "READ", identifier, threadId);
});
// ... later ...
TelemetryRegistry.setCallback(null);   // detach (restore no-op)
TelemetryRegistry.stop();              // flush + stop the drain thread
```

`start(callback)` starts the drain thread if it is not already running, or swaps the callback
if it is. `setCallback(callback)` swaps the callback without touching the running/stopped
state. The registry holds a single callback — the last writer wins.

> The agent's `premain`/`agentmain`/`selfAttach` install path calls `TelemetryRegistry.start()`
> with a **no-op** callback, so out of the box drained events are simply discarded. You must
> register a `TelemetryBridge` or a custom callback to see anything.

### 4.3 Advanced — `TelemetryRegistry.buffer()`

`TelemetryRegistry.buffer()` exposes the shared `TelemetryEventBuffer` for advanced consumers
that want to drive `drain(callback)` themselves or inspect `publishedCount()`. Most users
should not need this.

### The event identifier format
Agent-produced identifiers are **dot-separated**: `declaringClass.methodName`, e.g.
`com.example.OrderService.setCount`. This comes from the `@Advice.Origin("#t.#m")` pattern.
(Byte Buddy's origin parser **rejects** a doubled `##` escape, which is why the separator is a
literal `.` rather than a `#`.) The convenience overload
`TelemetryRegistry.recordAccess(threadId, className, methodName)` — used by tests and
examples, not the agent hot path — instead composes a `className#methodName` identifier and is
*not* allocation-free.

---

## 5. Scope & filtering

### Built-in ignores (always applied)
The agent re-establishes the exclusions Byte Buddy applies by default (a bare `ignore(...)`
call would otherwise **replace** them). A type is ignored when its fully-qualified name starts
with any of:

| Prefix | Why ignored |
|--------|-------------|
| `java.` | JDK core — instrumenting it risks recursion and is never the code under test. |
| `jdk.` | JDK internals. |
| `sun.` | Legacy JDK internals. |
| `com.sun.` | JDK-shipped internals. |
| `net.bytebuddy.` | The weaver itself — instrumenting it would recurse. |
| `se.deversity.asynctest.` | This library — the telemetry pipeline must not observe itself. |

Additionally ignored:
- **Synthetic types** (`ElementMatchers.isSynthetic()`) — e.g. lambda classes.
- **Bootstrap-class-loader types** — every type loaded by the bootstrap loader (a
  class-loader-scoped check applied at the install site).

### How `includes` / `excludes` interact with the built-ins
- **`excludes` is additive.** Each exclude prefix is OR-ed onto the built-in ignore matcher.
  It can only *remove* candidates; it never overrides a built-in ignore.
- **`includes` narrows the positive match.** With no `includes`, the positive matcher is
  `any()` (every non-ignored type). With `includes`, the positive matcher becomes the OR of
  `nameStartsWith(prefix)` — only types under one of those prefixes are candidates, and the
  built-in ignores still apply on top.
- A type is instrumented iff it matches `includes` (or `includes` is empty) **and** is not
  caught by the built-in ignores **and** is not caught by any `excludes` prefix.

Practical guidance: pass `includes=com.myapp` to keep weaving (and startup cost) bounded to
your own code.

---

## 6. Diagnostics

The agent installs a `DiagnosticListener` (a Byte Buddy `AgentBuilder.Listener`) so that
weaving outcomes — which Byte Buddy otherwise swallows silently — are visible.

**Default (no `debug`): errors only, one line each.** For every type the agent fails to
weave, it prints a single line to `System.err`:

```
[ASYNC-TEST-AGENT] Failed to instrument com.myapp.Foo: java.lang.IllegalStateException: ...
```

Only the throwable's `toString()` is printed — no stack trace — to keep CI logs clean. If you
see this line, that class was **not** instrumented; the message tail tells you why.

**With `debug=true`: verbose.** Additionally logs one line per **successfully** instrumented
type, and appends a full stack trace after each error line:

```
[ASYNC-TEST-AGENT] Instrumented com.myapp.OrderService
[ASYNC-TEST-AGENT] Instrumented com.myapp.Cart
[ASYNC-TEST-AGENT] Failed to instrument com.myapp.Weird: ...
    <full stack trace>
```

Use `debug=true` when a class you expected to see events from is not producing any: the
`Instrumented` lines confirm what was actually woven, and a `Failed to instrument` line points
at the cause.

---

## 7. Limitations (honest)

- **Method granularity only.** The agent records that a getter/setter ran and on which thread.
  It has **no field value** — so any analysis that needs values (e.g. `VisibilityMonitor`'s
  value-divergence check) cannot be driven by agent data. This is why the bridge routes to
  `AtomicityValidator` only.
- **Only getters/setters are intercepted, unless `fields=true`.** By default matching is
  `ElementMatchers.isGetter()` / `isSetter()`, so a **direct field access** — `this.x = 1` or
  `return x` inside the class, bypassing an accessor — is not intercepted. Supplying
  [`fields=true`](#32-launch-flag-with-arguments) removes that limitation by weaving the field
  instructions themselves, at the cost of instrumenting every field access in every matched class.
  Both halves are pinned against the real weaver, on the same fixture, so the boundary is
  specified rather than assumed: `AgentFeedsDetectorEndToEndTest` attaches without the flag and
  requires the directly-mutated field to produce nothing (with a control in the same run proving
  the pipeline was live while that held), and `FieldWeavingEndToEndTest` attaches with it and
  requires the opposite.
- **JDK and framework classes are never instrumented.** Anything under `java.`/`jdk.`/`sun.`/
  `com.sun.`, Byte Buddy, this library, synthetic types, and bootstrap-loaded types are
  excluded by design.
- **Drain is best-effort at JVM exit.** The drain thread flushes every 1 ms and once more on
  `stop()`, but events published in the final moments before an abrupt JVM exit may not be
  drained. (Under sustained overload the producer spin-waits rather than dropping events — the
  16 384-slot buffer is not an overwrite buffer — but shutdown timing is still best-effort.)
- **Thread attribution is by the accessing thread.** The event carries the id of the thread
  that ran the accessor. The bridge preserves this by using the explicit-thread-id overload,
  so analysis reflects the worker thread and not the drain thread — but only events from the
  configured worker-thread-id set are forwarded; accesses on other application threads during
  the round are treated as noise and dropped.

---

## 8. Troubleshooting

| Symptom | Likely cause & fix |
|---------|--------------------|
| **No events at all** | (1) Agent not attached — confirm the `-javaagent:` flag or that `selfAttach()` ran (and did not throw). (2) Not running inside an `@AsyncTest` — `ConcurrencyRunner` attaches the `TelemetryBridge` for the duration of a run; outside one, register a consumer yourself with `TelemetryBridge.activate(...)` or `TelemetryRegistry.start(callback)`. (3) `detectAtomicityViolations` is disabled for that test, so there is no detector to feed. (4) The registry was stopped — call `TelemetryRegistry.start(...)` again. |
| **No events from *some* classes** | Those classes are outside `includes=`, or caught by a built-in ignore / `excludes=`, or the access is a direct field access rather than a getter/setter call. Turn on `debug=true` and look for `Instrumented <type>` lines. |
| **`Failed to instrument <type>` in the log** | That class could not be woven; it is simply not instrumented (the rest still are). Run with `debug=true` for the full stack trace to see why. |
| **`IllegalStateException` from `selfAttach()`** | The JVM forbids self-attach. Start the test JVM with `-Djdk.attach.allowAttachSelf=true` (see the build snippets above) or use the `-javaagent:` launch flag instead. |
| **Double events after both premain and selfAttach** | This cannot happen — all entry points share a single at-most-once install gate; the second attach is a no-op. If you see duplicates, you likely registered the callback twice or have two consumers. |
| **`IllegalStateException` from `forCurrentContext(...)`** | Either there is no active `@AsyncTest` context on the current thread (call it from the test body/worker, not a `@BeforeEachInvocation` hook), or `detectAtomicityViolations` is disabled for that test. |
| **Startup is slow** | The default match weaves the whole classpath. Scope it: `includes=com.myapp`. |

---

## See also

- [ARCHITECTURE.md](ARCHITECTURE.md) — telemetry buffer and agent internals (§ High-Precision
  Contention Engine).
- [USAGE.md](USAGE.md) — full `@AsyncTest` reference.
