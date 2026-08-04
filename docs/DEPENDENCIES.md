# Third-party dependencies

Every library this project uses, why it is there, and how far it travels toward a consumer's
classpath. Ordered by exactly that: the list starts with what `testImplementation
'se.deversity.async-test-lib:async-test-lib'` actually pulls into a consumer's build and ends
with tools that never leave CI.

Versions are deliberately **not** repeated here. They are declared once, in the reactor root
[`pom.xml`](../pom.xml) `<properties>` block (the Gradle build reads them from there —
`BuildMetadataSyncTest` fails if that derivation is unwound), and Dependabot maintains them.
Each entry names its property so the current value is one lookup away.

## 1. On the consumer's classpath

What the published `async-test-lib` artifact brings along. The bar for this list is high:
this library runs inside other people's test suites, so every entry here is a version its
consumers must be able to live with.

| Library | Property | Why it is here |
|---|---|---|
| `org.junit.jupiter:junit-jupiter-api` | `junit.jupiter.version` | `@AsyncTest` is a JUnit 5 `@TestTemplate`; the annotation, extension SPI and assertion types are the library's public surface. |
| `org.junit.jupiter:junit-jupiter-engine` | `junit.jupiter.version` | The engine the templates execute on; compile scope means a consumer gets a compatible engine transitively instead of having to declare one. |
| `org.slf4j:slf4j-api` | `slf4j.version` | The diagnostic channel ([logging conventions](architecture/logging.md)). API only — the consumer's build chooses the backend, and a library that ships one would hijack somebody else's build log. |
| `org.apiguardian:apiguardian-api` | `apiguardian.version` | `@API` stability markers on public types, so consumers can see which surface is settled and which is experimental. |
| `se.deversity.common:common-license-lib` | `common-license-lib.version` | First-party, not third-party — listed for completeness. Implements the license guard (`LicenseGuard`); mock mode (`-Dlicense.mock.mode=true`) removes the network from tests and CI. |
| `org.jspecify:jspecify` | `jspecify.version` | Nullness annotations (`@Nullable`) that NullAway enforces at compile time. `provided` scope and CLASS retention: it never reaches a consumer's runtime classpath. |

## 2. Confined to a sibling artifact

Bytecode libraries a consumer only meets by opting into the artifact that carries them.
`ArchitectureTest` pins both confinements: the library module may reference neither, and
neither library may leak out of its module.

| Library | Property | Why it is here |
|---|---|---|
| `net.bytebuddy:byte-buddy` + `byte-buddy-agent` | `bytebuddy.version` | The optional agent (`async-test-agent`) weaves JavaBean accessors at load time so detectors observe field reads and writes without hand-written hooks ([AGENT.md](AGENT.md)). `byte-buddy-agent` provides the `selfAttach()` path. |
| `org.ow2.asm:asm` | `asm.version` | `StaticPinningScanner` (`async-test-analysis`) reads bytecode to flag `synchronized` blocks that would pin virtual threads — without executing any tests, which is why the module depends on nothing else in the project. |

## 3. Test-only, in this repository's own suite

Never published, never transitive.

| Library | Property | Why it is here |
|---|---|---|
| `org.junit.platform:junit-platform-testkit` | `junit.platform.version` | `EngineTestKit` meta-tests (`AsyncTestLibraryMetaTest` and friends) run nested test classes in a controlled engine and assert on the outcome — the only way to test that a failing `@AsyncTest` fails. |
| `com.tngtech.archunit:archunit-junit5` | `archunit.version` | Turns the module-boundary rules above from prose into failing tests (`ArchitectureTest`). |
| `com.code-intelligence:jazzer-api` | `jazzer.version` | Entry points for the scheduled fuzzing workflow (`fuzzing.yml`), which throws generated input at the config and report parsers. |
| `ch.qos.logback:logback-classic` | `logbackVersion` (in `build.gradle.kts` — no Maven twin, watched by the gradle Dependabot ecosystem) | The one test-only SLF4J backend, bound in the Gradle build so log-contract tests (`ConcurrencyRunnerLogContractTest`) can assert what the library actually logs. |

## 4. Build toolchain

Compile-time processors and quality gates. A consumer never sees these; CI fails on them.
[QUALITY_GATES.md](QUALITY_GATES.md) documents what each gate enforces and the build quirks
behind them.

| Tool | Property | Why it is here |
|---|---|---|
| Error Prone + NullAway | `error-prone.version`, `nullaway.version` | Compile-time bug patterns, plus nullness enforcement of the JSpecify annotations. A concurrency library that NPEs in somebody's suite is its own counterexample. |
| Checkstyle | `checkstyle.version`, `maven-checkstyle-plugin.version` | Style gate. |
| SpotBugs + FindSecBugs | `spotbugs.version`, `findsecbugs.version` | Bytecode-level bug and security-smell detection. |
| PMD | `pmd.version`, `maven-pmd-plugin.version` | Static analysis. Pinned to a version whose `LooseCoupling` rule is JDK-26-clean — see [QUALITY_GATES.md](QUALITY_GATES.md#build-with-jdk-21-or-25-not-26) for the measurement. |
| JaCoCo | `jacoco-maven-plugin.version` | Line/branch coverage feeding the codecov gate. |
| PITest + its JUnit 5 plugin | `pitest-maven.version`, `pitest-junit5-plugin.version` | Mutation testing, 75% threshold — coverage that proves the assertions bite, not just that lines ran. |
| CycloneDX | `cyclonedx-maven-plugin.version` | Generates the SBOM published with each release (`sbom.yml`). |
| vibetags | `vibetags.version` | First-party annotation processor that regenerates the guardrail blocks in each module's `CLAUDE.md` from source annotations. |

The remaining `maven-*` plugins in the properties block are standard Apache build plumbing
(compiler, jar, source, javadoc, gpg, surefire/failsafe, shade); they are pinned for
reproducibility and Dependabot visibility rather than listed here one by one.
