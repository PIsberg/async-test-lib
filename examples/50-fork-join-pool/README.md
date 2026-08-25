# Example 50 — A Forked Task That Is Never Joined

Demonstrates **ForkJoinPoolDetector** catching a `fork()` with no matching `join()`: the forked
task runs, its result goes nowhere, and the caller gets a plausible answer built from half the
data.

## The Problem

`ParallelSorter.sort()` is a fork/join merge sort. At every split it forks the left half and
then computes the right half in place, and never joins the left one:

```java
left.fork();
List<Integer> rightResult = right.compute();
return rightResult;              // the left half is simply gone
```

The abandoned task still runs. Nobody collects its result, so the merge is done with what is
left, and the caller gets a **sorted** list that is missing elements. There is no exception and
no warning; an assertion that only checks ordering stays green.

The second cost is worse and quieter. An exception thrown inside a forked task surfaces at
`join()`. With no `join()`, it surfaces nowhere.

## What this detector models, and what it does not

`ForkJoinPoolDetector` reports **fork without join** and **exceptions in forked tasks**. It does
not report blocking, and it does not report pool starvation. Its own javadoc records that two
such claims were removed because nothing in the analysis could produce them.

It also does **not infer** the fork/join imbalance from the counts it collects, deliberately: a
test that ends mid-computation would show an imbalance without a defect, and this library cannot
afford that kind of finding. So the code that abandons the task is the only thing that can report
it, through `recordForkWithoutJoin`. `ParallelSorter.observeForkJoin` is the seam; the hooks
default to no-ops, so the production path never touches the test library.

Before issue #346 this example demonstrated a `Thread.sleep()` inside a common-pool task and
recorded balanced fork/join pairs plus a task duration, none of which this detector reports on.
Enabling it produced no output at all. Blocking inside a ForkJoin worker is a real bug with a real
detector, and it is [example 51](../51-fork-join-task-blocking/).

## How to Reproduce

1. Open `ParallelSorterTest.java`.
2. Remove the `@Disabled` annotation from `testSort_concurrent_detectsForkWithoutJoin`.
3. Run the test:

```
FORKJOINPOOL ISSUES DETECTED:
  Tasks Forked But Not Joined:
    - common-pool:sort-left
  Problem: Forked tasks must be joined to get results and exceptions
```

`failOn = FailOn.LOW` is what turns that report into a failed run.

You do not need the detector to see the damage, though. `testSort_forkWithoutJoin_losesData` runs
on every build and shows five elements going in and fewer coming out.

## The Fix

Match every `fork()` with a `join()`, on every path:

```java
left.fork();
List<Integer> rightResult = right.compute();
List<Integer> leftResult = left.join();      // the missing line
return merge(leftResult, rightResult);
```

`ParallelSorter.sortFixed()` is that method. If a task is genuinely fire-and-forget, it does not
belong on a `ForkJoinPool`: submit it to an executor whose results you are prepared to ignore, and
attach an exception handler so failures still reach somebody.
