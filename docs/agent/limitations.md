# Agent limitations

Part of the [Agent Instrumentation Guide](../AGENT.md).

## 7. Limitations (honest)

- **Method granularity only.** The agent records that a getter/setter ran and on which thread.
  It has **no field value** — so any analysis that needs values (e.g. `VisibilityMonitor`'s
  value-divergence check) cannot be driven by agent data. This is why the bridge routes to
  `AtomicityValidator` only.
- **Only getters/setters are intercepted, unless `fields=true`.** By default matching is
  `ElementMatchers.isGetter()` / `isSetter()`, so a **direct field access** — `this.x = 1` or
  `return x` inside the class, bypassing an accessor — is not intercepted. Supplying
  [`fields=true`](attaching.md#32-launch-flag-with-arguments) removes that limitation by weaving the field
  instructions themselves, at the cost of instrumenting every field access in every matched class.
  Both halves are pinned against the real weaver, on the same fixture, so the boundary is
  specified rather than assumed: `AgentFeedsDetectorEndToEndTest` attaches without the flag and
  requires the directly-mutated field to produce nothing (with a control in the same run proving
  the pipeline was live while that held), and `FieldWeavingEndToEndTest` attaches with it and
  requires the opposite.
- **JDK and framework classes are never instrumented.** Anything under `java.`/`jdk.`/`sun.`/
  `com.sun.`, Byte Buddy, this library, synthetic types, and bootstrap-loaded types are
  excluded by design.
- **A serializable method reference is not woven.** A method reference such as `builder::append`
or `lock::lock` compiles to an `invokedynamic`, and the JVM makes the call from a hidden class
  no agent can weave. The weaver points the lambda factory at the hook instead, so those calls are
  observed like any other (#550), but it leaves a *serializable* lambda (`(Consumer<String> &`
  `Serializable) builder::append`) alone: its generated `$deserializeLambda$` checks the
  implementation method it was compiled against, and a rewritten one would fail to deserialize.
  `MethodReferenceWeavingSparesConfinedUseTest` round-trips one to prove it still works.
- **Drain is best-effort at JVM exit.** The drain thread flushes every 1 ms and once more on
  `stop()`, but events published in the final moments before an abrupt JVM exit may not be
  drained. (Under sustained overload the producer spin-waits rather than dropping events — the
  16 384-slot buffer is not an overwrite buffer — but shutdown timing is still best-effort.)
- **Thread attribution is by the accessing thread.** The event carries the id of the thread
  that ran the accessor. The bridge preserves this by using the explicit-thread-id overload,
  so analysis reflects the worker thread and not the drain thread — but only events from the
  configured worker-thread-id set are forwarded; accesses on other application threads during
  the round are treated as noise and dropped.
