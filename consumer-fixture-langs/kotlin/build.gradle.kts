plugins {
    kotlin("jvm") version "2.4.10"
}

kotlin {
    jvmToolchain(21)
}

// Gradle twin of the -javaagent argLine in kotlin/pom.xml (#714). The wait gates need the agent
// weaving Kotlin bytecode, and premain rather than a self-attach: a self-attach that is refused
// turns them into skips, and a skipped gate is not a passed gate. Its own configuration, not
// testRuntimeOnly, because what is wanted is the jar's path and not its contents on the
// classpath; non-transitive because the published agent jar already shades byte-buddy.
val asyncTestAgent = configurations.create("asyncTestAgent") {
    isTransitive = false
}

dependencies {
    asyncTestAgent("se.deversity.async-test-lib:async-test-agent:${rootProject.extra["asyncTestVersion"]}")
    // The Gradle build has no twin of Maven's shade step, so the agent jar publishToMavenLocal
    // produces carries Premain-Class and none of byte-buddy, and premain dies with a
    // NoClassDefFoundError that aborts the JVM before any test runs. -javaagent loads the agent
    // through the system class loader, so the same coordinate on the test runtime classpath,
    // transitively this time, is what makes its classes resolve. Inert under Maven, where the jar
    // is shaded and its dependency-reduced pom brings nothing along.
    testRuntimeOnly("se.deversity.async-test-lib:async-test-agent:${rootProject.extra["asyncTestVersion"]}")
}

tasks.withType<Test>().configureEach {
    // doFirst so the configuration is resolved when the task runs rather than while the build is
    // being configured. includes= is scoped to the bean package so the two race-condition
    // fixtures next door keep running unwoven.
    doFirst {
        jvmArgs(
            "-javaagent:${asyncTestAgent.singleFile}" +
                "=includes=com.example.kotlinfixture,collections=true"
        )
    }
}
