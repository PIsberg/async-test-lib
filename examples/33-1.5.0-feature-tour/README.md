# Example 33 — async-test-lib 1.6.0 feature tour

Runnable demo of every public API added in 1.6.0. Each test method
in [`FeatureTourTest`](src/test/java/se/deversity/asynctest/example/FeatureTourTest.java)
exercises one new surface:

| Section | API | One-line summary |
|---|---|---|
| 1 | `@AsyncTest(preset = Preset.X)` | Curated detector bundle instead of editing ~90 individual flags |
| 2 | `@AsyncTest(threadCounts = {2,4,8,16})` | Schedule matrix — one JUnit invocation per entry |
| 3 | `@AsyncTest(replaySeed = N)` + `AsyncTestContext.replaySeed()` | Reproducible RNG for flaky-failure investigation |
| 4 | `AsyncAssert.awaitAsync(stage, timeout)` | Block on a `CompletionStage` inside an `@AsyncTest` body |
| 5 | `AsyncTestListenerRegistry.registerScoped(listener)` | Try-with-resources scoping; no JVM-wide listener leak |
| 6 | `Violation` + `MarkdownFormatter` / `JsonFormatter` | Structured findings for CI/IDE tooling |
| 7 | `se.deversity.asynctest.spi.DetectorRegistry.build(cfg)` | Programmatic SPI discovery of every detector |
| 8 | `@AsyncTest(detectSharedSecureRandom = true)` | Phase 13 detector example (`SHARED_SECURE_RANDOM`) |

## Run

Once 1.6.0 is published to Maven Central:

```bash
cd examples/33-1.6.0-feature-tour
mvn test                       # or: ./gradlew test
```

Before publication (or to test in-progress source), install the local artifact first:

```bash
# 1. From the project root: install async-test-lib 1.6.0 to ~/.m2
mvn install -DskipTests        # or: ./gradlew publishToMavenLocal

# 2. Run the example
cd examples/33-1.6.0-feature-tour
mvn test                       # or: ./gradlew test
```

If Gradle complains about missing symbols after a re-install of the same
version number, bust the cache:

```bash
rm -rf ~/.gradle/caches/modules-2/files-2.1/se.deversity.async-test-lib
./gradlew --refresh-dependencies test
```

(Gradle treats non-SNAPSHOT versions as immutable in its cache.)

## What to look for

The toy `PaymentService` is intentionally racy (unsynchronized `HashMap`,
get-then-put across threads). When you bump up the thread count in the
matrix-sweep test, the detectors enabled by `Preset.CI_FAST` light up — that's
the framework demonstrating its value with a concrete example.

The Phase 13 `sharedSecureRandomDetector()` test deliberately runs with
`detectAll = false` plus `detectSharedSecureRandom = true` — showing how to
opt into a single check when you don't want the full preset. (Note: using
`Preset.NONE` plus per-flag overrides does NOT work — the preset's effective
excludes pin every per-flag back to false.)
