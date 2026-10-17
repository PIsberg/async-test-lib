// The Gradle side of versions/pom.xml. Every satellite settings.gradle.kts applies this script:
//
//     apply(from = "../versions/versions.settings.gradle.kts")
//
// and every <name.version> property in versions/pom.xml becomes the catalog version
// libs.versions.name.version, spelled exactly as Maven spells ${name.version}. A build file then
// reads `libs.versions.junit.jupiter.version.get()` and names no number of its own; the Kotlin
// plugin, which a plugins {} block resolves before any of that, is the one plugin alias below.
//
// The pom is found by walking up from the build's own directory, so the same line works from
// examples/01-x/ and from examples/ (where Gradle ignores the nested settings files).

val versionsPom: File = generateSequence(settingsDir) { it.parentFile }
    .map { it.resolve("versions/pom.xml") }
    .firstOrNull { it.isFile }
    ?: error("No versions/pom.xml above $settingsDir: the satellite builds take every version from it.")

val pomVersions: Map<String, String> = Regex("""<([A-Za-z0-9.-]+\.version)>([^<]+)</\1>""")
    .findAll(versionsPom.readText())
    .associate { it.groupValues[1] to it.groupValues[2].trim() }

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            pomVersions.forEach { (name, value) -> version(name, value) }
            plugin("kotlin-jvm", "org.jetbrains.kotlin.jvm").versionRef("kotlin.version")
        }
    }
}
