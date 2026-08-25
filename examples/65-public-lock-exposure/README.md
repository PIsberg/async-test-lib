# Example 65 — Public Lock Exposure

Demonstrates **PublicLockExposureDetector**: a class that guards its state with `synchronized`
instance methods, and hands that same object to anybody who asks. The lock and the public API are
the same reference, so external code can take it.

## The Problem

`SharedResourceManager` synchronizes every method that touches its state, which means the monitor
is `this`. `SharedResourceManager.forResource(name)` then hands `this` to callers.

A caller who needs two of the manager's operations to be atomic will write:

```java
synchronized (manager) {          // it works, which is the trap
    if (manager.getResourceValue() < limit) {
        manager.accessResource();
    }
}
```

and from that moment an unrelated piece of code decides how long every other thread waits inside
`accessResource()`. If that caller also holds another lock, the deadlock belongs to whoever gets
paged.

`testExternalCaller_canHoldTheManagersOwnLock` pins this half with no detector involved: a thread
calling `accessResource()` is still blocked 150ms later, because the test took the manager's lock
from outside the class.

## How the Detector Is Fed

`PublicLockExposureDetector` reports the **intersection** of two sets, matched by object identity:
objects used as a lock, and objects published to external code. Neither on its own is a finding,
and neither should be.

That intersection is what this example used to miss. It recorded a `ReentrantLock` as published
and the manager as synchronized-upon: two different objects, empty intersection, empty report,
three runs out of three. That is issue #346. Both hooks now report the same instance, which is
also the honest shape, since the object that is the lock is the object that gets handed out.

`SharedResourceManager.observePublication` and `observeLocking` are the seams; both default to
no-ops, so the production path never touches the test library.

## How to Reproduce

1. Remove `@Disabled` from `testAccessResource_concurrent_detectsExposedLock`.
2. Run: `mvn test` or `./gradlew test`
3. The test fails:

```
PUBLIC LOCK EXPOSURE DETECTED:
  - SharedResourceManager uses synchronized(this) but is publicly exposed via
    returned from SharedResourceManager.forResource(...) — external callers can acquire
    its lock, causing unintended coupling or deadlock
```

`failOn = FailOn.LOW` is what turns that report into a failed run.

## The Fix

Guard the state with a lock nobody else can name:

```java
private final Object lock = new Object();

public int accessResource() {
    synchronized (lock) {
        return ++resourceValue;
    }
}
```

External code can still call the methods. It can no longer take the lock, so the class keeps
control of its own synchronization. Callers that genuinely need a compound operation to be atomic
should be given one, as a method on the class, rather than a lock to borrow.
