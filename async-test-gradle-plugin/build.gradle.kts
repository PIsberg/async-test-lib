// The Gradle plugin that attaches async-test-agent at JVM startup (#1045): one plugins {} line in
// place of the configuration and doFirst block the README's quick start used to need. A build of its
// own, outside the Gradle derivation of the reactor, versioned with the library through
// versions/pom.xml. publish.yml publishes it to the Gradle Plugin Portal with every release, once
// the library of the same version is on Maven Central (#1099); consumer-fixture-langs/quickstart-plugin
// consumes it as an included build, which needs no publishing.
plugins {
    `java-gradle-plugin`
    // The Plugin Portal upload (publishPlugins). The one version this build names itself: a Gradle
    // plugin with no Maven twin, so no pom property can carry it; the gradle Dependabot entry for
    // this directory bumps it, and SatelliteVersionsTest lists it as Gradle-only.
    id("com.gradle.plugin-publish") version "2.2.1"
}

group = "se.deversity.async-test-lib"
version = libs.versions.async.test.version.get()

repositories {
    mavenCentral()
}

java {
    // The library itself needs 21, so a build that can use the agent already runs Gradle on 21+.
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

gradlePlugin {
    // What the Plugin Portal shows; every field is required by publishPlugins.
    website.set("https://github.com/PIsberg/async-test-lib")
    vcsUrl.set("https://github.com/PIsberg/async-test-lib")
    plugins {
        create("asyncTest") {
            id = "se.deversity.async-test"
            implementationClass = "se.deversity.asynctest.gradle.AsyncTestPlugin"
            displayName = "async-test"
            description = "Starts every Test task's JVM with -javaagent:async-test-agent.jar, so @AsyncTest sees unmodified code"
            tags.set(listOf("testing", "concurrency", "junit5", "java-agent", "race-condition"))
        }
    }
}

// The pom the portal serves beside the jar. plugin-publish fills the portal listing from the
// gradlePlugin block above; the pom itself would otherwise carry only the coordinates.
publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("async-test Gradle plugin")
            description.set("Starts every Test task's JVM with -javaagent:async-test-agent.jar, so @AsyncTest sees unmodified code")
            url.set("https://github.com/PIsberg/async-test-lib")
            licenses {
                license {
                    name.set("PolyForm Noncommercial License 1.0.0")
                    url.set("https://polyformproject.org/licenses/noncommercial/1.0.0/")
                }
            }
            developers {
                developer {
                    id.set("PIsberg")
                    name.set("Peter Isberg")
                    url.set("https://github.com/PIsberg")
                }
            }
            scm {
                url.set("https://github.com/PIsberg/async-test-lib")
                connection.set("scm:git:https://github.com/PIsberg/async-test-lib.git")
                developerConnection.set("scm:git:https://github.com/PIsberg/async-test-lib.git")
            }
        }
    }
}

val junitVersion = libs.versions.junit.jupiter.version.get()

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    // Gradle bundles a JUnit 5 launcher; an unaligned engine/launcher pair fails discovery.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:$junitVersion")
}

// The version the plugin asks for by default, baked in as a resource so it is read the same way
// from the jar an included build hands over and from the classes directory TestKit puts on its path.
val versionResource = tasks.register("versionResource") {
    val out = layout.buildDirectory.dir("generated/version")
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    outputs.dir(out)
    doLast {
        val file = out.get().file("se/deversity/asynctest/gradle/async-test-gradle-plugin.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=$pluginVersion\n")
    }
}
sourceSets.main {
    resources.srcDir(versionResource)
}

tasks.test {
    useJUnitPlatform()
}
