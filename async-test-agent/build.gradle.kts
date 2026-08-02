// Optional Byte Buddy field-access agent. The only module allowed to touch net.bytebuddy.
val junitVersion = rootProject.extra["junitVersion"] as String
val byteBuddyVersion = rootProject.extra["byteBuddyVersion"] as String

dependencies {
    api(project(":async-test-lib"))
    // Byte Buddy: Java agent instrumentation (AsyncTestAgent)
    implementation("net.bytebuddy:byte-buddy:$byteBuddyVersion")
    // Byte Buddy Agent: runtime self-attach (AsyncTestAgent.selfAttach)
    implementation("net.bytebuddy:byte-buddy-agent:$byteBuddyVersion")

    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
}

// The agent manifest. It moved here from the library JAR when the modules were split:
// attach with -javaagent:async-test-agent-<version>.jar. See docs/AGENT.md.
tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "se.deversity.asynctest.agent.AsyncTestAgent",
            "Agent-Class" to "se.deversity.asynctest.agent.AsyncTestAgent",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true"
        )
    }
}

mavenPublishing {
    coordinates(
        groupId = project.group.toString(),
        artifactId = "async-test-agent",
        version = project.version.toString()
    )
    pom {
        name = "Async Test Library — Java agent"
        description = "Optional Byte Buddy agent for async-test-lib: instruments field access so detectors " +
                "record reads and writes without manual hooks. Attach with -javaagent:async-test-agent.jar, " +
                "or call AsyncTestAgent.selfAttach()."
    }
}
