# What the agent is and why it exists

Part of the [Agent Instrumentation Guide](../AGENT.md).

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
  own packages and keep startup fast — see [Scope & filtering](scope-and-filtering.md#5-scope--filtering).
