# Migration Guide

> See [INDEX.md](INDEX.md) for the full documentation map.

Two migrations live here. The first is for a suite that does not use this library yet. The second
is for a suite that does, and needs to keep working across 2.0.0.

## From JUnit to async-test

**Before** (JUnit):
```java
@Test
void testCounter() {
    counter = 0;
    counter++;
    assertEquals(1, counter);
}
```

**After** (async-test):
```java
@AsyncTest(threads = 50, invocations = 100)
void testCounter() {
    counter++;
}

@AfterEach
void verify() {
    assertEquals(5000, counter);  // Catches race condition
}
```

## Turning detectors on

Start with a preset and narrow from there. `includes` adds to whatever the preset selected,
`excludes` removes from it, and `DetectorType` is the single vocabulary all three speak:

```java
// Start here: the default preset, no configuration
@AsyncTest(threads = 50, invocations = 100)
void test1() { }

// Add lock-order validation to it
@AsyncTest(threads = 50, invocations = 100,
           includes = DetectorType.LOCK_ORDER)
void test2() { }

// Add cache-line detection as well
@AsyncTest(threads = 50, invocations = 100,
           includes = { DetectorType.LOCK_ORDER, DetectorType.FALSE_SHARING })
void test3() { }

// A named preset, minus one detector that is noisy for this subject
@AsyncTest(threads = 50, invocations = 100,
           preset = Preset.STRICT,
           excludes = DetectorType.THREAD_POOL)
void test4() { }
```

The presets are `ALL`, `ESSENTIALS`, `STRICT`, `CI_FAST` and `NONE`.
[CONFIGURATION.md](CONFIGURATION.md) covers what each selects.

## From 1.x to 2.0.0

2.0.0 removes what 1.x deprecated. Nothing in this section is a behaviour change: every
replacement already exists and already works in 1.x, so **the whole migration can be done on your
current version, verified green, and only then followed by the version bump**. That ordering
matters, because a 1.x build that is clean of deprecation warnings is a build that compiles
against 2.0.0 unchanged.

Every deprecated element names its replacement in its own `@deprecated` javadoc, and
`DeprecationsNameTheirReplacementTest` keeps that true for all 188 of them, so your IDE's
deprecation warning is a complete instruction. This section is the shape of the work and the
handful of cases where the obvious rewrite is wrong.

### The boolean attributes on `@AsyncTest`

All 146 `detect*` / `validate*` / `monitor*` boolean attributes are deprecated in favour of
`preset`, `includes` and `excludes`. The rewrite is mechanical: an attribute set to `true` becomes
its `DetectorType` in `includes`, and one set to `false` becomes its `DetectorType` in `excludes`.

```java
// 1.x, deprecated
@AsyncTest(threads = 50, invocations = 100,
           validateLockOrder = true,
           detectFalseSharing = true,
           detectABAProblem = true)
void test() { }

// Works in 1.x, and in 2.0.0
@AsyncTest(threads = 50, invocations = 100,
           includes = { DetectorType.LOCK_ORDER,
                        DetectorType.FALSE_SHARING,
                        DetectorType.ABA_PROBLEM })
void test() { }
```

The attribute's own javadoc names its `DetectorType`; there is no table to consult.
[DETECTOR_CATALOG.md](DETECTOR_CATALOG.md) lists every detector and what it reports.

### The `*Monitor()` accessors on `AsyncTestContext`

All 42 are deprecated in favour of a `*Detector()` name. For 38 of them the suffix is the only
difference:

```java
AsyncTestContext.lockLeakMonitor()   // 1.x, deprecated
AsyncTestContext.lockLeakDetector()  // works in 1.x, and in 2.0.0
```

Four do not follow that rule, because the new name says what the detector actually looks for
rather than what it wraps:

| 1.x | 2.0.0 |
|---|---|
| `semaphoreMonitor()` | `semaphoreMisuseDetector()` |
| `completableFutureMonitor()` | `completableFutureExceptionDetector()` |
| `conditionMonitor()` | `conditionVariableDetector()` |
| `copyOnWriteMonitor()` | `copyOnWriteCollectionDetector()` |

And one name defeats a global search-and-replace of `Monitor` with `Detector`, which would
rewrite the first occurrence too:

```java
AsyncTestContext.nestedMonitorLockoutMonitor()   // 1.x
AsyncTestContext.nestedMonitorLockoutDetector()  // 2.0.0, not nestedDetectorLockoutDetector()
```

Anchor the replacement to the end of the identifier and all 42 are covered.

### Checking your suite is ready

Compile with deprecation warnings visible. A build with none left is a build that survives 2.0.0.
Verified on this repo: the Maven flag turns javac to `[debug deprecation target 21]` and reports every
deprecated call site.

```bash
mvn -Dmaven.compiler.showDeprecation=true test
```

Gradle does not pass `-Xlint:deprecation` by default, and `--warning-mode all` reports Gradle's
own deprecations rather than javac's. Ask javac directly:

```kotlin
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:deprecation")
}
```

---
