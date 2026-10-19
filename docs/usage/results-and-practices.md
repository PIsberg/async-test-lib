# Analyzing results and best practices

Part of the [Usage guide](../USAGE.md).

## Analyzing Results

### First, know what kind of finding you are reading

Not every detector makes the same kind of claim. Some can tell broken code from the correctly
synchronized version of that same code and are safe to fail a build on. Others only observe that an
object was touched by two threads, which correct code does too — those are a prompt to check your
synchronization, not a verdict. Which is which is measured, not asserted:
[DETECTOR_CATALOG.md § Trust tiers](../DETECTOR_CATALOG.md#trust-tiers).

Start with `failOn = CRITICAL`, which gates on the trustworthy end of the scale.

The console follows the same ranking. Each report opens with a banner naming its detector and tier
(`trust=VERDICT`, or a span such as `trust=PROMPT..VERDICT` when a detector grades its findings one
by one, followed by one line per graded finding). On a passing run, VERDICT and FACT reports print
in full, and each PROMPT or ADVISORY report prints as a single line:

```
[AsyncTest] RecordMutableComponentLeakDetector trust=PROMPT findings=1: structural risk in a shared record: order
[AsyncTest] 1 PROMPT/ADVISORY report(s) shown as one line each; rerun with -Dasync-test.report.full=true to print them in full
```

The count and the headline are the report's findings, from its graded findings or else its
structured ones, never its "Why" or "Fix" bullets; only a third-party report that keeps neither is
counted by its bullets.

Pass `-Dasync-test.report.full=true` to print every report in full. A report that fails the build
always prints in full, and so does every report of a test that failed or timed out, because there
a prompt is a candidate cause. Listeners, and the JSON, JUnit XML and SARIF output, receive the full
text either way.

### Adopting into an existing suite

An established codebase will produce findings the first time `detectAll` runs, and a gate that is
red from the first commit gets switched off. Record what you already have, gate on what is new:

```bash
mvn test -Dasync-test.baseline=async-test-baseline.txt -Dasync-test.baseline.update=true  # once
mvn test -Dasync-test.baseline=async-test-baseline.txt                                    # thereafter
```

Commit the file and review its diff. Full mechanics, including what a baseline does not suppress:
[CI_INTEGRATION.md](../CI_INTEGRATION.md#adopting-into-a-codebase-that-already-has-findings).

### Reproducing a failure

Every failing run prints the seed of the failing round:

```
[AsyncTest] Failure with replaySeed=8134729471193L — paste into @AsyncTest(replaySeed=...) to replay the seed's random choices; add controlledSchedule = true to replay the interleaving too.
```

On its own the seed replays what the body draws from it (`AsyncTestContext.replaySeed()`, drawn
scenarios), not the thread schedule: the OS decides which worker runs when, so a pasted seed can
still pass. Under `@AsyncTest(controlledSchedule = true)` the seed decides that too. Workers run one
at a time and change hands only at scheduling points (every field access the agent weaves with
`fields=true`, every collection and atomic call it substitutes, and every
`AsyncTestContext.schedulePoint()`), so the line reads

```
[AsyncTest] Failure with replaySeed=8134729471193L — paste into @AsyncTest(replaySeed=..., controlledSchedule = true) to replay this interleaving.
```

and pasting the seed fails the same way every time, which is the difference between a flaky failure
and one you can debug. A woven monitor or `ReentrantLock` held by a paused worker no longer costs
anything: the schedule knows the holder and runs another worker. When a worker blocked without
reaching a point (on a lock the schedule does not track, a sleep, a `get`) it left the schedule
until its next point, and the line says how many times
that happened, since only a round without such an escape is guaranteed to replay. Each round's
`runner.schedule.round` event at DEBUG carries the same count as `escapes=`.

When a test fails, the library provides detailed diagnostics:

```
[RACE CONDITION] Field 'counter' accessed without synchronization
  - Expected final value: 2000
  - Actual final value: 1847
  - Missing synchronization at: AtomicCounterTest.testRaceCondition:15

[DEADLOCK DETECTED]
  Thread-1 waiting for lock@0x7fa1234 held by Thread-2
  Thread-2 waiting for lock@0x7fa5678 held by Thread-1
  Thread dump saved to: target/deadlock-dump-2024-03-24.txt

[VISIBILITY ISSUE]
  Field 'flag' accessed without volatile modifier
  - Thread-1 wrote value at 14:23:45.123
  - Thread-2 never saw the update (timed out)
  - Suggestion: Add 'volatile' to field declaration
```

## Best Practices

### 1. Use detectAll = true
For most application code, `detectAll = true` is the best starting point. It provides maximum coverage with zero boilerplate.

### 2. Use excludes selectively
If a specific detector (like `FALSE_SHARING`) causes too much overhead in a large test suite, exclude it rather than turning off everything.

### 3. Start Simple
```java
@AsyncTest(detectAll = true)
void simpleTest() { }
```

### 4. Provide Sufficient Timeout
Stress tests with many threads or all detectors enabled may need more time.
```java
@AsyncTest(detectAll = true, timeoutMs = 10000)
void deepStressTest() { }
```
