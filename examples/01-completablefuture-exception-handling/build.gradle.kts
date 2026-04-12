plugins {
    java
}

group = "se.deversity.async-test-lib"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
    // Resolves the locally published async-test-lib artifact (run publishToMavenLocal first)
    mavenLocal()
}

val asyncTestVersion = "0.5.0"
val junitVersion = "5.10.2"
// The library brings in junit-jupiter-engine:6.0.3 as an api dependency, which wins
// over junitVersion above. Pin the launcher to match so Gradle's bundled 5.x launcher
// does not cause "OutputDirectoryCreator not available" failures.
val junitPlatformVersion = "6.0.3"

dependencies {
    testImplementation("se.deversity.async-test-lib:async-test-lib:$asyncTestVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:$junitPlatformVersion")
}

tasks.test {
    useJUnitPlatform()
}
