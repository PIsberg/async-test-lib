# Example 28 — Broken Double-Checked Locking

## The Problem

`ConfigurationSingleton.getInstance()` implements the double-checked locking
(DCL) pattern to avoid the overhead of synchronization on every call:

```java
private static ConfigurationSingleton instance; // BUG: not volatile

public static ConfigurationSingleton getInstance() {
    if (instance == null) {                         // unsynchronized outer check
        synchronized (ConfigurationSingleton.class) {
            if (instance == null) {
                instance = new ConfigurationSingleton(); // non-volatile write
            }
        }
    }
    return instance;
}
```

Without `volatile` on the `instance` field, the JIT compiler and CPU are
permitted to reorder the constructor call and the assignment. Thread A may
write a non-null reference to `instance` **before** all field writes inside
the constructor are visible to other threads. Thread B passes the outer null
check and returns a partially-constructed object.

## Why This Happens

The Java Memory Model (JMM) allows compilers and processors to reorder
memory operations as long as the reordering is not visible within a single
thread. The assignment `instance = new ConfigurationSingleton()` is
logically three steps:

1. Allocate memory.
2. Initialize fields (constructor).
3. Write the reference to `instance`.

Steps 2 and 3 can be reordered in the absence of a happens-before relationship.
`volatile` establishes that relationship: any write to a `volatile` field
happens-before any subsequent read of that field.

This is not theoretical — the pattern produced real production bugs on
pre-Java-5 JVMs and remains formally broken without `volatile` even today.

## How to Reproduce

1. Open `ConfigurationSingletonTest`.
2. Remove `@Disabled` from `testGetInstance_concurrent_detectsBrokenDCL`.
3. Run the test.

`LazyInitValidator` will report:

```
LAZY INITIALIZATION ISSUES DETECTED:
  - instance: lazy init observed from 8 threads without volatile/synchronization
  Fix: guard initialization with synchronization, holder class, or volatile DCL
```

## The Solution

**Option A — add `volatile` (minimal change):**

```java
private static volatile ConfigurationSingleton instance; // fixed
```

**Option B — initialization-on-demand holder (preferred):**

```java
private ConfigurationSingleton() { /* ... */ }

private static class Holder {
    static final ConfigurationSingleton INSTANCE = new ConfigurationSingleton();
}

public static ConfigurationSingleton getInstance() {
    return Holder.INSTANCE;
}
```

The class loader guarantees that `Holder.INSTANCE` is initialized exactly once
and that the initialization is safely published to all threads. No `volatile`,
no `synchronized` needed on the accessor.

**Option C — eager initialization (simplest):**

```java
private static final ConfigurationSingleton INSTANCE = new ConfigurationSingleton();
```

## Key Takeaways

- DCL without `volatile` is a well-documented broken pattern in Java. Always
  declare the lazily-initialized field `volatile` when using DCL.
- The initialization-on-demand holder idiom is safer and equally efficient —
  prefer it over DCL.
- The bug is invisible in single-threaded tests and under low concurrency; it
  only surfaces under high thread counts or on weakly-ordered hardware.
- The `LazyInitValidator` detects the pattern by observing concurrent accesses
  where some threads see null and others see an initialized value without any
  happens-before synchronization.
