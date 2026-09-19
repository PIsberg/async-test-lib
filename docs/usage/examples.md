# Examples

Part of the [Usage guide](../USAGE.md).

## Examples

### Example 1: Basic Race Condition Detection

```java
public class AtomicCounterTest {
    private int counter = 0;
    
    @AsyncTest(threads = 20, invocations = 100, detectAll = true)
    void testRaceCondition() {
        counter++;  // Race condition: unsynchronized increment
    }
}
```

### Example 2: Opting out of expensive detectors

```java
public class PerformanceSensitiveTest {
    @AsyncTest(
        detectAll = true,
        excludes = { DetectorType.FALSE_SHARING, DetectorType.VISIBILITY }
    )
    void testHighThroughput() {
        // Enables everything EXCEPT false sharing and visibility detection
    }
}
```

### Example 3: Deadlock Detection

```java
public class DeadlockTest {
    private final Object lock1 = new Object();
    private final Object lock2 = new Object();
    
    @AsyncTest(threads = 5, invocations = 50, detectAll = true)
    void testDeadlock() {
        if (System.nanoTime() % 2 == 0) {
            synchronized (lock1) {
                synchronized (lock2) {
                    // Perform work
                }
            }
        } else {
            synchronized (lock2) {
                synchronized (lock1) {
                    // Opposite lock order - deadlock!
                }
            }
        }
    }
}
```

### Example 4: Virtual Thread Stress Testing

```java
public class VirtualThreadStressTest {
    private final List<String> list = Collections.synchronizedList(new ArrayList<>());
    
    @AsyncTest(
        useVirtualThreads = true,
        virtualThreadStressMode = "HIGH",
        threads = 100000,  // Create 100,000 virtual threads
        invocations = 10,
        timeoutMs = 10000
    )
    void testVirtualThreadScalability() {
        list.add("item-" + Thread.currentThread().threadId());
    }
}
```
