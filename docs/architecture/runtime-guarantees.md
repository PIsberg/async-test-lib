# Runtime Guarantees — License Guard & Worker Latch

> Part of the [architecture documentation](../ARCHITECTURE.md).

## License Guard (1.6.0)

Previously `ConcurrencyRunner.execute()` constructed a fresh `LicenseGate` and
called `gate.check(...)` on **every test invocation**, including in mock-mode CI
runs. For a suite with 1000 `@AsyncTest` methods that meant 1000 redundant gate
constructions — pure noise on the hot path and a layering smell (a concurrency
engine should not know about license vendors).

`LicenseGuard` (in `se.deversity.async-test-lib.runner`) now owns the concern:

- `check(config)` is a `ConcurrentHashMap.get()` on a `Fingerprint` derived from
  the resolved license-config fields (account, key, product, store, license,
  mockMode + their System-property fallbacks).
- First call per fingerprint runs the real gate exactly once; all later calls
  with matching fingerprints return immediately.
- "Zero-Config CI" announcement and "LICENSE GRANTED" message are guarded by
  volatile flags so they print at most once per JVM, not per test.
- Denied results still throw `SecurityException` with the original message
  format (no behavior change for failing licenses).

`ConcurrencyRunner.execute()` dropped ~40 lines of license plumbing in favor of
a single `LicenseGuard.check(config)` call.

---

## Worker latch.countDown() guarantee (1.6.0)

The per-worker code in `runSingleInvocationRound` previously placed the
`AsyncTestContext.uninstall()` and `phase1.livelock.captureSnapshot()` calls
**before** `latch.countDown()` inside a single `finally` block. If any of those
cleanup calls threw, `countDown()` was skipped and the runner blocked on
`latch.await(roundTimeoutMs)` until the deadline elapsed — surfacing a
misleading "timed out — possible deadlock" instead of the real cause.

In 1.6.0 the structure is:

```java
boolean installed = false;
try {
    AsyncTestContext.install(phase2Context);
    installed = true;
    try { barrier.await(); method.invoke(target, args); }
    catch (Throwable ex) { failures.add(unwrap(ex)); }
} catch (Throwable installErr) {
    failures.add(installErr);
} finally {
    if (installed) { try { AsyncTestContext.uninstall(); }
                     catch (Throwable e) { failures.add(e); } }
    if (phase1.livelock != null) { try { phase1.livelock.captureSnapshot(); }
                                   catch (Throwable e) { /* warn-only */ } }
    latch.countDown();   // ALWAYS — last statement in the outermost finally
}
```

Each cleanup step is independently guarded so one failure cannot suppress the
next. The `installed` flag preserves the ThreadLocal install/uninstall symmetry
rule from `CLAUDE.md` (only uninstall what was installed).

---

