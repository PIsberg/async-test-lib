# Example 127 — Shared SecureRandom

**Detector**: `SharedSecureRandomDetector` (`DetectorType.SHARED_SECURE_RANDOM`, also usable standalone)

## The Problem

`SecureRandom` is the awkward one in this family, because the javadoc gives a **conditional**
guarantee:

> A `SecureRandom` object is thread-safe if the underlying implementation is thread-safe.
> [...] Applications are encouraged to use a separate `SecureRandom` instance per thread.

That is not a promise, it is a delegation. The bundled SUN providers do synchronize, so a
shared instance on a stock JVM is **correct** — and slow, because every thread serialises on
one lock at exactly the moment every request needs a token. Swap in an HSM-backed, PKCS#11
or FIPS provider — which is precisely what a security review tends to require — and thread
safety becomes theirs to promise. One that does not synchronize can hand two concurrent
requests the same bytes.

For a session token, "the same bytes" means two users sharing a session. That is not a
performance footnote.

## The buggy pattern

```java
private final SecureRandom sharedRandom = new SecureRandom();   // ✗ one instance, N threads

String mintToken() {
    byte[] token = new byte[32];
    sharedRandom.nextBytes(token);      // ✗ a lock at best, a duplicate at worst
    return HexFormat.of().formatHex(token);
}
```

## The Fix

```java
private static final ThreadLocal<SecureRandom> PER_THREAD =
        ThreadLocal.withInitial(SecureRandom::new);             // ✓ one per thread

String mintToken() {
    byte[] token = new byte[32];
    PER_THREAD.get().nextBytes(token);
    return HexFormat.of().formatHex(token);
}
```

There is nothing to weigh up here. Per-thread instances are **faster** where sharing was
safe and **correct** where it was not. Each is seeded independently by the provider, so
splitting the instance does not split the entropy.

One caveat on `SecureRandom.getInstanceStrong()`: it resolves through the
`securerandom.strongAlgorithms` security property, which on Linux is `NativePRNGBlocking` —
it reads `/dev/random` and can block waiting for entropy. That makes it a poor thing to put
behind a shared lock, and a poor thing to call on a request path at all. Reserve it for
long-lived key material.

## How to Detect

```java
var d = new SharedSecureRandomDetector();
d.recordAccess(random, "session-token-rng", Thread.currentThread());
// ... same instance recorded from a second thread → flagged (HIGH)
assertTrue(d.analyze().hasIssues());
```

The report names the **algorithm and provider** of the instance, not just that it was
shared — so a finding tells you which implementation you are actually relying on, which is
the whole question with this one.

Inside `@AsyncTest`, grab it with `AsyncTestContext.sharedSecureRandomDetector()`, select it
alone with `includes = { DetectorType.SHARED_SECURE_RANDOM }`, or drop it with `excludes`.
Siblings: `SHARED_RANDOM` (`java.util.Random`, which is thread-safe but contended — see
[example 72](../72-shared-random/)), `SHARED_MESSAGE_DIGEST`, `SHARED_STATEFUL_CRYPTO` and
`SHARED_KDF`.

See [`SessionTokenServiceTest`](src/test/java/se/deversity/asynctest/example/SessionTokenServiceTest.java)
for the per-thread / shared / tokens-must-stay-unique walkthrough.

## Running

```bash
mvn -f ../../pom.xml install -DskipTests -Dlicense.mock.mode=true
mvn -f pom.xml test
```
