# Example 56 — Lock Downgrade

Shows the incorrect `ReentrantReadWriteLock` downgrade, the correct one, and exactly what
`LockDowngradeDetector` will tell you about either. The short version of the last part: nothing.

## The Problem

Correct lock downgrade requires acquiring the read lock **before** releasing the write lock.
The wrong order is:

```java
writeLock.unlock();        // gap opens here: another thread can write
readLock.lock();           // too late: the value we just wrote may already be gone
```

The correct pattern is:

```java
readLock.lock();           // acquire read FIRST, while still holding write
writeLock.unlock();        // then release write, with no gap
```

`DataStore.updateAndRead` releases the write lock first, so another writer can modify the entry
before the current thread reads it back. `DataStore.updateAndReadFixed` does it in the right
order.

## What the detector reports

`LockDowngradeDetector` reports two things: the unsafe downgrade above, and the read-to-write
upgrade `ReentrantReadWriteLock` cannot grant.

The downgrade finding is **evidence-gated**, and that is the part worth understanding. "Released
the write lock, then acquired the read lock" is also what correct code produces when a thread
writes one thing and later reads something unrelated, and nothing in the recorded events
distinguishes the two. So the shape alone is not a finding. It becomes one when another thread was
seen taking the write lock inside the gap: a fact about this run, and the exact reason the
downgrade is unsafe. The cost is a false negative, a gap nobody happened to enter. See
[#355](https://github.com/PIsberg/async-test-lib/issues/355) for that decision.

Under `@AsyncTest`, the gap does get entered. A thread that has just released the write lock
blocks in `readLock().lock()` behind whichever of the other seven took it next, so its write
acquire is recorded before the read is. Measured on this example at `threads = 8,
invocations = 20`, three runs: 40, 45 and 44 of the 160 downgrade-shaped sequences had a writer
inside the gap.

For a while this example had **no demonstration at all**: it used to have one that recorded a
write-acquire, a write-release, a read-acquire and a read-release and told you that removing
`@Disabled` would show the bad downgrade detected. It did not, and could not, three runs out of
three. That was issue #346; the gap in the detector was #355, now closed, and the demonstration is
back.

The behaviour is pinned by ordinary `@Test` methods that run on every build:

| Test | What it pins |
|---|---|
| `testLockDowngradeDetector_readThenWriteOnOneThread_reports` | the upgrade |
| `testLockDowngradeDetector_correctDowngrade_isSilent` | the correct downgrade is not a finding, which is right |
| `testLockDowngradeDetector_incorrectDowngradeAlone_isSilent` | the shape with nobody in the gap is not a finding either, which is the deliberate false negative |
| `testLockDowngradeDetector_writerInsideTheGap_reports` | the finding, driven by hand so it does not depend on the scheduler |

## The store's own behaviour

| Test | What it shows |
|---|---|
| `test_singleThread_updateAndRead_works` | sequentially, the buggy downgrade reads back what it wrote every time |
| `test_updateAndReadFixed_returnsTheValueItWrote` | so does the correct one, which is why a sequential test cannot tell them apart |
| `test_readThenUpdate_neverGetsTheWriteLock` | the upgrade never succeeds; written with `lock()` instead of `tryLock()` it would never return |

## Running

```
mvn test
```

Or with Gradle:

```
./gradlew test
```
