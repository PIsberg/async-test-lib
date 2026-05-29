# CI/CD Integration Guide

async-test ships two ready-made `AsyncTestListener` implementations in the `se.deversity.async-test-lib.report` package for CI pipeline integration.

## JUnitXmlReportListener

Writes detector findings to a JUnit-compatible XML file that CI dashboards can parse and display as named test failures — not just stderr noise.

### Setup

Register the listener once per test suite (e.g., in a shared base class or JUnit extension):

```java
import se.deversity.async-test-lib.AsyncTestListenerRegistry;
import se.deversity.async-test-lib.report.JUnitXmlReportListener;
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
import se.deversity.async-test-lib.AsyncTestListenerRegistry;
import se.deversity.async-test-lib.report.StrictModeListener;

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

## Choosing a strategy

| Scenario | Recommended |
|----------|-------------|
| New project, enable gradually | `JUnitXmlReportListener` only — visible in CI, does not break builds |
| Mature project, zero-tolerance policy | `StrictModeListener` (optionally combined with `JUnitXmlReportListener`) |
| CI dashboard + hard gate | Both listeners registered together |
