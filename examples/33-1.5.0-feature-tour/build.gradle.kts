plugins {
    java
}

group = "se.deversity.async-test-lib"
version = "1.6.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    // mavenLocal MUST come first: pre-release builds (and snapshots) should
    // resolve locally before Maven Central. After 1.6.0 is published to Central,
    // either order works for that pinned version.
    mavenLocal()
    mavenCentral()
}

// MUST match the parent project's gradle.properties / pom.xml version.
val asyncTestVersion = "1.9.7"
val junitVersion = "6.1.3"
val junitPlatformVersion = "6.1.3"

dependencies {
    testImplementation("se.deversity.async-test-lib:async-test-lib:$asyncTestVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:$junitPlatformVersion")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("license.mock.mode", "true")
}
