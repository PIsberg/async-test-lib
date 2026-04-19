import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.CoreJavadocOptions

plugins {
    `java-library`
    jacoco
    id("com.vanniktech.maven.publish") version "0.30.0"
}

// group and version are read from gradle.properties

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenLocal()
    mavenCentral()
}

val junitVersion = "6.0.3"
val jazzerVersion = "0.22.1"

dependencies {
    api("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    api("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
    testImplementation("org.junit.platform:junit-platform-testkit:$junitVersion")
    testImplementation("com.code-intelligence:jazzer-api:$jazzerVersion")
    implementation("se.deversity.common:common-license-lib:0.2.1")
}

tasks.test {
    useJUnitPlatform()
    // Match Maven surefire forkCount=1, reuseForks=false: new JVM for each test class
    forkEvery = 1
    finalizedBy(tasks.jacocoTestReport)

    // Exclude inner/static nested classes from direct test discovery.
    // Maven Surefire discovers tests by filename (*.java), so inner classes are never run
    // directly — only via EngineTestKit. Gradle's JUnit Platform discovery finds them too
    // and would run intentionally-buggy "Dummy" fixtures directly, causing failures.
    filter {
        excludeTestsMatching("*\$*")
    }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.withType<Javadoc> {
    options.encoding = "UTF-8"
    (options as CoreJavadocOptions).addStringOption("Xdoclint:none", "-quiet")
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)

    // Only sign when the in-memory key is present (set via ORG_GRADLE_PROJECT_signingInMemoryKey
    // in the release workflow). Skipped for local builds and the test workflow's publishToMavenLocal.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    coordinates(
        groupId = project.group.toString(),
        artifactId = "async-test-lib",
        version = project.version.toString()
    )

    pom {
        name = "Async Test Library"
        description = "Enterprise-grade JUnit 5 concurrency testing library with 51+ problem detectors " +
                "for detecting deadlocks, visibility issues, false sharing, livelocks, and other subtle concurrency bugs."
        url = "https://github.com/PIsberg/async-test-lib"

        licenses {
            license {
                name = "PolyForm Noncommercial License 1.0.0"
                url = "https://polyformproject.org/licenses/noncommercial/1.0.0/"
                distribution = "repo"
            }
        }

        developers {
            developer {
                id = "PIsberg"
                name = "Peter Isberg"
                url = "https://github.com/PIsberg"
            }
        }

        scm {
            url = "https://github.com/PIsberg/async-test-lib"
            connection = "scm:git:https://github.com/PIsberg/async-test-lib.git"
            developerConnection = "scm:git:https://github.com/PIsberg/async-test-lib.git"
        }

        issueManagement {
            system = "GitHub"
            url = "https://github.com/PIsberg/async-test-lib/issues"
        }
    }
}
