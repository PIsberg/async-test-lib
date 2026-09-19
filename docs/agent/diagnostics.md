# Agent diagnostics and troubleshooting

Part of the [Agent Instrumentation Guide](../AGENT.md).

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

### When the runner says a detector cannot see

A detector that is switched on but has nothing feeding it produces an empty report, and an empty
report looks exactly like a clean bill of health. The runner refuses to let those two read the
same, so it announces each case once per JVM at INFO. Once per JVM, not once per test: a suite of
a thousand `@AsyncTest` methods must not drown in it, and the user this affects is precisely the
one who does not have DEBUG enabled.

| Event | What it means | What to do |
|---|---|---|
| `runner.agent.absent` | `AtomicityValidator` is enabled and the agent's telemetry pipeline is not running, so nothing auto-records field accesses. | Attach the agent, or record through `AsyncTestContext` explicitly. |
| `runner.detector.inert detector=DaemonThreadHygieneDetector` | `useVirtualThreads = true` makes every thread the body creates daemon by inheritance, so the detector's rule has nothing left to judge. | `@AsyncTest(useVirtualThreads = false)` on the test that instruments threads. |
| `runner.detector.inert detector=DeadlockDetector` | `findDeadlockedThreads()` reports platform threads, and this JVM's thread dump does not name monitors either, so a cycle between the virtual workers cannot be seen here. | `useVirtualThreads = false`, or a JDK whose thread dump carries monitor ownership. |
| `runner.detector.inert detector=LivelockDetector` | `dumpAllThreads()` does not report virtual threads, so the snapshots this detector filters for are never in the dump. | `useVirtualThreads = false` on the test whose threads you want watched. |

Read a clean report from any of these as "not observed" rather than "nothing there". Each event
names the test that triggered it, and each carries a `hint=` field with the same advice as the
table above.

## 8. Troubleshooting

| Symptom | Likely cause & fix |
|---------|--------------------|
| **No events at all** | (1) Agent not attached — confirm the `-javaagent:` flag or that `selfAttach()` ran (and did not throw). (2) Not running inside an `@AsyncTest` — `ConcurrencyRunner` attaches the `TelemetryBridge` for the duration of a run; outside one, register a consumer yourself with `TelemetryBridge.activate(...)` or `TelemetryRegistry.start(callback)`. (3) `detectAtomicityViolations` is disabled for that test, so there is no detector to feed. (4) The registry was stopped — call `TelemetryRegistry.start(...)` again. |
| **No events from *some* classes** | Those classes are outside `includes=`, or caught by a built-in ignore / `excludes=`, or the access is a direct field access rather than a getter/setter call. Turn on `debug=true` and look for `Instrumented <type>` lines. |
| **`Failed to instrument <type>` in the log** | That class could not be woven; it is simply not instrumented (the rest still are). Run with `debug=true` for the full stack trace to see why. |
| **`IllegalStateException` from `selfAttach()`** | The JVM forbids self-attach. Start the test JVM with `-Djdk.attach.allowAttachSelf=true` (see the [build snippets](attaching.md#34-build-snippets-for-self-attach)) or use the `-javaagent:` launch flag instead. |
| **Double events after both premain and selfAttach** | This cannot happen — all entry points share a single at-most-once install gate; the second attach is a no-op. If you see duplicates, you likely registered the callback twice or have two consumers. |
| **`IllegalStateException` from `forCurrentContext(...)`** | Either there is no active `@AsyncTest` context on the current thread (call it from the test body/worker, not a `@BeforeEachInvocation` hook), or `detectAtomicityViolations` is disabled for that test. |
| **Startup is slow** | The default match weaves the whole classpath. Scope it: `includes=com.myapp`. |
