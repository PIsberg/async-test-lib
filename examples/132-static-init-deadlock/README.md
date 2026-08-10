# Example 132 — Static Initializer Deadlock

**Detector**: `StaticInitDeadlockDetector` (`DetectorType.STATIC_INIT_DEADLOCK`, also usable standalone)

## The Problem

Two classes whose static initializers reference each other:

```java
public final class Config {
    public static final String ENDPOINT = Registry.lookup("endpoint");
}

public final class Registry {
    public static final String DESCRIPTION = "registry for " + Config.ENDPOINT;
}
```

Read either file alone and it is unremarkable. Neither mentions threads, locks, or
concurrency. The cycle exists only across the pair, plus two threads arriving at the same
moment — which is why it passes review and then hangs in production.

JLS 12.4.2: the first thread to touch a class takes that class's **initialization lock** and
runs `<clinit>`. Any other thread touching the same class blocks until it finishes.

```
thread-a: touches Config   → holds Config's init lock   → needs Registry
thread-b: touches Registry → holds Registry's init lock → needs Config
```

Circular wait. Neither proceeds.

## Why this needs its own detector

Class initialization locks **are not monitors**.

- They do not appear in `ThreadMXBean.findDeadlockedThreads()`.
- A thread dump shows the threads merely as parked, with no lock edge between them.
- The JVM's own deadlock detection reports nothing at all.

`DeadlockDetector`, which finds ordinary monitor cycles through `ThreadMXBean`, is blind to
this by construction. Only a detector that tracks `<clinit>` entry and cross-requests can see
it. That gap is the entire reason this detector exists.

Single-threaded startup runs the cycle cleanly every time — a thread already inside
`Config.<clinit>` that re-enters `Config` proceeds immediately per the JLS, reading the
not-yet-assigned field as `null`. So the process starts fine for months. The first request
that arrives concurrently with a warm-up thread hangs it, and the thread dump is empty.

## The Fix

Break the cycle rather than reorder it. Reordering only moves which thread wins the race.

**Defer through a holder class.** No thread is inside `Config.<clinit>` while it needs
`Registry`, so the cycle never forms:

```java
public static final class LazyEndpoint {
    private static final class Holder {
        static final String VALUE = Registry.lookup("endpoint");
    }
    public static String value() { return Holder.VALUE; }
}
```

**Or move the shared constant into a third class** that neither initializer calls back into.

The general shape to aim for: a static initializer that calls into no class which can call
back.

## How to Detect

```java
var d = new StaticInitDeadlockDetector();
d.recordInitStart(Config.class,   threadA);
d.recordInitStart(Registry.class, threadB);
d.recordInitRequest(Registry.class, threadA);
d.recordInitRequest(Config.class,   threadB);
assertTrue(d.analyze().hasIssues());          // cycle: Config ↔ Registry
```

`recordInitEnd` clears the waits an initializer was blocking, so a completed `<clinit>`
releases the threads queued behind it and the finding disappears.

Inside `@AsyncTest`, select it with `includes = { DetectorType.STATIC_INIT_DEADLOCK }`.
Sibling: `DEADLOCKS` ([example 06](../06-deadlock/)) for ordinary monitor cycles, which is the
detector people reach for here and which cannot help.

### Why the test does not actually deadlock

Reproducing this for real would hang the JVM with no safe timeout — once two threads are stuck
in the cycle, both classes stay permanently uninitializable in that classloader and the test
JVM has to be killed. So the test drives the detector with the same event sequence the JVM
generates, exactly as the library's own unit tests do, and then loads the classes for real on
a single thread to show the path that always works.

See [`StaticInitDeadlockTest`](src/test/java/se/deversity/asynctest/example/StaticInitDeadlockTest.java).

## Running

```bash
mvn -f ../../pom.xml install -DskipTests -Dlicense.mock.mode=true
mvn -f pom.xml test
```
