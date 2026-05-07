# System Property Mutation Example

This example demonstrates the **SystemPropertyMutationDetector** (Phase 12, `async-test-lib` 0.10.0).

## The Problem

`ConfigService.configure()` calls `System.setProperty("app.mode", mode)` to store a feature flag.
`System.setProperty` is globally shared, inherently mutable JVM state. Under concurrent
load two effects occur:

1. **Race condition** — threads reading `app.mode` between competing writes see arbitrary values.
2. **Test pollution** — the property is not restored after the test, so subsequent test methods
   inherit a mutated JVM state.

## Why Sequential Tests Miss This Bug

```java
@Test
void part1_configureMode_singleThread() {
    svc.configure("production");
    assertEquals("production", svc.getMode()); // ✅ Passes — no other thread racing
}
```

Sequential calls serialise naturally: one write then one read, no interleaving possible.

## How `@AsyncTest` Exposes the Bug

```java
@AsyncTest(threads = 4, invocations = 3, detectSystemPropertyMutation = true, timeoutMs = 5000)
void part2_detectConcurrentPropertyMutation() {
    var d = AsyncTestContext.systemPropertyMutationDetector();
    d.recordSet("app.mode", "test", Thread.currentThread());
    System.setProperty("app.mode", "test");
}
```

The detector reports:

```
SYSTEM PROPERTY MUTATION DETECTED:
  - 'app.mode' mutated from 4 threads — concurrent System.setProperty/clearProperty
    calls cause non-deterministic configuration and pollute subsequent tests.
    Fix: use a local configuration map or restore the original value in @AfterEach.
```

## Running the Example

```bash
cd examples/13-system-property-mutation
mvn clean test
# ✅ Tests pass — @Test gives false confidence

# Upgrade to 0.10.0 and enable @AsyncTest (see comments in the test file)
```

## The Fix

```java
void configureFixed(String mode, Map<String, String> localConfig) {
    localConfig.put("app.mode", mode); // ✅ Thread-local, no global mutation
}
```

Alternatively: save and restore in `@BeforeEach` / `@AfterEach`, or use
`@SystemProperty` from a test-utility library.

## Severity

| Failure mode | Symptom |
|-------------|---------|
| Concurrent write race | Threads read wrong configuration values non-deterministically |
| Test pollution | Later test methods see the mutated property, producing flaky failures |
