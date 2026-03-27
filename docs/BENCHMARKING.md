# Benchmarking Guide

## Overview

The async-test library now includes built-in benchmarking capabilities that allow you to:
- **Track execution times** of your concurrent tests
- **Store baselines** for comparison across runs
- **Detect performance regressions** automatically
- **Fail builds** when performance degrades beyond acceptable thresholds

## Quick Start

### Option 1: Enable Per-Test (Fine-Grained Control)

Add `enableBenchmarking = true` to specific `@AsyncTest` annotations:

```java
@AsyncTest(threads = 10, invocations = 50, enableBenchmarking = true)
void testMyConcurrentCode() {
    // your concurrent test code
}
```

### Option 2: Enable Globally via System Property (All Tests)

Enable benchmarking for ALL tests in your project via a system property:

```bash
# Command line
mvn test -Dasync-test.benchmarking.enabled=true
```

Or in your `pom.xml`:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>3.5.4</version>
    <configuration>
        <systemPropertyVariables>
            <async-test.benchmarking.enabled>true</async-test.benchmarking.enabled>
        </systemPropertyVariables>
    </configuration>
</plugin>
```

**Note:** The annotation parameter `enableBenchmarking` takes precedence if both are set.

### Configure Regression Threshold

Set a custom regression threshold (default is 20%):

```java
@AsyncTest(
    threads = 10, 
    invocations = 50, 
    enableBenchmarking = true,
    benchmarkRegressionThreshold = 0.15  // 15% threshold
)
void testMyConcurrentCode() {
    // your concurrent test code
}
```

### Fail on Regression

Make the test fail when a regression is detected:

```java
@AsyncTest(
    threads = 10, 
    invocations = 50, 
    enableBenchmarking = true,
    benchmarkRegressionThreshold = 0.20,
    failOnBenchmarkRegression = true  // Throws exception on regression
)
void testMyConcurrentCode() {
    // your concurrent test code
}
```

## How It Works

### First Run (Baseline Creation)

When you run a benchmarked test for the first time:
1. Execution times are recorded for each invocation
2. Statistics are calculated (avg, min, max, standard deviation)
3. A baseline is stored in `target/benchmark-data/baseline-store.dat`
4. Output: `[BENCHMARK] Baseline created for MyClass#myTest: avg=5.23 ms`

### Subsequent Runs (Comparison)

On subsequent runs:
1. Current execution times are measured
2. Compared against the stored baseline
3. Percentage change is calculated
4. If change exceeds threshold, a regression is detected
5. Output examples:
   - `[BENCHMARK] ✓ STABLE for MyClass#myTest (change: +5.12%)`
   - `[BENCHMARK] ✓ IMPROVEMENT for MyClass#myTest (change: -10.34%)`
   - `[BENCHMARK] ⚠️ REGRESSION for MyClass#myTest (change: +25.67%)`

### Regression Detection

When a regression is detected, a detailed report is printed:

```
================================================================================
⚠️  BENCHMARK REGRESSION DETECTED ⚠️
================================================================================
Performance regression detected in com.example.MyTest#myTest
  Baseline: 5.23 ms (2026-03-27T23:25:33.545855600)
  Current:  6.54 ms (2026-03-27T23:30:15.123456700)
  Change:   +25.00% (threshold: 20.0%)
  Difference: 1.31 ms slower

Suggested actions:
  - Review recent code changes for performance impact
  - Check for increased contention or resource constraints
  - If this is expected, update the baseline by running with -Dbenchmark.update=true
================================================================================
```

## Configuration Options

### Annotation Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `enableBenchmarking` | boolean | false | Enable benchmarking for this test |
| `benchmarkRegressionThreshold` | double | 0.2 (20%) | Regression threshold as decimal (0.2 = 20%) |
| `failOnBenchmarkRegression` | boolean | false | If true, throw exception on regression |

### System Properties

| Property | Description | Default |
|----------|-------------|---------|
| `-Dasync-test.benchmarking.enabled` | Enable benchmarking for all @AsyncTest tests | false |
| `-Dbenchmark.store.path=<path>` | Custom path for baseline storage | `target/benchmark-data/baseline-store.dat` |
| `-Dbenchmark.update=true` | Force update baseline with current results | false |
| `-Dbenchmark.regression.threshold=<value>` | Override regression threshold (decimal) | 0.20 (20%) |
| `-Dbenchmark.fail.on.regression=true` | Fail tests on regression | false |

**Example usage:**
```bash
# Enable benchmarking and set custom threshold
mvn test -Dasync-test.benchmarking.enabled=true -Dbenchmark.regression.threshold=0.15

# Update all baselines
mvn test -Dasync-test.benchmarking.enabled=true -Dbenchmark.update=true

# Custom storage location for CI/CD
mvn test -Dasync-test.benchmarking.enabled=true -Dbenchmark.store.path=/shared/benchmarks/baseline-store.dat
```

## Usage Examples

### Basic Benchmarking

```java
@AsyncTest(threads = 10, invocations = 50, enableBenchmarking = true)
void testRaceCondition() {
    counter++;  // Test with built-in performance tracking
}
```

### Enable Benchmarking for All Tests in Module

If you want to enable benchmarking for all tests in a specific module (e.g., `consumer-fixture`), configure it in the module's `pom.xml`:

