# Observability — Event Listener System

> Part of the [architecture documentation](../ARCHITECTURE.md).

## Observability (Event Listener System)

The async-test library provides an opt-in observability system via the `AsyncTestListener` interface.

### AsyncTestListener Interface

**Purpose:** Allow users to observe async-test lifecycle events for logging, metrics, or custom reporting.

**Events:**
- `onInvocationStarted(int round, int threads)` — Called before each invocation round
- `onInvocationCompleted(int round, long durationMs)` — Called after each round completes
- `onTestFailed(Throwable cause)` — Called when a test fails
- `onDetectorReport(String detectorName, String report)` — Called when a detector reports an issue
- `onTimeout(long timeoutMs)` — Called when a timeout occurs

### Registration

```java
// Register a custom listener
AsyncTestListenerRegistry.register(new MyCustomListener());

// Unregister later
AsyncTestListenerRegistry.unregister(myListener);

// Clear all listeners (useful for test cleanup)
AsyncTestListenerRegistry.clearAll();
```

### Opt-Out

To silence all output, register a `NoopAsyncTestListener`:

```java
AsyncTestListenerRegistry.register(new NoopAsyncTestListener());
```

### Thread Safety

Listeners may be called from multiple worker threads concurrently. All listener implementations must be thread-safe. The registry uses a `CopyOnWriteArrayList` to allow concurrent iteration without locking.

### Default Behavior

If no listeners are registered, detector reports are printed to `System.err` (backward-compatible behavior). Registering custom listeners does not suppress this default output — both will receive events.

---

