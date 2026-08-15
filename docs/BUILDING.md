# Building from Source

> Extracted from the former `docs/README.md`. See [INDEX.md](INDEX.md) for the full documentation map.

> **Reactor layout.** The build has three modules — `async-test-lib` (the library),
> `async-test-agent` and `async-test-analysis`. Every command below runs from the repository root
> and builds all three. To work on one, add `-pl async-test-agent` (Maven) or use
> `:async-test-agent:test` (Gradle). Artifacts land in `<module>/target/` rather than `target/`.

### Build and test: what the maintainers know

Moved here verbatim from the root `CLAUDE.md` on 2026-08-15 (context diet); the root file keeps
the five commands and links here for the rest.

**Maven is canonical, and it is the only place versions are declared.** CI (`tests.yml`) and
releases (`publish.yml`) run `mvn`. Gradle is a secondary developer build for fast local iteration,
and `build.gradle.kts` reads `pom.xml`'s `<properties>` block at configuration time rather than
restating it, so a shared version is changed in one file and both builds follow. The project
coordinates come from there too, which is why `gradle.properties` declares neither.

Add a shared version to `pom.xml` and read it with `pomVersion("...")`. `BuildMetadataSyncTest`
fails if a version literal reappears in a Gradle file, if the derivation is unwound, or if the
published descriptions stop matching. The only Gradle-declared versions are the ones with no Maven
twin: the `plugins` block and the test-only logback backend, both watched by the gradle Dependabot
ecosystem.

```bash
mvn test                                   # local tier (@Tag("e2e") engine tests excluded)
mvn test -P fast                           # same 190 classes, ~3x faster (no jacoco, 0.5C forks)
mvn test -P e2e                            # full suite: what CI runs (auto via env.CI)
mvn -pl async-test-lib test                # one module
mvn -Dtest=AsyncTestContextTest test       # one class
mvn -pl async-test-lib -am test -Dtest=X -Dsurefire.failIfNoSpecifiedTests=false
                                           # one class in a multi-module run: without that flag
                                           # surefire aborts the *sibling* module with
                                           # "No tests matching pattern". -DfailIfNoTests is a
                                           # different, silently-ignored property.
mvn install -DskipTests -Djacoco.skip=true # the static gate chain CI runs first (~3 min):
                                           # PMD, SpotBugs, Checkstyle, japicmp, javadoc.
                                           # `-P fast` skips all of these — a branch green
                                           # under it can still fail every CI job.
mvn clean install
./gradlew test                             # secondary build (same split; -Pe2e for full)
./gradlew test --tests "se.deversity.asynctest.diagnostics.*"
```

Run locally without a license key with `-Dlicense.mock.mode=true`. CI activates mock mode by itself.

