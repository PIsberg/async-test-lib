# 139 — CompletableFuture completion race

**Detector**: `CompletableFutureCompletionRaceDetector` (`DetectorType.COMPLETABLE_FUTURE_COMPLETION_RACE`) · **Severity**: 🔴 High

## The bug

```java
CompletableFuture<String> quote = new CompletableFuture<>();

for (String provider : providers) {
    pool.execute(() -> quote.complete(quoteFrom(provider)));   // BUG: one slot, N publishers
}
```

`complete()` is first-writer-wins. It returns `false` for every later caller, and that boolean
is the only record that a result was thrown away. Two of three quotes vanish here, and the code
cannot say which one it kept.

The expensive version is a provider reporting a failure:

```java
quote.completeExceptionally(new IllegalStateException("backend unavailable"));
```

If a successful provider got there first, the outage is discarded and the caller sees a clean
success built on a partial fan-out.

## The fix

```java
List<CompletableFuture<String>> slots = providers.stream()
        .map(p -> supplyAsync(() -> quoteFrom(p), pool))    // FIX: one future per provider
        .toList();

allOf(slots.toArray(CompletableFuture[]::new)).join();
String best = cheapestOf(slots.stream().map(CompletableFuture::join).toList());
```

Nothing competes for a single slot, so nothing is dropped. Where a race genuinely is intended —
"first answer wins", a cache fill — read the boolean and route the losers somewhere instead of
discarding them.

## What the detector observes

Complete through `detector.complete(future, label, value)` (or record what your own
`complete()` call returned) and it reports the attempts that **lost**. That is the whole
criterion, which is why a slot completed by exactly one thread is silent: this is a fact about
what happened, not an inference from the shape of the code.

Severity follows what was lost. A discarded exception, or a value different from the winner's,
is 🔴 High. A loser carrying the same value the winner published is 🟡 Medium — still a race,
but this run lost nothing observable.

## Run it

```bash
mvn test                 # or: gradle test
```
