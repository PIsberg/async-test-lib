plugins {
    java
}

// The README's quick start, built the way it tells a user to build it. Everything below the
// plugins block is the README's Gradle agent snippet line for line (QuickStartAgentSnippetTest),
// with the version read from versions/pom.xml instead of a release number.
val asyncTestAgent = configurations.create("asyncTestAgent") {
    isTransitive = false
}

dependencies {
    testImplementation("se.deversity.async-test-lib:async-test-lib:${rootProject.extra["asyncTestVersion"]}")
    asyncTestAgent("se.deversity.async-test-lib:async-test-agent:${rootProject.extra["asyncTestVersion"]}")
}

tasks.test {
    useJUnitPlatform()
    doFirst {
        jvmArgs("-javaagent:${asyncTestAgent.singleFile}=fields=true,collections=true,includes=com.example")
    }
}
