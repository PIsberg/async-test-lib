plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.6.0"
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
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
