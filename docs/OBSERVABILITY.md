# Observability — Event Listeners

> Extracted from the former `docs/README.md`. See [INDEX.md](INDEX.md) for the full documentation map.

The async-test library provides an **opt-in observability system** via the `AsyncTestListener` interface. This allows you to integrate test events with your logging, metrics, or CI/CD reporting systems.

### Built-in Listener Events

Listeners receive callbacks for:
- **Invocation started/completed** — Track test execution timing
- **Test failed** — Capture failures for reporting
- **Detector report** — Get notified when a detector finds an issue
- **Timeout** — Handle timeout events

### Creating a Custom Listener

```java
import se.deversity.async-test-lib.AsyncTestListener;

public class MyCustomListener implements AsyncTestListener {
    
    @Override
    public void onInvocationStarted(int round, int threads) {
        System.out.println("Starting round " + round + " with " + threads + " threads");
    }
    
    @Override
    public void onInvocationCompleted(int round, long durationMs) {
        System.out.println("Round " + round + " completed in " + durationMs + "ms");
    }
    
    @Override
    public void onTestFailed(Throwable cause) {
        System.err.println("Test failed: " + cause.getMessage());
        // Send to Slack, Teams, or logging system
    }
    
    @Override
    public void onDetectorReport(String detectorName, String report) {
        System.out.println("[" + detectorName + "] " + report);
        // Log detector findings to your monitoring system
    }
    
    @Override
    public void onTimeout(long timeoutMs) {
        System.err.println("Test timed out after " + timeoutMs + "ms");
    }
}
```

### Registering Listeners

```java
import se.deversity.async-test-lib.AsyncTestListenerRegistry;

@BeforeAll
static void setUp() {
    // Register custom listener
    AsyncTestListenerRegistry.register(new MyCustomListener());
}

@AfterAll
static void tearDown() {
    // Clean up listeners
    AsyncTestListenerRegistry.clearAll();
}
```

### Opt-Out: Silencing Default Output

To suppress all default output, register a `NoopAsyncTestListener`:

```java
AsyncTestListenerRegistry.register(new NoopAsyncTestListener());
```

### Thread Safety

Listeners may be called from multiple worker threads concurrently. Ensure your implementation is thread-safe:

```java
public class ThreadSafeListener implements AsyncTestListener {
    private final ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
    
    @Override
    public void onDetectorReport(String detectorName, String report) {
        events.add(detectorName + ": " + report);
        // Thread-safe collection for later processing
    }
}
```

### Use Cases

| Use Case | Implementation |
|----------|---------------|
| **CI/CD Integration** | Use `JUnitXmlReportListener` or `StrictModeListener` (see below) |
| **Metrics Collection** | Track invocation times, failure rates |
| **Custom Logging** | Route output to Log4j, SLF4J, or file |
| **Alerting** | Send Slack/Teams notifications on failures |
| **Test Reporting** | Use `JsonReportListener` or `JUnitXmlReportListener` (see below) |

### Structured Report: `onStructuredReport` (v1.6.0+)

In addition to `onDetectorReport(String, String)`, listeners receive a richer callback with the parsed severity:

```java
@Override
public void onStructuredReport(String detectorName, IssueSeverity severity, String report) {
    if (severity == IssueSeverity.CRITICAL || severity == IssueSeverity.HIGH) {
        alertChannel.send("[" + severity + "] " + detectorName + ": " + report);
    }
}
```

Both `onDetectorReport` and `onStructuredReport` are fired for every finding. Existing listeners that don't override `onStructuredReport` receive a no-op default — no migration needed.

---

