# Runtime Guarantees — License Guard & Worker Latch

> Part of the [architecture documentation](../ARCHITECTURE.md).

## License Guard (1.6.0)

Previously `ConcurrencyRunner.execute()` constructed a fresh `LicenseGate` and
called `gate.check(...)` on **every test invocation**, including in mock-mode CI
runs. For a suite with 1000 `@AsyncTest` methods that meant 1000 redundant gate
constructions — pure noise on the hot path and a layering smell (a concurrency
engine should not know about license vendors).

`LicenseGuard` (in `se.deversity.asynctest.runner`) now owns the concern:

- `check(config)` is a `ConcurrentHashMap.get()` on a `Fingerprint` derived from
  the resolved license-config fields (provider, Keygen account/key/product,
  LemonSqueezy store subdomain/store id/product id/API base URI/email binding,
  license key, user email, mockMode + their System-property fallbacks).
- Every input that can change the decision is in the fingerprint. Switching
  provider or store id mid-JVM therefore recomputes the gate rather than
  reusing a grant issued under different terms.
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
`AsyncTestContext.uninstall()` call and a livelock snapshot, removed since,
**before** `latch.countDown()` inside a single `finally` block. If either of those
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
    latch.countDown();   // ALWAYS — last statement in the outermost finally
}
```

The cleanup step is guarded so its failure cannot suppress the `countDown()`. The `installed` flag preserves the ThreadLocal install/uninstall symmetry
rule from `CLAUDE.md` (only uninstall what was installed).

---

