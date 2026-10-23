// The quick start with the se.deversity.async-test plugin in place of the configuration and the
// doFirst block (#1045). The plugin comes from ../async-test-gradle-plugin as an included build
// (settings.gradle.kts), so nothing has to be published; the agent it asks for is the plugin's own
// version, async-test.version in versions/pom.xml, from mavenLocal like every fixture here.
plugins {
    java
    id("se.deversity.async-test")
}

asyncTest {
    includes.add("com.example")
}

tasks.test {
    // The Maven twin runs JaCoCo next to the agent; this one does not.
    systemProperty("asynctest.fixture.expectJacoco", "false")
}
