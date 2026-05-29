# Example 104 — Shared Stateful Crypto (Cipher / Mac / Signature)

Demonstrates **SharedStatefulCryptoDetector** catching a stateful `Mac` shared across threads.

## The Problem

`TokenSigner` creates one HmacSHA256 `Mac` instance and stores it in a field that is shared
across all threads. A `Mac` is stateful: a signing operation is `init` → `update` → `doFinal`,
and the intermediate bytes accumulate inside the instance. When two threads call `sign()`
concurrently on the same `Mac`, their input bytes interleave into one shared buffer, producing
a MAC that verifies for neither caller's message (and may throw `IllegalStateException`). The
same hazard applies to `javax.crypto.Cipher` and `java.security.Signature`.

## How to Reproduce

1. Remove `@Disabled` from `test_concurrent_detectsSharedMac` in `TokenSignerTest`.
2. Run the test:
   ```
   mvn test
   gradle test
   ```
3. **SharedStatefulCryptoDetector** will report multiple threads accessing the same
   stateful `Mac` instance without synchronization.

## The Fix

Never share a stateful `Mac` across threads. Use a `ThreadLocal<Mac>` so each thread owns its
own instance, or create and `init` a fresh `Mac` per `sign()` call.

> Requires async-test-lib 1.7.0+ (the release that introduces this detector).
