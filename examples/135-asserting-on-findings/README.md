# 135 — Asserting on Findings

Most examples here let the run fail and leave the report to a human. This one keeps the run green
and asserts on the findings instead, which is what you want when the finding **is** the expected
outcome: a regression test for a known hazard, or a test proving a fix silenced a detector.

## The shape

```java
class InventoryServiceFindingsTest {

    static AsyncFindings findings;

    @BeforeAll static void collect() { findings = AsyncFindings.collect(); }

    @AsyncTest(threads = 4, invocations = 25, failOn = FailOn.NONE)
    void reserving_stock_from_four_threads_reports_a_race() {
        AsyncTestContext.get().sharedRaceConditionDetector()
                .recordFieldWrite(service, "available");
        service.reserveOne();
    }

    @AfterAll
    static void theRaceWasReported() {
        findings.assertReported("RaceConditionDetector");
        findings.assertNotReported("DeadlockDetector");
        findings.close();
    }
}
```

Three things make it work:

| | Why |
|---|---|
| `failOn = FailOn.NONE` | at the default threshold the run fails before the assertion is reached, so the findings would never be assertable |
| `@BeforeAll` / `@AfterAll` | detectors analyse after the last round, so a finding cannot be observed from inside the test body, and JUnit does not order `@Test` against `@AsyncTest` methods |
| `findings.close()` | the listener registry is JVM-wide; an unclosed collector keeps recording findings from every later test in the same JVM |

`assertReported` matches the detector's simple class name, case-insensitively and by substring, so
`"RaceCondition"` works too. When it fails it lists what was reported instead:

```
Expected a finding from detector 'NoSuchDetector', but 2 finding(s) were reported:
BusyWaitDetector (MEDIUM), RaceConditionDetector (HIGH)
```

`violationsFrom(name)` returns the `Violation` records for anything the fixed assertions do not
cover: detector, severity, message, sites, attributes. The full report text stays available under
the `"report"` attribute.

## Also shown

- `AsyncAssert.awaitUntil(condition, timeout, description)` — the failure names the wait, counts
  the evaluations, and carries the exception the condition last threw as the `AssertionError`'s
  cause instead of discarding it.
- `FutureCapture.isSuccess()` / `isFailed()` / `requireResult()` — `getResult()` returns `null`
  for "still running", "failed" and "completed with null" alike; these separate the three.

## Run it

```bash
mvn -f examples/135-asserting-on-findings/pom.xml test
./gradlew -p examples/135-asserting-on-findings test
```

Both run against the locally installed library, so `mvn install -DskipTests` at the repo root
first if you have changed the source.

See [docs/ASYNC_ASSERT.md](../../docs/ASYNC_ASSERT.md) for the full API.
