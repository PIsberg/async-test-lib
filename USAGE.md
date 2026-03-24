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
```

### 2. Annotate your test method

```java
public class MyAsyncTests {
    
    @AsyncTest(
        threads = 10,
        invocations = 100,
        detectDeadlocks = true,
        detectVisibility = true
    )
    void testConcurrentAccess() {
        // Your test code here
        // Runs 100 times across 10 threads
    }
}
```

## Configuration Options

### Core Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `threads` | int | 5 | Number of threads to spawn |
| `invocations` | int | 10 | Number of times each thread runs the test |
| `timeoutMs` | long | 5000 | Test timeout in milliseconds |
| `useVirtualThreads` | boolean | false | Use Java 21+ virtual threads |
| `virtualThreadStressMode` | String | "OFF" | Virtual thread stress level (OFF, LIGHT, MEDIUM, HEAVY, EXTREME) |

### Phase 1 Detectors (Race Conditions & Synchronization)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `detectRaceConditions` | boolean | true | Detect concurrent access to shared state |
| `detectDeadlocks` | boolean | true | Detect circular lock dependencies |
| `detectThreadStarvation` | boolean | false | Detect threads being starved of CPU |
| `detectLockOrdering` | boolean | false | Detect improper lock acquisition order |

### Phase 2 Detectors (Memory Model & Advanced Issues)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `detectVisibility` | boolean | false | Detect missing volatile keywords |
| `detectFalseSharing` | boolean | false | Detect cache line contention |
| `detectLivelocks` | boolean | false | Detect livelock conditions |
| `detectWakeupIssues` | boolean | false | Detect spurious/lost wakeups |
| `detectABAProblems` | boolean | false | Detect ABA problems in lock-free code |
| `detectMemoryOrdering` | boolean | false | Detect JMM happens-before violations |
| `detectConstructorSafety` | boolean | false | Detect unsafe object publication |
| `detectSynchronizerIssues` | boolean | false | Detect problems in synchronizers |
| `monitorThreadPool` | boolean | false | Monitor thread pool behavior |
| `detectMemoryLeaks` | boolean | false | Detect memory leaks in concurrent code |

## Examples

### Example 1: Basic Race Condition Detection

```java
public class AtomicCounterTest {
    private int counter = 0;
    
    @AsyncTest(threads = 20, invocations = 100)
    void testRaceCondition() {
        counter++;  // Race condition: unsynchronized increment
    }
}
```

The library will:
- Run the test 100 times across 20 threads
- Detect that counter doesn't reach expected value (20 * 100)
- Report the race condition with detailed diagnostics

### Example 2: Detecting Missing Volatile

```java
public class VisibilityTest {
    private boolean flag = false;  // Should be volatile!
    
    @AsyncTest(
        threads = 10,
        invocations = 50,
        detectVisibility = true,
        timeoutMs = 2000
    )
    void testMissingVolatile() throws Exception {
        if (Random.nextBoolean()) {
            Thread.sleep(10);
            flag = true;
        } else {
            // May spin endlessly without volatile
            int spinCount = 0;
            while (!flag && spinCount < 10000) {
                spinCount++;
            }
        }
    }
}
```

### Example 3: Deadlock Detection

```java
public class DeadlockTest {
    private final Object lock1 = new Object();
    private final Object lock2 = new Object();
    
    @AsyncTest(threads = 5, invocations = 50, detectDeadlocks = true)
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

The library will:
- Timeout on deadlock
- Generate thread dump showing which threads hold which locks
- Identify the circular dependency

### Example 4: Virtual Thread Stress Testing

```java
public class VirtualThreadStressTest {
    private final List<String> list = Collections.synchronizedList(new ArrayList<>());
    
    @AsyncTest(
        useVirtualThreads = true,
        virtualThreadStressMode = "HEAVY",
        threads = 100000,  // Create 100,000 virtual threads
        invocations = 10,
        timeoutMs = 10000
    )
    void testVirtualThreadScalability() {
        list.add("item-" + Thread.currentThread().threadId());
    }
}
```

This tests for:
- Thread pinning issues (virtual thread blocking carrier thread)
- Scale to millions of lightweight threads
- Memory efficiency compared to OS threads

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

### 1. Start Simple
```java
@AsyncTest  // Uses defaults
void simpleTest() { }
```

### 2. Gradually Increase Complexity
```java
@AsyncTest(threads = 2, invocations = 10)
void basicConcurrency() { }

@AsyncTest(threads = 50, invocations = 100)
void stressTest() { }

@AsyncTest(useVirtualThreads = true, virtualThreadStressMode = "HEAVY")
void virtualThreadTest() { }
```

### 3. Enable Specific Detectors for Known Issues
```java
@AsyncTest(
    threads = 20,
    detectDeadlocks = true,      // You suspect deadlock issues
    detectVisibility = false      // Not relevant for this code
)
void testSpecificIssue() { }
```

### 4. Use Appropriate Timeouts
```java
@AsyncTest(
    threads = 10,
    timeoutMs = 1000    // Short timeout for quick feedback
)
void fastTest() { }

@AsyncTest(
    threads = 1000,
    timeoutMs = 30000   // Longer timeout for stress tests
)
void stressTest() { }
```

## Troubleshooting

### Test Hangs (Timeout)
- Increase `timeoutMs`
- Check for deadlocks in test code
- Enable `detectDeadlocks = true`

### False Positives
- Reduce `threads` or `invocations`
- Check test isolation (ensure no shared state)
- Use proper synchronization in test fixtures

### Memory Usage
- Reduce thread count
- Reduce invocations
- Avoid accumulating results in memory

## Integration with CI/CD

The library integrates seamlessly with GitHub Actions:

```yaml
- name: Run async tests
  run: mvn clean test
  
- name: Check test coverage
  run: mvn jacoco:report
```

Generate coverage reports in `target/site/jacoco/`.

## Support

For issues, questions, or feature requests:
- GitHub Issues: https://github.com/yourusername/async-test-lib/issues
- Documentation: https://github.com/yourusername/async-test-lib/wiki

## License

MIT License - See LICENSE file for details
