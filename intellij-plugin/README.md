# async-test IntelliJ IDEA Plugin

Surfaces [async-test](https://github.com/PIsberg/async-test-lib) concurrency findings directly inside IntelliJ IDEA — no terminal, no log-grepping required.

After your test suite runs with `JsonReportListener` registered, open the **async-test Findings** tool window to see every detector finding with severity colours, the full report text, and the timestamp of when it fired.

---

## Requirements

| Requirement | Version |
|-------------|---------|
| IntelliJ IDEA | 2024.1 or later (Community or Ultimate) |
| async-test library | 1.6.0 or later |
| Java | 17+ (plugin build); 21+ (library at runtime) |

---

## Installation

### Option A — Install from disk (recommended for now)

1. Build the plugin JAR:
   ```bash
   cd intellij-plugin
   ./gradlew buildPlugin
   ```
   The plugin ZIP is written to `intellij-plugin/build/distributions/`.

2. In IntelliJ IDEA: **Settings → Plugins → ⚙ → Install Plugin from Disk…**

3. Select the ZIP file and restart IntelliJ.

### Option B — JetBrains Marketplace *(coming soon)*

Search for **"async-test Detector"** in the Marketplace tab inside IntelliJ IDEA.

---

## Setup: wire JsonReportListener

The plugin reads the JSON report produced by `JsonReportListener`. Register it in your test class (or a shared base class):

```java
import se.deversity.asynctest.AsyncTestListenerRegistry;
import se.deversity.asynctest.report.JsonReportListener;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

class MyServiceTest {

    private static final JsonReportListener JSON_REPORTER = new JsonReportListener();

    @BeforeAll
    static void setup() {
        AsyncTestListenerRegistry.register(JSON_REPORTER);
    }

    @AfterAll
    static void teardown() {
        JSON_REPORTER.flush();                       // write immediately after each class
        AsyncTestListenerRegistry.clearAll();
    }

    @AsyncTest(threads = 10, invocations = 100)
    void myService_isThreadSafe() {
        // test body
    }
}
```

After the tests run, `target/async-test-reports/async-test-report.json` (Maven) or
`build/async-test-reports/async-test-report.json` (Gradle) will contain the structured findings.

---

## Using the tool window

### Opening it

- **View → Tool Windows → async-test Findings**
- Or click the **async-test** tab in the bottom panel

### What you see

```
┌──────────────────────────────────────────────────────────────────────┐
│  3 finding(s) — 1 CRITICAL, 1 HIGH, 1 MEDIUM | target/async-test-…  │
├────────────┬────────────────────────────────────┬────────────────────┤
│  Severity  │  Detector                          │  Time              │
├────────────┼────────────────────────────────────┼────────────────────┤
│  CRITICAL  │  DeadlockDetector                  │  2026-05-16 10:30  │
│  HIGH      │  FalseSharingDetector               │  2026-05-16 10:31  │
│  MEDIUM    │  LockContentionDetector             │  2026-05-16 10:31  │
├────────────┴────────────────────────────────────┴────────────────────┤
│  ▼ DeadlockDetector                                                   │
│                                                                       │
│  🔴 CRITICAL — Deadlock detected                                      │
│  Thread-0 holds lock on Object@1a2b waiting for Object@3c4d          │
│  Thread-1 holds lock on Object@3c4d waiting for Object@1a2b          │
│  ...                                                                  │
└──────────────────────────────────────────────────────────────────────┘
```

- **Severity column** is colour-coded: CRITICAL = red, HIGH = orange, MEDIUM = yellow, LOW = green
- **Click a row** to expand the full detector report in the detail pane below
- **Refresh** button (or **Tools → Refresh async-test Findings**) re-reads the report file after you re-run tests

### Refreshing after a test run

The plugin reads the report file on demand — it does not watch for file changes automatically. After re-running your tests, click the **Refresh** button in the tool window toolbar or use **Tools → Refresh async-test Findings**.

---

## Settings

**Settings → Tools → async-test**

| Setting | Default | Description |
|---------|---------|-------------|
| Report file paths | `target/async-test-reports/async-test-report.json, build/async-test-reports/async-test-report.json` | Comma-separated list of paths (relative to project root). The first file that exists is used. |

If your project uses a non-standard output directory, update this setting to point at the correct location.

---

## Severity levels

| Level | Colour | Meaning |
|-------|--------|---------|
| CRITICAL | 🔴 Red | Application will hang, deadlock, or crash — fix immediately |
| HIGH | 🟠 Orange | Data corruption or incorrect results possible — fix before production |
| MEDIUM | 🟡 Yellow | Performance degradation or resource leak — fix soon |
| LOW | 🟢 Green | Minor inefficiency or best-practice violation — fix when convenient |

---

## Combining with CI reports

The plugin is designed to complement `JUnitXmlReportListener` and `StrictModeListener`:

```java
@BeforeAll
static void setup() {
    AsyncTestListenerRegistry.register(new JsonReportListener());    // → IntelliJ plugin
    AsyncTestListenerRegistry.register(new JUnitXmlReportListener()); // → CI XML report
    // AsyncTestListenerRegistry.register(new StrictModeListener());  // → fail build on any finding
}
```

See [`docs/CI_INTEGRATION.md`](../docs/CI_INTEGRATION.md) for GitHub Actions, Jenkins, and GitLab CI setup.

---

## Building from source

```bash
cd intellij-plugin

# Build the plugin ZIP
./gradlew buildPlugin

# Run the plugin tests (pure-Java model layer)
./gradlew test

# Run an IntelliJ sandbox instance with the plugin loaded
./gradlew runIde

# Verify plugin compatibility
./gradlew verifyPlugin
```

The plugin build is independent of the main library build — it does not need to be a subproject of the parent Gradle build.

---

## Troubleshooting

**Tool window shows "Report file not found"**
- Check that `JsonReportListener` is registered in your tests and that at least one `@AsyncTest` test has run.
- Verify the report file path in **Settings → Tools → async-test**.
- Make sure `flush()` is being called (it happens automatically at JVM shutdown, or explicitly via `@AfterAll`).

**No findings appear even though tests ran**
- Detectors only fire when a concurrency issue is actually detected. A clean run produces no findings — that's the expected behaviour.
- Try running with `detectAll = true` (the default) to enable every detector.

**Severity shows as UNKNOWN**
- The JSON report was written by an older version of `JsonReportListener` (before 1.6.0). Upgrade the library dependency.

**Plugin fails to install**
- Ensure you're on IntelliJ IDEA 2024.1 or later.
- Try rebuilding the plugin: `./gradlew clean buildPlugin`.
