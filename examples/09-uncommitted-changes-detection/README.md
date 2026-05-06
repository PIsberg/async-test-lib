# Example 09: Uncommitted Changes Detection

This example demonstrates how to use the **Uncommitted Changes Detector** to ensure your concurrent tests are running against a clean repository baseline.

## Why this matters

Concurrent tests are notoriously sensitive to environmental factors. An untracked file or uncommitted change might:
1.  **Pollute Classpath**: A rogue file in `src/test/resources` might be loaded by mistake.
2.  **Break Reproducibility**: If a test fails due to a local change that isn't committed, other developers won't be able to reproduce the failure.
3.  **Conflict with CI**: Tests might pass locally but fail in CI (or vice-versa) because of uncommitted state.

## How to use it

Enable the detector using the `detectUncommittedChanges` parameter in the `@AsyncTest` annotation:

```java
@AsyncTest(
    detectUncommittedChanges = true
)
void myTest() {
    // ...
}
```

Or enable all detectors:

```java
@AsyncTest(detectAll = true)
void myTest() {
    // ...
}
```

## Running the example

### Modifying the repository
To see the detector in action, make a small change to any file in your repository (or create a new untracked file) and then run the tests.

### Maven
```bash
mvn test
```

### Gradle
```bash
./gradlew test
```

## Understanding the output
If changes are detected, you will see a `LOW` severity issue in the diagnostic report similar to:

```text
[ISSUE] Uncommitted Changes (LOW)
Description: Git repository has uncommitted or untracked changes.
Details: The following files are modified or untracked:
  M  src/main/java/se/deversity/asynctest/MyFile.java
  ?? new-file.txt
```
