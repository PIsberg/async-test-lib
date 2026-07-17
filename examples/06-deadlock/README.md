# Deadlock Example

This example demonstrates **detection of a classic A→B / B→A deadlock** in a banking
transfer service using the `DeadlockDetector`.

## The Problem

`BankTransferService.transfer(from, to, amount)` acquires the two account locks in
argument order. When two threads perform opposite-direction transfers simultaneously:

```
Thread 1: transfer(accountA, accountB, 100)  →  locks A, then waits for B
Thread 2: transfer(accountB, accountA, 100)  →  locks B, then waits for A
```

Neither thread can proceed — both are blocked forever waiting for a lock the other
thread holds. This is the textbook *circular-wait* deadlock condition.

## Why Sequential Tests Miss This Bug

```java
@Test
void testTransfer_reverseDirection_singleThread() {
    service.transfer(accountA, accountB, new BigDecimal("200.00"));
    service.transfer(accountB, accountA, new BigDecimal("200.00"));
    // ✅ Passes — transfers complete one at a time, never overlap
}
```

With a single thread, Transfer 1 completes fully before Transfer 2 begins. There is
never a moment when two threads each hold one lock and wait for the other — so the
deadlock condition never forms.

## How to Reproduce

### 1. Run with @Test (PASSES)

```bash
cd examples/06-deadlock
mvn clean test
# ✅ All @Test methods pass
```

### 2. Run with @AsyncTest (DEADLOCK DETECTED)

Remove `@Disabled` from `testTransfer_concurrent_detectsDeadlock()` and run:

```bash
mvn clean test
# Test times out after 5000 ms
# DeadlockDetector reports the circular lock dependency
```

Expected output:

```
=======================================================
   ASYNC-TEST DEADLOCK / TIMEOUT DETECTED
   ENHANCED THREAD DUMP WITH LOCK ANALYSIS
=======================================================

CRITICAL: Application threads are deadlocked

=== LOCK ANALYSIS ===

*** CIRCULAR DEADLOCK DETECTED ***
Deadlocked threads: [17, 18]

Thread-17 (async-test-thread-1):
  State: BLOCKED
  Waiting for lock: se.deversity.asynctest.example.service.BankTransferService$Account@...
  Lock held by: Thread-18
  -> Which is waiting for: se.deversity.asynctest.example.service.BankTransferService$Account@...
  Holds monitors:
    - BankTransferService$Account@...
```

## How the Detector Works

`DeadlockDetector` is fully automatic — no manual instrumentation is required in test
code. When the `@AsyncTest` times out, the framework calls:

```java
ThreadMXBean mxBean = ManagementFactory.getThreadMXBean();
long[] deadlockedThreadIds = mxBean.findDeadlockedThreads();
```

The JVM's `ThreadMXBean` tracks which monitor each thread is waiting to acquire and
which monitors it currently holds. When it finds a cycle in this wait-for graph, it
reports the deadlocked thread IDs.

`DeadlockDetector` then prints the full lock chain — each deadlocked thread, the lock
it holds, and the lock it is waiting for — making it easy to identify the code path
that needs to be fixed.

## The Fix

Always acquire locks in a consistent global order, regardless of argument order:

```java
// FIXED: acquire in ascending account-ID order
public void transferFixed(Account from, Account to, BigDecimal amount) {
    Account first  = from.id.compareTo(to.id) <= 0 ? from : to;
    Account second = first == from ? to : from;

    synchronized (first) {       // always the lower-ID account
        synchronized (second) {  // always the higher-ID account
            from.balance = from.balance.subtract(amount);
            to.balance   = to.balance.add(amount);
        }
    }
}
```

With this fix, both Thread 1 and Thread 2 compete for `ACC-001` first. One of them
wins and proceeds to acquire `ACC-002` — the other waits only for `ACC-001`, which
the winner will release after completing. The circular-wait condition is impossible.

## Files in This Example

- **`BankTransferService.java`** — Buggy service with argument-order locking + fixed version
- **`BankTransferServiceTest.java`** — Sequential `@Test` methods that pass + `@AsyncTest`
  that triggers DeadlockDetector
- **`pom.xml`** — Maven dependencies (JUnit 5 + async-test-lib 1.7.0-RC2)

## Key Takeaways

1. **Sequential tests cannot catch deadlocks**: the circular-wait condition requires
   two threads to be in-flight simultaneously — something a single `@Test` thread
   can never produce.
2. **DeadlockDetector is zero-overhead until a timeout fires**: it only queries
   `ThreadMXBean` when the test does not complete within `timeoutMs`, so it adds
   no cost to healthy test runs.
3. **Lock-ordering is the safest fix**: compared to `tryLock()` with timeouts,
   consistent lock ordering prevents deadlocks structurally rather than recovering
   from them reactively.
