plugins {
    java
    id("me.champeau.jmh") version "0.7.3"
}

group = "se.deversity.async-test-lib"
version = "load-tests"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenLocal()
    mavenCentral()
}

// Default to the version in the reactor's pom.xml -- the build sitting next to this one --
// rather than a literal. A hard-coded fallback is how this drifted: the workflow passed '1.6.0'
// and this file fell back to '1.3.0', so the load tests resolved an old jar from Maven Central
// and the publishToMavenLocal step that precedes them was dead weight. Every automatic run
// measured a release that was five versions stale, which makes a throughput regression in the
// current build impossible to see. Reading the pom means a release bump moves one file and this
// follows, exactly as the root build.gradle.kts already does for the reactor version.
val asyncTestVersion: String = (project.findProperty("asyncTestVersion") as String?)
    ?.takeIf { it.isNotBlank() }
    ?: run {
        val pom = rootProject.file("../pom.xml")
        require(pom.isFile) {
            "Cannot resolve the version under test: ${pom.absolutePath} does not exist. Pass " +
                "-PasyncTestVersion=<version> to load-test a published release instead."
        }
        val text = pom.readText()
        requireNotNull(
            Regex("""<artifactId>async-test-parent</artifactId>\s*<version>([^<]+)</version>""")
                .find(text)?.groupValues?.get(1)?.trim()
        ) {
            "Could not read the reactor version out of ${pom.absolutePath}. If the parent " +
                "artifactId changed, update this regex and the matching one in the root " +
                "build.gradle.kts together."
        }
    }
val junitVersion = "6.1.3"

// Publish library to local Maven before running load tests:
//   ./gradlew publishToMavenLocal          (from project root)
//   ./gradlew -p load-tests test           (then run load tests)
//
// Compare against a previous release:
//   ./gradlew -p load-tests test -PasyncTestVersion=0.7.0
//
// Cap to a fast subset for CI:
//   ./gradlew -p load-tests test -PloadTestFast=true

dependencies {
    // Shared by both test and JMH source sets via implementation scope
    implementation("se.deversity.async-test-lib:async-test-lib:$asyncTestVersion")
    implementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    implementation("org.junit.jupiter:junit-jupiter-params:$junitVersion")
    implementation("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
    implementation("org.junit.platform:junit-platform-testkit:$junitVersion")
    runtimeOnly("org.junit.platform:junit-platform-launcher:$junitVersion")
}

tasks.test {
    useJUnitPlatform()

    // License gate: use mock mode so no network calls are made
    systemProperty("license.mock.mode", "true")
    systemProperty("async.test.version", asyncTestVersion)

    val fast = project.findProperty("loadTestFast") as String? ?: "false"
    systemProperty("load.test.fast", fast)

    val outputDir = file("results/$asyncTestVersion")
    doFirst { outputDir.mkdirs() }
    systemProperty("load.test.output.dir", outputDir.absolutePath)

    // Exclude inner benchmark target classes — discovered only via EngineTestKit
    filter { excludeTestsMatching("*\$*") }

    // Serial execution for reproducible timing
    maxParallelForks = 1
}

jmh {
    jmhVersion.set("1.37")
    fork.set(1)
    warmupIterations.set(3)
    iterations.set(5)
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("jmh-results.json"))
    jvmArgsAppend.addAll(
        listOf(
            "-Dlicense.mock.mode=true",
            "-Dasync.test.version=$asyncTestVersion"
        )
    )

    // Scope a run to one class or method while iterating on a benchmark; a full jmh run is
    // ~20 minutes, which is too slow a loop to check whether a change moved the number.
    //   ./gradlew -p load-tests jmh -PjmhIncludes=DetectorLifecycleBenchmark
    (project.findProperty("jmhIncludes") as String?)?.let {
        includes.addAll(it.split(","))
    }
}
