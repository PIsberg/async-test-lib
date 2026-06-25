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
  their public `recordXxx` / `analyze()` API. These detectors are not wired into
  `@AsyncTest` (no `DetectorType` constant — that enum is locked), so a consumer
  reaches them directly; a passing run proves the classes and their report
  accessors are part of the stable public surface.
