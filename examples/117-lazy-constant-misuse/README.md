# Example 117 — LazyConstant Misuse (JDK 26, Lazy Constants 2nd preview)

**Detector**: `LazyConstantMisuseDetector` (`DetectorType.LAZY_CONSTANT_MISUSE`, also usable standalone)
**JDK feature**: `java.lang.LazyConstant` — Lazy Constants, second preview in JDK 26; the renamed, simplified successor of the JDK 25 `StableValue` preview (JEP 502)

## The Problem

`LazyConstant<T>` is created with `LazyConstant.of(supplier)` and computed on first
`get()`: the supplier runs **at most once**, every later `get()` returns the cached
result, and the JVM may constant-fold the value like a `final` field. Compared to the
JDK 25 `StableValue` preview, the low-level methods (`trySet` / `setOrThrow` /
`orElseSet`) were removed, lazy collections moved to `List.ofLazy` / `Map.ofLazy`, and
**null values now throw `NullPointerException`**.

The classic mistakes migrate into the supplier:

- **Null-producing supplier** — allowed by the old `StableValue`, rejected by
  `LazyConstant` with an NPE at first `get()`.
- **Reentrant supplier** — a supplier that reads the same constant it is computing
  throws `IllegalStateException` (a hand-rolled holder recurses forever).
- **Hand-rolled lazy holders** — a check-then-act "lazy" getter lets two threads both
  run the supplier: wasted work, and a lost update if the supplier is non-deterministic.
- **Non-deterministic suppliers** — `Map.ofLazy` / `List.ofLazy` mapping functions must
  be deterministic; otherwise the stored value depends on which thread computed it.

## The buggy pattern (real JDK 26 API)

```java
static final LazyConstant<Config> CONFIG =
        LazyConstant.of(() -> maybeNullConfig());       // ✗ null → NPE on first get()

static final LazyConstant<Config> SELF =
        LazyConstant.of(() -> SELF.get().refresh());    // ✗ reentrant → IllegalStateException
```

## The Fix

```java
static final LazyConstant<Config> CONFIG =
        LazyConstant.of(() -> loadConfig());   // pure, non-null, deterministic, fast

Config c = CONFIG.get();                       // computes once, cached forever
```

> This example models `LazyConstant` with an `AtomicReference` in
> [`LazyConstantConfigService`](src/main/java/se/deversity/asynctest/example/service/LazyConstantConfigService.java)
> so it compiles and runs on the Java 21 baseline. The detector itself is API-agnostic —
> it works off recorded events, so it applies unchanged to the real `LazyConstant`.

## How to Detect

```java
var d = new LazyConstantMisuseDetector();
d.recordComputeStart("CONFIG", Thread.currentThread());
d.recordComputeEnd("CONFIG", Thread.currentThread(), null);   // null result → flagged
assertTrue(d.analyze().hasIssues());
```

Inside `@AsyncTest` the detector is pipeline-wired: grab it with
`AsyncTestContext.lazyConstantMisuseDetector()` (exclude with
`excludes = { DetectorType.LAZY_CONSTANT_MISUSE }`).

See [`LazyConstantConfigServiceTest`](src/test/java/se/deversity/asynctest/example/LazyConstantConfigServiceTest.java)
for the clean / null-supplier / racy-holder / reentrant walkthrough.

## Running

These detectors ship in the in-progress build. Install the parent artifact to your local
Maven repo first (same workflow as `consumer-fixture`):

```bash
mvn -f ../../pom.xml install -DskipTests -Dlicense.mock.mode=true
mvn -f pom.xml test
```
