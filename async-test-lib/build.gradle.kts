// The library. Keeps the artifactId consumers already depend on.
val apiguardianVersion = rootProject.extra["apiguardianVersion"] as String
val junitVersion = rootProject.extra["junitVersion"] as String
val jazzerVersion = rootProject.extra["jazzerVersion"] as String
val slf4jVersion = rootProject.extra["slf4jVersion"] as String
val commonLicenseLibVersion = rootProject.extra["commonLicenseLibVersion"] as String
val archunitVersion = rootProject.extra["archunitVersion"] as String
val logbackVersion = rootProject.extra["logbackVersion"] as String

dependencies {
    api("org.apiguardian:apiguardian-api:$apiguardianVersion")
    api("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    api("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
    api("org.slf4j:slf4j-api:$slf4jVersion")
    implementation("se.deversity.common:common-license-lib:$commonLicenseLibVersion")

    testImplementation("org.junit.platform:junit-platform-testkit:$junitVersion")
    testImplementation("com.code-intelligence:jazzer-api:$jazzerVersion")
    testImplementation("com.tngtech.archunit:archunit-junit5:$archunitVersion")
    // Test-only SLF4J backend: the library ships slf4j-api and no binding on purpose, but the
    // suite needs a real one to assert the DEBUG narrative (ConcurrencyRunnerLogContractTest).
    // src/test/resources/logback-test.xml keeps the root at WARN so this stays quiet.
    testImplementation("ch.qos.logback:logback-classic:$logbackVersion")
}

mavenPublishing {
    coordinates(
        groupId = project.group.toString(),
        artifactId = "async-test-lib",
        version = project.version.toString()
    )
    pom {
        name = "Async Test Library"
        description = "Enterprise-grade JUnit 5 concurrency testing library with 139 problem detectors " +
                "for detecting deadlocks, visibility issues, false sharing, livelocks, and other subtle concurrency bugs."
    }
}
