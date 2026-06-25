# Example 114 — StableValue Misuse (JDK 25/26, JEP 502)

**Detector**: `StableValueMisuseDetector` (standalone — not pipeline-wired)
**JDK feature**: `java.lang.StableValue` — JEP 502, preview in JDK 25, continuing in JDK 26

## The Problem

`StableValue<T>` is a *deferred-immutable* holder: unset at construction, settable **at
most once**, then treated by the JVM as a true constant (constant-folded like a `final`
field) — the modern, thread-safe replacement for double-checked locking and holder-class
lazy initialization. Its guarantees only hold if you respect the at-most-once contract:

- **Read before set** — `orElseThrow()` / `get()` before any value is set throws
  `NoSuchElementException`. Under concurrency this is a publication race: a reader can
  observe the holder before the writer's set happens-before the read.
- **Double set** — a second `setOrThrow(...)` after the holder is set throws
  `IllegalStateException`; `trySet(...)` silently drops the second value (a lost update).
- **Reentrant `orElseSet`** — a supplier that reads the same `StableValue` while it is
  still computing deadlocks / throws.

## The buggy pattern (real JDK 25/26 API)

```java
static final StableValue<Config> CONFIG = StableValue.of();

Config get()  { return CONFIG.orElseThrow(); }   // ✗ read before set → NoSuchElementException
void   init() {
    CONFIG.setOrThrow(load());
    CONFIG.setOrThrow(reload());                  // ✗ second set → IllegalStateException
}
```

> This example models `StableValue` with an `AtomicReference` in
> [`StableValueConfigService`](src/main/java/se/deversity/asynctest/example/service/StableValueConfigService.java)
> so it compiles and runs on the Java 21 baseline. The detector itself is API-agnostic —
> it works off recorded events, so it applies unchanged to the real `StableValue`.

## The Fix

```java
static final StableValue<Config> CONFIG = StableValue.of();

Config get() {
    return CONFIG.orElseSet(() -> load());   // lazy, at-most-once, thread-safe, pure supplier
}
```

## How to Detect

`StableValueMisuseDetector` is standalone — instantiate it, record events around the
holder, then assert on `analyze()`:

```java
var d = new StableValueMisuseDetector();
d.recordRead("CONFIG", Thread.currentThread());   // before any set → flagged
d.recordSet("CONFIG", Thread.currentThread());
d.recordSet("CONFIG", Thread.currentThread());    // double set → flagged
assertTrue(d.analyze().hasIssues());
```

See [`StableValueConfigServiceTest`](src/test/java/se/deversity/asynctest/example/StableValueConfigServiceTest.java)
for the full happy-path / read-before-set / double-set walkthrough.

## Running

These detectors ship in the in-progress build. Install the parent artifact to your local
Maven repo first (same workflow as `consumer-fixture`):

```bash
mvn -f ../../pom.xml install -DskipTests -Dlicense.mock.mode=true
mvn -f pom.xml test
```
