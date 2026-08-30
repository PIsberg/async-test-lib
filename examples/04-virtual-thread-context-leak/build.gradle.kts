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
    mavenLocal()
}

val asyncTestVersion = "1.11.0"
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
