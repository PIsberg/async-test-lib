# Example 39 — CompletableFuture Completion Leak

Demonstrates **CompletableFutureCompletionLeakDetector**: `CompletableFuture`
objects are created and stored but `complete()` is never called. Any thread
waiting on them blocks forever.

## The Problem

`NotificationService.sendNotification()` creates a new `CompletableFuture<Void>`
for each notification, adds it to a list so callers can await delivery, but
then forgets to call `future.complete(null)`. Callers that do `future.get()`
hang indefinitely. Under concurrent load the list grows unboundedly and every
waiter is stuck.

## How to Reproduce

1. Remove `@Disabled` from `testNotify_concurrent_detectsCompletionLeak`.
2. Run: `mvn test` or `./gradlew test`
3. The test fails with a **CompletableFutureCompletionLeakDetector** report
   listing every future that was created but never completed within the
   observation window.

**Fix**: call `future.complete(null)` (or `future.completeExceptionally(ex)`)
after the notification is sent, or use `CompletableFuture.completedFuture(null)`
when no asynchronous handoff is needed.
