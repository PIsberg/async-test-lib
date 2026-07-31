# High-Precision Contention Engine

> Part of the [architecture documentation](../ARCHITECTURE.md).

## High-Precision Contention Engine (1.6.0+)

Four architectural improvements that address the baseline's contention precision, observer
effect, manual instrumentation overhead, and late detection of Loom pinning sites.

---

### 1. SpinContentionBarrier — Lock-Free Busy-Spin Barrier

**Package:** `se.deversity.async-test-lib.runner`

#### Baseline limitation
`CyclicBarrier` parks threads via `LockSupport.park()`.  When the last thread arrives it
wakes sleeping threads through the OS scheduler.  Threads are released staggered over
20–100 µs, dispersing the execution window and reducing the probability of triggering
true microsecond-level memory-ordering races.

#### Design
`SpinContentionBarrier` replaces OS-level parking with a VarHandle acquire/release spin:

- A `volatile int currentPhase` field (protected with manual cache-line padding `long` fields
  on both sides) tracks the barrier generation.
- An `AtomicInteger arrivalCount` is incremented by each arriving thread.
- The **last** thread to arrive resets `arrivalCount` to 0 and publishes the new phase via
  `VarHandle.setRelease`.  All spinners observe the change via `VarHandle.getAcquire` and
  return simultaneously within a sub-microsecond window.
- `Thread.onSpinWait()` emits the `x86 PAUSE` / `ARM YIELD` hint to reduce pipeline stalls
  during the spin, and the interrupt flag is checked every 64 iterations for clean teardown.

#### Integration
`ConcurrencyRunner.createBarrier(threads)` returns a `ContentionBarrier` functional interface.
Enable the spin variant at runtime with:

```
-Dasyc-test.spin-barrier.enabled=true
```

The default remains `CyclicBarrier` to preserve compatibility with virtual-thread schedulers
that are not benefit from busy-spinning platform threads.

---

### 2. TelemetryEventBuffer + TelemetryRegistry — Lock-Free Ring Buffer

**Package:** `se.deversity.async-test-lib.telemetry`

#### Baseline limitation
Synchronous writes to thread-local lists during detector `recordAccess()` calls change the
scheduling pattern of the recording thread — the overhead of a lock acquisition or stack
trace capture slows the thread enough to prevent the race from manifesting
(**Heisenbug / observer effect**).

