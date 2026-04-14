plugins {
    java
}

group = "se.deversity.async-test-lib"
version = "0.6.2"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
    // Resolves the locally published async-test-lib artifact (run publishToMavenLocal first)
    mavenLocal()
}

val asyncTestVersion = "0.6.2"
val junitVersion = "6.0.3"

dependencies {
    testImplementation("se.deversity.async-test-lib:async-test-lib:$asyncTestVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
    // Gradle bundles its own junit-platform-launcher (JUnit 5.x). The library brings in
    // junit-jupiter-engine 6.x, so we must pin the launcher to the same version to avoid
    // "OutputDirectoryCreator not available" failures at test discovery time.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:$junitVersion")
}

tasks.test {
    useJUnitPlatform()
    // Enable benchmarking for all @AsyncTest tests in this module
    systemProperty("async-test.benchmarking.enabled", "true")
    // Benchmark regression threshold (20% default)
    systemProperty("benchmark.regression.threshold", "0.20")
    // Do not fail tests on benchmark regression
    systemProperty("benchmark.fail.on.regression", "false")
}
