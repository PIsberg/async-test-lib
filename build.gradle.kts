plugins {
    `java-library`
    jacoco
    `maven-publish`
}

group = "se.deversity.async-test-lib"
version = "1.2.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

val junitVersion = "6.0.3"
val jazzerVersion = "0.22.1"

dependencies {
    api("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    api("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
    testImplementation("org.junit.platform:junit-platform-testkit:$junitVersion")
    testImplementation("com.code-intelligence:jazzer-api:$jazzerVersion")
}

tasks.test {
    useJUnitPlatform()
    // Match Maven surefire forkCount=1, reuseForks=false: new JVM for each test class
    forkEvery = 1
    finalizedBy(tasks.jacocoTestReport)

    // Exclude inner/static nested classes from direct test discovery.
    // Maven Surefire discovers tests by filename (*.java), so inner classes (which share
    // the outer class file) are never run directly — they're only run when the outer test
    // class invokes them via EngineTestKit. Gradle's JUnit Platform discovery finds inner
    // classes too and would run e.g. Phase2AsyncIntegrationTest$LockOrderViolationDummy
    // directly, causing intentionally-buggy "Dummy" fixtures to fail on their own.
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

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name = "Async Test Library"
                description = "Enterprise-grade JUnit 5 concurrency testing library with 35+ problem detectors"
                url = "https://github.com/PIsberg/async-test-lib"

                developers {
                    developer {
                        name = "Async Test Contributors"
                        url = "https://github.com/PIsberg/async-test-lib"
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
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/PIsberg/async-test-lib")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
        mavenLocal()
    }
}