#### Design — TelemetryEventBuffer
An MPSC (multi-producer single-consumer) ring buffer modelled after the
[LMAX Disruptor](https://lmax-exchange.github.io/disruptor/) pattern:

| Concern | Solution |
|---------|----------|
| Slot claim (producer) | `AtomicLong.getAndIncrement()` — no lock, no CAS loop |
| Publication signal | `VarHandle.setRelease` on the per-slot `sequence` field |
| Consumer ordering | `VarHandle.getAcquire` check before processing each slot |
| Allocation on hot path | Zero — all `AccessEvent` slots are pre-allocated at construction |
| Capacity | Power-of-two (default 16 384); on overflow producers **spin-wait** (`Thread.onSpinWait()`) until the consumer drains — slots are never overwritten |

**Key API:**

```java
buffer.publish(threadId, "com.example.OrderService.setCount", isWrite); // producer hot path
buffer.drain(callback);                                                 // single consumer thread
```

#### Design — TelemetryRegistry
Global singleton that owns the shared buffer and a daemon drain thread (scheduled at 1 ms
intervals).  The advice hot path is `recordAccess(threadId, qualifiedName, isWrite)`
(1.7.0+): it receives an already-combined constant-pool identifier and a pre-decided
`isWrite` flag from the split getter/setter advice, so it does no string work and stays
allocation-free and lock-free.  A convenience `recordAccess(threadId, className, methodName)`
overload (which composes the identifier and derives `isWrite` from the method prefix) is
retained for tests and documented examples but is not used on the hot path.

---

### 3. AsyncTestAgent — Java Agent Bytecode Instrumentation

**Package:** `se.deversity.asynctest.agent`  
**Dependency:** `net.bytebuddy:byte-buddy`, `net.bytebuddy:byte-buddy-agent` (self-attach)

> **User-facing guide:** [AGENT.md](../AGENT.md) — attachment (all three ways), event
> consumption, scope/filtering, diagnostics, limitations, and troubleshooting.

#### Baseline limitation
Detectors that rely on manual `recordFieldAccess("count", count)` calls require production
code to carry testing hooks, coupling production and test concerns and polluting JIT profiling.

#### Design
`AsyncTestAgent` is a [Byte Buddy](https://bytebuddy.net) Java agent that instruments
getter/setter methods at class-load time:

```
attach → premain()/agentmain()/selfAttach() → install() (shared, at-most-once via AtomicBoolean CAS)
       → AgentBuilder with DiagnosticListener
       → ignore(): java.*, jdk.*, sun.*, com.sun.*, net.bytebuddy.*, se.deversity.asynctest.*,
                   synthetic types, bootstrap-loaded types (+ any excludes= prefixes)
       → type(): any()  OR  the OR of includes= prefixes
       → ReadAccessAdvice.enter()  injected before each getter (isWrite=false)
       → WriteAccessAdvice.enter()  injected before each setter (isWrite=true)
       → TelemetryRegistry.recordAccess(threadId, qualifiedName, isWrite) called transparently
```

The advice `enter` methods are inlined at the call site by Byte Buddy (not called via
reflection), so they do not appear in stack traces and incur minimal overhead after JIT
compilation.  Splitting the advice by read/write moves the access-kind decision to
instrumentation time, and each advice passes a single `@Advice.Origin("#t.#m")` identifier
— a constant-pool string (`declaringClass.methodName`, e.g. `com.example.OrderService.setCount`)
— so the prologue performs no string concatenation and allocates nothing per call. (The `#t.#m`
pattern uses a literal `.` separator because Byte Buddy's origin parser rejects a doubled `##`
escape.) The deprecated 1.6.0 `FieldAccessAdvice` is retained for binary compatibility but is
no longer bound.

**Scope guards** re-establish Byte Buddy's default ignores (a bare `ignore(...)` would replace
them), preventing recursive instrumentation of `java.*`, `jdk.*`, `sun.*`, `com.sun.*`,
`net.bytebuddy.*`, and `se.deversity.asynctest.*` itself, plus synthetic and bootstrap-loaded
types. `agentArgs` (parsed by `AgentOptions`) tunes scope: `includes=` narrows the positive
match to the named prefixes; `excludes=` appends extra ignore prefixes; `debug=true` turns on
the `DiagnosticListener`'s verbose logging (per-type `Instrumented` lines + full error stack
traces). Absent/blank args preserve the default `any()` behavior. A `DiagnosticListener`
(installed always) logs a one-line `[ASYNC-TEST-AGENT] Failed to instrument <type>: <throwable>`
for weaving errors that Byte Buddy would otherwise swallow.

#### Attachment
The library JAR is agent-capable — its MANIFEST contains:
```
Premain-Class: se.deversity.asynctest.agent.AsyncTestAgent
Agent-Class:   se.deversity.asynctest.agent.AsyncTestAgent
Can-Retransform-Classes: true
Can-Redefine-Classes: true
```

Three attachment paths, all routed through the shared `install(...)`:

- **Static attach** — `-javaagent:async-test-lib-<version>.jar[=<agentArgs>]` → `premain`.
  Classes are woven as they load; no retransformation.
- **Dynamic attach** — the Attach API or `selfAttach` → `agentmain`. Installs with
  `RedefinitionStrategy.RETRANSFORMATION` + `disableClassFormatChanges()`, so accessors of
  classes loaded **before** attach are re-woven in place (the `@Advice` adds no members, so the
  schema is unchanged and retransformation is schema-safe — verified by `SelfAttachTest`).
- **`AsyncTestAgent.selfAttach()` / `selfAttach(String)`** — obtains an `Instrumentation` via
  `ByteBuddyAgent.install()` and routes through the dynamic path; idempotent via the shared CAS
  gate; throws `IllegalStateException` (with `-javaagent` fallback advice) when the JVM forbids
  self-attach (needs `-Djdk.attach.allowAttachSelf=true`).

