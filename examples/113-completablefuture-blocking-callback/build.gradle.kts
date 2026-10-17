plugins { java }
group = "se.deversity.async-test-lib"
version = "1.0.0"
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
repositories {
    mavenLocal()
    mavenCentral()
}
val asyncTestVersion = libs.versions.async.test.version.get()
val junitVersion = libs.versions.junit.jupiter.version.get()
val junitPlatformVersion = libs.versions.junit.platform.version.get()
dependencies {
    testImplementation("se.deversity.async-test-lib:async-test-lib:$asyncTestVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:$junitPlatformVersion")
}
tasks.test {
    useJUnitPlatform()
    systemProperty("license.mock.mode", "true")
}