```xml
<project>
    ...
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.5.4</version>
                <configuration>
                    <systemPropertyVariables>
                        <!-- Enable benchmarking for all @AsyncTest tests -->
                        <async-test.benchmarking.enabled>true</async-test.benchmarking.enabled>
                        <!-- Optional: Set regression threshold -->
                        <benchmark.regression.threshold>0.20</benchmark.regression.threshold>
                    </systemPropertyVariables>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

Now all `@AsyncTest` tests in this module will have benchmarking enabled without needing `enableBenchmarking = true` on each test:

```java
// No need for enableBenchmarking = true - it's enabled globally for this module
@AsyncTest(threads = 10, invocations = 50, detectAll = true)
void testRaceCondition() {
    counter++;
}
```

### Strict Performance Requirements

```java
@AsyncTest(
    threads = 20, 
    invocations = 100, 
    enableBenchmarking = true,
    benchmarkRegressionThreshold = 0.10,  // 10% threshold
    failOnBenchmarkRegression = true      // Fail CI/CD on regression
)
void testCriticalPath() {
    // Performance-critical concurrent code
}
```

### Update Baseline After Intentional Changes

After making intentional performance changes:

```bash
# Run with baseline update flag
mvn test -Dtest=MyTest#myTest -Dbenchmark.update=true
```

### Custom Storage Location

```bash
# Store baselines in a shared location for CI/CD
mvn test -Dbenchmark.store.path=/shared/benchmarks/baseline-store.dat
```

## Integration with CI/CD

### GitHub Actions Example

```yaml
- name: Run Performance Tests
  run: mvn test -Dtest='**/*AsyncTest.java' -Dbenchmark.store.path=${{ github.workspace }}/benchmarks/baseline-store.dat

- name: Upload Benchmark Results
  uses: actions/upload-artifact@v3
  with:
    name: benchmark-data
    path: target/benchmark-data/
```

### Jenkins Example

```groovy
stage('Performance Tests') {
    steps {
        sh 'mvn test -Dtest="**/*AsyncTest.java" -Dbenchmark.store.path=${WORKSPACE}/benchmarks/baseline-store.dat'
    }
    post {
        always {
            archiveArtifacts artifacts: 'target/benchmark-data/**/*'
        }
    }
}
```

## Best Practices

### 1. Start with Lenient Thresholds

Begin with 20-30% thresholds to establish baselines, then tighten gradually:

```java
@AsyncTest(enableBenchmarking = true, benchmarkRegressionThreshold = 0.25)
```

### 2. Enable Benchmarking Selectively

Not every test needs benchmarking. Focus on:
- Performance-critical paths
- Tests that historically had regressions
- Core concurrency primitives

### 3. Use failOnBenchmarkRegression Carefully

Enable `failOnBenchmarkRegression` only for:
- Critical performance tests
- Tests with stable, well-understood baselines
- CI/CD pipelines where performance is a requirement

### 4. Regular Baseline Updates

Periodically update baselines to account for:
- Intentional performance improvements
- Infrastructure changes
- JVM version upgrades

### 5. Monitor Trends

Look for patterns across multiple runs:
- Gradual degradation may indicate accumulating technical debt
- Sudden spikes often correlate with specific code changes

## Troubleshooting

### Benchmark Data Not Persisting

Ensure the `target/benchmark-data/` directory is writable and not cleaned between runs.

### False Positives on Regression

If you're seeing regressions that aren't real:
1. Increase the threshold (e.g., from 20% to 30%)
2. Run more invocations for statistical significance
3. Ensure consistent test environment (CPU, memory, etc.)

### Baseline Location

Default baseline storage: `target/benchmark-data/baseline-store.dat`

To change location:
```bash
mvn test -Dbenchmark.store.path=/custom/path/baseline-store.dat
```

## API Reference

### BenchmarkResult

Represents benchmark results with statistics:
- `getAvgTimePerInvocationNanos()` - Average execution time
- `getMinTimePerInvocationNanos()` - Minimum execution time
- `getMaxTimePerInvocationNanos()` - Maximum execution time
- `getStandardDeviation()` - Standard deviation of execution times

### BenchmarkComparisonResult

Result of comparing current vs baseline:
- `isRegression()` - True if performance degraded beyond threshold
- `isImprovement()` - True if performance improved beyond threshold
- `isWithinThreshold()` - True if change is within acceptable bounds
- `getPercentChange()` - Percentage change from baseline

### BenchmarkRegressionException

Thrown when `failOnBenchmarkRegression = true` and regression detected:
- Contains full `BenchmarkComparisonResult` for analysis

## Migration Guide

### Adding Benchmarking to Existing Tests

1. Add `enableBenchmarking = true` to existing `@AsyncTest` annotations
2. Run tests to establish baselines
3. Commit baseline files to version control (optional)
4. Configure CI/CD to preserve baseline data between runs

### Example Migration

**Before:**
```java
@AsyncTest(threads = 10, invocations = 50, detectAll = true)
void testMyCode() {
    // test code
}
```

**After:**
```java
@AsyncTest(
    threads = 10, 
    invocations = 50, 
    detectAll = true,
    enableBenchmarking = true,
    benchmarkRegressionThreshold = 0.20
)
void testMyCode() {
    // test code with performance tracking
}
```

## See Also

- [AsyncTest Annotation Documentation](../src/main/java/com/github/asynctest/AsyncTest.java)
- [BenchmarkResult API](../src/main/java/com/github/asynctest/benchmark/BenchmarkResult.java)
- [BenchmarkComparator API](../src/main/java/com/github/asynctest/benchmark/BenchmarkComparator.java)
