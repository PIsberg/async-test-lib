# Analyzing results and best practices

Part of the [Usage guide](../USAGE.md).

## Analyzing Results

### First, know what kind of finding you are reading

Not every detector makes the same kind of claim. Some can tell broken code from the correctly
synchronized version of that same code and are safe to fail a build on. Others only observe that an
object was touched by two threads, which correct code does too — those are a prompt to check your
synchronization, not a verdict. Which is which is measured, not asserted:
[DETECTOR_CATALOG.md § Trust tiers](../DETECTOR_CATALOG.md#trust-tiers).

Start with `failOn = CRITICAL`, which gates on the trustworthy end of the scale.

### Adopting into an existing suite

An established codebase will produce findings the first time `detectAll` runs, and a gate that is
red from the first commit gets switched off. Record what you already have, gate on what is new:

```bash
mvn test -Dasync-test.baseline=async-test-baseline.txt -Dasync-test.baseline.update=true  # once
mvn test -Dasync-test.baseline=async-test-baseline.txt                                    # thereafter
```

Commit the file and review its diff. Full mechanics, including what a baseline does not suppress:
[CI_INTEGRATION.md](../CI_INTEGRATION.md#adopting-into-a-codebase-that-already-has-findings).

### Reproducing a failure

Every failing run prints the seed that produced the interleaving:

```
[AsyncTest] Failure with replaySeed=8134729471193L — paste into @AsyncTest(replaySeed=...) to reproduce.
```

Paste it into the annotation and the same schedule is replayed, which is the difference between a
flaky failure and one you can debug.

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
