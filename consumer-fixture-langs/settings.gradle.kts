// quickstart-plugin applies se.deversity.async-test from the plugin's own build, which needs no
// publishing (#1045).
pluginManagement {
    includeBuild("../async-test-gradle-plugin")
}

rootProject.name = "consumer-fixture-langs"

// Every version this build uses comes from versions/pom.xml (libs.versions.*).
apply(from = "../versions/versions.settings.gradle.kts")

// Clojure is Maven-only: Gradle has no first-party Clojure plugin, and clojurephant's AOT model
// differs from clojure-maven-plugin's. Maven is the canonical build (docs/BUILDING.md); the
// Gradle twin covers the three languages Gradle supports out of the box or with a JetBrains plugin.
include("kotlin", "groovy", "scala", "quickstart", "quickstart-plugin")