**CI builds on JDK 21 and 25.** The JDK 26 static-analysis blocker is gone — `pmd.version` is now
pinned to 7.26.0, which reports 0 `LooseCoupling` violations where 7.17.0 reported 243. See
[docs/QUALITY_GATES.md](QUALITY_GATES.md#build-with-jdk-21-or-25-not-26) for what was measured.

`forkEvery = 1` means each test class gets its own JVM. Nested classes matching `*$*` are excluded
from direct discovery — they run only via JUnit's `EngineTestKit` in meta-tests such as
`AsyncTestLibraryMetaTest`. Those meta-tests carry `@E2E` (= `@Tag("e2e")`) and are excluded from
the default local run; the `e2e` profile (automatic in CI via the `CI` env var) runs them and
re-enables the jacoco check gate. `E2eTagGuardTest` pins the tag set.


### Prerequisites

- **Java 21+**
- **Maven 3.6+** or **Gradle 8.13+** (Gradle wrapper included)

### JUnit compatibility

`async-test-lib` is built against JUnit Jupiter 6.x and runs on the 5.x line as well. The
supported range is **Jupiter 5.9.3 through 6.1.2**.

| Jupiter | Status |
|---|---|
| 5.9.3 | supported — the floor |
| 5.10.5, 5.11.4, 5.12.2, 5.13.4 | supported |
| 6.0.3, 6.1.2 | supported |
| earlier than 5.9.3 | untested; assume unsupported |

This is measured, not asserted. The `junit-compatibility` job in
[`.github/workflows/e2e-tests.yml`](../.github/workflows/e2e-tests.yml) runs the
`consumer-fixture` module — which resolves the library the way a downstream project does and
exercises only the published surface — once per version in that table, 256 tests each. The job
also asserts that the declared Jupiter version is the one Maven actually resolved, because a
matrix that silently tests one version seven times reports green just as convincingly as one that
works.

You do not need to match the library's own Jupiter version. Declare whichever Jupiter your project
already uses; Maven and Gradle both resolve your direct declaration ahead of the library's
transitive one. To reproduce a single cell locally:

```bash
mvn -f consumer-fixture/pom.xml test -Djunit.jupiter.version=5.10.5 -Dlicense.mock.mode=true
```

Raising the floor is a minor-version change and is announced in the changelog. See
[SUPPORT_POLICY.md](SUPPORT_POLICY.md) for what else is covered.

### Clone the repository

```bash
git clone https://github.com/PIsberg/async-test-lib.git
cd async-test-lib
```

### Maven

```bash
# Run the local tier (plain JUnit; @Tag("e2e") engine tests excluded)
mvn test

# Same 190 test classes, ~3x faster: no coverage agent, forks across half the cores
mvn test -P fast

# Run the full suite including the e2e tier (what CI runs automatically)
mvn test -P e2e

# Build and install to local Maven repository
mvn clean install

# Run consumer fixture tests (requires install first)
mvn -f consumer-fixture/pom.xml test

# Run example project tests
mvn -f examples/01-completablefuture-exception-handling/pom.xml test
mvn -f examples/02-visibility-volatile-flag/pom.xml test

# Build artifacts only (skip tests)
mvn clean package -DskipTests

# Generate Javadoc
mvn javadoc:javadoc
```

#### Local test runtime

`forkEvery`-style isolation is not free. `reuseForks=false` gives every test class its own JVM,
so the local tier launches about 190 of them, and the JaCoCo agent re-instruments in each one
while all of them append to a single `jacoco.exec`. Coverage, not the tests, is the bulk of the
wall clock: the 190 classes only spend 78s inside test methods.

Measured on a 16-core Windows box, full local tier, all 190 classes green in every cell:

| `surefire.forkCount` | JaCoCo on | JaCoCo off |
|---|---|---|
| `1` (default) | 504s | 258s |
| `0.5C` (`-P fast`) | 426s | **164s** |
| `1C` | 438s | — |

`-P fast` takes the bottom-right cell. It changes no test semantics: `reuseForks=false` still
gives each class its own JVM, so concurrent forks share no static state and isolation is
identical to a serial run. Past `0.5C` it gets slower again, which is why `-P fast` stops at
half the cores — the same default `build.gradle.kts` has always used for `maxParallelForks`.

Nothing is gated on the coverage a local `mvn test` produces: `jacoco-check` binds to `verify`,
which `mvn test` never reaches, and `jacoco.check.skip` is true outside `-P e2e`. Run `-P e2e`
or `mvn verify` when the coverage numbers are the point.

The default stays at `forkCount=1` so CI is unchanged — CI runners have 2 to 4 cores and run the
timing-sensitive e2e tier. Tune any run with `-Dsurefire.forkCount=N`. Note that `-DforkCount=N`
does **not** work: Surefire's own parameter is set from a literal in the POM, and a literal beats
a user property, so the flag is silently accepted and ignored.

### Gradle

The Gradle wrapper (`gradlew` / `gradlew.bat`) is included — no local Gradle installation needed.

```bash
# Run the local tier (plain JUnit; @Tag("e2e") engine tests excluded)
./gradlew test

# Run the full suite including the e2e tier (what CI runs)
./gradlew test -Pe2e

# Build and publish to local Maven repository
./gradlew publishToMavenLocal

# Run consumer fixture tests (requires publishToMavenLocal first)
./gradlew -p consumer-fixture test

# Run example project tests (requires publishToMavenLocal first)
./gradlew -p examples/01-completablefuture-exception-handling test
./gradlew -p examples/02-visibility-volatile-flag test

# Build artifacts only (skip tests)
./gradlew assemble -x test

# Generate Javadoc
./gradlew javadoc
```

> **Windows:** Use `gradlew.bat` instead of `./gradlew`, or run `./gradlew` from Git Bash.

### Code coverage

Coverage is generated automatically when running tests:

- **Maven**: `target/site/jacoco/jacoco.xml`
- **Gradle**: `build/reports/jacoco/test/jacocoTestReport.xml`

