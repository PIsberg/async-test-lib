plugins {
    kotlin("jvm") version "2.4.10"
}
group = "se.deversity.async-test-lib"
version = "1.0.0"
kotlin {
    jvmToolchain(21)
}
repositories {
    mavenLocal()
    mavenCentral()
}
val asyncTestVersion = "1.9.2"
val junitVersion = "5.10.2"
val junitPlatformVersion = "6.1.2"
dependencies {
    testImplementation("se.deversity.async-test-lib:async-test-lib:$asyncTestVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:$junitPlatformVersion")
}
tasks.test {
    useJUnitPlatform()
    systemProperty("license.mock.mode", "true")
}
