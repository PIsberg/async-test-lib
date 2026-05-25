# Example 81 — Synchronized on Non-Final Field

**Detector**: `SynchronizedNonFinalDetector`  
**Flag**: `detectSynchronizedNonFinal = true`

## The Problem

`LockableCache` uses `synchronized(lockObject)` for its critical sections, but
`lockObject` is a non-final field. If `reassignLock()` is called while another
thread is blocked on `synchronized(lockObject)`, the two threads end up
synchronizing on different objects — the old and new `Object` instances — and
can both enter the critical section simultaneously, defeating mutual exclusion.

This is a subtle bug: the code looks like it is using proper locking but the
lock identity silently changes at runtime.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsNonFinalLock`
and run the test. `SynchronizedNonFinalDetector` records the monitor object
passed to `recordLockObject()` per invocation, detects when different object
instances are used for the same field ID, and flags the violation.

## The Fix

Declare `lockObject` as `private final Object lockObject = new Object()`. A
final field can never be reassigned, guaranteeing all threads always synchronize
on the same monitor.
