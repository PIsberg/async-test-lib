# Consuming agent events

Part of the [Agent Instrumentation Guide](../AGENT.md).

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
  - **`synchronized` methods are answered at the access, not by weaving.** A `synchronized`
    *method* carries the `ACC_SYNCHRONIZED` flag and contains no `MONITORENTER` instruction, so
    there is nothing to weave; instead each woven field access passes its receiver, whose monitor
    is probed with `Thread.holdsLock`, and the monitor of the enclosing synchronized method
    outright. A field guarded by its owner's synchronized methods is not reported; one guarded by
    *another* object's synchronized method, with none of its accesses on that object's own
    methods, still is.
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