#### Consuming events — `TelemetryBridge`
The install path starts `TelemetryRegistry` with a **no-op** callback, so drained events are
discarded unless a consumer is registered. `TelemetryBridge` (in
`se.deversity.asynctest.telemetry`) is the built-in consumer: `activate(AtomicityValidator,
Set<Long> workerThreadIds)` registers it as the drain callback and forwards worker-thread
events into the validator via the explicit-thread-id overload
`AtomicityValidator.recordFieldAccess(String, Object, boolean, long)` (attributing the access
to the originating worker thread, not the drain thread). It routes to `AtomicityValidator`
**only** — `VisibilityMonitor` needs field values, which the agent does not capture. The bridge
is `AutoCloseable` (try-with-resources), idempotent on `close()`, and filters out non-worker
thread ids. `forCurrentContext(Set<Long>)` resolves the validator via
`AsyncTestContext.atomicityValidator()`.

---

### 4. StaticPinningScanner — Compile-Time Pinning Pre-Scanner

**Package:** `se.deversity.async-test-lib.analysis`  
**Dependency:** `org.ow2.asm:asm`

#### Baseline limitation
`VirtualThreadPinningDetector` finds pinning at runtime, but only if the pinning code path
is exercised during the stress-test window.  Rarely-executed synchronized blocks that call
blocking operations go undetected until production load triggers them.

#### Design
`StaticPinningScanner` walks compiled `.class` files using the ASM bytecode library and flags
any method that calls a **blocking JDK method** while inside a `MONITORENTER` block:

```
MONITORENTER detected → monitorDepth++
Method call detected  → if monitorDepth > 0 && isBlockingMethod(owner, name) → PinningSite
MONITOREXIT detected  → monitorDepth--
```

The `BLOCKING_METHODS` set covers `Thread.sleep`, `Object.wait`, socket I/O,
`FileInputStream/OutputStream`, `Selector.select`, `Process.waitFor`,
`Condition.await*`, and `BlockingQueue.take/put`.

**Key API:**

```java
// Scan a single class from its bytes
List<PinningSite> sites = StaticPinningScanner.scanClass(classBytes);

// Scan all .class files under a build output directory
List<PinningSite> sites = StaticPinningScanner.scanDirectory(Path.of("target/classes"));

// Use as a JUnit @BeforeAll guard
@BeforeAll
static void noPinningSites() throws IOException {
    var sites = StaticPinningScanner.scanDirectory(Path.of("target/classes"));
    assertTrue(sites.isEmpty(), "Pinning sites detected:\n" + sites);
}
```

**Known limitation:** nesting depth is tracked per method body only.  Cross-method
synchronization (e.g. a `synchronized` method calling a blocking helper) is not detected
without inter-procedural analysis.

---

### Component Map (1.6.0)

```
runner/
  SpinContentionBarrier      ← lock-free barrier; enabled via system property
  ConcurrencyRunner          ← createBarrier() selects spin vs. cyclic at runtime

telemetry/
  TelemetryEventBuffer       ← MPSC ring buffer, zero allocation on publish(); spin-wait backpressure
  TelemetryRegistry          ← global singleton; daemon drain thread; setCallback() hook
  TelemetryBridge            ← DrainCallback → AtomicityValidator; AutoCloseable, worker-id filtered

agent/
  AsyncTestAgent             ← premain / agentmain / selfAttach; shared install(); DiagnosticListener
  AgentOptions               ← parses includes=/excludes=/debug= from agentArgs
  ReadAccessAdvice           ← inlined getter Advice (isWrite=false); calls TelemetryRegistry.recordAccess
  WriteAccessAdvice          ← inlined setter Advice (isWrite=true);  calls TelemetryRegistry.recordAccess

analysis/
  StaticPinningScanner       ← ASM ClassVisitor; produces List<PinningSite>
```

---

**Last Updated**: May 2026
**Version**: 1.6.0-SNAPSHOT
