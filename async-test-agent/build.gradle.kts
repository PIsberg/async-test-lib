// Optional Byte Buddy field-access agent. The only module allowed to touch net.bytebuddy.
val junitVersion = rootProject.extra["junitVersion"] as String
val byteBuddyVersion = rootProject.extra["byteBuddyVersion"] as String
val ecjVersion = rootProject.extra["ecjVersion"] as String

dependencies {
    api(project(":async-test-lib"))
    // Byte Buddy: Java agent instrumentation (AsyncTestAgent)
    implementation("net.bytebuddy:byte-buddy:$byteBuddyVersion")
    // Byte Buddy Agent: runtime self-attach (AsyncTestAgent.selfAttach)
    implementation("net.bytebuddy:byte-buddy-agent:$byteBuddyVersion")

    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
    // ECJ: MissedSignalRotatedLoopWeavingTest compiles the wait-loop shapes with a
    // loop-rotating compiler, which javac is not (#710).
    testImplementation("org.eclipse.jdt:ecj:$ecjVersion")
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

// Bundle Byte Buddy into the agent jar, relocated, exactly as maven-shade-plugin does (#719).
// -javaagent: loads this jar on its own: the JVM resolves AsyncTestAgent's method signatures
// before premain runs, and nothing on a consumer classpath provides Byte Buddy, because
// ArchitectureTest forbids the library from carrying it. Relocation rather than plain bundling
// keeps the copy invisible to consumers that ship their own Byte Buddy, such as anything using
// Mockito. The pattern and the two exclusions below are the Maven configuration restated; if one
// of them moves, both builds have to move together.
apply(plugin = "com.gradleup.shadow")

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier = ""
    dependencies {
        include(dependency("net.bytebuddy:byte-buddy"))
        include(dependency("net.bytebuddy:byte-buddy-agent"))
    }
    relocate("net.bytebuddy", "se.deversity.asynctest.agent.shaded.bytebuddy")
    // The shaded jar stays a classpath artifact; a relocated module descriptor would be a lie
    // in two ways.
    exclude("module-info.class")
    exclude("META-INF/versions/*/module-info.class")
    manifest {
        // Byte Buddy is a multi-release jar; its versioned classes are relocated under
        // META-INF/versions/ and need this flag to load.
        attributes("Multi-Release" to "true")
    }
}

// `jar` and `shadowJar` would otherwise both write async-test-agent-<version>.jar and the one
// that ran last would win, which is a silent way to put the unshaded jar back (#719). The plain
// jar is disabled rather than given a classifier: nothing needs it, and a second jar on disk is
// one more thing that can be attached by mistake. The outgoing artifacts are repointed so the
// publication and anything resolving this project get the shaded jar and not a file that merely
// happens to sit at the same path. Safe to repoint here and nowhere else: ArchitectureTest keeps
// every other module from depending on the agent.
tasks.jar { enabled = false }

configurations.named("apiElements").configure { outgoing.artifacts.clear() }
configurations.named("runtimeElements").configure { outgoing.artifacts.clear() }
artifacts {
    add("apiElements", tasks.named("shadowJar"))
    add("runtimeElements", tasks.named("shadowJar"))
}
tasks.named("assemble") { dependsOn(tasks.named("shadowJar")) }

mavenPublishing {
    coordinates(
        groupId = project.group.toString(),
        artifactId = "async-test-agent",
        version = project.version.toString()
    )
    pom {
        name = "Async Test Library — Java agent"
        description = "Optional Byte Buddy agent for async-test-lib: instruments JavaBean accessors so " +
                "detectors record reads and writes without manual hooks. Attach with " +
                "-javaagent:async-test-agent.jar, or call AsyncTestAgent.selfAttach()."
    }
}

// ── The packaged agent jar, and the gate that it is actually an agent ───────────────────────
//
// Maven shades a relocated Byte Buddy into this jar, because -javaagent loads it on its own:
// the JVM resolves AsyncTestAgent's method signatures before premain runs, and nothing on a
// consumer classpath provides Byte Buddy. The Gradle build did not, so the jar it published
// carried Premain-Class and none of Byte Buddy, and any JVM attaching it aborted at startup
// with NoClassDefFoundError: net/bytebuddy/matcher/ElementMatcher (#719).
//
// Nothing caught it because the Maven gate, AgentJarPremainIT, ran only under Failsafe, and
// this build excludes *IT from `test`. The task below runs the same gate against what this
// build packages, so the two builds are held to one standard rather than one of them being
// trusted.
val agentJarIntegrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Attaches the packaged agent jar to a Byte-Buddy-free JVM, the way a consumer does."

    val packaged = tasks.named<Jar>("shadowJar").flatMap { it.archiveFile }

    useJUnitPlatform()
    testClassesDirs = sourceSets["test"].output.classesDirs
    // The packaged jar stands in for the main classes rather than joining them. With
    // build/classes/java/main on the classpath the child JVM resolves the unshaded
    // AsyncTestAgent first and aborts, which would test the wrong jar. Maven's Failsafe makes
    // the same substitution, and its comment records that the premain scenario proved it.
    classpath = files(packaged) +
        sourceSets["test"].runtimeClasspath.minus(files(sourceSets["main"].output))
    filter { includeTestsMatching("*IT") }
    systemProperty("agent.jar", packaged.get().asFile.absolutePath)
    systemProperty("license.mock.mode", "true")
}

tasks.named("check") { dependsOn(agentJarIntegrationTest) }
