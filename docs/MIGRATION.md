# Migration Guide

> See [INDEX.md](INDEX.md) for the full documentation map.

This guide is for a suite that does not use this library yet. Upgrading an existing suite across
a breaking release has its own guide per version, listed [at the end](#upgrading-between-versions).

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

## Upgrading between versions

A release that breaks the public API gets a guide of its own in `docs/migration/`,
linked from that release's [CHANGELOG.md](CHANGELOG.md) entry.

| Upgrading to | Guide |
|---|---|
| 1.13.0 | [migration/1.13.0.md](migration/1.13.0.md): the removed `@AsyncTest` attributes and `*Monitor()` accessors, the new `ESSENTIALS` default, the SPI bridge, renamed finding names |

---
