# AsyncAssert — Side-Effect Polling

> Extracted from the former `docs/README.md`. See [INDEX.md](INDEX.md) for the full documentation map.

Wait for async operations cleanly without blocking:

```java
@Test
void testAsync() {
    triggerAsyncProcess();
    
    // Poll until condition is true
    AsyncAssert.awaitUntil(() -> database.hasRecord("id-123"), Duration.ofSeconds(5));
}
```

Capture CompletableFuture results seamlessly:

```java
CompletableFuture<String> future = myService.runAsync();
AsyncAssert.FutureCapture<String> capture = AsyncAssert.capture(future);

capture.awaitDone(Duration.ofSeconds(2));
assertEquals("SUCCESS", capture.getResult());
```

## Naming what you are waiting for (1.9.0)

`Condition not met within 5000 ms` says nothing about which condition. Pass a description, and
the failure names the wait, counts the evaluations, and carries the last exception the condition
threw (attached as the `AssertionError`'s cause):

```java
AsyncAssert.awaitUntil(() -> queue.isEmpty(), Duration.ofSeconds(5), "queue drained");

// Condition not met within 5000 ms (queue drained) after 498 evaluation(s);
// last evaluation threw java.lang.IllegalStateException: queue closed
```

Exceptions thrown while polling still mean "not yet true", they are just no longer discarded.
An `Error` from the condition, including a nested `AssertionError`, propagates immediately
rather than being retried until the deadline.

Two timing behaviours changed in 1.9.0, both of which used to report a timeout for a condition
that was true inside the window:

- The condition is evaluated after the last sleep, not only before it.
- A poll interval longer than the remaining budget is clamped to it instead of slept through.

The condition is therefore always evaluated at least once, including at `Duration.ZERO`.

## Reading a captured outcome (1.9.0)

`getResult()` returns `null` for "still running", "failed" and "completed with null" alike.
`requireResult()` separates the three, and `capture(...)` now also accepts a `CompletionStage`:

```java
AsyncAssert.FutureCapture<String> capture = AsyncAssert.capture(service.runAsync());
capture.awaitDone(Duration.ofSeconds(2));

assertTrue(capture.isSuccess());          // completed, no exception (a null value counts)
assertFalse(capture.isFailed());
assertEquals("SUCCESS", capture.requireResult());
```

`requireResult()` throws an `AssertionError` when the future has not completed, and when it
completed exceptionally attaches that exception as the cause.

## Asserting on detector findings: `AsyncFindings` (1.9.0)

`AsyncAssert` waits for the code under test. `AsyncFindings` asserts on what the detectors saw.
It records the structured `Violation` behind every finding, so a test no longer has to
substring-match a report written for humans:

```java
class CounterTest {
    static AsyncFindings findings;

    @BeforeAll static void collect() { findings = AsyncFindings.collect(); }
    @AfterAll  static void release() { findings.close(); }

    @AsyncTest(threads = 4, invocations = 50, failOn = FailOn.NONE)
    void increments() {
        counter.increment();
    }

    @AfterAll
    static void theRaceIsReported() {
        findings.assertReported("RaceConditionDetector");
        findings.assertNotReported("DeadlockDetector");
    }
}
```

`failOn = FailOn.NONE` is what makes the findings assertable rather than fatal: at the default
threshold the run fails before the assertion is reached.

| Call | Asserts |
|---|---|
| `assertReported(name)` | that detector reported at least one finding |
| `assertReported(name, severity)` | that it reported one at that severity |
| `assertNotReported(name)` | that it reported nothing |
| `assertNone()` | that no detector reported anything |
| `violations()` / `violationsFrom(name)` | returns the `Violation` records for custom assertions |

Detector names are simple class names, as the runner keys its reports (`RaceConditionDetector`,
`BusyWaitDetector`). Matching is case-insensitive and accepts any substring, so `"RaceCondition"`
matches. A null or blank name is rejected with `IllegalArgumentException` rather than matching
nothing: silently matching nothing would make `assertNotReported` pass forever on a typo. A failed
assertion lists what was reported instead.

Because the registry is JVM-wide, close the collector (try-with-resources or `@AfterAll`) or it
keeps recording findings from every later test in the same JVM. `clear()` resets one collector
between tests.

The same data is available to any listener through
[`AsyncTestListener.onViolation(Violation)`](OBSERVABILITY.md).
