# Consumer Fixture

This project exercises `async-test-lib` as a downstream consumer would:

- it depends on the built `se.deversity.async-test-lib:async-test-lib` artifact
- it runs its own JUnit test suite
- it only uses the library's public API surface (no internals)

## Why "install parent first"?

The fixture depends on the **same version** as the parent project
(see `gradle.properties` / `pom.xml` `<version>`). The version in
Maven Central may lag the in-progress source — so the fixture resolves
the artifact through `mavenLocal()` (`~/.m2/repository/`) first.

Without a locally-installed parent, any test referencing APIs added
since the last published release will fail with `cannot find symbol`
(historically: ~51 errors when consumer-fixture pinned 1.3.0 but tests
used 1.4.0+ APIs).

## Workflow

```powershell
# 1. Install the in-progress artifact to ~/.m2
mvn -f pom.xml install -DskipTests       # or: .\gradlew publishToMavenLocal

# 2. Run the consumer-fixture suite
mvn -f consumer-fixture\pom.xml test
# or
.\gradlew -p consumer-fixture test
```

If you change the parent's source and want fresh local-install:

```powershell
mvn install -DskipTests && mvn -f consumer-fixture\pom.xml test
```

## What gets tested

- `ConsumerAsyncTestUsageTest` — original suite covering the pre-1.0.0
  public surface (every detector accessor, every @AsyncTest attribute).
- `Consumer1_0_0FeaturesTest` — added in the 1.0.0 cycle; smoke-tests the
  new APIs (`Preset`, `threadCounts`, `replaySeed`, `AsyncAssert.awaitAsync`,
  scoped listeners, `Violation` + formatters, SPI registry, Phase 13
  detector accessors). A passing run proves these symbols are part of
  the stable public surface a consumer can rely on without internal
  imports.
- `AsyncTestPublishedDependencyTest` — verifies the published POM exposes
  exactly the expected transitive dependencies.
- `ConsumerJdk25And26DetectorsTest` — added in the 1.7.0 cycle; smoke-tests the
  standalone JDK 25/26 detectors (`StableValueMisuseDetector`,
  `StructuredTaskScopeMisuseDetector`, `GathererConcurrencyMisuseDetector`) through
  their public `recordXxx` / `analyze()` API — a consumer can drive them standalone,
  without `@AsyncTest`. A passing run proves the classes and their report accessors are
  part of the stable public surface. Selecting the same three *through* `@AsyncTest` is a
  different path, covered by `detectors/Phase16PreviewEraDetectorsFixtureTest`.
- `detectors/` — one `@AsyncTest` fixture per `DetectorType`, grouped by the phase
  headings in the enum. See below.

## The `detectors/` package

`src/test/java/se/deversity/asynctest/fixture/detectors/` holds **one fixture method per
detector**, scoped with `@AsyncTest(includes = {DetectorType.X})` so a failure names exactly
one detector. Each fixture does two things:

1. **Proves the detector is reachable.** Every `AsyncTestContext.xxxDetector()` accessor
   throws `IllegalStateException` when its detector is disabled for the round, so a
   non-throwing call inside an `includes`-scoped round proves the enum constant resolves to
   a real registered detector, that `includes` enabled it, and that the accessor is on the
   published artifact's surface.
2. **Runs a small workload of the kind that detector watches**, mirroring the corresponding
   `examples/` module. Workloads are deliberately short and never actually deadlock, hang or
   leak threads — the fixture demonstrates the shape, the example demonstrates the failure.

Seven detectors (`DEADLOCKS`, `VISIBILITY`, `LIVELOCKS`, `RACE_CONDITIONS`,
`THREAD_LOCAL_LEAKS`, `BUSY_WAITING`, `INTERRUPT_MISHANDLING`) have no *public* per-detector
accessor — their `shared*` accessors on `AsyncTestContext` are documented as internal. Those
fixtures assert the weaker "this body is running inside a configured round" claim instead,
and say so.

`DetectorCoverageTest` is the gate that keeps this honest: it reflects over every fixture
class and fails unless the `includes` sets union to exactly `DetectorType.values()`, with no
detector covered twice and no fixture enabling more than one. **Adding a `DetectorType`
without adding a fixture fails the build here.**

### Relationship to `examples/`

Every detector has both: a fixture here and a runnable module under `examples/`. The two
answer different questions. A fixture proves the detector is **reachable** from consumer
code with only that detector enabled; an example demonstrates the **hazard** and the fix, at
length, for a human reading it.

The mapping between them is a naming convention, not something the build checks — no example
references a `DetectorType` in code. Three detectors have no directory named after them and
are demonstrated by `examples/10-shared-non-thread-safe-types`: `SHARED_MATCHER`,
`SHARED_DECIMAL_FORMAT` and `SHARED_MESSAGE_DIGEST`. Each fixture class names the examples
it corresponds to in its javadoc.

## In CI

This module and the `examples/` reactor run together as the end-to-end suite in
`.github/workflows/e2e-tests.yml` — both consume the *built artifact*, so both need
`mvn install` first. `tests.yml` covers the library's own unit tests, packaging and
Javadoc. The Gradle mirror of both lives in `gradle-tests.yml`.
