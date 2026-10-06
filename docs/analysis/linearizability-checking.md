# History-based (linearizability) checking: design note

Issue: #924. Status: experimental prototype in `OperationHistory` and `SequentialSpec`, 2026-10-06.

## The gap

The 146 detectors recognise known race shapes: a shared `SimpleDateFormat`, a check-then-act on a
`ConcurrentMap`, a lock taken in two orders. A data structure that returns a wrong answer without
matching any of those shapes passes. A concurrent counter whose increment is a read followed by a
write can hand two threads the same value, and unless the test author asserts on that exact
outcome, nothing in the run notices.

History-based checking asks a question that does not depend on knowing the bug's shape: can the
results the workers saw be explained by *some* order of their operations, one at a time, that
respects real time? If an operation finished before another started, it must come first. If no
such order exists, the object is not linearizable, and the history is the counterexample.

On the JVM the established tool is Lincheck, which is Kotlin-first and runs its own scenarios. This
note is about offering the check inside a plain `@AsyncTest`, where the runner already produces
the contention.

## What a test writes

```java
private static final OperationHistory<AtomicInteger> HISTORY = OperationHistory.of(AtomicInteger::new);

@AsyncTest(threads = 3, invocations = 200)
void increments() {
    AtomicInteger counter = HISTORY.subject();      // one fresh counter per round
    for (int i = 0; i < 2; i++) {
        HISTORY.call("increment", null, counter::incrementAndGet);
    }
}

@AfterAll
static void everyRoundIsLinearizable() {
    HISTORY.assertLinearizable(SequentialSpec.of(
            () -> new int[1],                       // the model's initial state
            state -> state.clone(),                 // a copy, so the search can backtrack
            (state, op, arg) -> ++state[0]));       // the operation applied sequentially
}
```

- **Recording.** `call(operation, argument, action)` takes a ticket from one global sequence before
  the action runs (the invocation) and another after it returns (the response), and stores the
  operation, the argument, the result and the calling thread under the current round. A shared
  `AtomicLong` gives a total order that is consistent with real time: if A's response ticket is
  lower than B's invocation ticket, A returned before B was called.
- **Rounds.** Each round is checked on its own, against a fresh model. Histories that span rounds
  would be long, and a round's subject is the unit the runner makes collide. `subject()` returns
  one instance per round, created on first use from the supplier. The round is the one the runner
  opens before it starts the round's workers (`AsyncTestContext.Round`, internal), compared by
  identity, so round 1 of one run is never round 1 of another.
- **The model.** `SequentialSpec<M>` gives an initial state, a copy of a state and the sequential
  effect of one operation (which may mutate the copy and returns the expected result). Results are
  compared with `Objects.equals`.

## The checker

Wing and Gong's search, with the usual memoisation:

1. Among the operations not yet placed, a candidate is one whose invocation comes before every
   unplaced operation's response: nothing that had already returned when it was called is still
   waiting to be placed.
2. Apply the candidate to a copy of the model state. If the model's result equals the recorded one,
   place it and recurse; otherwise try the next candidate.
3. Success when every completed operation is placed. An operation whose action threw has no
   response; it may be placed anywhere after its invocation, or left out, which is the standard
   treatment of a pending call.
4. A (placed set, model state) pair already proven to fail is not explored again. This needs the
   model state to implement `equals` and `hashCode`; arrays are compared by content.

The general problem is NP-complete, so two bounds keep it honest rather than slow:

- **History size.** A round may record at most 64 operations, and `call` refuses the 65th with a
  message naming the bound. Three threads with ten operations each is 30. The search's speed on
  such histories has not been measured separately; `LinearizabilityE2eTest`'s 30 rounds of six
  operations each, engine included, run in about a second.
- **Search budget.** At most one million states per round. A round whose search exhausts the budget
  is reported as undecided and fails the assertion: a check that could not decide never passes.

## The report

On failure, `assertLinearizable` throws an `AssertionError` for the first round with no
linearization, listing that round's operations in invocation order:

```
Round 17 is not linearizable: no order of its 6 operations that respects real time gives the
results the workers saw.
  [async-test-worker-0] increment() -> 1   (invoked #1031, returned #1036)
  [async-test-worker-1] increment() -> 1   (invoked #1032, returned #1035)
  ...
```

The tickets show which operations overlapped, so the reader can see why the two results of 1
cannot both be right: the two increments overlapped, and any order of them returns 1 then 2.

## Both directions (invariant 10)

`LinearizabilityCheckerTest` checks the search on hand-built histories (a sequential history
passes, a stale read after a completed write fails, a pending call can be placed or dropped, an
exhausted budget answers undecided rather than linearizable, more than 64 operations are refused),
and `LinearizabilityE2eTest` runs real `@AsyncTest` rounds:
`AtomicInteger.incrementAndGet` and `ConcurrentLinkedQueue` stay linearizable, and a counter whose
increment is a read, a `rendezvous()`, then a write fails on the first round, deterministically,
because the rendezvous makes every worker read the same value. Verified the gates can fail: letting
the search ignore real-time order turns three checker tests red, and handing every round the same
subject turns the atomic-counter case red ("Round 2 is not linearizable").

## What is left out of the prototype

- **No corpus subjects yet.** The issue asked for prototype cases drawn from the corpus; the tests
  use JDK subjects (`AtomicInteger`, `ConcurrentLinkedQueue`) and a hand-written lost update.
- **No search reduction by object.** P-compositionality (checking each key of a map separately)
  would let much longer histories through; it is a follow-up once the API settles.
- **No integration with `failOn` or the reports.** The check is an assertion the test calls, like
  `RunOutcomes`; findings do not flow through the detector pipeline.
- **No automatic scenario generation.** The test author chooses the operations, unlike Lincheck.
