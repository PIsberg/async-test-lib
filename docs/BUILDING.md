# Building from Source

> Extracted from the former `docs/README.md`. See [INDEX.md](INDEX.md) for the full documentation map.

> **Reactor layout.** The build has three modules — `async-test-lib` (the library),
> `async-test-agent` and `async-test-analysis`. Every command below runs from the repository root
> and builds all three. To work on one, add `-pl async-test-agent` (Maven) or use
> `:async-test-agent:test` (Gradle). Artifacts land in `<module>/target/` rather than `target/`.

### Prerequisites

- **Java 21+**
- **Maven 3.6+** or **Gradle 8.13+** (Gradle wrapper included)

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

