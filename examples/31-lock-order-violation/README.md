# Example 31 — Lock Order Violation / Transfer Deadlock

## The Problem

`FundsTransferService.transfer(from, to, amount)` holds both account locks
simultaneously to prevent any other thread from touching either account
during the transfer. It always acquires the source lock first:

```java
synchronized (from.lock()) {
    synchronized (to.lock()) {
        performLedgerUpdate(from.id(), to.id(), amount);
    }
}
```

When Thread A transfers `account1 → account2` and Thread B transfers
`account2 → account1` concurrently:

```
Thread A: lock(account1.lock) ─── waiting for lock(account2.lock)
                                                     ↑
Thread B: lock(account2.lock) ─── waiting for lock(account1.lock)
              ↑ deadlock — neither thread can proceed
```

## Why This Happens

This is a textbook **lock-order deadlock** caused by a circular dependency
in the lock acquisition graph:

- `account1.lock → account2.lock` (Thread A's order)
- `account2.lock → account1.lock` (Thread B's order)

Together these form a cycle. Whenever you hold multiple locks you must ensure
that **every thread acquires them in the same global order**. Any cycle in
the acquisition graph is a potential deadlock.

## How to Reproduce

1. Open `FundsTransferServiceTest`.
2. Remove `@Disabled` from `testTransfer_concurrent_detectsLockOrderViolation`.
3. Run the test.

`LockOrderValidator` will report:

```
LOCK ORDERING VIOLATIONS DETECTED:

Inconsistent lock acquisition orders:
  - Lock pair {Object@<hash1>, Object@<hash2>} acquired in different orders:
    [Object@<hash1> -> Object@<hash2>, Object@<hash2> -> Object@<hash1>]

Potential deadlock cycles in lock graph:
  - Object@<hash1>
```

## The Solution

Impose a **total ordering** on all lock acquisitions. Always acquire the
account with the lexicographically smaller ID first, regardless of which
direction the transfer is flowing:

```java
public void transfer(Account from, Account to, long amount) {
    Account first  = from.id().compareTo(to.id()) < 0 ? from : to;
    Account second = from.id().compareTo(to.id()) < 0 ? to   : from;

    synchronized (first.lock()) {
        synchronized (second.lock()) {
            performLedgerUpdate(from.id(), to.id(), amount);
        }
    }
}
```

With this fix:
- `transfer(account1, account2)` locks `account1` first (ID "ACC-001" < "ACC-002")
- `transfer(account2, account1)` also locks `account1` first (same canonical order)
- No cycle in the lock graph — no deadlock possible

## Key Takeaways

- Whenever you acquire two or more locks simultaneously, you **must** acquire
  them in a globally consistent order. Any inconsistency is a latent deadlock.
- Deadlocks caused by lock-order violations are notoriously hard to reproduce
  in tests — they require a precise interleaving that only occurs under load.
- `LockOrderValidator` detects the inconsistency **without** requiring an actual
  deadlock to occur by analyzing the lock acquisition sequences recorded across
  all threads.
- Common fix strategies: canonical ordering (ID-based), lock hierarchies
  (never acquire child lock without holding parent), or timeout-based
  `tryLock()` as a deadlock-avoidance fallback.
