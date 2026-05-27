# Example 65 — Public Lock Exposure

Demonstrates **PublicLockExposureDetector**: a `SharedResourceManager` exposes
its internal `ReentrantLock` via a public `getLock()` method. An external caller
can acquire the lock and never release it, starving every other thread that
calls `accessResource()`.

## The Problem

`SharedResourceManager.getLock()` hands out the exact lock object that protects
the resource. Any external code (or a misbehaving test) that calls
`getLock().lock()` without a matching `unlock()` in a `finally` block will hold
the lock indefinitely. All other threads block inside `accessResource()` waiting
for a lock that will never be released.

The detector records calls to `synchronized`-on-`this` and published object
references, then reports objects whose internal monitor or lock has been exposed
to external callers.

## How to Reproduce

1. Remove `@Disabled` from `testAccessResource_concurrent_detectsExposedLock`.
2. Run: `mvn test` or `./gradlew test`
3. The test fails with a **PublicLockExposureDetector** report showing the lock
   object published to external callers.

**Fix**: remove `getLock()` and keep the `ReentrantLock` strictly private.
Callers that need custom lock semantics should use a higher-level API provided
by the service.
