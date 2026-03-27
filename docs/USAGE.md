# Using Async Test Library

## Installation

### Via Maven (GitHub Packages)

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>com.github.asynctest</groupId>
    <artifactId>async-test</artifactId>
    <version>1.0.0</version>
    <scope>test</scope>
</dependency>
```

Configure the GitHub Packages repository in your `pom.xml`:

```xml
<repositories>
    <repository>
        <id>github</id>
        <name>GitHub Packages</name>
        <url>https://maven.pkg.github.com/yourusername/async-test-lib</url>
        <releases>
            <enabled>true</enabled>
        </releases>
        <snapshots>
            <enabled>true</enabled>
        </snapshots>
    </repository>
</repositories>
```

### Via Gradle

```gradle
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/yourusername/async-test-lib")
        credentials {
            username = project.findProperty("gpr.user") ?: System.getenv("USERNAME")
            password = project.findProperty("gpr.key") ?: System.getenv("TOKEN")
        }
    }
}

dependencies {
    testImplementation 'com.github.asynctest:async-test:1.0.0'
}
```

## Basic Usage

### 1. Import the annotation

```java
import com.github.asynctest.AsyncTest;
import com.github.asynctest.DetectorType;
```

### 2. Annotate your test method

```java
public class MyAsyncTests {
    
    @AsyncTest(
        threads = 10,
        invocations = 100,
        detectAll = true
    )
    void testConcurrentAccess() {
        // All 35+ detectors are enabled!
    }
}
```

## Configuration Options

### Core Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `threads` | int | 10 | Number of threads to spawn |
| `invocations` | int | 100 | Number of times the concurrent round runs |
| `timeoutMs` | long | 5000 | Test timeout in milliseconds |
| `useVirtualThreads` | boolean | true | Use Java 21+ virtual threads |
| `virtualThreadStressMode` | String | "OFF" | Virtual thread stress level (OFF, LOW, MEDIUM, HIGH, EXTREME) |
| `detectAll` | boolean | false | **Enable ALL detectors in one shot (Recommended)** |
| `excludes` | DetectorType[] | {} | Detectors to skip when `detectAll = true` |

### Phase 1 Detectors (Enabled by default if detectAll=true)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `detectDeadlocks` | boolean | true | Detect circular lock dependencies |
| `detectVisibility` | boolean | false | Detect missing volatile keywords |
| `detectLivelocks` | boolean | false | Detect thread spinning and starvation |

### Phase 2 Detectors (Enabled by default if detectAll=true)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `detectFalseSharing` | boolean | false | Detect cache line contention |
| `detectWakeupIssues` | boolean | false | Detect spurious/lost wakeups |
| `validateConstructorSafety` | boolean | false | Detect unsafe object publication |
| `detectABAProblem` | boolean | false | Detect ABA problems in lock-free code |
| `validateLockOrder` | boolean | false | Detect improper lock acquisition order |
| `monitorSynchronizers` | boolean | false | Detect problems in synchronizers |
| `monitorThreadPool` | boolean | false | Monitor thread pool behavior |
| `detectMemoryOrderingViolations` | boolean | false | Detect JMM happens-before violations |
| `monitorAsyncPipeline` | boolean | false | Monitor event flow through async pipelines |
| `monitorReadWriteLockFairness` | boolean | false | Detect writer starvation and unfair locks |

### Phase 3 Detectors (Enabled by default if detectAll=true)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `detectRaceConditions` | boolean | false | Track concurrent field access patterns |
| `detectThreadLocalLeaks` | boolean | false | Detect ThreadLocal values not cleaned up |
| `detectBusyWaiting` | boolean | false | Detect spin loops and tight polling |
| `detectAtomicityViolations` | boolean | false | Detect non-atomic compound operations |
| `detectInterruptMishandling` | boolean | false | Detect swallowed interrupts and missing restoration |

## Manual Legacy Diagnostics

For older Java async patterns that need explicit instrumentation, instantiate the diagnostics directly:

```java
NotifyAllValidator notifyValidator = new NotifyAllValidator();
LazyInitValidator lazyInitValidator = new LazyInitValidator();
FutureBlockingDetector futureBlockingDetector = new FutureBlockingDetector();
ExecutorDeadlockDetector executorDeadlockDetector = new ExecutorDeadlockDetector();
LatchMisuseDetector latchMisuseDetector = new LatchMisuseDetector();
```

Use these for:
- `wait()`/`notify()` vs `notifyAll()` bugs
- unsafe lazy initialization and broken double-checked locking
- blocking on sibling futures inside bounded executors
- single-thread or bounded executor self-deadlocks
- missing `CountDownLatch.countDown()` paths

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

## Analyzing Results

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

## Support

For issues, questions, or feature requests:
- GitHub Issues: https://github.com/yourusername/async-test-lib/issues
- Documentation: https://github.com/yourusername/async-test-lib/wiki

## License

MIT License - See LICENSE file for details
