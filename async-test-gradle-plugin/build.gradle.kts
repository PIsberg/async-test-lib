// The Gradle plugin that attaches async-test-agent at JVM startup (#1045): one plugins {} line in
// place of the configuration and doFirst block the README's quick start used to need. A build of its
// own, outside the Gradle derivation of the reactor and outside the release, until publishing it to
// the plugin portal is decided (#1099). consumer-fixture-langs/quickstart-plugin consumes it as an
// included build, which needs no publishing.
plugins {
    `java-gradle-plugin`
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
    plugins {
        create("asyncTest") {
            id = "se.deversity.async-test"
            implementationClass = "se.deversity.asynctest.gradle.AsyncTestPlugin"
            displayName = "async-test"
            description = "Starts every Test task's JVM with -javaagent:async-test-agent.jar"
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
