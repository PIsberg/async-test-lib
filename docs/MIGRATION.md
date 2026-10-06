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

Start with the default and widen or narrow from there. `includes` replaces the selection with
exactly the listed detectors, `detectAll = true` widens it to every detector, `excludes` removes
from whatever was selected, and `DetectorType` is the single vocabulary they all speak:

```java
// Start here: the default, Preset.ESSENTIALS, no configuration
@AsyncTest(threads = 50, invocations = 100)
void test1() { }

// Exactly lock-order validation and cache-line detection, nothing else
@AsyncTest(threads = 50, invocations = 100,
           includes = { DetectorType.LOCK_ORDER, DetectorType.FALSE_SHARING })
void test2() { }

// Every detector
@AsyncTest(threads = 50, invocations = 100, detectAll = true)
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

2.0.0 removes what 1.x deprecated, and changes one default: a bare `@AsyncTest` runs
`Preset.ESSENTIALS` instead of every detector ([below](#the-default-selection)). Apart from that
default nothing in this section is a behaviour change: every replacement already exists and
already works in 1.x, so **the whole migration can be done on your current version, verified
green, and only then followed by the version bump**. That ordering matters, because a 1.x build
that is clean of deprecation warnings is a build that compiles against 2.0.0 unchanged, and adding
`detectAll = true` where you relied on the old default is a no-op in 1.x.

Every deprecated element names its replacement in its own `@deprecated` javadoc, and
`DeprecationsNameTheirReplacementTest` kept that true for all 188 of them in 1.x, so your IDE's
deprecation warning is a complete instruction. This section is the shape of the work and the
handful of cases where the obvious rewrite is wrong.

### The boolean attributes on `@AsyncTest`

All 146 `detect*` / `validate*` / `monitor*` boolean attributes were deprecated in favour of
`preset`, `includes` and `excludes`, and 2.0.0 removes them (#920). The rewrite is mechanical: an
attribute set to `true` becomes its `DetectorType` in `includes`, and one set to `false` becomes its
`DetectorType` in `excludes`. Under `detectAll = true`, the 1.x default, a flag set to `false`
never opted its detector out, so that rewrite turns off a detector that was running; drop the attribute
instead if you want to keep it.

**`detectAll = false` did not mean "only the flags I set".** 144 of the 146 attributes defaulted
to `true`, so `@AsyncTest(detectAll = false, detectFalseSharing = true)` ran every detector except
`VISIBILITY` and `LIVELOCKS`, measured through `AsyncTestConfig.from` on 1.12.4. In 2.0.0
`detectAll = false` only declines the every-detector opt-in, so on its own it runs the default
preset, `ESSENTIALS`. If you relied on the 1.x behaviour, say so with
`excludes = { DetectorType.VISIBILITY, DetectorType.LIVELOCKS }`; if you meant the flags you set,
that is `includes`, which is what the rewrite above produces.

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

All 42 were deprecated in favour of a `*Detector()` name, and 2.0.0 removes them (#921). For 38 of
them the suffix is the only difference:

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

### The default selection

A bare `@AsyncTest` ran every detector in 1.x; in 2.0.0 it runs `Preset.ESSENTIALS`, 12 detectors
none of which sits at the ADVISORY trust tier (#923). The defaults behind it are
`detectAll = false` and `preset = Preset.ESSENTIALS`, and `detectAll = true` is the explicit opt-in
to every detector, whatever the preset says. `includes` still beats both.

This is the one change in 2.0.0 that alters what an unchanged, warning-free test detects, and it
does so silently: the test still compiles and still passes, it just reads fewer detectors. To keep
the 1.x selection, add `detectAll = true` to every `@AsyncTest` that sets none of `includes`,
`preset` or `detectAll`, including annotations on classes and composed annotations. This repository
did exactly that to 694 annotations in 95 files before flipping the default.

```java
// 1.x: every detector.  2.0.0: Preset.ESSENTIALS
@AsyncTest(threads = 8)

// Every detector, in both
@AsyncTest(threads = 8, detectAll = true)
```

### `spi.DetectorRegistry.build(config)` and `spi.adapters`

The built-in SPI bridge is gone (#922): the `se.deversity.asynctest.spi.adapters` package and
`spi.DetectorRegistry.build(config)`, which returned a fresh, unobserving copy of every built-in
detector beside your own. Your own `DetectorFactory` keeps working through `buildExternal(config)`,
which the runner already used. Code that called `build(config)` wanted one of two things:

```java
// 1.x: which detectors does this config select?
DetectorRegistry.build(cfg).all()
// 2.0.0
cfg.enabledDetectors()                        // Set<DetectorType>; also cfg.isEnabled(type)

// 1.x: find my third-party detector
DetectorRegistry.build(cfg).get(MyDetector.class)
// 2.0.0
DetectorRegistry.buildExternal(cfg).get(MyDetector.class)   // or .get("com.acme.my-detector")
```

### Checking your suite is ready

The default selection is the one change a compiler cannot show you; search for `@AsyncTest`
without `includes`, `preset` or `detectAll` and decide for each one. For everything else, compile
with deprecation warnings visible. A build with none left is a build that survives 2.0.0.
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
