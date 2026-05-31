import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.CoreJavadocOptions

plugins {
    `java-library`
    jacoco
    pmd
    id("com.vanniktech.maven.publish") version "0.30.0"
    id("net.ltgt.errorprone") version "4.1.0"
    id("com.github.spotbugs") version "6.1.1"
    id("org.cyclonedx.bom") version "2.1.0"
}

// group and version are read from gradle.properties

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenLocal()
    mavenCentral()
}

// ── Dependency versions ─────────────────────────────────────────────────────
// Maven (pom.xml) is the canonical source of truth for versions.
// Keep these in sync with the <properties> block in pom.xml.
val junitVersion    = "6.1.0"   // pom: junit.jupiter.version
val jazzerVersion   = "0.30.0"  // pom: jazzer.version
val byteBuddyVersion = "1.18.8" // pom: bytebuddy.version
val asmVersion      = "9.10.1"  // pom: asm.version
val slf4jVersion    = "2.0.16"  // pom: slf4j.version

dependencies {
    api("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    api("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
    testImplementation("org.junit.platform:junit-platform-testkit:$junitVersion")
    testImplementation("com.code-intelligence:jazzer-api:$jazzerVersion")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
    implementation("se.deversity.common:common-license-lib:0.2.1")
    // Byte Buddy: Java agent instrumentation (AsyncTestAgent)
    implementation("net.bytebuddy:byte-buddy:$byteBuddyVersion")
    // ASM: static bytecode pre-scanner (StaticPinningScanner)
    implementation("org.ow2.asm:asm:$asmVersion")
    api("org.slf4j:slf4j-api:$slf4jVersion")
    compileOnly("se.deversity.vibetags:vibetags-processor:0.9.9")
    annotationProcessor("se.deversity.vibetags:vibetags-processor:0.9.9")
    compileOnly("com.github.spotbugs:spotbugs-annotations:4.9.8")
    compileOnly("com.google.errorprone:error_prone_annotations:2.36.0")
    errorprone("com.google.errorprone:error_prone_core:2.36.0")
}

// Error Prone runs on main sources only; test sources are excluded per project policy
tasks.named<JavaCompile>("compileTestJava") {
    options.errorprone.isEnabled.set(false)
}

tasks.test {
    useJUnitPlatform()
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
    
    finalizedBy(tasks.jacocoTestReport)

    // Exclude inner/static nested classes from direct test discovery.
    // Maven Surefire discovers tests by filename (*.java), so inner classes are never run
    // directly — only via EngineTestKit. Gradle's JUnit Platform discovery finds them too
    // and would run intentionally-buggy "Dummy" fixtures directly, causing failures.
    filter {
        excludeTestsMatching("*\$*")
    }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

// Configure the JAR manifest so this library is also usable as a Java agent.
// Attach with: -javaagent:async-test-lib-<version>.jar
tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "se.deversity.asynctest.agent.AsyncTestAgent",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true"
        )
    }
}

tasks.withType<Javadoc> {
    options.encoding = "UTF-8"
    (options as CoreJavadocOptions).addStringOption("Xdoclint:none", "-quiet")
}

// ── PMD static analysis (matches Maven maven-pmd-plugin:3.28.0 / PMD 7.9.0) ─
pmd {
    toolVersion = "7.9.0"
    ruleSets = listOf()
    ruleSetFiles = files("pmd-ruleset.xml")
    isConsoleOutput = true
    isIgnoreFailures = false
}
tasks.named("pmdMain") { enabled = true }
tasks.named("pmdTest") { enabled = false }

// ── SpotBugs static analysis (matches Maven spotbugs-maven-plugin:4.9.8.3) ──
spotbugs {
    toolVersion.set("4.9.8")
    excludeFilter.set(file("spotbugs-exclude.xml"))
    effort.set(com.github.spotbugs.snom.Effort.MAX)
    reportLevel.set(com.github.spotbugs.snom.Confidence.LOW)
    ignoreFailures.set(false)
}
tasks.named("spotbugsMain") { enabled = true }
tasks.named("spotbugsTest") { enabled = false }

// ── CycloneDX SBOM (matches Maven cyclonedx-maven-plugin:2.9.1) ─────────────
tasks.cyclonedxBom {
    includeConfigs.set(listOf("runtimeClasspath"))
    schemaVersion.set("1.6")
    destination.set(project.file("build/reports"))
    outputName.set("bom")
    outputFormat.set("xml")
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)

    // Only sign when the in-memory key is present (set via ORG_GRADLE_PROJECT_signingInMemoryKey
    // in the release workflow). Skipped for local builds and the test workflow's publishToMavenLocal.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    coordinates(
        groupId = project.group.toString(),
        artifactId = "async-test-lib",
        version = project.version.toString()
    )

    pom {
        name = "Async Test Library"
        description = "Enterprise-grade JUnit 5 concurrency testing library with 93+ problem detectors " +
                "for detecting deadlocks, visibility issues, false sharing, livelocks, and other subtle concurrency bugs."
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
