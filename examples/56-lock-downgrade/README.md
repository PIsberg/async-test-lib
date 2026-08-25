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

`LockDowngradeDetector` reports **one** thing: a thread acquiring the write lock while it already
holds the read lock, which is the upgrade `ReentrantReadWriteLock` cannot grant. It reports
nothing about downgrades, correct or incorrect. Its own class javadoc lists the correct downgrade
as "not flagged" and does not mention the incorrect one at all.

So this example has **no `@Disabled` `@AsyncTest` demonstration**, and that is deliberate. It used
to have one, which recorded a write-acquire, a write-release, a read-acquire and a read-release
and told you that removing `@Disabled` would show the bad downgrade detected. Enabling it produced
no report, three runs out of three, and the test passed. That is issue #346; the gap in the
detector is issue [#355](https://github.com/PIsberg/async-test-lib/issues/355).

Rather than change this example's subject to an upgrade, which is
[example 111](../111-lock-upgrade-deadlock/) with its own detector, the detector's real behaviour
is pinned by ordinary `@Test` methods that run on every build:

| Test | What it pins |
|---|---|
| `testLockDowngradeDetector_readThenWriteOnOneThread_reports` | the upgrade, the one finding it produces |
| `testLockDowngradeDetector_correctDowngrade_isSilent` | the correct downgrade is not a finding, which is right |
| `testLockDowngradeDetector_incorrectDowngrade_isAlsoSilent` | the incorrect downgrade is not a finding either, which is the gap |

The last one exists to pin the gap, not to bless it. If #355 is fixed, that assertion flips, and
flipping it is the point.

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
