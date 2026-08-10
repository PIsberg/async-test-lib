# Example 131 — VarHandle Non-Atomic Update

**Detector**: `VarHandleNonAtomicUpdateDetector` (`DetectorType.VAR_HANDLE_NON_ATOMIC_UPDATE`, also usable standalone)

## The Problem

Someone reaches for `VarHandle` because `AtomicInteger` allocates and the counter is on the
hot path. That reasoning is sound. The operation they pick is not:

```java
int current = (int) COUNT.getVolatile(this);
COUNT.setVolatile(this, current + 1);        // two operations, not one
```

Both halves are volatile, so it reads as atomic. It is not. A volatile read and a volatile
write are each *individually* indivisible, and the gap between them is wide open. Two threads
read 5, both write 6, and one increment vanishes.

**Volatile buys visibility. It never buys atomicity of a read-modify-write.** That single
sentence is the whole bug, and it survives review routinely because "volatile" appears twice
in the code.

## The Fix

`VarHandle` already has the operation the author wanted, at the cost they wanted:

```java
COUNT.getAndAdd(this, 1);                     // one indivisible operation
```

When the new value is not a simple sum, a CAS retry loop keeps it indivisible — the write only
lands if nobody moved the value since the read:

```java
int current, next;
do {
    current = (int) COUNT.getVolatile(this);
    next = Math.min(current + 1, ceiling);
} while (!COUNT.compareAndSet(this, current, next));
```

## A second, different bug

Plain-mode accessors (`get` / `set`) carry **no memory-ordering guarantee at all**. A plain
write may never become visible to a reader on another thread — not late, not eventually,
possibly never, because the compiler is free to hoist the read out of a loop.

The detector reports that separately from the lost update, and it should: the fix is not an
atomic operation, it is volatile mode.

## How to Detect

```java
var d = new VarHandleNonAtomicUpdateDetector();
d.recordGet(COUNT, holder, "count", Mode.VOLATILE, t);
d.recordSet(COUNT, holder, "count", Mode.VOLATILE, t);   // get-then-set → flagged
assertTrue(d.analyze().hasIssues());
```

| Recorded sequence | Verdict |
|---|---|
| `recordAtomicUpdate` (`getAndAdd`, `compareAndSet`) | silent — indivisible |
| `recordGet` then `recordSet` on one field | flagged — lost update |
| `Mode.PLAIN` set and get from two threads | flagged — visibility, reported separately |

`Mode` is `VarHandleNonAtomicUpdateDetector.Mode`: `PLAIN`, `OPAQUE`, `ACQUIRE_RELEASE`,
`VOLATILE`. Passing the mode is what lets the detector tell a lost update apart from a
visibility bug rather than lumping both into one finding.

Inside `@AsyncTest`, select it with `includes = { DetectorType.VAR_HANDLE_NON_ATOMIC_UPDATE }`.
Siblings: `ATOMIC_NON_ATOMIC_UPDATE` for the same mistake on `AtomicInteger`
([example 99](../99-atomic-non-atomic/)), and `HIGH_CONTENTION_ATOMIC`
([example 125](../125-high-contention-atomic/)) for when the atomic operation is correct but
the contention is the problem.

See [`RateLimiterTest`](src/test/java/se/deversity/asynctest/example/RateLimiterTest.java) for
the atomic / lost-update / plain-mode walkthrough, plus a four-thread run showing the racy
counter finishing short while `getAndAdd` does not.

## Running

```bash
mvn -f ../../pom.xml install -DskipTests -Dlicense.mock.mode=true
mvn -f pom.xml test
```
