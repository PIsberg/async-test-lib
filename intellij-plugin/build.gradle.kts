import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "se.deversity.asynctest"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val junitVersion = "5.11.0"

dependencies {
    intellijPlatform {
        // Target IntelliJ IDEA Community 2024.1+
        intellijIdeaCommunity("2024.1")
        bundledPlugin("com.intellij.java")
        pluginVerifier()
        zipSigner()
    }
    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
    // Gradle 9 no longer puts a launcher on the test runtime classpath itself. JUnit 5.x
    // numbers the platform 1.x, so Jupiter 5.11.0 pairs with launcher 1.11.0.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.0")
}

tasks.test {
    useJUnitPlatform()
}

intellijPlatform {
    pluginConfiguration {
        name = "async-test Detector"
        version = project.version.toString()
    }
    signing {
        // Configure signing for Marketplace uploads; skip for local builds
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
    pluginVerification {
        // With no ides block verifyPlugin resolves every recommended release, 9 full IDE
        // downloads when this was written (#722). The two ends of the declared range are what
        // can break: current() is the 2024.1 build target, the since-build floor, and is already
        // on disk for compilation; latest() is resolved when the task runs, so it follows new
        // releases instead of going stale like a pinned version. Unfiltered it also returns the
        // newest Community edition, which ended at 2025.2 and sits mid-range, so it is limited
        // to the unified IDEA product. It can resolve an EAP: on 2026-09-22 it picked
        // IU-263.5153.40, published as type "eap". That is deliberate here - this task runs on a
        // schedule, where the point is to hear about the next release before it ships - but it
        // does mean a red run can be JetBrains' in-flight change rather than ours, so read the
        // report before touching the plugin.
        ides {
            current()
            latest {
                types = listOf(IntelliJPlatformType.IntellijIdea)
            }
        }
        // Left unset, the task reports problems and still succeeds: a plugin.xml depending on a
        // plugin that does not exist printed "1 missing mandatory dependency" for both IDEs and
        // ended BUILD SUCCESSFUL. These are the levels that mean the plugin will not load or is
        // about to stop loading.
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            VerifyPluginTask.FailureLevel.SCHEDULED_FOR_REMOVAL_API_USAGES,
        )
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
