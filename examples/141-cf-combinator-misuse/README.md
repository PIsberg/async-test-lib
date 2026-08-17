# 141 — CompletableFuture combinator misuse

**Detector**: `CompletableFutureCombinatorMisuseDetector` (`DetectorType.COMPLETABLE_FUTURE_COMBINATOR_MISUSE`) · **Severity**: 🔴 High

## The bug

```java
CompletableFuture.allOf(row, audit, index);   // BUG: called for its side effect
return "order written";                       // ... of which it has none
```

`allOf` waits for nothing. It builds a new future that completes when the group does, and that
future is the only thing in the program that knows. Discard it and the method returns while two
of the three writes are still in flight.

Reading it without waiting has the same effect:

```java
all.getNow(null);      // BUG: returns instantly whatever the group is doing
if (all.isDone()) { ... }
```

And `anyOf` loses failures. Once the fast replica answers, the slow one's
`completeExceptionally` has no handler left to reach:

```java
CompletableFuture.anyOf(fast, slow).join();   // slow's corruption error is never seen
```

## The fix

```java
CompletableFuture.allOf(row, audit, index).join();          // FIX: actually wait

// or, better, keep it in the pipeline:
allOf(row, audit, index).thenApply(v -> "order written");
```

For `anyOf`, give every constituent somewhere to report:

```java
slow.whenComplete((v, err) -> { if (err != null) log.warn("replica failed", err); });
```

## What the detector observes

Register the combinator with its arity, its constituents as they complete, and each read.
Three findings, each tied to something observed rather than to the mere use of a combinator:

- 🔴 **High** — the combined future was never awaited **and** constituents were still
  outstanding when the run ended. A dropped combinator whose constituents all happened to
  finish lost nothing, and is not reported.
- 🔴 **High** — the group was read with `getNow`/`isDone` at a point when fewer constituents had
  completed than the combinator was given.
- 🟡 **Medium** — an `anyOf` constituent failed after the combined future had already been read.

## Run it

```bash
mvn test                 # or: gradle test
```
