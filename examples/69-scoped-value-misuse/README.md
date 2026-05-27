# Example 69 — Scoped Value Misuse

Demonstrates **ScopedValueMisuseDetector**: a `ScopedContextService` calls
`ScopedValue.get()` in `getCurrentUser()` without checking `isBound()` first.
When invoked outside a `ScopedValue.where().run()` block the call throws
`NoSuchElementException`.

## The Problem

`ScopedValue` (Java 21+) is scope-bound: a value is only accessible inside
the `ScopedValue.where(KEY, value).run(task)` closure that bound it. Calling
`KEY.get()` outside that closure throws `NoSuchElementException`. The
`ScopedContextService.getCurrentUser()` method calls `USER_ID.get()` directly,
without guarding with `USER_ID.isBound()`. Under concurrent test runs some
threads may call this method from a context where no binding exists.

## How to Reproduce

1. Remove `@Disabled` from `testGetCurrentUser_concurrent_detectsUnboundGet`.
2. Run: `mvn test` or `./gradlew test`
3. The test fails with a **ScopedValueMisuseDetector** report listing unbound
   `get()` calls.

**Fix**: guard every `get()` call with `USER_ID.isBound()`, or restructure the
API so `getCurrentUser()` is only callable from within a bound scope.
