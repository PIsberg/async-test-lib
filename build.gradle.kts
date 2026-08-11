import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.CoreJavadocOptions

// Reactor root. Mirrors pom.xml: aggregation and shared build configuration only, no sources.
// Maven remains the canonical build; this exists so `./gradlew test` stays a working smoke test.
plugins {
    // java-library / jacoco / pmd are core Gradle plugins — they are already on the classpath and
    // cannot be declared here with `apply false`. Subprojects apply them directly below.
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
    id("net.ltgt.errorprone") version "5.1.0" apply false
    id("com.github.spotbugs") version "6.5.10" apply false
    id("org.cyclonedx.bom") version "3.4.0"
}

// ── Dependency versions ─────────────────────────────────────────────────────
// pom.xml is the single source of truth for every version the two builds share, and this block
// reads it rather than restating it.
//
// These numbers used to be written twice, once here and once in pom.xml's <properties>, kept
// equal by a comment asking people to remember. That failed repeatedly: spotbugs, error-prone
// and pmd each drifted. It also lagged by construction, because Dependabot raises its update
// PRs against pom.xml only, so every bump landed in Maven and left the Gradle copy behind until
// somebody noticed. Deriving the values deletes the second copy instead of guarding it.
//
// Adding a shared version means adding it to pom.xml. There is nothing to mirror here, and
// BuildMetadataSyncTest fails if a literal version reappears in this file.
val pomText: String =
    providers.fileContents(layout.projectDirectory.file("pom.xml")).asText.get()

/** The reactor pom's `<properties>` block, which is where every shared version is declared. */
val pomProperties: Map<String, String> = run {
    val start = pomText.indexOf("<properties>")
    val end = pomText.indexOf("</properties>", start)
    require(start >= 0 && end > start) {
        "pom.xml has no <properties> block, so there is nothing to read versions from."
    }
    Regex("""<([A-Za-z0-9._-]+)>([^<>]*)</\1>""")
        .findAll(pomText.substring(start, end))
        .associate { it.groupValues[1] to it.groupValues[2].trim() }
}

fun pomVersion(property: String): String = requireNotNull(pomProperties[property]) {
    "pom.xml <properties> does not define <$property>. Maven is the single source for shared " +
        "versions: add the property there rather than hard-coding a number in build.gradle.kts."
}

fun pomValue(pattern: String, what: String): String = requireNotNull(
    Regex(pattern).find(pomText)?.groupValues?.get(1)
) { "Could not read $what out of pom.xml." }

// Coordinates come from the pom too, so a release bump moves one file rather than two.
group = pomValue("""<groupId>([^<]+)</groupId>\s*<artifactId>async-test-parent</artifactId>""",
                 "the reactor groupId")
version = pomValue("""<artifactId>async-test-parent</artifactId>\s*<version>([^<]+)</version>""",
                   "the reactor version")

extra["apiguardianVersion"] = pomVersion("apiguardian.version")
extra["junitVersion"] = pomVersion("junit.jupiter.version")
extra["junitPlatformVersion"] = pomVersion("junit.platform.version")
extra["jazzerVersion"] = pomVersion("jazzer.version")
extra["byteBuddyVersion"] = pomVersion("bytebuddy.version")
extra["asmVersion"] = pomVersion("asm.version")
extra["slf4jVersion"] = pomVersion("slf4j.version")
extra["commonLicenseLibVersion"] = pomVersion("common-license-lib.version")
extra["archunitVersion"] = pomVersion("archunit.version")
extra["vibetagsVersion"] = pomVersion("vibetags.version")
extra["spotbugsVersion"] = pomVersion("spotbugs.version")
extra["findsecbugsVersion"] = pomVersion("findsecbugs.version")
extra["errorProneVersion"] = pomVersion("error-prone.version")
extra["nullawayVersion"] = pomVersion("nullaway.version")
extra["jspecifyVersion"] = pomVersion("jspecify.version")
extra["pmdVersion"] = pomVersion("pmd.version")

