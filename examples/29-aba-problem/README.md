# Example 29 — ABA Problem in a Lock-Free Stack

## The Problem

`LockFreeStack.pop()` uses a CAS loop that reads the current head and swaps
it with `head.next`. The vulnerability is the window between the read and the
CAS where another thread can change the stack and change it back:

```
Thread A reads:  head = NodeX (NodeX.next = NodeY)

Thread B executes:
  pop()  → head = NodeY
  pop()  → head = null
  push(NodeX) → head = NodeX  (NodeX is reused, but NodeX.next is now null)

Thread A executes:
  CAS(head, NodeX, NodeX.next)
  → head == NodeX  ✓  CAS succeeds
  → sets head = null (NodeX.next was null after B recycled NodeX)
  → NodeY is permanently lost from the stack
```

## Why This Happens

`AtomicReference.compareAndSet()` compares by **object identity** (or
`equals()`). It cannot detect that the object at the expected address was
removed, replaced with something else, and then replaced back. From the CAS's
perspective, the value is still the expected one — even if the object's internal
state has changed between the read and the swap.

This class of bug is called the **ABA problem** and is particularly dangerous
in lock-free data structures backed by node pools or allocators that recycle
memory addresses.

## Why this stack has a free list

A stack that allocates `new Node<>(value)` on every push **cannot** produce ABA in Java. The
popped node is garbage, and the next push returns a reference that has never been head, so a
stale CAS fails and retries, which is correct behaviour. ABA needs the same node to come back,
and in a managed language the usual reason it comes back is an allocation-avoidance pool.

So `LockFreeStack` has one: `pop()` returns the node it removed to a `freeList`, and `push()`
takes from there before allocating. That is the line between a demonstration and a story about
C, and `testPop_recyclesTheNode_soPushHandsTheSameOneBack` pins that the node really does come
back.

## How to Reproduce

1. Open `LockFreeStackTest`.
2. Remove `@Disabled` from `testPop_concurrent_detectsABAProblem`.
3. Run the test.

`ABAProblemDetector` will report:

```
ABA PROBLEM DETECTED:

Variables with A->B->A cycles:
  - head: 71 cycles detected

CAS operations that succeeded despite ABA:
  - head: CAS succeeded despite ABA (expected LockFreeStack$Node@674c583e, set to null)

Why: An ABA race occurs when a location holds value A, is changed to B, then
     changed back to A before a competing CAS reads it ...
Fix: Use AtomicStampedReference<V> ...
```

`failOn = FailOn.LOW` turns that report into a failed run.

## How the Detector Is Fed

`ABAProblemDetector` is **recording-fed**: it reasons about a history of values, so it has to be
told what the head was and what it became at each successful CAS. `LockFreeStack.observeHead`
reports that from inside `push()` and `pop()`; the hooks default to no-ops, so the production
path never touches the test library.

The detector also has to be **the one the run owns**, from `AsyncTestContext.abaProblemDetector()`.
Before issue #346 this demonstration wrote out three `recordValueChange` calls and one
`recordCASAttempt` by hand into a locally constructed detector and asserted on the result, without
touching `LockFreeStack` at all. It proved that the detector recognises a cycle it was handed,
which was never in doubt, and told the library nothing, so `failOn` had no finding to gate on and
enabling the demonstration left it green.

## The Solution

Replace `AtomicReference<Node<T>>` with `AtomicStampedReference<Node<T>>`.
Each mutation increments a version stamp, so an A → B → A cycle produces
stamp 0 → 1 → 2. A stale CAS comparing stamp=0 will fail even though the
reference matches:

```java
// Before (ABA-vulnerable)
private final AtomicReference<Node<T>> head = new AtomicReference<>(null);

// After (ABA-safe)
private final AtomicStampedReference<Node<T>> head =
        new AtomicStampedReference<>(null, 0);

public T pop() {
    int[] stampHolder = new int[1];
    Node<T> observed;
    Node<T> next;
    int stamp;
    do {
        observed = head.get(stampHolder);
        stamp = stampHolder[0];
        if (observed == null) return null;
        next = observed.next;
    } while (!head.compareAndSet(observed, next, stamp, stamp + 1));
    return observed.value;
}
```

## Key Takeaways

- `AtomicReference.compareAndSet()` only checks **identity** — it cannot
  detect that a reference was removed and re-added between read and CAS.
- The ABA problem silently corrupts data structures. It produces no exception,
  no error log — only subtle data loss or incorrect state.
- Always use `AtomicStampedReference` (integer version) or
  `AtomicMarkableReference` (boolean mark) when recycling objects in
  lock-free data structures.
- `ABAProblemDetector` identifies A → B → A cycles in recorded value-change
  history and flags CAS operations that succeeded despite such a cycle.
