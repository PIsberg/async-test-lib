rootProject.name = "consumer-fixture-langs"

// Clojure is Maven-only: Gradle has no first-party Clojure plugin, and clojurephant's AOT model
// differs from clojure-maven-plugin's. Maven is the canonical build (docs/BUILDING.md); the
// Gradle twin covers the three languages Gradle supports out of the box or with a JetBrains plugin.
include("kotlin", "groovy", "scala")
