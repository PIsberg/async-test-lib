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

