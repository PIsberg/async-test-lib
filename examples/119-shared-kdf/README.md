# Example 119 — Shared KDF (JEP 510, JDK 25)

**Detector**: `SharedKdfDetector` (`DetectorType.SHARED_KDF`, also usable standalone)
**JDK feature**: `javax.crypto.KDF` — JEP 510, Key Derivation Function API, final in JDK 25 (LTS)

## The Problem

The `KDF` javadoc is explicit:

> Unless otherwise documented by an implementation, the methods defined in this class
> are not thread-safe. Multiple threads that need to access a single object
> concurrently should synchronize amongst themselves.

A KDF derivation (`deriveKey` / `deriveData`) threads algorithm parameters and provider
state through the underlying SPI. Two threads interleaving derivations on **one shared
instance** can fold each other's salt/info bytes into the same state and silently derive
wrong keys — no exception at the point of corruption, just a session key that fails to
match what the peer derives, surfacing later as failed handshakes or undecryptable data.

## The buggy pattern (real JDK 25 API)

```java
private final KDF hkdf = KDF.getInstance("HKDF-SHA256");   // ✗ one instance...

byte[] sessionKey(byte[] ikm, byte[] salt, byte[] info) throws Exception {
    var params = HKDFParameterSpec.ofExtract().addIKM(ikm).addSalt(salt)
                     .thenExpand(info, 32);
    return hkdf.deriveData(params);                          // ✗ ...hit by every request thread
}
```

## The Fix

```java
byte[] sessionKey(...) throws Exception {
    KDF hkdf = KDF.getInstance("HKDF-SHA256");   // per call — getInstance is cheap
    return hkdf.deriveData(params);              // (a ThreadLocal<KDF> works too)
}
```

Or synchronize every `deriveKey()`/`deriveData()` call on the shared instance. Only share
freely if the provider explicitly documents its KDF implementation as thread-safe.

> This example models the KDF's mutable per-operation state with a shared
> `MessageDigest` in
> [`SessionKeyService`](src/main/java/se/deversity/asynctest/example/service/SessionKeyService.java)
> so it compiles and runs on the Java 21 baseline (the real `KDF` type is JDK 24+). The
> detector takes the KDF instance as `Object` for the same reason — it applies unchanged
> to the real `javax.crypto.KDF`.

## How to Detect

```java
var d = new SharedKdfDetector();
d.recordAccess(kdf, "HKDF-SHA256", "deriveData", Thread.currentThread());
// ... same instance recorded from a second thread → flagged (HIGH)
assertTrue(d.analyze().hasIssues());
```

Inside `@AsyncTest` the detector is pipeline-wired: grab it with
`AsyncTestContext.sharedKdfDetector()` (exclude with
`excludes = { DetectorType.SHARED_KDF }`). Sibling of `SHARED_MESSAGE_DIGEST`,
`SHARED_SECURE_RANDOM`, and `SHARED_STATEFUL_CRYPTO`.

See [`SessionKeyServiceTest`](src/test/java/se/deversity/asynctest/example/SessionKeyServiceTest.java)
for the clean / shared-instance / corrupted-key walkthrough.

## Running

These detectors ship in the in-progress build. Install the parent artifact to your local
Maven repo first (same workflow as `consumer-fixture`):

```bash
mvn -f ../../pom.xml install -DskipTests -Dlicense.mock.mode=true
mvn -f pom.xml test
```
