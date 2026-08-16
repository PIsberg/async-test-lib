// Gradle twin of consumer-fixture-langs/pom.xml. Same shape as consumer-fixture/build.gradle.kts:
// the artifact comes from mavenLocal() first so CI tests the build it just produced, not the last
// release on Central. The version pin below is what the release skill bumps.
val asyncTestVersion = "1.9.3"
val junitVersion = "6.1.3"

subprojects {
    repositories {
        mavenLocal()
        mavenCentral()
    }

    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        }
        dependencies {
            "testImplementation"("se.deversity.async-test-lib:async-test-lib:$asyncTestVersion")
            "testImplementation"("org.junit.jupiter:junit-jupiter:$junitVersion")
            // Gradle bundles its own launcher (JUnit 5.x). The library brings junit-jupiter-engine
            // 6.x, and an unaligned engine/launcher pair fails discovery with "OutputDirectoryCreator
            // not available", so the launcher is pinned to the same line.
            "testRuntimeOnly"("org.junit.platform:junit-platform-launcher:$junitVersion")
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            // The fixture is offline; the tests also set licenseMockMode = true on the annotation.
            systemProperty("license.mock.mode", "true")
        }
    }
}
