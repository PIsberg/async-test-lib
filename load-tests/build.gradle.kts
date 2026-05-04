plugins {
    java
    id("me.champeau.jmh") version "0.7.2"
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

val asyncTestVersion: String = project.findProperty("asyncTestVersion") as String? ?: "0.8.0"
val junitVersion = "6.0.3"

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
}
