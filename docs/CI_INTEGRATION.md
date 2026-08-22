# CI/CD Integration Guide

async-test ships two ready-made `AsyncTestListener` implementations in the `se.deversity.asynctest.report` package for CI pipeline integration.

## JUnitXmlReportListener

Writes detector findings to a JUnit-compatible XML file that CI dashboards can parse and display as named test failures — not just stderr noise.

### Setup

Register the listener once per test suite (e.g., in a shared base class or JUnit extension):

```java
import se.deversity.asynctest.AsyncTestListenerRegistry;
import se.deversity.asynctest.report.JUnitXmlReportListener;
import org.junit.jupiter.api.BeforeAll;

class MyServiceTest {

    @BeforeAll
    static void setup() {
        AsyncTestListenerRegistry.register(new JUnitXmlReportListener());
    }

    @AsyncTest(threads = 10, invocations = 100)
    void myService_isThreadSafe() {
        // ...
    }
}
```

The report is written automatically when the JVM shuts down. The default output path is:
- Maven: `target/async-test-reports/TEST-AsyncTestConcurrencyReport.xml`
- Gradle: `build/async-test-reports/TEST-AsyncTestConcurrencyReport.xml`

To write immediately (e.g., in `@AfterAll`):

```java
JUnitXmlReportListener reporter = new JUnitXmlReportListener();
AsyncTestListenerRegistry.register(reporter);

@AfterAll
static void writeReport() {
    reporter.flush(); // idempotent — safe to call multiple times
}
```

### GitHub Actions

```yaml
- name: Run tests
  run: mvn test

- name: Upload async-test detector reports
  uses: actions/upload-artifact@v4
  if: always()
  with:
    name: async-test-concurrency-reports
    path: target/async-test-reports/
    retention-days: 14

# Optional: surface findings as PR annotations (requires a JUnit reporter action)
- name: Publish async-test results
  uses: dorny/test-reporter@v1
  if: always()
  with:
    name: Async-Test Concurrency Findings
    path: target/async-test-reports/TEST-*.xml
    reporter: java-junit
```

### Jenkins

```groovy
stage('Test') {
    steps {
        sh 'mvn test'
    }
    post {
        always {
            junit 'target/async-test-reports/TEST-*.xml'
        }
    }
}
```

### GitLab CI

```yaml
test:
  script:
    - mvn test
  artifacts:
    when: always
    reports:
      junit: target/async-test-reports/TEST-*.xml
    paths:
      - target/async-test-reports/
```

---

## StrictModeListener

Converts any detector report into an immediate test failure. Use this in pipelines where concurrency findings must always break the build.

```java
import se.deversity.asynctest.AsyncTestListenerRegistry;
import se.deversity.asynctest.report.StrictModeListener;

@BeforeAll
static void setup() {
    // Any detector firing fails the test immediately
    AsyncTestListenerRegistry.register(new StrictModeListener());
}
```

You can combine both listeners to get hard failures AND structured reports:

```java
@BeforeAll
static void setup() {
    AsyncTestListenerRegistry.register(new JUnitXmlReportListener());
    AsyncTestListenerRegistry.register(new StrictModeListener());
}
```

---

## Adopting into a codebase that already has findings

