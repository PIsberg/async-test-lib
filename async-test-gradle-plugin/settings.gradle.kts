rootProject.name = "async-test-gradle-plugin"

// Every version this build uses comes from versions/pom.xml (libs.versions.*), the library's own
// included: the plugin is versioned with the library and asks for the agent of that version.
apply(from = "../versions/versions.settings.gradle.kts")
