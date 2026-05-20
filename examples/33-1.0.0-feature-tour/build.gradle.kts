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
    // mavenLocal MUST come first: the example pins the same version as the
    // parent (1.4.0) but uses in-progress APIs that Maven Central's 1.4.0
    // doesn't have. Run `mvn install -DskipTests` at the project root first
    // so the local artifact resolves before Central's stale one.
    mavenLocal()
    mavenCentral()
}

// MUST match the parent project's gradle.properties / pom.xml version.
// The example uses APIs from the in-progress source; run
// `mvn install -DskipTests` (or `./gradlew publishToMavenLocal`) at the
// project root first so mavenLocal() can resolve them.
val asyncTestVersion = "1.4.0"
val junitVersion = "5.10.2"
val junitPlatformVersion = "6.0.3"

dependencies {
    testImplementation("se.deversity.async-test-lib:async-test-lib:$asyncTestVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:$junitPlatformVersion")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("license.mock.mode", "true")
}