Turning `detectAll` on across an existing suite produces findings on day one. Some are real, some
are the access-pattern detectors telling you that an object was touched by two threads and that you
should check the synchronization yourself — see
[the trust tiers](DETECTOR_CATALOG.md#trust-tiers). Either way, a team cannot fix all of them in the
sprint they adopt the library, and a gate that is red from the first commit gets switched off.

The baseline mechanism exists for that. It records the findings you already have so the build gates
only on new ones.

### Recording the baseline

Run once in update mode. Instead of failing, every finding that *would* have failed is written to
the file:

```bash
mvn test -Dasync-test.baseline=async-test-baseline.txt \
         -Dasync-test.baseline.update=true
```

The file is plain text, one `testId | DetectorName` pair per line, sorted and de-duplicated:

```
com.example.OrderServiceTest#concurrentCheckout | RaceConditionDetector
com.example.OrderServiceTest#concurrentCheckout | AtomicityValidator
com.example.CacheTest#parallelWarmup | SharedCollectionDetector
```

Commit it. Reviewing it in the pull request is the point: each line is a known problem the team has
decided not to fix yet, and a diff that adds lines is visible rather than silent.

### Gating on it

Drop the update flag. Findings listed in the file are suppressed; anything new fails as normal.

```bash
mvn test -Dasync-test.baseline=async-test-baseline.txt -Dasync-test.failOn=HIGH
```

Suppressed findings are announced at `INFO` (`N baselined finding(s) suppressed for <testId>`), so a
baseline that has quietly grown to cover the whole suite is visible in the build log rather than
invisible.

### Shrinking it

The file is the backlog. Delete a line, run the test, fix what it reports. A line that no longer
reproduces can simply be removed — nothing checks that every entry is still needed, so a periodic
`--baseline.update` regeneration into a fresh file and a diff against the committed one is the way
to find entries that have become stale.

### What a baseline does not do

- It suppresses findings, not failures from your own assertions.
- It is keyed on test id plus detector name, not on the specific object or line, so a second
  instance of the same detector firing in the same test is also suppressed.
- A missing baseline file is a warning, not an error, and suppresses nothing. A typo in the path
  therefore makes the build stricter rather than looser, which is the safe direction.

### Recommended starting point

| Stage | Configuration |
|---|---|
| First run, see what you have | `failOn = NONE`, read the reports |
| Adopt | `failOn = HIGH, minTrust = TrustTier.VERDICT` |
| Tighten once that stays green | record a baseline, then lower `minTrust` to `FACT` |
| Tighten again once the baseline stops growing | `minTrust = TrustTier.PROMPT` |

Two independent questions, two settings. `failOn` asks how bad a finding would be if it were real.
`minTrust` asks whether it is real, and it is the one that decides whether a gate is worth having.

A finding's trust tier is a property of the detector that raised it, published in `DetectorTrust`
and measured rather than asserted: `VERDICT` requires a case that fires on the bug and a case that
stays silent on its correctly synchronized twin, and a gate refuses the tier without both. Ten
detectors carry it today, nine of them in the `ESSENTIALS` preset. Most of the rest are `PROMPT`, meaning the detector saw a pattern it
cannot fully model, so a finding is a reason to look rather than proof of a bug.

Findings below the floor are still printed and still reach every listener, the JSON and the SARIF
output. They just cannot fail the build, which is the difference between a report a team reads and
one it learns to ignore.

Severity alone is the wrong floor to start from, and worth knowing why before trusting an old
recipe: 86 of the 142 detectors never set a severity at all, and `IssueSeverity.fromReport`
recovers one by matching upper-case keywords in the report text, defaulting to `HIGH` when it
finds none. So `failOn = HIGH` on its own is close to "fail on anything". That number is pinned by
`DetectorSeverityMarkerTest` and can only go down; the remaining work is
[issue #291](https://github.com/PIsberg/async-test-lib/issues/291).

---

## SARIF: findings in GitHub code scanning

`SarifFormatter` renders findings as SARIF 2.1.0, which GitHub code scanning, Azure DevOps, GitLab
and SonarQube all ingest. A finding in a build log is read once, by whoever broke the build. A
finding in the code-scanning UI is annotated on the pull request diff, visible to the whole team,
and carries the triage and dismissal workflow they already use for CodeQL.

```java
class SarifCollector implements AsyncTestListener {
    private final List<Violation> found = new CopyOnWriteArrayList<>();

    @Override
    public void onStructuredReport(String detector, IssueSeverity severity, String report) {
        found.add(new Violation(detector, severity, report, List.of(), Map.of(), Instant.now()));
    }

    void writeSarif() throws IOException {
        Files.writeString(Path.of("target/async-test.sarif"), new SarifFormatter().format(found));
    }
}
```

Register it with `AsyncTestListenerRegistry.register(...)` and call `writeSarif()` once the suite
has finished — a JUnit Platform `TestExecutionListener` or a Maven `@AfterSuite`-equivalent hook is
the usual place.

> **Locations via this route are empty.** `onStructuredReport` hands you the detector name,
> severity and rendered report, not the captured sites. Findings collected this way become
> run-level results rather than file annotations. If you want the annotations, build the
> `Violation` list from a detector's own `structuredViolations` field, which does carry the sites.

```yaml
- name: Upload async-test findings
  if: always()
  uses: github/codeql-action/upload-sarif@v3
  with:
    sarif_file: target/async-test.sarif
    category: async-test
```

`if: always()` matters: the run that produced findings is usually the run that failed, and without
it the upload is skipped exactly when there is something to upload.

### Severity mapping

| `IssueSeverity` | SARIF level | `security-severity` |
|---|---|---|
| CRITICAL | `error` | 9.0 |
| HIGH | `error` | 7.0 |
| MEDIUM | `warning` | 5.0 |
| LOW | `note` | 3.0 |

MEDIUM maps to `warning` rather than `error` deliberately. That is the tier the access-pattern
detectors use for correct-but-shared code, and a tool that blocks a merge over something it cannot
prove gets uninstalled. If your organisation gates on `error` only, this mapping means the
verdict-tier findings gate and the prompt-tier ones inform.

### Locations

A concurrency bug's location is genuinely ambiguous — the interleaving involves at least two
sites. The first captured site becomes the SARIF location and the rest are attached as related
locations. A finding with no captured site is emitted with an empty `locations` array rather than
being pinned to an arbitrary file, so you get a run-level finding instead of an annotation on a
line that is not the problem.

## Choosing a strategy

| Scenario | Recommended |
|----------|-------------|
| New project, enable gradually | `JUnitXmlReportListener` only — visible in CI, does not break builds |
| Mature project, zero-tolerance policy | `StrictModeListener` (optionally combined with `JUnitXmlReportListener`) |
| CI dashboard + hard gate | Both listeners registered together |

---

## SonarQube & Quality Gates Integration

SonarQube and other quality analysis tools can ingest the JUnit XML reports generated by `JUnitXmlReportListener` to count concurrency findings as test execution details or quality issues.

### Configuring SonarQube Scanner

Configure your `sonar-project.properties` or scanner configuration to point to the output directory of the reports:

```properties
# Maven projects
sonar.junit.reportPaths=target/async-test-reports

# Gradle projects
sonar.junit.reportPaths=build/async-test-reports
```

### Enforcing Quality Gates

To prevent merging code with concurrency findings:
1. Register both `StrictModeListener` and `JUnitXmlReportListener` in your test suite setup.
2. If any concurrency finding is detected, the test fails, causing the CI pipeline to exit with a non-zero code.
3. The SonarQube Quality Gate will automatically block the pull request due to the failed tests.
