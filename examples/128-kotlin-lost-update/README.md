# Kotlin: a lost update

`@AsyncTest` from Kotlin. There is no Kotlin adapter, no extra dependency and no configuration:
the annotation is a JUnit 5 `@TestTemplate`, so it works from any language that produces JUnit 5
test classes.

```kotlin
@AsyncTest(threads = 8, invocations = 200, detectAll = true)
fun concurrentIncrementsLoseUpdates() {
    counter.record()
}
```

The attributes read as named arguments, which is the only visible difference from the Java form.

## What it shows

`InventoryCounter.count` is a `var` property incremented with `count++`. Kotlin's defaults —
immutability where you ask for it, null safety everywhere — create a general impression that the
language handles this sort of thing. It does not handle this one. `count++` is a read, an add and a
write, and two threads interleaving those three steps lose an increment.

The test class runs the same body three ways:

| Test | Result | What it tells you |
|---|---|---|
| `sequentialIncrementsAreFine` | passes | nothing — one thread cannot race itself |
| `concurrentIncrementsLoseUpdates` | fails by design | the count lands below 8 x 200 and the report names the field |
| `atomicIncrementsSurviveContention` | passes | `AtomicInteger` is the fix, and it holds under the same contention |

The middle test carries `@Disabled` so this module's build stays green. Remove it to watch the
failure.

## Run it

```bash
mvn test -Dlicense.mock.mode=true
```

The mock flag is how you run without a licence key while evaluating; see
[docs/LICENSING.md](../../docs/LICENSING.md). This example's POM sets it already.

When the disabled test is enabled and fails, the output carries a line like:

```
[AsyncTest] Failure with replaySeed=8134729471193L — paste into @AsyncTest(replaySeed=...) to reproduce.
```

Pasting that seed back into the annotation reruns the same interleaving, which is the difference
between a flaky failure and a reproducible one.

## Note on `@Volatile`

Marking `count` as `@Volatile` is the usual first attempt and it does not work. Volatile fixes
visibility: it guarantees a reader sees the latest write. It does nothing about atomicity, and this
bug is an atomicity bug — the increment is three operations, and volatile does not make them one.
`AtomicInteger`, or a lock, or `LongAdder` under heavy contention.