// Gradle-only, with no Maven twin: Maven's test run has no SLF4J binding, so this backend exists
// only for `./gradlew test`. Kept current by the gradle Dependabot ecosystem, along with the
// plugin versions above.
extra["logbackVersion"] = "1.6.1"     // test-only SLF4J backend, built against slf4j 2.0.18

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")
    apply(plugin = "pmd")
    apply(plugin = "net.ltgt.errorprone")
    apply(plugin = "com.github.spotbugs")
    apply(plugin = "com.vanniktech.maven.publish")

    group = rootProject.group
    version = rootProject.version

    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // Mirrors build-helper-maven-plugin's add-fuzz-test-source execution in async-test-lib/pom.xml.
    // Jazzer fuzz targets compile as test classes but cannot live under src/test/java: OpenSSF
    // Scorecard's fuzzing check drops every path containing "/src/test/" before scanning for the
    // Jazzer import, so a target kept there leaves the repo scoring 0 on Fuzzing while it is in
    // fact fuzzed weekly. Only async-test-lib has the directory; the check keeps this a no-op
    // for the other modules.
    val fuzzSrc = layout.projectDirectory.dir("src/fuzz/java")
    if (fuzzSrc.asFile.isDirectory) {
        extensions.configure<SourceSetContainer> {
            named("test") { java.srcDir(fuzzSrc) }
        }
    }

    repositories {
        mavenLocal()
        mavenCentral()
    }

    dependencies {
        // Gradle supplies its own junit-platform-launcher unless one is on the test runtime
        // classpath, and a launcher older than the engine fails discovery with
        // "OutputDirectoryCreator not available". The library module used to get an aligned one
        // transitively from junit-platform-testkit; the agent and analysis modules do not depend
        // on testkit, so pin it for every module. Maven's surefire resolves this on its own.
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher:"
                + rootProject.extra["junitPlatformVersion"] as String)

        val vibetags = rootProject.extra["vibetagsVersion"] as String
        add("compileOnly", "se.deversity.vibetags:vibetags-processor:$vibetags")
        add("annotationProcessor", "se.deversity.vibetags:vibetags-processor:$vibetags")
        add("compileOnly", "com.github.spotbugs:spotbugs-annotations:${rootProject.extra["spotbugsVersion"]}")
        add("compileOnly", "com.google.errorprone:error_prone_annotations:${rootProject.extra["errorProneVersion"]}")
        add("errorprone", "com.google.errorprone:error_prone_core:${rootProject.extra["errorProneVersion"]}")
        add("errorprone", "com.uber.nullaway:nullaway:${rootProject.extra["nullawayVersion"]}")
        add("compileOnly", "org.jspecify:jspecify:${rootProject.extra["jspecifyVersion"]}")
    }

    // VibeTags resolves the module root by walking up from a source file to the nearest build
    // file. Under Gradle the compiler runs in a worker whose working directory is
    // ~/.gradle/workers, so that walk lands nowhere and no guardrails are written. Pin the output
    // root explicitly to this module's directory — the same place Maven's ModuleRootResolver
    // finds — so `./gradlew build` regenerates CLAUDE.md and .claude/rules/ exactly as `mvn
    // compile` does.
    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.add("-Avibetags.root=${project.projectDir}")
    }

    // Error Prone runs on main sources only; test sources are excluded per project policy
    tasks.named<JavaCompile>("compileTestJava") {
        options.errorprone.enabled.set(false)
    }

    // NullAway gates nullness on main sources, alongside Error Prone. Mirrors the parent POM's
    // -Xep:NullAway:ERROR / AnnotatedPackages arguments; see docs/QUALITY_GATES.md.
    tasks.named<JavaCompile>("compileJava") {
        options.errorprone.error("NullAway")
        options.errorprone.option("NullAway:AnnotatedPackages", "se.deversity.asynctest")
    }

    tasks.named<Test>("test") {
        useJUnitPlatform {
            // Mirrors Maven's surefire.excludedGroups default: local runs skip the
            // @Tag("e2e") engine tier. CI (Actions always sets the CI env var) and
            // -Pe2e run the full suite.
            if (System.getenv("CI") == null && !project.hasProperty("e2e")) {
                excludeTags("e2e")
            }
        }
        // Match Maven surefire forkCount=1, reuseForks=false: new JVM for each test class
        forkEvery = 1
        // Run multiple test JVMs in parallel. forkEvery=1 already gives each class its own
        // JVM, so concurrent forks share no static state (ThreadLocal contexts, static
        // registries, per-JVM license/benchmark state) — isolation is identical to serial
        // execution, only wall-clock time drops. Overridable with -PtestForks=N; defaults to
        // half the available processors (min 1) to leave headroom for the heavily-threaded
        // ConcurrencyRunner stress phases inside each fork.
        val requestedForks = (project.findProperty("testForks") as String?)?.toIntOrNull()
        maxParallelForks = requestedForks
            ?: (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
        systemProperty("license.mock.mode", System.getProperty("license.mock.mode", "true"))
        systemProperty("license.key", System.getProperty("license.key", ""))
        // Mirrors the Maven surefire property: a detector that throws during analysis stays
        // contained for consumers but fails this project's own build, because a detector that
        // reports nothing is indistinguishable from a clean run. See DetectorFailurePolicy.
        systemProperty("async-test.strict-detectors", "true")
        // Permit AsyncTestAgent.selfAttach() to attach to the forked test JVM
        // (self-attach is disabled by default since JDK 9). Mirrors the Maven surefire argLine.
        jvmArgs("-Djdk.attach.allowAttachSelf=true")

        finalizedBy(tasks.named("jacocoTestReport"))

        // Exclude inner/static nested classes from direct test discovery.
        // Maven Surefire discovers tests by filename (*.java), so inner classes are never run
        // directly — only via EngineTestKit. Gradle's JUnit Platform discovery finds them too
        // and would run intentionally-buggy "Dummy" fixtures directly, causing failures.
        filter {
            excludeTestsMatching("*\$*")
            // Failsafe integration tests (*IT) verify the Maven-packaged artifact — for
            // the agent, the shaded jar that only the Maven build produces. Gradle builds
            // an unshaded jar for local iteration, so running them here would fail on a
            // difference that is expected. Maven (`mvn verify`, and CI's `mvn clean
            // install`) is the build that runs them.
            excludeTestsMatching("*IT")
        }
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        reports {
            xml.required = true
            html.required = true
        }
    }

    tasks.withType<Javadoc> {
        options.encoding = "UTF-8"
        (options as CoreJavadocOptions).addStringOption("Xdoclint:none", "-quiet")
    }

    // ── PMD static analysis (engine pinned to the same ${pmdVersion} as pom.xml) ─
    // Rule and exclude files live at the reactor root, so resolve them there rather than
    // relative to each module.
    extensions.configure<PmdExtension> {
        toolVersion = rootProject.extra["pmdVersion"] as String
        ruleSets = listOf()
        ruleSetFiles = rootProject.files("pmd-ruleset.xml")
        isConsoleOutput = true
        isIgnoreFailures = false
    }
    tasks.named<Pmd>("pmdMain") {
        enabled = true
        // Mirrors the <excludes> in pom.xml's maven-pmd-plugin config. Without it `./gradlew build`
        // fails on UnnecessaryConstructor / UncommentedEmptyConstructor in an intentionally empty
        // class. The two builds had drifted here because CI only ever ran `./gradlew test`, so the
        // Gradle PMD gate was never exercised.
        exclude("**/NoopAsyncTestListener.java")
    }
    tasks.named("pmdTest") { enabled = false }

    // ── SpotBugs static analysis (matches Maven spotbugs-maven-plugin:4.10.3.0) ─
    extensions.configure<com.github.spotbugs.snom.SpotBugsExtension> {
        toolVersion.set(rootProject.extra["spotbugsVersion"] as String)
        excludeFilter.set(rootProject.file("spotbugs-exclude.xml"))
        effort.set(com.github.spotbugs.snom.Effort.MAX)
        reportLevel.set(com.github.spotbugs.snom.Confidence.LOW)
        ignoreFailures.set(false)
    }
    // find-sec-bugs rides inside the SpotBugs gate rather than adding a new one. Mirrors the
    // parent POM's <plugins> block under spotbugs-maven-plugin; the triage lives in the shared
    // spotbugs-exclude.xml, so both builds see the same findings and the same exclusions.
    dependencies {
        add("spotbugsPlugins",
            "com.h3xstream.findsecbugs:findsecbugs-plugin:${rootProject.extra["findsecbugsVersion"]}")
    }
    tasks.named("spotbugsMain") { enabled = true }
    tasks.named("spotbugsTest") { enabled = false }

    // Shared POM metadata; each module supplies its own coordinates, name and description.
    extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
        // gradle-maven-publish-plugin dropped the SonatypeHost argument once OSSRH shut down and
        // the Central Portal became the only host, so the overload now takes the release flag
        // alone. Same destination as before, and the same as the Maven build's
        // central-publishing-maven-plugin with autoPublish.
        publishToMavenCentral(automaticRelease = true)

        // Only sign when the in-memory key is present (set via ORG_GRADLE_PROJECT_signingInMemoryKey
        // in the release workflow). Skipped for local builds and the test workflow's
        // publishToMavenLocal.
        if (providers.gradleProperty("signingInMemoryKey").isPresent) {
            signAllPublications()
        }

        pom {
            url = "https://github.com/PIsberg/async-test-lib"

            licenses {
                license {
                    name = "PolyForm Noncommercial License 1.0.0"
                    url = "https://polyformproject.org/licenses/noncommercial/1.0.0/"
                    distribution = "repo"
                }
            }

            developers {
                developer {
                    id = "PIsberg"
                    name = "Peter Isberg"
                    url = "https://github.com/PIsberg"
                }
            }

            scm {
                url = "https://github.com/PIsberg/async-test-lib"
                connection = "scm:git:https://github.com/PIsberg/async-test-lib.git"
                developerConnection = "scm:git:https://github.com/PIsberg/async-test-lib.git"
            }

            issueManagement {
                system = "GitHub"
                url = "https://github.com/PIsberg/async-test-lib/issues"
            }
        }
    }
}

// ── CycloneDX SBOM (matches Maven cyclonedx-maven-plugin, aggregate at the root) ─
// cyclonedx-gradle-plugin 3.x replaced the task type and its whole property set: CycloneDxTask
// became CyclonedxAggregateTask, schemaVersion takes the Version enum instead of a string, and
// destination/outputName/outputFormat collapsed into xmlOutput/jsonOutput. includeConfigs is
// gone — the aggregate task walks each subproject's own SBOM rather than a named configuration.
// The released SBOM is still the Maven one (sbom.yml runs cyclonedx-maven-plugin); this task
// exists so the secondary build produces the same artifact shape.
tasks.named<org.cyclonedx.gradle.CyclonedxAggregateTask>("cyclonedxBom") {
    schemaVersion.set(org.cyclonedx.Version.VERSION_16)
    includeBomSerialNumber.set(true)
    projectType.set(org.cyclonedx.model.Component.Type.LIBRARY)
    xmlOutput.set(layout.buildDirectory.file("reports/bom.xml"))
}
